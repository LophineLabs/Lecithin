package fun.bm.lecithin.compat.legacy;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Serial plugin execution with JVM-thread-affine reentry. Calls carry their complete body: a
 * synchronously waiting owner services it on its own thread, where monitors remain reentrant.
 * Logical domain ownership is never borrowed by another JVM thread. Plugin-created async work
 * and direct calls bypassing platform boundaries keep their own threading contract.
 *
 * <h2>Whose thread runs a contended body</h2>
 * A body is bound to its caller's world ownership when the caller is a tick thread that owns a
 * region or the global region: an event fired while ticking an entity is about that region's
 * state. Such a body runs on its caller, after the domain is released, so the plugin sees Paper's
 * serial order and the event's owner context at once. It is handed to the parked holder only when
 * the holder waits, directly or through other parked threads, for the caller itself: then the
 * hand-off is the Paper re-entry that keeps the call cycle from deadlocking. A body whose caller
 * owns nothing (a lane, an async thread) loses nothing by moving and is handed off as before.
 */
public final class LegacyExecutionDomain {
    /** Whether a contended body is bound to its caller's world ownership. Replaced by tests. */
    static volatile BooleanSupplier ownsWorldState = () ->
        fun.bm.lecithin.config.modules.CompatConfig.legacyOwnerBoundEntries && currentThreadOwnsWorldState();

    private final String name;
    private volatile Thread owner;
    private LegacyCalls.Channel channel;
    private int depth;
    // Contended calls not yet started, oldest first. A released domain goes to the oldest.
    private final java.util.TreeMap<Long, Request<?>> waiting = new java.util.TreeMap<>();
    private long tickets;
    final LongAdder acquisitions = new LongAdder();
    final LongAdder contended = new LongAdder();
    final LongAdder callbacks = new LongAdder();
    final LongAdder ownerBoundWaits = new LongAdder();
    final LongAdder waitNanos = new LongAdder();
    volatile long maxWaitNanos;

    LegacyExecutionDomain(final String name) { this.name = name; }
    public String name() { return this.name; }
    public boolean isHeldByCurrentThread() { return this.owner == Thread.currentThread(); }

    private static boolean currentThreadOwnsWorldState() {
        return ca.spottedleaf.moonrise.common.util.TickThread.isTickThread()
            && (io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegion() != null
                || io.papermc.paper.threadedregions.RegionizedServer.isGlobalTickThread());
    }

    /** @param request the contended call entering, or {@code null} for a fresh entry */
    private synchronized boolean tryEnter(final Request<?> request) {
        final Thread self = Thread.currentThread();
        if (this.owner != null && this.owner != self) return false;
        if (this.owner == null && !this.waiting.isEmpty()) {
            // The oldest waiter enters first. A call made above that waiter's parked frame on the same
            // thread may enter too: the older call cannot continue before this one returns.
            final Request<?> head = this.waiting.firstEntry().getValue();
            if (head != request && head.caller.thread != self) return false;
        }
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
            if (!this.waiting.isEmpty()) java.util.concurrent.locks.LockSupport.unpark(this.waiting.firstEntry().getValue().caller.thread);
        }
    }

    private synchronized LegacyCalls.Channel holder() { return this.channel; }

    /** Whose progress a waiting call needs: the holder, or the older waiter a free domain goes to. */
    private synchronized LegacyCalls.Channel blocker(final Request<?> request) {
        if (this.channel != null || this.waiting.isEmpty()) return this.channel;
        final Request<?> head = this.waiting.firstEntry().getValue();
        return head == request ? null : head.caller;
    }

    private synchronized void enqueue(final Request<?> request) { this.waiting.put(request.ticket = this.tickets++, request); }
    private synchronized void dequeue(final Request<?> request) { this.waiting.remove(request.ticket, request); }

    /** Return or throw only after the complete synchronous body has finished. */
    public <T> T call(final Supplier<T> body) {
        if (this.tryEnter(null)) {
            try { return body.get(); }
            finally { this.exit(); }
        }
        this.contended.increment();
        final long start = System.nanoTime();
        final Request<T> request = new Request<>(body, ownsWorldState.getAsBoolean());
        this.enqueue(request);
        try {
            return LegacyCalls.await(request.result, request::attempt, () -> this.blocker(request));
        } finally {
            this.dequeue(request);
            request.removeCallbacks();
            final long elapsed = System.nanoTime() - start;
            this.waitNanos.add(elapsed);
            synchronized (this) { this.maxWaitNanos = Math.max(this.maxWaitNanos, elapsed); }
        }
    }

    private final class Request<T> {
        final Supplier<T> body;
        final boolean ownerBound;
        final LegacyCalls.Channel caller = LegacyCalls.current();
        final CompletableFuture<T> result = new CompletableFuture<>();
        final AtomicBoolean started = new AtomicBoolean();
        long ticket;
        boolean counted;
        // Only the requesting thread modifies this map.
        final Map<LegacyCalls.Channel, Runnable> queued = new IdentityHashMap<>();
        Request(final Supplier<T> body, final boolean ownerBound) { this.body = body; this.ownerBound = ownerBound; }

        void attempt() {
            if (this.run()) return;
            final LegacyCalls.Channel target = LegacyExecutionDomain.this.holder();
            if (target == null || !target.isWaiting() || this.queued.containsKey(target)) return;
            if (this.ownerBound && !LegacyCalls.waitsOn(target, this.caller)) {
                // The holder waits for something else; it will release. Run here, as the owner.
                if (!this.counted) { this.counted = true; LegacyExecutionDomain.this.ownerBoundWaits.increment(); }
                return;
            }
            final Runnable callback = () -> {
                if (this.run()) LegacyExecutionDomain.this.callbacks.increment();
            };
            this.queued.put(target, callback);
            target.offer(callback);
        }

        boolean run() {
            if (this.started.get() || !LegacyExecutionDomain.this.tryEnter(this)) return false;
            if (!this.started.compareAndSet(false, true)) {
                LegacyExecutionDomain.this.exit();
                return false;
            }
            LegacyExecutionDomain.this.dequeue(this);
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
