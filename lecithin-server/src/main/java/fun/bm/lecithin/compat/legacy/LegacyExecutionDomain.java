package fun.bm.lecithin.compat.legacy;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
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
 *
 * <h2>Entry cost</h2>
 * Hot plugins are entered hundreds of thousands of times a second from many regions, so the entry
 * itself must stay cheap. The holder is taken by a compare-and-set on {@link #owner}: re-entry and
 * a free domain nobody waits for cost no monitor and no allocation. Only a domain that is held or
 * has waiters goes through the monitor-guarded queue, oldest first. Release publishes the domain
 * ({@code owner = null}) before it reads whether anyone waits, and a waiter is counted before it
 * re-checks the holder, so one of the two always sees the other: no wakeup is lost. Release wakes
 * only the oldest waiter, which re-checks and enters; nobody waits on a timer for a free domain.
 * Waiters do not spin: on a loaded server region threads outnumber cores, and a spinning waiter
 * would take the CPU from the very holder it waits for.
 */
public final class LegacyExecutionDomain {
    /** Whether a contended body is bound to its caller's world ownership. Replaced by tests. */
    static volatile BooleanSupplier ownsWorldState = () ->
        fun.bm.lecithin.config.modules.CompatConfig.legacyOwnerBoundEntries && currentThreadOwnsWorldState();

    private static final VarHandle OWNER;
    private static final VarHandle MAX_WAIT;
    private static final VarHandle ACQUISITIONS;
    static {
        try {
            final MethodHandles.Lookup lookup = MethodHandles.lookup();
            OWNER = lookup.findVarHandle(LegacyExecutionDomain.class, "owner", LegacyCalls.Channel.class);
            MAX_WAIT = lookup.findVarHandle(LegacyExecutionDomain.class, "maxWaitNanos", long.class);
            ACQUISITIONS = lookup.findVarHandle(LegacyExecutionDomain.class, "acquisitions", long.class);
        } catch (final ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final String name;
    /** The thread inside, as its channel; taken only by compare-and-set, released only by itself. */
    private volatile LegacyCalls.Channel owner;
    /** Nesting of the holder's entries; read and written by the holder only. */
    private int depth;
    // Contended calls not yet started, oldest first. A released domain goes to the oldest.
    private final TreeMap<Long, Request<?>> waiting = new TreeMap<>();
    /** {@code waiting.size()} and its first entry, readable without the monitor. */
    private volatile int waiters;
    private volatile Request<?> head;
    private long tickets;
    /** Entries, re-entries included. Counted by the holder only, so no atomic update is needed. */
    private volatile long acquisitions;
    final LongAdder contended = new LongAdder();
    final LongAdder callbacks = new LongAdder();
    final LongAdder ownerBoundWaits = new LongAdder();
    final LongAdder waitNanos = new LongAdder();
    volatile long maxWaitNanos;

    LegacyExecutionDomain(final String name) { this.name = name; }
    public String name() { return this.name; }
    public boolean isHeldByCurrentThread() {
        final LegacyCalls.Channel current = this.owner;
        return current != null && current.thread == Thread.currentThread();
    }

    private static boolean currentThreadOwnsWorldState() {
        return ca.spottedleaf.moonrise.common.util.TickThread.isTickThread()
            && (io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegion() != null
                || io.papermc.paper.threadedregions.RegionizedServer.isGlobalTickThread());
    }

    /**
     * Enter without waiting and without the monitor: the holder re-entering, or a free domain with
     * nobody waiting for it. {@code false} leaves the decision to the queued path.
     */
    boolean tryEnterFast(final LegacyCalls.Channel self) {
        final LegacyCalls.Channel current = this.owner;
        if (current == self) {
            this.depth++;
        } else if (current != null || this.waiters != 0 || !OWNER.compareAndSet(this, null, self)) {
            return false;
        } else {
            this.depth = 1;
            self.holds++;
        }
        this.countAcquisition();
        return true;
    }

    /** @param request the contended call entering, or {@code null} for a fresh entry */
    private synchronized boolean tryEnter(final Request<?> request, final LegacyCalls.Channel self) {
        final LegacyCalls.Channel current = this.owner;
        if (current == self) {
            this.depth++;
            this.countAcquisition();
            return true;
        }
        if (current != null) return false;
        final Request<?> first = this.head;
        // The oldest waiter enters first. A call made above that waiter's parked frame on the same
        // thread may enter too: the older call cannot continue before this one returns.
        if (first != null && first != request && first.caller != self) return false;
        // A fast entry may take a domain it saw free with nobody waiting; ownership is only ever a CAS.
        if (!OWNER.compareAndSet(this, null, self)) return false;
        this.depth = 1;
        self.holds++;
        this.countAcquisition();
        return true;
    }

    /** Holder only: plain read-increment-write, published without a fence. */
    private void countAcquisition() { ACQUISITIONS.setOpaque(this, this.acquisitions + 1); }
    long acquisitions() { return this.acquisitions; }

    void exit(final LegacyCalls.Channel self) {
        if (this.owner != self) throw new IllegalStateException("Leaving foreign legacy domain " + this.name);
        if (--this.depth == 0) {
            self.holds--;
            this.owner = null;
            // After the release: a waiter counted before it re-checked the holder is seen here.
            if (this.waiters != 0) {
                final Request<?> first = this.head;
                if (first != null) LockSupport.unpark(first.caller.thread);
            }
        }
    }

    /** Whose progress a waiting call needs: the holder, or the older waiter a free domain goes to. */
    private synchronized LegacyCalls.Channel blocker(final Request<?> request) {
        final LegacyCalls.Channel current = this.owner;
        if (current != null || this.waiting.isEmpty()) return current;
        final Request<?> first = this.waiting.firstEntry().getValue();
        return first == request ? null : first.caller;
    }

    private synchronized void enqueue(final Request<?> request) {
        this.waiting.put(request.ticket = this.tickets++, request);
        request.inQueue = true;
        this.head = this.waiting.firstEntry().getValue();
        this.waiters = this.waiting.size();
    }

    private synchronized void dequeue(final Request<?> request) { this.unqueue(request); }

    /** A queued call entering: take the domain and leave the queue in one monitor section. */
    private synchronized boolean enterQueued(final Request<?> request, final LegacyCalls.Channel self) {
        if (!this.tryEnter(request, self)) return false;
        if (!request.started.compareAndSet(false, true)) {
            this.exit(self);
            return false;
        }
        this.unqueue(request);
        return true;
    }

    /** Monitor held. */
    private void unqueue(final Request<?> request) {
        if (!request.inQueue) return;
        request.inQueue = false;
        this.waiting.remove(request.ticket, request);
        final Map.Entry<Long, Request<?>> first = this.waiting.firstEntry();
        this.head = first == null ? null : first.getValue();
        this.waiters = this.waiting.size();
    }

    /** Return or throw only after the complete synchronous body has finished. */
    public <T> T call(final Supplier<T> body) {
        final LegacyCalls.Channel self = LegacyCalls.current();
        if (this.tryEnterFast(self) || (this.mayEnterUnqueued(self) && this.tryEnter(null, self))) {
            try { return body.get(); }
            finally { this.exit(self); }
        }
        return this.callContended(body, self);
    }

    /** Without the monitor: whether {@link #tryEnter} could admit a fresh entry. A miss only queues it. */
    private boolean mayEnterUnqueued(final LegacyCalls.Channel self) {
        if (this.owner != null) return false;
        final Request<?> first = this.head;
        return first == null || first.caller == self;
    }

    private <T> T callContended(final Supplier<T> body, final LegacyCalls.Channel self) {
        this.contended.increment();
        final long start = System.nanoTime();
        final Request<T> request = new Request<>(body, ownsWorldState.getAsBoolean(), self);
        this.enqueue(request);
        try {
            return LegacyCalls.await(request.result, request::attempt, () -> this.blocker(request));
        } finally {
            if (request.inQueue) this.dequeue(request);
            request.removeCallbacks();
            final long elapsed = System.nanoTime() - start;
            this.waitNanos.add(elapsed);
            long max;
            while (elapsed > (max = this.maxWaitNanos) && !MAX_WAIT.compareAndSet(this, max, elapsed)) {
                Thread.onSpinWait();
            }
        }
    }

    private final class Request<T> {
        final Supplier<T> body;
        final boolean ownerBound;
        final LegacyCalls.Channel caller;
        final CompletableFuture<T> result = new CompletableFuture<>();
        final AtomicBoolean started = new AtomicBoolean();
        long ticket;
        /** In {@link #waiting}; written under the monitor. */
        volatile boolean inQueue;
        boolean counted;
        // Only the requesting thread modifies this map; created on the first hand-off.
        Map<LegacyCalls.Channel, Runnable> queued;
        Request(final Supplier<T> body, final boolean ownerBound, final LegacyCalls.Channel caller) {
            this.body = body;
            this.ownerBound = ownerBound;
            this.caller = caller;
        }

        void attempt() {
            if (this.run()) return;
            final LegacyCalls.Channel target = LegacyExecutionDomain.this.owner;
            if (target == null || !target.isWaiting() || (this.queued != null && this.queued.containsKey(target))) return;
            if (this.ownerBound && !LegacyCalls.waitsOn(target, this.caller)) {
                // The holder waits for something else; it will release. Run here, as the owner.
                if (!this.counted) { this.counted = true; LegacyExecutionDomain.this.ownerBoundWaits.increment(); }
                return;
            }
            final Runnable callback = () -> {
                if (this.run()) LegacyExecutionDomain.this.callbacks.increment();
            };
            if (this.queued == null) this.queued = new IdentityHashMap<>(4);
            this.queued.put(target, callback);
            target.offer(callback);
        }

        boolean run() {
            if (this.started.get()) return false;
            // The requester itself, or the holder servicing a hand-off in its own wait.
            final LegacyCalls.Channel self = LegacyCalls.current();
            // Without the monitor: a domain another thread holds cannot be entered, and its release
            // wakes whoever is next in line.
            final LegacyCalls.Channel current = LegacyExecutionDomain.this.owner;
            if (current != null && current != self) return false;
            if (!LegacyExecutionDomain.this.enterQueued(this, self)) return false;
            LegacyCalls.complete(this.result, () -> {
                try { return this.body.get(); }
                finally { LegacyExecutionDomain.this.exit(self); }
            });
            return true;
        }
        void removeCallbacks() { if (this.queued != null) this.queued.forEach(LegacyCalls.Channel::remove); }
    }

    String describeOwner() {
        final LegacyCalls.Channel current = this.owner;
        return current == null ? "-" : current.thread.getName();
    }
}
