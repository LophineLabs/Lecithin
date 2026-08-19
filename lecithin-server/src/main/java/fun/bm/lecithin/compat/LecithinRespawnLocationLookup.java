package fun.bm.lecithin.compat;

import ca.spottedleaf.moonrise.common.util.TickThread;
import com.mojang.logging.LogUtils;
import fun.bm.lecithin.config.modules.CompatConfig;
import io.papermc.paper.threadedregions.ThreadedRegionizer;
import io.papermc.paper.threadedregions.TickRegionScheduler;
import io.papermc.paper.threadedregions.TickRegions;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lecithin: answer {@code Player#getRespawnLocation()} from any tick thread, instead of throwing
 * {@code World mismatch} when the caller is not ticking the respawn world.
 *
 * <h2>The gap, as measured</h2>
 * {@code CraftPlayer#getRespawnLocation(true)} resolves the <b>respawn</b> world from the player's
 * persisted respawn data and then validates the spawn block in it:
 *
 * <pre>
 *   ServerLevel world = server.getLevel(respawnData.dimension());   // the BED's world
 *   ServerPlayer.findRespawnAndUseSpawnBlock(world, respawnConfig, false)
 *     -&gt; world.getBlockState(pos)
 *     -&gt; Level#getCurrentWorldData()   // throws when the ticking region's world != world
 * </pre>
 * <p>
 * {@code getCurrentWorldData()} resolves the world data from <i>whatever region this thread is
 * currently ticking</i> and then asserts identity with the level being read. The respawn world is
 * chosen from stored data and is unrelated to either the origin or the destination of whatever the
 * caller happens to be doing, so the assertion fails whenever a player's bed is in a different
 * world from the one whose region is ticking.
 *
 * <p>That is not an exotic case. Every cross-world move fires {@code PlayerChangedWorldEvent} on the
 * <b>destination</b> region, and reading a player's bed spawn from that handler is a standard Paper
 * idiom - directly, or through {@code PaperLib.getBedSpawnLocationAsync}, whose selected handler on
 * this version calls straight through to {@code getRespawnLocation()} on the calling thread. A
 * player whose bed is in the overworld moving between any two other worlds therefore took an
 * {@code IllegalStateException} out of the plugin's handler.
 *
 * <h2>What this does</h2>
 * Ask, before validating, whether this thread may read the respawn world at that position. When it
 * may, nothing changes at all: upstream's validating path runs exactly as written. When it may not,
 * the <b>stored</b> respawn location is returned - which is precisely what the sibling overload
 * {@code getRespawnLocation(false)} returns, and what {@code OfflinePlayer#getRespawnLocation(false)}
 * has always returned.
 *
 * <p>On a thread that owns a region, no ownership check is disabled, bypassed or swallowed: this
 * <em>consults</em> the platform's own {@code TickThread.isTickThreadFor} and stays on the side of it
 * where the read is legal. No chunk is loaded, no work is dispatched to another region or to the
 * global scheduler, and the calling thread never waits.
 *
 * <p><b>Tick threads only, deliberately.</b> On a thread that is not a tick thread at all - a
 * plugin's own async pool - upstream's behaviour is a hard failure, and that failure is the async
 * catcher doing its job rather than a regionisation artefact. Answering it here instead would delete
 * a real diagnostic, so those callers are left to fail exactly as loudly as before. This is the same
 * line the sibling {@link LecithinCrossRegionBlockRead} draws, for the same reason: only threads that
 * legitimately tick something can reach the fallback.
 *
 * <h2>The bounded semantic difference, stated rather than hidden</h2>
 * On the fallback path the answer is not validated, so a bed that has since been broken - or an
 * obstructed respawn anchor - is reported as a respawn location instead of {@code null}.
 *
 * <p>That is strictly better than throwing, which aborts the caller entirely. It is <b>not</b>
 * strictly better than {@code null}, and that is worth being blunt about: the fallback hands back the
 * raw stored block coordinate - the integer corner of the bed or anchor block, with no centring, no
 * safety scan and no chunk load - so a plugin doing
 * {@code player.teleport(player.getRespawnLocation())} can put the player inside a block, where
 * {@code null} would have routed them to world spawn instead. The trade is an unvalidated and
 * possibly unsafe location in exchange for a call that completes at all; a caller that needs a safe
 * answer still has to check it itself. It cannot be improved on synchronously, because validating it
 * would mean blocking a region tick on another region's chunk.
 *
 * <p><b>The fallback is the common path, not an edge case.</b> A bed in another world can never be
 * validated from a region of this one - every world builds its own regioniser - and neither can a bed
 * far enough away within this world to sit in a different region, nor one whose chunks are not
 * loaded. So every cross-world respawn lookup, which is the case this exists for, takes the fallback;
 * the validating path is what the minority of same-region callers get. The semantics described above
 * are what most callers will actually observe.
 *
 * <p>Grouped by API symbol, not by plugin: the gap is {@code Player#getRespawnLocation} and
 * {@code OfflinePlayer#getRespawnLocation}, both of which are guarded here, and every plugin that
 * asks a player where they would respawn hits it identically. There is no plugin name, jar hash or
 * library name anywhere here.
 *
 * <p>Kill switch: {@code respawn-location-lookup: false} restores the unconditional validating read,
 * and with it the {@code World mismatch} exception.
 */
public final class LecithinRespawnLocationLookup {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * The spawn-block search does not stop at the exact position: a bed looks for a free stand-up
     * spot around itself. One chunk of slack covers that scan without needing to model it.
     */
    private static final int SEARCH_RADIUS = 16;

    /**
     * One diagnostic line per distinct respawn world and calling thread. The key is deliberately
     * cheap to build - see {@link #report} - because the fallback is the common path here, so
     * anything computed before the dedup is paid on every call rather than only on the first.
     */
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private LecithinRespawnLocationLookup() {
    }

    /**
     * Whether the calling thread may read {@code level} around {@code pos} to validate a respawn
     * location.
     *
     * @return {@code true} to run upstream's validating path unchanged, {@code false} to fall back
     * to the stored respawn location
     */
    public static boolean canValidate(final ServerLevel level, final BlockPos pos) {
        if (!CompatConfig.respawnLocationLookup) {
            return true; // stock body: validate unconditionally, and throw where it is not legal
        }
        if (!TickThread.isTickThread()) {
            // Not a tick thread at all: upstream's failure there is the async catcher, not a
            // regionisation artefact, so it is left exactly as loud as it is today.
            return true;
        }
        if (TickThread.isShutdownThread() && !shutdownThreadOwns(level, pos)) {
            report(level);
            return false;
        }
        if (TickThread.isTickThreadFor(level, pos, SEARCH_RADIUS)) {
            return true;
        }
        report(level);
        return false;
    }

    /**
     * The shutdown thread needs its own answer, because {@code TickThread.isTickThreadFor} does not
     * give a usable one there: when {@code TickRegionScheduler.getCurrentRegion()} is {@code null} it
     * returns {@code isShutdownThread()}, i.e. {@code true} for <em>every</em> chunk in
     * <em>every</em> world, ignoring both arguments entirely. Taking that at face value would send
     * the validating read through anyway, and {@code Level#getCurrentWorldData()} would still throw
     * {@code World mismatch} - or be dereferenced while {@code null}, because
     * {@code RegionShutdownThread.getWorldData()} returns {@code null} whenever its
     * {@code shuttingDown} region is unset, which is exactly the window in which
     * {@code PlayerQuitEvent} and plugin {@code onDisable} run.
     *
     * <p>The shutdown thread does expose a usable accessor, so this consults it rather than making
     * {@code canValidate} blanket-refuse there: {@code TickRegionScheduler.getCurrentRegion()} reads
     * the thread's {@code shuttingDown} region. The read is allowed only when a region <b>of this
     * level</b> is the one being shut down, and only for positions that region actually covers -
     * which is the same standard every other tick thread is held to.
     *
     * @return {@code true} only when this shutdown thread genuinely owns {@code pos} in {@code level}
     */
    private static boolean shutdownThreadOwns(final ServerLevel level, final BlockPos pos) {
        final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> shuttingDown =
                TickRegionScheduler.getCurrentRegion();
        if (shuttingDown == null || shuttingDown.regioniser.world != level) {
            return false;
        }
        // A region of this level is being shut down, so getCurrentRegion() is non-null and
        // isTickThreadFor no longer short-circuits: it compares this level's regioniser against that
        // region section by section, which is exactly the check wanted here.
        return TickThread.isTickThreadFor(level, pos, SEARCH_RADIUS);
    }

    private static void report(final ServerLevel level) {
        if (!CompatConfig.diagnostics) {
            return;
        }
        final String world = level.getWorld().getName();
        final String thread = Thread.currentThread().getName();
        final String key = world + "@" + thread;
        if (!REPORTED.add(key)) {
            return; // dedup before the stack walk: only a genuinely new record pays for it
        }
        LOGGER.info("""
                        [Lecithin] Returned a player's stored respawn location without validating the spawn block.
                          callsite : {}
                          context  : respawn world={}, thread={} (not ticking a region of that world at the bed)
                          why      : validating reads the respawn world's blocks, which this thread may not do. The
                        stored location is returned instead - the same answer getRespawnLocation(false) gives - rather
                        than throwing World mismatch out of the caller. A bed broken since the player last slept in it
                        is therefore not detected here, and the coordinate is the stored block corner rather than a
                        validated safe spot.
                          switch   : compat-config.respawn-location-lookup=false restores the unconditional read.""",
                callsite(), world, thread);
    }

    /**
     * The first frame outside the platform's own respawn plumbing, i.e. whoever asked. Only reached
     * for a record that is going to be printed.
     */
    private static String callsite() {
        for (final StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            final String cls = frame.getClassName();
            if (cls.startsWith("java.") || cls.startsWith("fun.bm.lecithin.compat.")
                    || cls.startsWith("org.bukkit.craftbukkit.") || cls.startsWith("net.minecraft.")
                    || cls.startsWith("org.bukkit.entity.") || cls.startsWith("org.bukkit.OfflinePlayer")) {
                continue;
            }
            return frame.toString();
        }
        return "<no caller frame outside the platform>";
    }
}
