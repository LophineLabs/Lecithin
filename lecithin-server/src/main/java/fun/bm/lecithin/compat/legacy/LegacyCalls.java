package fun.bm.lecithin.compat.legacy;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Synchronous, thread-affine callbacks. A waiting caller services its own mailbox, so a callback
 * re-enters the JVM thread which still holds the plugin's monitors. This class grants no world
 * ownership. The owner adapter installs the progress hook for actual owner operations.
 *
 * <p>Every wait also records whose progress it needs ({@link #waitsOn}). That wait-for edge is what
 * lets a domain tell a hand-off that is required to avoid a deadlock from one that would only move
 * a body onto a thread which does not own its world state.
 *
 * <h2>Waits are woken, not polled</h2>
 * A waiting thread parks until something it can act on happens, and whoever makes that happen
 * unparks it: its result completing, a callback offered to it, its domain released or its turn in
 * the domain coming up, a domain holder starting a wait of its own (a hand-off or a wait cycle may
 * now be possible), an owner operation being queued that it may be the owner for. A timed park is
 * only a backstop for a missed signal. It must not carry any ordinary wait: HotSpot on Windows
 * turns every timed park into at least one millisecond, and the OS rounds that up to its timer
 * period (15.6 ms by default), so a wait that ended by timeout cost a timer period instead of the
 * microseconds the work took. The one wait that does poll is an owner operation's own attempt to
 * acquire an idle region ({@link #awaitPolling}): nothing signals that a region became idle.
 */
final class LegacyCalls {
    private static final ThreadLocal<Channel> LOCAL = ThreadLocal.withInitial(Channel::new);
    static final ThreadLocal<Channel> RETURN_TO = new ThreadLocal<>();
    static volatile Runnable progress = () -> {};
    /** A wait-for chain longer than this is treated as a cycle: handing off is always deadlock-safe. */
    private static final int MAX_WAIT_HOPS = 64;
    /** Re-poll period of a wait whose attempt has no completion signal: acquiring an idle region. */
    private static final long POLL_NANOS = 100_000L;
    /** Upper bound of a signalled wait, against a missed signal only. */
    private static final long BACKSTOP_NANOS = 1_000_000L;
    /** Threads inside {@link #await}: the ones a new owner operation or a waiting holder must wake. */
    private static final Set<Channel> WAITING = ConcurrentHashMap.newKeySet();

    static final class Channel {
        final Thread thread = Thread.currentThread();
        private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
        private final BiConsumer<Object, Throwable> wake = (value, failure) -> LockSupport.unpark(this.thread);
        private volatile int waiting;
        // The thread whose progress this thread's innermost wait needs; null while it runs a body.
        private volatile Supplier<Channel> blockedOn;
        /** Domains this thread is inside; read and written by this thread only. */
        int holds;

        boolean isWaiting() { return this.waiting != 0; }
        void offer(final Runnable callback) {
            this.queue.add(callback);
            LockSupport.unpark(this.thread);
        }
        void remove(final Runnable callback) { this.queue.remove(callback); }
        private boolean poll() {
            final Runnable next = this.queue.poll();
            if (next == null) return false;
            this.running(next);
            return true;
        }
        private void running(final Runnable body) {
            final Supplier<Channel> waitingFor = this.blockedOn;
            this.blockedOn = null;
            try { body.run(); }
            finally { this.blockedOn = waitingFor; }
        }

        <T> T call(final Supplier<T> body) {
            if (this.thread == Thread.currentThread()) return body.get();
            final CompletableFuture<T> result = new CompletableFuture<>();
            final Runnable callback = () -> complete(result, body);
            this.offer(callback);
            try {
                return await(result, () -> {}, () -> result.isDone() ? null : this);
            } finally {
                this.remove(callback);
            }
        }
    }

    static Channel current() { return LOCAL.get(); }

    static <T> T callback(final Supplier<T> body) {
        final Channel target = RETURN_TO.get();
        return target == null ? body.get() : target.call(body);
    }

    static <T> void complete(final CompletableFuture<T> result, final Supplier<T> body) {
        try { result.complete(body.get()); }
        catch (final Throwable failure) { result.completeExceptionally(failure); }
    }

    /** Let every waiting thread re-check its wait: an owner operation it may serve was queued. */
    static void wakeWaiting() {
        final Thread self = Thread.currentThread();
        for (final Channel channel : WAITING) {
            if (channel.thread != self) LockSupport.unpark(channel.thread);
        }
    }

    /**
     * {@code true} when {@code waiter} cannot make progress until {@code target} does: following
     * each parked thread to the thread it waits for reaches {@code target}. A chain that stops at a
     * running thread is not a cycle. Waits change only while threads run, so a real deadlock is a
     * stable chain that every later check sees.
     */
    static boolean waitsOn(Channel waiter, final Channel target) {
        for (int hops = 0; waiter != null; hops++) {
            if (waiter == target || hops == MAX_WAIT_HOPS) return true;
            final Supplier<Channel> next = waiter.blockedOn;
            waiter = next == null ? null : next.get();
        }
        return false;
    }

    /** Wait for {@code result}, which is completed by another thread or by {@code attempt}. */
    static <T> T await(final CompletableFuture<T> result, final Runnable attempt, final Supplier<Channel> blockedOn) {
        return await(result, attempt, blockedOn, BACKSTOP_NANOS);
    }

    /** {@link #await} whose {@code attempt} can also succeed without any signal, so it is retried periodically. */
    static <T> T awaitPolling(final CompletableFuture<T> result, final Runnable attempt, final Supplier<Channel> blockedOn) {
        return await(result, attempt, blockedOn, POLL_NANOS);
    }

    private static <T> T await(final CompletableFuture<T> result, final Runnable attempt, final Supplier<Channel> blockedOn,
                               final long parkNanos) {
        final Channel channel = current();
        final Supplier<Channel> outer = channel.blockedOn;
        boolean interrupted = false;
        // The wait-for edge is visible before the wait is: whoever sees this thread waiting sees whom for.
        channel.blockedOn = blockedOn;
        if (channel.waiting++ == 0) WAITING.add(channel);
        // A holder that starts waiting can now take a hand-off or close a wait cycle: whoever is
        // parked behind one of its domains must look again.
        if (channel.holds != 0) wakeWaiting();
        if (!result.isDone()) result.whenComplete(channel.wake);
        try {
            while (!result.isDone()) {
                channel.running(attempt);
                if (result.isDone()) break;
                final boolean callback = channel.poll();
                channel.running(progress);
                if (callback || result.isDone()) continue;
                // Extra unparks are harmless: every pass re-checks everything above.
                LockSupport.parkNanos(parkNanos);
                interrupted |= Thread.interrupted();
            }
            return outcome(result);
        } finally {
            channel.blockedOn = outer;
            if (--channel.waiting == 0) WAITING.remove(channel);
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /**
     * The value a completed {@code result} holds, or the very throwable it was completed
     * exceptionally with. Every future awaited here is completed directly by {@link #complete} or
     * {@code completeExceptionally}, so that throwable is the body's own failure or the owner
     * operation's cancellation, and it crosses the boundary as it is. {@code join()} does not keep
     * it: since JDK 23 (JDK-8331987) it throws a new {@code CancellationException} whose cause is
     * the stored one, so a caller handling the cancellation it knows never sees it.
     */
    private static <T> T outcome(final CompletableFuture<T> result) {
        final Throwable[] failure = {null};
        final T value = result.handle((done, thrown) -> { failure[0] = thrown; return done; }).join();
        if (failure[0] != null) throw unchecked(failure[0]);
        return value;
    }

    static RuntimeException unchecked(final Throwable failure) {
        LegacyCalls.<RuntimeException>raise(failure);
        throw new AssertionError("unreachable");
    }
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void raise(final Throwable failure) throws E { throw (E) failure; }
}
