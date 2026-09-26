package fun.bm.lecithin.compat.legacy;

import com.mojang.logging.LogUtils;
import fun.bm.lecithin.compat.LecithinDispatchedTasks;
import io.papermc.paper.threadedregions.RegionizedServer;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.scheduler.CraftTask;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Lecithin: the legacy sync scheduler for managed plugins.
 *
 * <h2>The model</h2>
 * Paper's {@code runTask}/{@code runTaskLater}/{@code runTaskTimer}/{@code callSyncMethod} mean "run
 * this on the main thread, this many ticks from now, until cancelled or the plugin is disabled". That
 * sentence has three parts and Folia needs them separated:
 * <ul>
 *   <li><b>When</b> - tick-based timing. Taken from the global clock (a global-region timer), which is
 *       the one tick counter every region agrees on.</li>
 *   <li><b>As whom</b> - the plugin. The body always runs inside the plugin's
 *       {@link LegacyExecutionDomain}, so it is serial with the plugin's listeners, commands and other
 *       tasks, exactly as on the main thread.</li>
 *   <li><b>Where</b> - the owner of the task's {@link LegacyAffinity}, decided when it fires: the
 *       anchor entity's scheduler if that entity is still present, the anchor region if that region
 *       is loaded, otherwise the {@link LegacyLane}. The global tick thread only ever runs the timer,
 *       never the body.</li>
 * </ul>
 *
 * <h2>Continuation vs. plugin-lifetime work</h2>
 * A one-shot task of at most {@link #CONTINUATION_MAX_DELAY} ticks created on behalf of an entity or
 * a region is a continuation of what that owner was doing - "undo this fake block next tick", "open
 * the menu after the click resolves". It goes straight to the owner's Folia scheduler, the cheapest
 * correct path. If its entity is removed first, the body still runs, on the lane: Paper would have
 * run it, and the plugin checks {@code isOnline()} itself.
 *
 * <p>Everything else - repeating tasks, long delays, work with no anchor - is plugin-lifetime work.
 * Its timer is independent of any entity, so a repeating task started while handling one player's
 * join no longer dies with that player's entity scheduler at logout; it keeps firing, runs where its
 * anchor is when that still exists, and on the lane when it does not. It stops when it is cancelled
 * or its plugin is disabled, as on Paper.
 *
 * <h2>Paper details kept</h2>
 * A repeating task never overlaps itself: a firing that arrives while the previous run has not
 * finished is coalesced, as a late main thread would. Same-tick lane work runs FIFO per plugin.
 * {@code BukkitTask#cancel}, {@code cancelTasks(plugin)}, {@code isQueued} and disable-time
 * cancellation go through {@link LecithinDispatchedTasks}, as for the previous shim.
 */
public final class LegacyScheduler {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Longest one-shot delay still treated as a continuation of the owner's current work: one
     * second. Past that the task outlives the moment that created it and is timed as plugin work.
     */
    static final long CONTINUATION_MAX_DELAY = 20L;

    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private LegacyScheduler() {
    }

    /**
     * @return the task once routed, or {@code null} when its plugin is not managed
     */
    public static CraftTask tryRoute(final CraftTask task, final long delay, final long period) {
        final Plugin plugin = task.getOwner();
        final LegacyPluginState state = LegacyPluginRuntime.stateOf(plugin);
        if (state == null) {
            return null;
        }
        final LegacyAffinity affinity = LegacyPluginRuntime.affinityForNewTask();
        final LegacyTask legacy = new LegacyTask(state, task, affinity, period > 0, period);
        // Registered before it can possibly run, so a fast one-shot that finishes immediately
        // removes an entry that exists.
        LecithinDispatchedTasks.track(plugin, task, legacy);
        try {
            legacy.start(Math.max(delay, 1L));
        } catch (final Throwable t) {
            LecithinDispatchedTasks.forget(plugin, task.getTaskId());
            throw t;
        }
        report(state, task, legacy, period);
        return task;
    }

    private static void report(final LegacyPluginState state, final CraftTask task, final LegacyTask legacy, final long period) {
        final String taskClass = taskClassOf(task);
        final String key = state.plugin.getName() + '|' + taskClass + '|' + legacy.route;
        if (!REPORTED.add(key)) {
            return;
        }
        LOGGER.info("[Lecithin] {}: legacy sync task {} (period={}) managed as {}; anchor: {}",
                state.plugin.getName(), taskClass, period, legacy.route, legacy.affinity.describe());
    }

    static String taskClassOf(final CraftTask task) {
        final Object body = task.rTask != null ? task.rTask : task.cTask;
        return body == null ? task.getClass().getName() : body.getClass().getName();
    }

    /**
     * One managed legacy task. It is also the {@link ScheduledTask} the dispatched-task registry
     * cancels, so {@code BukkitTask#cancel} reaches whichever Folia task currently carries it.
     */
    static final class LegacyTask implements ScheduledTask {

        private final LegacyPluginState state;
        private final CraftTask craftTask;
        final LegacyAffinity affinity;
        private final boolean repeating;
        private final long period;
        private final AtomicBoolean inFlight = new AtomicBoolean();
        private volatile boolean cancelled;
        private volatile boolean finished;
        private volatile boolean running;
        private volatile ScheduledTask carrier;
        String route = "?";

        LegacyTask(final LegacyPluginState state, final CraftTask craftTask, final LegacyAffinity affinity,
                   final boolean repeating, final long period) {
            this.state = state;
            this.craftTask = craftTask;
            this.affinity = affinity;
            this.repeating = repeating;
            this.period = period;
        }

        void start(final long delay) {
            final Plugin plugin = this.state.plugin;
            if (!this.repeating && delay <= CONTINUATION_MAX_DELAY) {
                if (this.affinity.kind() == LegacyAffinity.Kind.ENTITY) {
                    this.inFlight.set(true);
                    final ScheduledTask scheduled = this.affinity.entity().getScheduler().runDelayed(plugin,
                            handle -> this.runOnOwner(true), this::retired, delay);
                    if (scheduled != null) {
                        this.carrier = scheduled;
                        this.route = "entity continuation";
                        this.state.continuationsOnEntity.increment();
                        return;
                    }
                    // Already retired between naming the entity and scheduling on it: the work is
                    // still the plugin's, so time it like any unanchored task.
                    this.inFlight.set(false);
                } else if (this.affinity.kind() == LegacyAffinity.Kind.REGION) {
                    this.inFlight.set(true);
                    this.carrier = Bukkit.getRegionScheduler().runDelayed(plugin, this.affinity.world(),
                            this.affinity.chunkX(), this.affinity.chunkZ(), handle -> this.runOnOwner(false), delay);
                    this.route = "region continuation";
                    this.state.continuationsOnRegion.increment();
                    return;
                }
            }
            this.route = this.repeating ? "plugin-lifetime timer" : "plugin-lifetime delayed task";
            this.state.clockTimedTasks.increment();
            this.carrier = this.repeating
                    ? Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, handle -> this.fire(), delay, this.period)
                    : Bukkit.getGlobalRegionScheduler().runDelayed(plugin, handle -> this.fire(), delay);
        }

        /**
         * The global clock says it is time. Decide where the body runs; run nothing here.
         */
        private void fire() {
            if (this.isDone()) {
                cancelQuietly(this.carrier);
                return;
            }
            if (!this.inFlight.compareAndSet(false, true)) {
                this.state.coalescedFirings.increment();
                return;
            }
            final Plugin plugin = this.state.plugin;
            try {
                switch (this.affinity.kind()) {
                    case ENTITY -> {
                        if (LegacyPluginRuntime.usableEntity(this.affinity.entity())) {
                            final ScheduledTask onEntity = this.affinity.entity().getScheduler().run(plugin,
                                    handle -> this.runOnOwner(true), this::retired);
                            if (onEntity != null) {
                                return;
                            }
                        }
                        this.state.retiredToLane.increment();
                    }
                    case REGION -> {
                        final World world = this.affinity.world();
                        if (Bukkit.getWorld(world.getUID()) != null
                                && world.isChunkLoaded(this.affinity.chunkX(), this.affinity.chunkZ())) {
                            Bukkit.getRegionScheduler().run(plugin, world, this.affinity.chunkX(), this.affinity.chunkZ(),
                                    handle -> this.runOnOwner(false));
                            return;
                        }
                        // Paper never loaded a chunk to run a timer; neither do we.
                        this.state.unloadedToLane.increment();
                    }
                    case NONE -> {
                    }
                }
                this.runViaLane();
            } catch (final Throwable t) {
                this.inFlight.set(false);
                LOGGER.warn("[Lecithin] {}: could not dispatch legacy task #{}", plugin.getName(),
                        this.craftTask.getTaskId(), t);
            }
        }

        private void retired() {
            this.state.retiredToLane.increment();
            this.runViaLane();
        }

        private void runOnOwner(final boolean entityOwner) {
            try {
                LegacyPluginRuntime.call(this.state, this.affinity, false, () -> {
                    (entityOwner ? this.state.ranOnEntityOwner : this.state.ranOnRegionOwner).increment();
                    this.runBody();
                    return null;
                });
            } finally {
                this.afterRun();
            }
        }

        private void runViaLane() {
            final boolean queued = this.state.lane.submit(() -> {
                try {
                    this.runBody();
                } finally {
                    this.afterRun();
                }
            });
            if (!queued) {
                this.afterRun();
            }
        }

        private void runBody() {
            if (this.isDone() || this.craftTask.isCancelled()) {
                return;
            }
            if (RegionizedServer.isGlobalTickThread()) {
                this.state.scheduledBodiesOnGlobalThread.increment();
            }
            this.running = true;
            try {
                this.craftTask.run();
            } catch (final Throwable throwable) {
                // Paper's own wording and level, so existing log tooling keeps matching.
                this.state.plugin.getLogger().log(Level.WARNING, String.format("Task #%s for %s generated an exception",
                        this.craftTask.getTaskId(), this.state.plugin.getDescription().getFullName()), throwable);
            } finally {
                this.running = false;
            }
        }

        private void afterRun() {
            if (!this.repeating || this.craftTask.isCancelled()) {
                this.finished = true;
                LecithinDispatchedTasks.forget(this.state.plugin, this.craftTask.getTaskId());
                if (this.repeating) {
                    cancelQuietly(this.carrier);
                }
            }
            this.inFlight.set(false);
        }

        private boolean isDone() {
            return this.cancelled || this.finished;
        }

        @Override
        public @NotNull Plugin getOwningPlugin() {
            return this.state.plugin;
        }

        @Override
        public boolean isRepeatingTask() {
            return this.repeating;
        }

        @Override
        public @NotNull CancelledState cancel() {
            if (this.cancelled) {
                return CancelledState.CANCELLED_ALREADY;
            }
            if (this.finished) {
                return CancelledState.ALREADY_EXECUTED;
            }
            this.cancelled = true;
            cancelQuietly(this.carrier);
            return this.running ? CancelledState.RUNNING : CancelledState.CANCELLED_BY_CALLER;
        }

        @Override
        public @NotNull ExecutionState getExecutionState() {
            if (this.cancelled) {
                return this.running ? ExecutionState.CANCELLED_RUNNING : ExecutionState.CANCELLED;
            }
            if (this.finished) {
                return ExecutionState.FINISHED;
            }
            return this.running ? ExecutionState.RUNNING : ExecutionState.IDLE;
        }
    }

    private static void cancelQuietly(final ScheduledTask task) {
        if (task == null) {
            return;
        }
        try {
            task.cancel();
        } catch (final Throwable ignored) {
            // Already finished or cancelled.
        }
    }
}
