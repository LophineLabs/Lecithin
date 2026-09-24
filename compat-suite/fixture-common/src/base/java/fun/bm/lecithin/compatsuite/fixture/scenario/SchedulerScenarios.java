package fun.bm.lecithin.compatsuite.fixture.scenario;

import fun.bm.lecithin.compatsuite.fixture.Harness;
import fun.bm.lecithin.compatsuite.fixture.Probe;
import fun.bm.lecithin.compatsuite.fixture.Scenario;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitScheduler;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The legacy {@link BukkitScheduler} contract: "the main thread, later", repeating, cancel by id, cancel
 * before first run, async, and {@code callSyncMethod}. All of these exist unchanged since Bukkit 1.8.8.
 */
public final class SchedulerScenarios {

    private SchedulerScenarios() {
    }

    public static void install(final Harness h) {
        h.add(new DelayedFromOnEnable());
        h.add(new RepeatingFromOnEnable());
        h.add(new SyncDelayed());
        h.add(new RepeatingCancelById());
        h.add(new BukkitRunnableSelfCancel());
        h.add(new CancelBeforeFirstRun());
        h.add(new RunTaskAsync());
        h.add(new CallSyncMethodFromBukkitAsync());
        h.add(new AsyncThenLegacySync());
    }

    static BukkitScheduler scheduler() {
        return Bukkit.getScheduler();
    }

    /** Shared bookkeeping for "a task that runs and records where". */
    static final class Runs {
        final AtomicInteger count = new AtomicInteger();
        final AtomicReference<Boolean> primary = new AtomicReference<Boolean>();
        final AtomicReference<String> thread = new AtomicReference<String>();

        void hit() {
            this.count.incrementAndGet();
            this.primary.compareAndSet(null, Probe.primary());
            this.thread.compareAndSet(null, Probe.thread());
        }
    }

    static final class DelayedFromOnEnable extends Scenario {
        private final Runs runs = new Runs();

        DelayedFromOnEnable() {
            super("sched.legacy_delayed_from_onenable", "scheduler", SERVER,
                    "scheduleSyncDelayedTask(plugin, task) during onEnable runs once on the main thread after startup");
        }

        @Override
        public void onEnable(final Harness h) {
            this.result.expect("scheduleException", Probe.NONE).expect("runs", 1).expect("primaryThread", true);
            try {
                final int id = scheduler().scheduleSyncDelayedTask(h.plugin, new Runnable() {
                    @Override
                    public void run() {
                        DelayedFromOnEnable.this.runs.hit();
                    }
                });
                this.result.observe("scheduleException", Probe.NONE).diag("taskId", id);
            } catch (final Throwable t) {
                this.result.observe("scheduleException", Probe.exName(t)).diag("scheduleMessage", Probe.exMessage(t));
            }
        }

        @Override
        public void finish(final Harness h) {
            this.result.observe("runs", this.runs.count.get())
                    .observe("primaryThread", Probe.orNotRun(this.runs.primary.get()))
                    .diag("thread", this.runs.thread.get());
        }
    }

    static final class RepeatingFromOnEnable extends Scenario {
        private final Runs runs = new Runs();
        private final AtomicInteger id = new AtomicInteger(-1);

        RepeatingFromOnEnable() {
            super("sched.legacy_repeating_from_onenable", "scheduler", SERVER,
                    "scheduleSyncRepeatingTask during onEnable repeats until cancelTask(id) stops it after 3 runs");
        }

        @Override
        public void onEnable(final Harness h) {
            this.result.expect("scheduleException", Probe.NONE).expect("runs", 3).expect("primaryThread", true);
            try {
                this.id.set(scheduler().scheduleSyncRepeatingTask(h.plugin, new Runnable() {
                    @Override
                    public void run() {
                        RepeatingFromOnEnable.this.runs.hit();
                        if (RepeatingFromOnEnable.this.runs.count.get() == 3) {
                            scheduler().cancelTask(RepeatingFromOnEnable.this.id.get());
                        }
                    }
                }, 1L, 1L));
                this.result.observe("scheduleException", Probe.NONE);
            } catch (final Throwable t) {
                this.result.observe("scheduleException", Probe.exName(t)).diag("scheduleMessage", Probe.exMessage(t));
            }
        }

        @Override
        public void finish(final Harness h) {
            this.result.observe("runs", this.runs.count.get())
                    .observe("primaryThread", Probe.orNotRun(this.runs.primary.get()))
                    .diag("thread", this.runs.thread.get());
        }
    }

    static final class SyncDelayed extends Scenario {
        private final Runs runs = new Runs();
        private final AtomicLong elapsedMs = new AtomicLong(-1);

        SyncDelayed() {
            super("sched.legacy_sync_delayed", "scheduler", SERVER,
                    "scheduleSyncDelayedTask(plugin, task, 10) from a console command runs once, on the main thread, no earlier than ~10 ticks");
        }

        @Override
        public void onPhase(final Harness h, final String phase) {
            if (!SERVER.equals(phase)) {
                return;
            }
            this.result.expect("scheduleException", Probe.NONE).expect("runs", 1).expect("primaryThread", true)
                    .expect("delayRespected", true);
            final long start = System.nanoTime();
            try {
                scheduler().scheduleSyncDelayedTask(h.plugin, new Runnable() {
                    @Override
                    public void run() {
                        SyncDelayed.this.elapsedMs.compareAndSet(-1, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
                        SyncDelayed.this.runs.hit();
                    }
                }, 10L);
                this.result.observe("scheduleException", Probe.NONE);
            } catch (final Throwable t) {
                this.result.observe("scheduleException", Probe.exName(t)).diag("scheduleMessage", Probe.exMessage(t));
            }
        }

        @Override
        public void finish(final Harness h) {
            final long ms = this.elapsedMs.get();
            this.result.observe("runs", this.runs.count.get())
                    .observe("primaryThread", Probe.orNotRun(this.runs.primary.get()))
                    // 10 ticks is 500 ms at 20 TPS; half of that tolerates tick-phase jitter, not "ran at once".
                    .observe("delayRespected", ms < 0 ? Probe.NOT_RUN : (Object) (ms >= 250))
                    .diag("elapsedMs", ms)
                    .diag("thread", this.runs.thread.get());
        }
    }

    static final class RepeatingCancelById extends Scenario {
        private final Runs runs = new Runs();
        private final AtomicInteger id = new AtomicInteger(-1);
        private final AtomicReference<Object> queuedAfterCancel = new AtomicReference<Object>();

        RepeatingCancelById() {
            super("sched.legacy_repeating_cancel_by_id", "scheduler", SERVER,
                    "scheduleSyncRepeatingTask(period 2) stops at exactly 5 runs after cancelTask(id), and isQueued(id) turns false");
        }

        @Override
        public void onPhase(final Harness h, final String phase) {
            if (!SERVER.equals(phase)) {
                return;
            }
            this.result.expect("scheduleException", Probe.NONE).expect("runs", 5).expect("primaryThread", true)
                    .expect("queuedAfterCancel", false);
            try {
                this.id.set(scheduler().scheduleSyncRepeatingTask(h.plugin, new Runnable() {
                    @Override
                    public void run() {
                        RepeatingCancelById.this.runs.hit();
                        if (RepeatingCancelById.this.runs.count.get() == 5) {
                            final int taskId = RepeatingCancelById.this.id.get();
                            scheduler().cancelTask(taskId);
                            RepeatingCancelById.this.queuedAfterCancel.set(scheduler().isQueued(taskId));
                        }
                    }
                }, 2L, 2L));
                this.result.observe("scheduleException", Probe.NONE).diag("taskId", this.id.get());
            } catch (final Throwable t) {
                this.result.observe("scheduleException", Probe.exName(t)).diag("scheduleMessage", Probe.exMessage(t));
            }
        }

        @Override
        public void finish(final Harness h) {
            this.result.observe("runs", this.runs.count.get())
                    .observe("primaryThread", Probe.orNotRun(this.runs.primary.get()))
                    .observe("queuedAfterCancel", Probe.orNotRun(this.queuedAfterCancel.get()))
                    .diag("thread", this.runs.thread.get());
        }
    }

    static final class BukkitRunnableSelfCancel extends Scenario {
        private final Runs runs = new Runs();

        BukkitRunnableSelfCancel() {
            super("sched.bukkitrunnable_timer_self_cancel", "scheduler", SERVER,
                    "BukkitRunnable.runTaskTimer(plugin, 1, 1) that calls cancel() on its 3rd run runs exactly 3 times");
        }

        @Override
        public void onPhase(final Harness h, final String phase) {
            if (!SERVER.equals(phase)) {
                return;
            }
            this.result.expect("scheduleException", Probe.NONE).expect("runs", 3).expect("primaryThread", true);
            try {
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        BukkitRunnableSelfCancel.this.runs.hit();
                        if (BukkitRunnableSelfCancel.this.runs.count.get() == 3) {
                            cancel();
                        }
                    }
                }.runTaskTimer(h.plugin, 1L, 1L);
                this.result.observe("scheduleException", Probe.NONE);
            } catch (final Throwable t) {
                this.result.observe("scheduleException", Probe.exName(t)).diag("scheduleMessage", Probe.exMessage(t));
            }
        }

        @Override
        public void finish(final Harness h) {
            this.result.observe("runs", this.runs.count.get())
                    .observe("primaryThread", Probe.orNotRun(this.runs.primary.get()))
                    .diag("thread", this.runs.thread.get());
        }
    }

    static final class CancelBeforeFirstRun extends Scenario {
        private final Runs runs = new Runs();

        CancelBeforeFirstRun() {
            super("sched.legacy_cancel_before_first_run", "scheduler", SERVER,
                    "a delayed task cancelled by id before it is due never runs; isQueued is true before and false after");
        }

        @Override
        public void onPhase(final Harness h, final String phase) {
            if (!SERVER.equals(phase)) {
                return;
            }
            this.result.expect("scheduleException", Probe.NONE).expect("queuedBeforeCancel", true)
                    .expect("queuedAfterCancel", false).expect("runs", 0);
            try {
                final int id = scheduler().scheduleSyncDelayedTask(h.plugin, new Runnable() {
                    @Override
                    public void run() {
                        CancelBeforeFirstRun.this.runs.hit();
                    }
                }, 40L);
                final boolean before = scheduler().isQueued(id);
                scheduler().cancelTask(id);
                this.result.observe("scheduleException", Probe.NONE)
                        .observe("queuedBeforeCancel", before)
                        .observe("queuedAfterCancel", scheduler().isQueued(id));
            } catch (final Throwable t) {
                this.result.observe("scheduleException", Probe.exName(t)).diag("scheduleMessage", Probe.exMessage(t));
            }
        }

        @Override
        public void finish(final Harness h) {
            this.result.observe("runs", this.runs.count.get());
        }
    }

    static final class RunTaskAsync extends Scenario {
        private final Runs runs = new Runs();

        RunTaskAsync() {
            super("sched.run_task_asynchronously", "scheduler", SERVER,
                    "runTaskAsynchronously runs once, off the main thread");
        }

        @Override
        public void onPhase(final Harness h, final String phase) {
            if (!SERVER.equals(phase)) {
                return;
            }
            this.result.expect("scheduleException", Probe.NONE).expect("runs", 1).expect("primaryThread", false);
            try {
                scheduler().runTaskAsynchronously(h.plugin, new Runnable() {
                    @Override
                    public void run() {
                        RunTaskAsync.this.runs.hit();
                    }
                });
                this.result.observe("scheduleException", Probe.NONE);
            } catch (final Throwable t) {
                this.result.observe("scheduleException", Probe.exName(t)).diag("scheduleMessage", Probe.exMessage(t));
            }
        }

        @Override
        public void finish(final Harness h) {
            this.result.observe("runs", this.runs.count.get())
                    .observe("primaryThread", Probe.orNotRun(this.runs.primary.get()))
                    .diag("thread", this.runs.thread.get());
        }
    }

    static final class CallSyncMethodFromBukkitAsync extends Scenario {
        private final AtomicReference<Object> value = new AtomicReference<Object>();
        private final AtomicReference<Object> callablePrimary = new AtomicReference<Object>();
        private final AtomicReference<String> failure = new AtomicReference<String>();

        CallSyncMethodFromBukkitAsync() {
            super("sched.call_sync_method_from_bukkit_async", "scheduler", SERVER,
                    "callSyncMethod from a Bukkit async task returns the callable's value, computed on the main thread");
        }

        @Override
        public void onPhase(final Harness h, final String phase) {
            if (!SERVER.equals(phase)) {
                return;
            }
            this.result.expect("exception", Probe.NONE).expect("value", 42).expect("callablePrimary", true);
            scheduler().runTaskAsynchronously(h.plugin, new Runnable() {
                @Override
                public void run() {
                    try {
                        final Future<Integer> f = scheduler().callSyncMethod(h.plugin, new Callable<Integer>() {
                            @Override
                            public Integer call() {
                                CallSyncMethodFromBukkitAsync.this.callablePrimary.set(Probe.primary());
                                return 42;
                            }
                        });
                        CallSyncMethodFromBukkitAsync.this.value.set(f.get(5, TimeUnit.SECONDS));
                        CallSyncMethodFromBukkitAsync.this.failure.set(Probe.NONE);
                    } catch (final Throwable t) {
                        CallSyncMethodFromBukkitAsync.this.failure.set(Probe.exName(t));
                        CallSyncMethodFromBukkitAsync.this.result.diag("message", Probe.exMessage(t));
                    }
                }
            });
        }

        @Override
        public void finish(final Harness h) {
            this.result.observe("exception", Probe.orNotRun(this.failure.get()))
                    .observe("value", Probe.orNotRun(this.value.get()))
                    .observe("callablePrimary", Probe.orNotRun(this.callablePrimary.get()));
        }
    }

    static final class AsyncThenLegacySync extends Scenario {
        private final Runs runs = new Runs();
        private final AtomicReference<String> innerFailure = new AtomicReference<String>();

        AsyncThenLegacySync() {
            super("sched.bukkit_async_then_legacy_sync", "scheduler", SERVER,
                    "a Bukkit async task started from a console command can hop back with runTask; the task runs once on the main thread");
        }

        @Override
        public void onPhase(final Harness h, final String phase) {
            if (!SERVER.equals(phase)) {
                return;
            }
            this.result.expect("innerScheduleException", Probe.NONE).expect("runs", 1).expect("primaryThread", true);
            scheduler().runTaskAsynchronously(h.plugin, new Runnable() {
                @Override
                public void run() {
                    try {
                        scheduler().runTask(h.plugin, new Runnable() {
                            @Override
                            public void run() {
                                AsyncThenLegacySync.this.runs.hit();
                            }
                        });
                        AsyncThenLegacySync.this.innerFailure.set(Probe.NONE);
                    } catch (final Throwable t) {
                        AsyncThenLegacySync.this.innerFailure.set(Probe.exName(t));
                        AsyncThenLegacySync.this.result.diag("innerMessage", Probe.exMessage(t));
                    }
                }
            });
        }

        @Override
        public void finish(final Harness h) {
            this.result.observe("innerScheduleException", Probe.orNotRun(this.innerFailure.get()))
                    .observe("runs", this.runs.count.get())
                    .observe("primaryThread", Probe.orNotRun(this.runs.primary.get()))
                    .diag("thread", this.runs.thread.get());
        }
    }
}
