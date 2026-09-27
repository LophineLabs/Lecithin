package fun.bm.lecithin.compat.legacy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/**
 * Synchronous, thread-affine callbacks. A waiting caller services its own mailbox, so a callback
 * re-enters the JVM thread which still holds the plugin's monitors. This class grants no world
 * ownership. The owner adapter installs the progress hook for actual owner operations.
 *
 * <p>Every wait also records whose progress it needs ({@link #waitsOn}). That wait-for edge is what
 * lets a domain tell a hand-off that is required to avoid a deadlock from one that would only move
 * a body onto a thread which does not own its world state.
 */
final class LegacyCalls {
    private static final ThreadLocal<Channel> LOCAL = ThreadLocal.withInitial(Channel::new);
    static final ThreadLocal<Channel> RETURN_TO = new ThreadLocal<>();
    static volatile Runnable progress = () -> {};
    /** A wait-for chain longer than this is treated as a cycle: handing off is always deadlock-safe. */
    private static final int MAX_WAIT_HOPS = 64;

    static final class Channel {
        final Thread thread = Thread.currentThread();
        private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
        private volatile int waiting;
        // The thread whose progress this thread's innermost wait needs; null while it runs a body.
        private volatile Supplier<Channel> blockedOn;

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

    static <T> T await(final CompletableFuture<T> result, final Runnable attempt, final Supplier<Channel> blockedOn) {
        final Channel channel = current();
        final Supplier<Channel> outer = channel.blockedOn;
        boolean interrupted = false;
        channel.waiting++;
        channel.blockedOn = blockedOn;
        try {
            while (!result.isDone()) {
                channel.running(attempt);
                if (result.isDone()) break;
                final boolean callback = channel.poll();
                channel.running(progress);
                if (!callback && !result.isDone()) LockSupport.parkNanos(100_000L);
                interrupted |= Thread.interrupted();
            }
            try { return result.join(); }
            catch (final CompletionException failed) { throw unchecked(failed.getCause()); }
        } finally {
            channel.blockedOn = outer;
            channel.waiting--;
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    static RuntimeException unchecked(final Throwable failure) {
        LegacyCalls.<RuntimeException>raise(failure);
        throw new AssertionError("unreachable");
    }
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void raise(final Throwable failure) throws E { throw (E) failure; }
}
