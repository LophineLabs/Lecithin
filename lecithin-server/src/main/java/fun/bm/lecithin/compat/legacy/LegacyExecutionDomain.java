package fun.bm.lecithin.compat.legacy;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lecithin: the serial execution domain of one legacy (non-Folia) plugin.
 *
 * <h2>What it replaces</h2>
 * On Paper every synchronous entry into a plugin - a listener, a sync task, a command, a service
 * call made from the main thread - ran on one thread, so a plugin written for Paper never had two of
 * its own entry points running at once, and every write one entry made was visible to the next.
 * Folia runs those entries on whichever region thread happens to own the triggering object, so two
 * players' join and quit callbacks can be inside the same plugin's unsynchronised maps at the same
 * moment. A domain gives the plugin back exactly that property: at most one thread executes inside a
 * plugin's domain at a time, and each hand-over is a {@code synchronized} release/acquire, so memory
 * written inside the domain is visible to the next entry.
 *
 * <h2>Why this is not "one coarse lock per plugin"</h2>
 * A plain lock per plugin deadlocks the moment two plugins call into each other from two threads:
 * thread A is inside plugin X and fires an event plugin Y listens to, while thread B is inside Y and
 * fires one X listens to. On Paper that is not a deadlock, it is re-entrancy - one thread, X calls Y
 * calls X. A domain therefore keeps a wait-for graph, and the thread that would close a cycle does
 * not wait: every other thread in the cycle is parked at a call-out point (it is waiting to enter a
 * domain, which only ever happens at a platform entry point), so the closing thread may run inside
 * the parked thread's domain the same way Paper's single thread would have re-entered it. The parked
 * owner is suspended, not preempted: it cannot resume until the closing thread has left every domain
 * it borrowed, because what it waits for is further up the closing thread's own stack. The domain is
 * handed back with its original hold count when the borrower leaves.
 *
 * <p>Domains are only ever entered at platform entry points and are released in {@code finally}, so
 * acquisition is strictly nested per thread. Nothing here is held across a scheduler hand-off.
 *
 * <h2>What it does not do</h2>
 * It never times out and never lets a thread in without either owning the domain or proving a cycle.
 * A wait that lasts unusually long is reported, with the owner's stack, once per episode - a
 * diagnostic, not a recovery.
 */
public final class LegacyExecutionDomain {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * One monitor for the whole wait-for graph. Held only for bookkeeping - never while plugin code
     * runs - so its critical sections are a handful of field reads and writes.
     */
    private static final Object GRAPH = new Object();

    /**
     * Thread -> the domain it is currently waiting to enter. Guarded by {@link #GRAPH}.
     */
    private static final Map<Thread, LegacyExecutionDomain> WAITING = new HashMap<>();

    private static final long SLOW_WAIT_NANOS = TimeUnit.SECONDS.toNanos(5);

    private record Suspension(Thread owner, int depth) {
    }

    private final String name;

    // Guarded by GRAPH, except the volatile read of owner in isHeldByCurrentThread.
    private volatile Thread owner;
    private int depth;
    private final ArrayDeque<Suspension> suspended = new ArrayDeque<>(1);

    final LongAdder acquisitions = new LongAdder();
    final LongAdder contended = new LongAdder();
    final LongAdder borrows = new LongAdder();
    final LongAdder waitNanos = new LongAdder();
    volatile long maxWaitNanos;

    LegacyExecutionDomain(final String name) {
        this.name = name;
    }

    public String name() {
        return this.name;
    }

    public boolean isHeldByCurrentThread() {
        return this.owner == Thread.currentThread();
    }

    /**
     * Enter the domain; returns once the calling thread owns it or has proven that waiting would
     * close a cycle. Must be paired with {@link #exit()} in a {@code finally}.
     */
    public void enter() {
        final Thread self = Thread.currentThread();
        if (this.owner == self) {
            // Only the owner ever changes depth while it is running, so the re-entrant fast path
            // needs no graph bookkeeping.
            this.depth++;
            return;
        }
        long waitStart = 0L;
        boolean interrupted = false;
        boolean reported = false;
        synchronized (GRAPH) {
            try {
                for (;;) {
                    final Thread current = this.owner;
                    if (current == null) {
                        this.owner = self;
                        this.depth = 1;
                        break;
                    }
                    if (current == self) {
                        this.depth++;
                        break;
                    }
                    if (waitsOn(current, self)) {
                        this.suspended.push(new Suspension(current, this.depth));
                        this.owner = self;
                        this.depth = 1;
                        this.borrows.increment();
                        break;
                    }
                    if (waitStart == 0L) {
                        waitStart = System.nanoTime();
                        this.contended.increment();
                    }
                    WAITING.put(self, this);
                    try {
                        GRAPH.wait(1000L);
                    } catch (final InterruptedException e) {
                        // An entry point cannot be abandoned half way; remember and restore it.
                        interrupted = true;
                    } finally {
                        WAITING.remove(self);
                    }
                    if (!reported && System.nanoTime() - waitStart > SLOW_WAIT_NANOS) {
                        reported = true;
                        reportSlowWait(self, this.owner);
                    }
                }
            } finally {
                if (interrupted) {
                    self.interrupt();
                }
            }
        }
        this.acquisitions.increment();
        if (waitStart != 0L) {
            final long waited = System.nanoTime() - waitStart;
            this.waitNanos.add(waited);
            if (waited > this.maxWaitNanos) {
                this.maxWaitNanos = waited;
            }
        }
    }

    public void exit() {
        final Thread self = Thread.currentThread();
        if (this.owner != self) {
            throw new IllegalStateException("[Lecithin] " + self.getName() + " left legacy domain " + this.name
                    + " it does not own (owner: " + this.owner + ')');
        }
        if (this.depth > 1) {
            this.depth--;
            return;
        }
        synchronized (GRAPH) {
            final Suspension resumed = this.suspended.poll();
            if (resumed != null) {
                this.owner = resumed.owner();
                this.depth = resumed.depth();
            } else {
                this.owner = null;
                this.depth = 0;
            }
            GRAPH.notifyAll();
        }
    }

    /**
     * {@code true} when {@code thread} is, directly or through other waiting threads, waiting for a
     * domain {@code self} owns. Called with {@link #GRAPH} held.
     */
    private static boolean waitsOn(final Thread thread, final Thread self) {
        Thread cursor = thread;
        // The graph is finite and every hop moves to a distinct waiting thread; the bound only
        // guards against a corrupted graph turning into a spin.
        for (int hops = 0; hops < 1024 && cursor != null; hops++) {
            final LegacyExecutionDomain waitedFor = WAITING.get(cursor);
            if (waitedFor == null) {
                return false;
            }
            final Thread next = waitedFor.owner;
            if (next == self) {
                return true;
            }
            cursor = next;
        }
        return false;
    }

    private void reportSlowWait(final Thread waiter, final Thread holder) {
        final StringBuilder stack = new StringBuilder();
        if (holder != null) {
            for (final StackTraceElement element : holder.getStackTrace()) {
                stack.append("\n\tat ").append(element);
            }
        }
        LOGGER.warn("[Lecithin] {} has waited more than 5s to enter legacy domain {}; held by {}. "
                        + "No timeout is applied - this is a report, not a recovery. Holder stack:{}",
                waiter.getName(), this.name, holder == null ? "<nobody>" : holder.getName(), stack);
    }

    /**
     * Human-readable holder, for diagnostics only.
     */
    String describeOwner() {
        final Thread current = this.owner;
        return current == null ? "-" : current.getName();
    }
}
