package fun.bm.lecithin.compat.legacy;

import java.util.ArrayDeque;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lecithin: the legacy lane - where a legacy plugin's work runs when it has no world anchor.
 *
 * <h2>Why not the global region</h2>
 * A task a legacy plugin scheduled from {@code onEnable}, from its own timer thread or from the global
 * tick has no world owner. Sending it to the global region used to be the only option, and it made
 * the global tick the physical home of every such plugin's logic: its datastore saves, its player
 * sweeps and its cleanup passes all ran on the one thread that also drives world time, weather and
 * every other server-scope tick, and any entity or chunk access inside them failed there anyway.
 * The global region is the owner of <i>global state</i>, not of "work nobody owns".
 *
 * <p>The lane separates the three things that were fused: <b>timing</b> still comes from the global
 * clock (the timer that fires the work is a global-region task, so tick-based delays keep their
 * meaning), <b>logical execution</b> is the plugin's own domain, and <b>physical ownership</b> is
 * nobody - lane threads are not tick threads, so an ownership check inside lane work fails, at the
 * access, with the thread named. Nothing is relocated onto a thread that owns something it should
 * not.
 *
 * <h2>Ordering</h2>
 * Each plugin gets one mailbox. The mailbox is drained by at most one lane thread at a time, in
 * submission order, which is Paper's FIFO order for tasks due on the same tick. Different plugins'
 * mailboxes drain in parallel on the shared pool, so an independent plugin never waits behind
 * another plugin's lane work.
 */
final class LegacyLane {

    private static final AtomicInteger THREAD_IDS = new AtomicInteger();

    /**
     * Grows with demand and shrinks when idle. The pool bounds nothing about correctness - each
     * mailbox is serial on its own - so it only needs to be large enough that one plugin's slow body
     * never holds up another plugin's mailbox.
     */
    private static final ThreadPoolExecutor POOL = new ThreadPoolExecutor(
            0, Integer.MAX_VALUE, 30L, TimeUnit.SECONDS, new SynchronousQueue<>(),
            runnable -> {
                final Thread thread = new Thread(runnable, "Lecithin Legacy Lane #" + THREAD_IDS.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });

    private final LegacyPluginState state;
    private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    private boolean draining;
    private boolean closed;

    LegacyLane(final LegacyPluginState state) {
        this.state = state;
    }

    /**
     * Queue {@code work} behind everything already queued for this plugin.
     *
     * @return {@code false} when the plugin's lane is closed (the plugin is disabled)
     */
    boolean submit(final Runnable work) {
        synchronized (this) {
            if (this.closed) {
                return false;
            }
            this.queue.add(work);
            if (this.draining) {
                return true;
            }
            this.draining = true;
        }
        POOL.execute(this::drain);
        return true;
    }

    private void drain() {
        for (;;) {
            final Runnable next;
            synchronized (this) {
                next = this.queue.poll();
                if (next == null) {
                    this.draining = false;
                    return;
                }
            }
            this.state.runInLane(next);
        }
    }

    /**
     * Stop accepting work and drop what has not started. The body running right now, if any, is
     * finished by the domain the disabling thread has to enter first.
     */
    void close() {
        synchronized (this) {
            this.closed = true;
            this.queue.clear();
        }
    }

    synchronized void reopen() {
        this.closed = false;
    }

    synchronized int queued() {
        return this.queue.size();
    }
}
