package fun.bm.lophinya.compat;

import com.mojang.logging.LogUtils;
import fun.bm.lecithin.config.modules.CompatConfig;
import io.papermc.paper.threadedregions.TickRegionScheduler;
import io.papermc.paper.threadedregions.TickRegions;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.scheduler.CraftTask;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lophinya: run a rejected sync Bukkit scheduler task on the context the caller was already on.
 *
 * <h2>The gap</h2>
 * Paper's {@code scheduleSyncDelayedTask} / {@code runTaskLater} means "run this on the main thread,
 * {@code delay} ticks from now". Folia has no main thread, so it rejects the call outright - and
 * because plugins schedule these from inside event handlers, the rejection propagates out of the
 * handler and kills whatever the plugin was doing. Measured example: GriefPrevention's claim
 * boundary visualization schedules a one-tick task to undo its fake blocks, so the rejection escapes
 * {@code PlayerEventHandler.onPlayerInteract} and the golden-shovel interaction never completes -
 * no visualization, and the rest of the handler's bookkeeping is skipped too.
 *
 * <h2>Why "the caller's own context" is not a guess</h2>
 * {@code DEC-19} B1 forbids inferring a region owner from a contextless {@link Runnable}, and that
 * prohibition is right: nothing about a bare {@code Runnable} says which region may touch what.
 * This class does not look at the runnable. It looks at <b>which thread asked</b>, which Folia
 * already knows the answer for:
 *
 * <ul>
 *   <li>The caller is ticking a region ⇒ the task goes to that region. Paper's promise was "the
 *       thread you are on, later"; on Folia the nearest true statement of that is "the region you
 *       are on, later".</li>
 *   <li>The caller is the global region (plugin {@code onEnable}, console command, global tick) ⇒
 *       the task goes to the global region, for the same reason.</li>
 *   <li>The caller is neither - an async thread, or a thread with no owning context ⇒ <b>nothing is
 *       inferred and the call is rejected exactly as on stock Folia</b>, with the existing
 *       diagnostics. This is the case B1 is about, and it stays closed.</li>
 * </ul>
 *
 * This is also not the "route any rejected sync task to GlobalRegionScheduler" shim that
 * {@code DEC-05} / {@code N13} rejected on measured grounds. That proposal sent tasks to the global
 * region <i>regardless of where they came from</i>, which relocates a region-scoped task onto a
 * thread that owns nothing. Here the global region is used only when the global region is what
 * asked, so no task ever changes context.
 *
 * <h2>Why this is safe, and what it does not promise</h2>
 * Redispatching cannot make an unsafe task safe, and does not try to. A task that reaches across
 * into another region's world state still hits {@code TickThread.ensureTickThread} and still fails,
 * loudly, at the actual access - with the plugin, the jar and the callsite named. Nothing is
 * swallowed and no check is weakened. What changes is that a task which was <i>never given a chance
 * to run</i> now runs, and only genuinely cross-region work fails - which is the project's model:
 * load it, let it fail at the real callsite, and make that callsite the next thing worth fixing.
 *
 * <p>Region merge and split are handled by the platform, not here: the task is scheduled through
 * {@link org.bukkit.Bukkit#getRegionScheduler()}, whose backing store is regionised data with
 * merge/split callbacks, and whose owner is resolved from the chunk key at execution time rather
 * than captured now.
 *
 * <h2>Relationship to the researched rule table</h2>
 * {@link LophinyaPluginSchedulerDispatch} runs first and still wins when it has an entry, because a
 * researched rule can be <i>more precise</i> than the caller's context - a task known to belong to
 * one player goes to that player's own {@code EntityScheduler}, which keeps working when the player
 * moves between regions, and a task known to belong to one chunk goes to that chunk's region even
 * when it was scheduled from somewhere else. This class is the general case underneath: it needs no
 * per-plugin entry, no jar hash, and no core release to cover a plugin nobody has looked at yet.
 * The rule table is therefore temporary compatibility data, not the mechanism.
 *
 * <p>Kill switch: {@code -Dlophinya.compat.callerContextDispatch=false} restores stock Folia
 * rejection for everything this class would have redispatched.
 */
public final class LophinyaCallerContextDispatch {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * The context an async task inherited, for the duration of that task's body only.
     *
     * <p>Pool threads are reused, so this is always restored in a {@code finally} - a leaked context
     * would hand the next unrelated task a region it never came from, which is the inference this
     * whole class exists to avoid.
     */
    private static final ThreadLocal<CapturedContext> INHERITED = new ThreadLocal<>();

    /** Report each distinct (plugin, task class, context kind) once; a ticker would flood the log. */
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    /** What a thread was ticking when it scheduled something, or {@code null} if nothing. */
    public record CapturedContext(OwnedChunk chunk, boolean global) {
    }

    /**
     * The calling thread's context right now, to be replayed later by {@link #runInherited}.
     * Called on the scheduling thread, so "now" is the honest answer.
     *
     * @return the context, or {@code null} when this thread has none to pass on
     */
    public static CapturedContext captureCallerContext() {
        if (!CompatConfig.callerContextDispatch || !CompatConfig.asyncContextInheritance) {
            return null;
        }
        try {
            final OwnedChunk owned = callerRegionChunk();
            if (owned != null) {
                return new CapturedContext(owned, false);
            }
            if (Bukkit.isGlobalTickThread()) {
                return new CapturedContext(null, true);
            }
            // Already contextless, or an inherited context - do not chain inheritance across a
            // second async hop, because each hop makes the captured region less likely to still be
            // the right one and nothing would bound the chain.
            return null;
        } catch (final Throwable t) {
            return null;
        }
    }

    /**
     * Run {@code body} with {@code captured} visible to {@link #tryDispatch}, then restore.
     * A {@code null} context is a plain call, so the common path costs nothing.
     */
    public static void runInherited(final CapturedContext captured, final Runnable body) {
        if (captured == null || !CompatConfig.callerContextDispatch || !CompatConfig.asyncContextInheritance) {
            body.run();
            return;
        }
        final CapturedContext previous = INHERITED.get();
        INHERITED.set(captured);
        try {
            body.run();
        } finally {
            if (previous == null) {
                INHERITED.remove();
            } else {
                INHERITED.set(previous);
            }
        }
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
            if (plugin == null || task.rTask == null) {
                return null;
            }
            // Folia's schedulers treat 0 as "next tick" inconsistently across flavours; Paper's
            // scheduleSyncDelayedTask(task) with no delay means "next tick" too, so 1 is exact.
            final long safeDelay = Math.max(delay, 1L);
            final boolean repeating = period > 0;

            final ScheduledTask scheduled;
            final String context;
            final CapturedContext inherited = INHERITED.get();
            final OwnedChunk direct = callerRegionChunk();
            // The thread's own context always wins. The inherited one is only consulted when this
            // thread has none, which is precisely the case that is refused today.
            final OwnedChunk owned = direct != null ? direct
                : (inherited != null ? inherited.chunk() : null);
            final String origin = direct != null ? "" : " (inherited from the thread that scheduled this async task)";
            if (owned != null) {
                scheduled = repeating
                    ? Bukkit.getRegionScheduler().runAtFixedRate(plugin, owned.world(), owned.chunkX(), owned.chunkZ(),
                        ignored -> task.run(), safeDelay, period)
                    : Bukkit.getRegionScheduler().runDelayed(plugin, owned.world(), owned.chunkX(), owned.chunkZ(),
                        ignored -> task.run(), safeDelay);
                context = "region owning " + owned.world().getName() + " chunk ["
                    + owned.chunkX() + ", " + owned.chunkZ() + ']' + origin;
            } else if (Bukkit.isGlobalTickThread() || (inherited != null && inherited.global())) {
                scheduled = repeating
                    ? Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, ignored -> task.run(), safeDelay, period)
                    : Bukkit.getGlobalRegionScheduler().runDelayed(plugin, ignored -> task.run(), safeDelay);
                context = "global region (the caller was the global region)" + origin;
            } else {
                // No observable context. Inferring one here is exactly DEC-19 B1.
                return null;
            }

            LophinyaPluginSchedulerDispatch.track(plugin, scheduled);
            report(plugin, task.rTask.getClass().getName(), context, delay, period);
            return task;
        } catch (final Throwable t) {
            // Any failure must fall through to stock rejection, never report success without
            // having actually scheduled anything.
            LOGGER.warn("[Lophinya] caller-context scheduler dispatch failed "
                + "(falling through to stock rejection)", t);
            return null;
        }
    }

    /** Package-private, not private: {@link CapturedContext} carries one across the async hop. */
    record OwnedChunk(World world, int chunkX, int chunkZ) {
    }

    /**
     * A chunk coordinate the calling thread's region owns, or {@code null} if this thread is not
     * ticking a region.
     *
     * <p>Region membership in Folia is section-granular, so any coordinate inside a section this
     * region owns is owned by this region - which means the first owned section is as good an answer
     * as the geometric middle one, and far cheaper to obtain. The unsynchronised iterator is the
     * documented accessor for a thread that is ticking the region, and
     * {@link TickRegionScheduler#getCurrentRegion()} being non-null is exactly that condition.
     */
    private static OwnedChunk callerRegionChunk() {
        final var region = TickRegionScheduler.getCurrentRegion();
        if (region == null) {
            return null;
        }
        final var worldData = TickRegionScheduler.getCurrentRegionizedWorldData();
        if (worldData == null) {
            return null;
        }
        final var sections = region.getOwnedSectionsUnsynchronised();
        if (!sections.hasNext()) {
            // A region mid-teardown can own no sections. Nothing to key a task on.
            return null;
        }
        final long sectionKey = sections.nextLong();
        final int shift = TickRegions.getRegionChunkShift();
        return new OwnedChunk(
            worldData.world.getWorld(),
            ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkX(sectionKey) << shift,
            ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkZ(sectionKey) << shift
        );
    }

    private static void report(final Plugin plugin, final String taskClass, final String context,
                               final long delay, final long period) {
        if (!CompatConfig.diagnostics) {
            return;
        }
        if (!REPORTED.add(plugin.getName() + '|' + taskClass + '|' + context)) {
            return;
        }
        LOGGER.info("[Lophinya] {}: sync scheduler task {} (delay={}, period={}) redispatched to the "
            + "caller's own context: {}. No region ownership was inferred - the calling thread's "
            + "context is the context. Cross-region access inside the task still fails at the access.",
            plugin.getName(), taskClass, delay, period, context);
    }
}
