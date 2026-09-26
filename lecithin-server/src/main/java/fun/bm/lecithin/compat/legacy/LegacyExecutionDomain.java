package fun.bm.lecithin.compat.legacy;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * Serial plugin execution with JVM-thread-affine reentry. Calls carry their complete body: a
 * synchronously waiting owner services it on its own thread, where monitors remain reentrant.
 * Logical domain ownership is never borrowed by another JVM thread. Plugin-created async work
 * and direct calls bypassing platform boundaries keep their own threading contract.
 */
public final class LegacyExecutionDomain {
    private final String name;
    private volatile Thread owner;
    private LegacyCalls.Channel channel;
    private int depth;
    final LongAdder acquisitions = new LongAdder();
    final LongAdder contended = new LongAdder();
    final LongAdder callbacks = new LongAdder();
    final LongAdder waitNanos = new LongAdder();
    volatile long maxWaitNanos;

    LegacyExecutionDomain(final String name) { this.name = name; }
    public String name() { return this.name; }
    public boolean isHeldByCurrentThread() { return this.owner == Thread.currentThread(); }

    private synchronized boolean tryEnter() {
        final Thread self = Thread.currentThread();
        if (this.owner != null && this.owner != self) return false;
        this.channel = LegacyCalls.current();
        this.owner = self;
        this.depth++;
        this.acquisitions.increment();
        return true;
    }

    private synchronized void exit() {
        if (this.owner != Thread.currentThread()) throw new IllegalStateException("Leaving foreign legacy domain " + this.name);
        if (--this.depth == 0) {
            this.owner = null;
            this.channel = null;
        }
    }

    /** Return or throw only after the complete synchronous body has finished. */
    public <T> T call(final Supplier<T> body) {
        if (this.tryEnter()) {
            try { return body.get(); }
            finally { this.exit(); }
        }
        this.contended.increment();
        final long start = System.nanoTime();
        final Request<T> request = new Request<>(body);
        try {
            return LegacyCalls.await(request.result, request::attempt);
        } finally {
            request.removeCallbacks();
            final long elapsed = System.nanoTime() - start;
            this.waitNanos.add(elapsed);
            synchronized (this) { this.maxWaitNanos = Math.max(this.maxWaitNanos, elapsed); }
        }
    }

    private final class Request<T> {
        final Supplier<T> body;
        final CompletableFuture<T> result = new CompletableFuture<>();
        final AtomicBoolean started = new AtomicBoolean();
        // Only the requesting thread modifies this map.
        final Map<LegacyCalls.Channel, Runnable> queued = new IdentityHashMap<>();
        Request(final Supplier<T> body) { this.body = body; }

        void attempt() {
            if (this.run()) return;
            final LegacyCalls.Channel target;
            synchronized (LegacyExecutionDomain.this) { target = LegacyExecutionDomain.this.channel; }
            if (target != null && target.isWaiting() && !this.queued.containsKey(target)) {
                final Runnable callback = () -> {
                    if (this.run()) LegacyExecutionDomain.this.callbacks.increment();
                };
                this.queued.put(target, callback);
                target.offer(callback);
            }
        }

        boolean run() {
            if (this.started.get() || !LegacyExecutionDomain.this.tryEnter()) return false;
            if (!this.started.compareAndSet(false, true)) {
                LegacyExecutionDomain.this.exit();
                return false;
            }
            LegacyCalls.complete(this.result, () -> {
                try { return this.body.get(); }
                finally { LegacyExecutionDomain.this.exit(); }
            });
            return true;
        }
        void removeCallbacks() { this.queued.forEach(LegacyCalls.Channel::remove); }
    }

    String describeOwner() {
        final Thread current = this.owner;
        return current == null ? "-" : current.getName();
    }
}
