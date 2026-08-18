package fun.bm.lecithin.compat;

import com.mojang.logging.LogUtils;
import fun.bm.lecithin.config.modules.CompatConfig;
import fun.bm.lecithin.compat.LecithinExecutionProvenance.Owner;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.scheduler.CraftTask;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lecithin: run a rejected sync Bukkit scheduler task on whatever the caller was already working on.
 *
 * <h2>The gap</h2>
 * Paper's {@code scheduleSyncDelayedTask} / {@code runTaskLater} means "run this on the main thread,
 * {@code delay} ticks from now". Folia has no main thread, so it rejects the call outright - and
 * because plugins make these calls from inside event handlers and from {@code onEnable}, the
 * rejection propagates out and kills whatever the plugin was doing. Two measured examples: a claim
 * plugin's one-tick "undo my fake blocks" task took its whole {@code PlayerInteractEvent} handler
 * down with it, and a chat plugin's "make sure we have a sync context" task took chat formatting and
 * delivery down with it.
 *
 * <h2>What this is, and what it deliberately is not</h2>
 * This is a <b>general</b> shim. It has no list of plugins, no jar hashes, no class names and no
 * version locks, and it never looks inside the {@link Runnable} it is handed. It asks
 * {@link LecithinExecutionProvenance} one question - <i>on whose behalf is this thread running</i> -
 * and dispatches to the Folia scheduler that owns the answer:
 *
 * <ul>
 *   <li>region ⇒ {@link org.bukkit.Bukkit#getRegionScheduler()} for that region</li>
 *   <li>global ⇒ {@link org.bukkit.Bukkit#getGlobalRegionScheduler()}</li>
 *   <li>entity ⇒ that entity's own {@link io.papermc.paper.threadedregions.scheduler.EntityScheduler}</li>
 *   <li>nothing known ⇒ <b>rejected exactly as on stock Folia</b>, with the existing diagnostics</li>
 * </ul>
 * <p>
 * The last line is the important one. Inferring an owner from a bare {@code Runnable} is forbidden
 * and stays forbidden; the point of the provenance model is that in the cases handled here nothing
 * is inferred, because the platform already knew whose work it was.
 *
 * <p>It is also not the "route any rejected sync task to the global region" shim that was tested and
 * rejected earlier: that relocates a region-scoped task onto a thread which owns nothing. Here the
 * global region is used only when the global region is what asked, so no task ever changes context.
 *
 * <h2>Why this cannot make an unsafe task safe, and does not claim to</h2>
 * A task that reaches into another region's state still hits {@code TickThread.ensureTickThread} and
 * still fails, loudly, at the actual access, with the plugin and callsite named. Nothing is
 * swallowed and no ownership check is weakened. What changes is only that a task which was
 * <i>never given a chance to run</i> now runs.
 *
 * <p>Region merge and split are handled by the platform, not here: a region task is scheduled
 * through {@link org.bukkit.Bukkit#getRegionScheduler()}, whose owner is resolved from the chunk key
 * at execution time rather than captured now, and an entity task through Folia's
 * {@code EntityScheduler}, which the platform transfers with the entity across regions and worlds.
 *
 * <p>Kill switch: {@code caller-context-dispatch: false} restores stock Folia rejection for
 * everything this class would have redispatched.
 */
public final class LecithinCallerContextDispatch {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Report each distinct (plugin, task class, owner kind) at INFO once; every later occurrence is
     * DEBUG. A successful redispatch is a working compatibility path, not a problem, and a ticker
     * redispatched every few ticks would otherwise bury the log in good news.
     */
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private LecithinCallerContextDispatch() {
    }

    /**
     * @param task   the sync task stock Folia is about to reject
     * @param delay  delay in ticks as passed to {@code CraftScheduler.handle}
     * @param period {@code > 0} for a repeating task, otherwise one-shot
     * @return the same task once scheduled, or {@code null} to fall through to stock rejection
     */
    public static CraftTask tryDispatch(final CraftTask task, final long delay, final long period) {
        if (!CompatConfig.callerContextDispatch) {
            return null;
        }
        try {
            final Plugin plugin = task.getOwner();
            if (plugin == null) {
                return null;
            }
            final Owner owner = LecithinExecutionProvenance.effectiveOwner();
            if (owner == null) {
                // No observable context and nothing the platform established. Inferring an owner
                // here is exactly the guess this design refuses to make.
                return null;
            }

            // Folia's schedulers reject a delay of 0, and Paper's scheduleSyncDelayedTask(task) with
            // no delay means "next tick" anyway, so 1 is the exact translation rather than a fudge.
            final long safeDelay = Math.max(delay, 1L);
            final boolean repeating = period > 0;

            final ScheduledTask scheduled = switch (owner.kind()) {
                case REGION -> repeating
                        ? Bukkit.getRegionScheduler().runAtFixedRate(plugin, owner.world(), owner.chunkX(), owner.chunkZ(),
                        handle -> runBody(plugin, task, handle, true), safeDelay, period)
                        : Bukkit.getRegionScheduler().runDelayed(plugin, owner.world(), owner.chunkX(), owner.chunkZ(),
                        handle -> runBody(plugin, task, handle, false), safeDelay);
                case GLOBAL -> repeating
                        ? Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin,
                        handle -> runBody(plugin, task, handle, true), safeDelay, period)
                        : Bukkit.getGlobalRegionScheduler().runDelayed(plugin,
                        handle -> runBody(plugin, task, handle, false), safeDelay);
                case ENTITY -> repeating
                        ? owner.entity().getScheduler().runAtFixedRate(plugin,
                        handle -> runBody(plugin, task, handle, true), null, safeDelay, period)
                        : owner.entity().getScheduler().runDelayed(plugin,
                        handle -> runBody(plugin, task, handle, false), null, safeDelay);
            };

            if (scheduled == null) {
                // Entity schedulers only: the entity was retired between naming it and scheduling on
                // it - the player disconnected, or the entity was removed. Folia's contract is that
                // neither the task nor a retired callback will run, and there is no other owner this
                // work could honestly belong to. Reporting success is correct: the caller asked to
                // schedule work for someone who is gone, and nothing is what that work amounts to.
                LOGGER.debug("[Lecithin] {}: sync scheduler task {} was not redispatched - {} is already gone",
                        plugin.getName(), taskClassOf(task), owner.description());
                return task;
            }

            LecithinDispatchedTasks.track(plugin, task, scheduled);
            report(plugin, task, owner, delay, period);
            return task;
        } catch (final Throwable t) {
            // Any failure must fall through to stock rejection. Never report success without having
            // actually scheduled anything.
            LOGGER.warn("[Lecithin] caller-context scheduler dispatch failed "
                    + "(falling through to stock rejection)", t);
            return null;
        }
    }

    /**
     * Runs the plugin's task body, honouring a cancel that arrived through the Bukkit API.
     *
     * <p>{@code CraftTask.run()} does not check its own cancelled flag - on Paper the scheduler's
     * queue does that before ever calling it. A redispatched task is not in that queue, so the check
     * has to happen here, and a repeating task additionally has to stop the Folia task it is running
     * on. Without this a cancelled repeating task would keep firing until its plugin was disabled.
     */
    private static void runBody(final Plugin plugin, final CraftTask task, final ScheduledTask handle,
                                final boolean repeating) {
        if (task.isCancelled()) {
            LecithinDispatchedTasks.forget(plugin, task.getTaskId());
            handle.cancel();
            return;
        }
        try {
            task.run();
        } finally {
            if (!repeating) {
                LecithinDispatchedTasks.forget(plugin, task.getTaskId());
            }
        }
    }

    private static String taskClassOf(final CraftTask task) {
        final Object body = task.rTask != null ? task.rTask : task.cTask;
        return body == null ? "<none>" : body.getClass().getName();
    }

    /**
     * One INFO per distinct (plugin, task class, owner kind); everything after that is DEBUG.
     */
    private static void report(final Plugin plugin, final CraftTask task, final Owner owner,
                               final long delay, final long period) {
        if (!CompatConfig.diagnostics) {
            return;
        }
        final String taskClass = taskClassOf(task);
        final boolean first = REPORTED.add(plugin.getName() + '|' + taskClass + '|' + owner.kind());
        if (!first && !LOGGER.isDebugEnabled()) {
            return;
        }
        final String message = "[Lecithin] {}: sync scheduler task {} (delay={}, period={}) redispatched to {}. "
                + "No ownership was inferred - the caller's own execution context is the context. "
                + "Cross-region access inside the task still fails at the access.";
        if (first) {
            LOGGER.info(message, plugin.getName(), taskClass, delay, period, owner.description());
        } else {
            LOGGER.debug(message, plugin.getName(), taskClass, delay, period, owner.description());
        }
    }
}
