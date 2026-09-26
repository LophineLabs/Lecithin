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
 */
final class LegacyCalls {
    private static final ThreadLocal<Channel> LOCAL = ThreadLocal.withInitial(Channel::new);
    static final ThreadLocal<Channel> RETURN_TO = new ThreadLocal<>();
    static volatile Runnable progress = () -> {};

    static final class Channel {
        final Thread thread = Thread.currentThread();
        private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
        private volatile int waiting;

        boolean isWaiting() { return this.waiting != 0; }
        void offer(final Runnable callback) {
            this.queue.add(callback);
            LockSupport.unpark(this.thread);
        }
        void remove(final Runnable callback) { this.queue.remove(callback); }
        private boolean poll() {
            final Runnable next = this.queue.poll();
            if (next == null) return false;
            next.run();
            return true;
        }

        <T> T call(final Supplier<T> body) {
            if (this.thread == Thread.currentThread()) return body.get();
            final CompletableFuture<T> result = new CompletableFuture<>();
            final Runnable callback = () -> complete(result, body);
            this.offer(callback);
            try {
                return await(result, () -> {});
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

    static <T> T await(final CompletableFuture<T> result, final Runnable attempt) {
        final Channel channel = current();
        boolean interrupted = false;
        channel.waiting++;
        try {
            while (!result.isDone()) {
                attempt.run();
                if (result.isDone()) break;
                final boolean callback = channel.poll();
                progress.run();
                if (!callback && !result.isDone()) LockSupport.parkNanos(100_000L);
                interrupted |= Thread.interrupted();
            }
            try { return result.join(); }
            catch (final CompletionException failed) { throw unchecked(failed.getCause()); }
        } finally {
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
