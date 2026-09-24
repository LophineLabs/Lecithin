package fun.bm.lecithin.compatsuite.fixture.scenario;

import fun.bm.lecithin.compatsuite.fixture.Harness;
import fun.bm.lecithin.compatsuite.fixture.Probe;
import fun.bm.lecithin.compatsuite.fixture.Scenario;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Task lifetime and plugin-disable cleanup, observed on a second plugin ("the victim", shipped next to
 * every fixture and compiled against the same era) so the observer survives the disable.
 *
 * <p>Victim tasks: started in the victim's own {@code onEnable} (sync repeating, a 30-minute delayed task,
 * async repeating), plus two sync repeating tasks the fixture asks the victim to start from test player
 * B's contexts - B's command callback (a region thread on region-threaded servers) and B's async chat
 * event. The runner then disconnects B.
 *
 * <p>Paper's contract: a Bukkit task belongs to its plugin, not to whoever happened to be on the stack
 * when it was scheduled. It keeps running after that player leaves, and it stops - all of it - when the
 * plugin is disabled.
 */
public final class LifecycleScenarios {

    static final String REGION_CTX = "region-ctx";
    static final String ASYNC_CHAT_CTX = "async-chat-ctx";
    static final String[] REPEATING = {"enable-sync", "enable-async", REGION_CTX, ASYNC_CHAT_CTX};

    private LifecycleScenarios() {
    }

    public static void install(final Harness h) {
        h.add(new TaskLifetime());
        h.add(new DisableStopsTasks());
        h.add(new ScheduleForDisabledPlugin());
    }

    static int pendingOwnedBy(final Plugin plugin) {
        int n = 0;
        for (final BukkitTask t : Bukkit.getScheduler().getPendingTasks()) {
            if (t.getOwner() == plugin) {
                n++;
            }
        }
        return n;
    }

    static long delta(final Map<String, Long> from, final Map<String, Long> to, final String key) {
        final Long a = from.get(key);
        final Long b = to.get(key);
        return (b == null ? 0 : b) - (a == null ? 0 : a);
    }

    static final class TaskLifetime extends Scenario {
        private final Map<String, Object> scheduleFailures = new ConcurrentHashMap<String, Object>();
        private final AtomicReference<Map<String, Long>> first = new AtomicReference<Map<String, Long>>();
        private final AtomicReference<Map<String, Long>> second = new AtomicReference<Map<String, Long>>();

        TaskLifetime() {
            super("lifecycle.task_outlives_scheduling_context", "lifecycle", LIFECYCLE_LIVENESS,
                    "repeating tasks keep running after the player whose callback scheduled them has left (tasks belong to the plugin)");
        }

        @Override
        public void onTrigger(final Harness h, final Player player, final String role, final String trigger) {
            if ("B".equals(role) && "life".equals(trigger)) {
                start(h, REGION_CTX);
            }
        }

        @Override
        public void onAsyncChat(final Harness h, final Player player, final String role, final String message,
                                final boolean eventAsync, final String eventType) {
            if ("B".equals(role) && "life".equals(message) && "AsyncPlayerChatEvent".equals(eventType)) {
                start(h, ASYNC_CHAT_CTX);
            }
        }

        private void start(final Harness h, final String key) {
            this.result.diag(key + ".scheduleThread", Probe.thread());
            try {
                h.callVictim("startRepeating", new Class<?>[]{String.class}, key);
                this.scheduleFailures.put(key, Probe.NONE);
            } catch (final Throwable t) {
                this.scheduleFailures.put(key, Probe.exName(t));
                this.result.diag(key + ".scheduleMessage", Probe.exMessage(t));
            }
        }

        @Override
        public void onPhase(final Harness h, final String phase) throws Throwable {
            if (!LIFECYCLE_LIVENESS.equals(phase)) {
                return;
            }
            this.result.expect(REGION_CTX + ".scheduleException", Probe.NONE)
                    .expect(ASYNC_CHAT_CTX + ".scheduleException", Probe.NONE)
                    .expect("victimEnableErrors", Probe.NONE);
            for (final String key : REPEATING) {
                this.result.expect("alive." + key, true);
            }
            @SuppressWarnings("unchecked") final Map<String, String> errors =
                    (Map<String, String>) h.callVictim("errors", new Class<?>[0]);
            this.result.observe("victimEnableErrors", errors.isEmpty() ? Probe.NONE : errors.toString());
            this.first.set(h.victimCounters());
            h.watchdog().schedule(new Runnable() {
                @Override
                public void run() {
                    try {
                        TaskLifetime.this.second.set(h.victimCounters());
                    } catch (final Throwable t) {
                        TaskLifetime.this.result.error(t);
                    }
                }
            }, 1500, TimeUnit.MILLISECONDS);
        }

        @Override
        public void finish(final Harness h) {
            this.result.observe(REGION_CTX + ".scheduleException", Probe.orNotRun(this.scheduleFailures.get(REGION_CTX)))
                    .observe(ASYNC_CHAT_CTX + ".scheduleException", Probe.orNotRun(this.scheduleFailures.get(ASYNC_CHAT_CTX)));
            final Map<String, Long> a = this.first.get();
            final Map<String, Long> b = this.second.get();
            for (final String key : REPEATING) {
                if (a == null || b == null) {
                    this.result.observe("alive." + key, Probe.NOT_RUN);
                    continue;
                }
                final long d = delta(a, b, key);
                this.result.observe("alive." + key, d > 0).diag("runsIn1500ms." + key, d);
            }
        }
    }

    static final class DisableStopsTasks extends Scenario {
        private final AtomicReference<Map<String, Long>> base = new AtomicReference<Map<String, Long>>();
        private final AtomicReference<Map<String, Long>> after = new AtomicReference<Map<String, Long>>();

        DisableStopsTasks() {
            super("lifecycle.disable_stops_all_tasks", "lifecycle", LIFECYCLE_DISABLE,
                    "disablePlugin(victim) cancels every task it owns - sync, async, delayed and context-scheduled - and none runs again");
        }

        @Override
        public void onPhase(final Harness h, final String phase) throws Throwable {
            if (!LIFECYCLE_DISABLE.equals(phase)) {
                return;
            }
            this.result.expect("pendingBeforeDisable", 5).expect("delayedQueuedBeforeDisable", true)
                    .expect("disableException", Probe.NONE).expect("enabledAfterDisable", false)
                    .expect("pendingAfterDisable", 0).expect("anyQueuedAfterDisable", false)
                    .expect("runsAfterDisable", 0);
            final Plugin victim = h.victim();
            final Map<String, Integer> ids = h.victimTaskIds();
            final Integer delayedId = ids.get("enable-delayed-long");
            this.result.observe("pendingBeforeDisable", pendingOwnedBy(victim))
                    .observe("delayedQueuedBeforeDisable", delayedId != null && Bukkit.getScheduler().isQueued(delayedId))
                    .diag("taskIds", ids.toString())
                    .diag("disableThread", Probe.thread());
            try {
                Bukkit.getPluginManager().disablePlugin(victim);
                this.result.observe("disableException", Probe.NONE);
            } catch (final Throwable t) {
                this.result.observe("disableException", Probe.exName(t)).diag("disableMessage", Probe.exMessage(t));
            }
            boolean anyQueued = false;
            for (final Integer id : ids.values()) {
                anyQueued |= Bukkit.getScheduler().isQueued(id);
            }
            this.result.observe("enabledAfterDisable", victim.isEnabled())
                    .observe("pendingAfterDisable", pendingOwnedBy(victim))
                    .observe("anyQueuedAfterDisable", anyQueued);
            // An async body already running at disable time may finish its current run; count from 300 ms on.
            h.watchdog().schedule(new Runnable() {
                @Override
                public void run() {
                    try {
                        DisableStopsTasks.this.base.set(h.victimCounters());
                    } catch (final Throwable t) {
                        DisableStopsTasks.this.result.error(t);
                    }
                }
            }, 300, TimeUnit.MILLISECONDS);
            h.watchdog().schedule(new Runnable() {
                @Override
                public void run() {
                    try {
                        DisableStopsTasks.this.after.set(h.victimCounters());
                    } catch (final Throwable t) {
                        DisableStopsTasks.this.result.error(t);
                    }
                }
            }, 1800, TimeUnit.MILLISECONDS);
        }

        @Override
        public void finish(final Harness h) {
            final Map<String, Long> a = this.base.get();
            final Map<String, Long> b = this.after.get();
            if (a == null || b == null) {
                this.result.observe("runsAfterDisable", Probe.NOT_RUN);
                return;
            }
            long total = 0;
            for (final String key : b.keySet()) {
                final long d = delta(a, b, key);
                total += d;
                if (d != 0) {
                    this.result.diag("runsAfterDisable." + key, d);
                }
            }
            this.result.observe("runsAfterDisable", total);
        }
    }

    static final class ScheduleForDisabledPlugin extends Scenario {
        ScheduleForDisabledPlugin() {
            super("lifecycle.schedule_for_disabled_plugin_rejected", "lifecycle", LIFECYCLE_DISABLE,
                    "scheduling a task for a disabled plugin is refused with IllegalPluginAccessException");
        }

        @Override
        public void onPhase(final Harness h, final String phase) {
            if (!LIFECYCLE_DISABLE.equals(phase)) {
                return;
            }
            this.result.expect("exception", "IllegalPluginAccessException");
            final Plugin victim = h.victim();
            this.result.diag("victimEnabled", victim.isEnabled());
            try {
                Bukkit.getScheduler().runTask(victim, new Runnable() {
                    @Override
                    public void run() {
                    }
                });
                this.result.observe("exception", Probe.NONE);
            } catch (final Throwable t) {
                this.result.observe("exception", Probe.exName(t)).diag("message", Probe.exMessage(t));
            }
        }
    }
}
