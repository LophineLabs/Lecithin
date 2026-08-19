package fun.bm.lecithin.compat;

import fun.bm.lecithin.config.modules.CompatConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lecithin: serve a <em>read-only</em> block lookup from an already-loaded chunk when the calling
 * tick thread does not own that chunk's region, instead of refusing it.
 *
 * <p>Reading another region's - or another world's - block synchronously is a very common Paper
 * idiom, and on this platform it is not merely inconvenient but structurally impossible to satisfy:
 * every {@code ServerLevel} builds its own regioniser, so a thread ticking a region in world A can
 * never be the tick thread for any chunk in world B. Multiverse's destination scan and its world
 * creation both die on exactly this, and there is no thread the plugin could have used instead.
 *
 * <h2>Why this is narrowing the check rather than disabling it</h2>
 * <p>
 * The blanket refusal in {@code CraftBlock} guards two different things, and only one of them is
 * still real for a read:
 *
 * <ol>
 *   <li><b>Triggering a chunk load off-thread.</b> Real, and the reason this class has two paths.
 *       A resident chunk is answered directly from {@code getChunkAtIfLoadedImmediately} and
 *       nothing is loaded at all; that path is open to every tick thread. A chunk that is
 *       <i>not</i> resident is loaded only from a thread that ticks no region - see
 *       {@link #readByLoading}, which records why a region thread cannot do it and what happened
 *       when it was allowed to. Nothing here loads a FULL chunk or ticks anything.</li>
 *   <li><b>Racing a concurrent writer on the block data.</b> Upstream has already made this safe.
 *       {@code PalettedContainer.data} is {@code volatile} and every mutator is {@code synchronized};
 *       a resize publishes a brand new {@code Data} rather than editing the old one; and Paper
 *       deliberately disabled the {@code ThreadingDetector} with the comment "use proper
 *       synchronization". A reader therefore takes one consistent snapshot and indexes into it.
 *       {@code LevelChunk.getBlockStateFinal} adds only a bounds check and a plain int read.</li>
 * </ol>
 * <p>
 * So the worst outcome of a read served from a resident chunk is a block state that is one tick
 * stale - the same thing every check-then-act plugin already races against on single-threaded
 * Paper. It cannot corrupt and cannot observe a torn value: entries never span two longs in
 * {@code SimpleBitStorage}, so a concurrent write is seen either wholly or not at all.
 *
 * <p>Two bounds on that "one tick stale" claim, because it is load-bearing in review and it is not
 * unconditional. A chunk read at a below-FULL status can be read while {@code ChunkFullTask} is
 * still running post-load processing into the same section arrays, so the answer there can be
 * pre-upgrade rather than merely old. And {@code readPalette} indexes the palette's live backing
 * array, so a reader can briefly see an index whose slot is not yet published - that one is caught
 * and turned back into the platform's own refusal rather than guessed at.
 *
 * <p><b>Writes are untouched.</b> {@code CraftBlock}'s "Cannot modify world asynchronously" check and
 * {@code LevelChunk.setBlockState}'s check are exactly as upstream wrote them.
 *
 * <p><b>Tick threads only.</b> Threads that are not tick threads at all - a plugin's own async pool -
 * still fail, so the async catcher keeps working as a diagnostic. Both cases this was written for
 * are tick threads: a region thread reading another world, and the global region thread, which owns
 * no chunks and therefore cannot legally read any block anywhere.
 *
 * <p>Nothing here is per-plugin: no plugin name, jar hash or call-site list.
 *
 * <p>Kill switch: {@code compat-config.cross-region-block-read=false} restores the stock refusal.
 */
public final class LecithinCrossRegionBlockRead {

    private static final Logger LOGGER = LogManager.getLogger(LecithinCrossRegionBlockRead.class);

    /**
     * One diagnostic line per distinct world pair, so the behaviour is observable without flooding.
     */
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    /**
     * Reads a block state without owning its region, if that can be done without loading anything.
     *
     * @return the block state, or {@code null} to let the caller apply the stock ownership check
     */
    public static BlockState readIfResident(final ServerLevel level, final BlockPos pos) {
        if (!CompatConfig.crossRegionBlockRead || !ca.spottedleaf.moonrise.common.util.TickThread.isTickThread()) {
            return null;
        }
        if (level.isOutsideBuildHeight(pos)) {
            return Blocks.VOID_AIR.defaultBlockState();
        }
        final int chunkX = pos.getX() >> 4;
        final int chunkZ = pos.getZ() >> 4;
        LevelChunk chunk = level.getChunkSource().getChunkAtIfLoadedImmediately(chunkX, chunkZ);
        if (chunk == null) {
            // A chunk can be fully loaded and held by its holder without yet being published into
            // fullChunks - which is the state a freshly created or freshly loaded world is in right
            // after its spawn area is prepared. Upstream's own comment on this accessor is "Note:
            // Bypass cache since we need to check ticket level, and to make this MT-Safe", so it is
            // the correct primitive here; it still returns null when nothing is resident.
            chunk = level.getChunkSource().getChunkAtIfCachedImmediately(chunkX, chunkZ);
        }
        if (chunk == null) {
            return readByLoading(level, pos);
        }
        try {
            final BlockState state = chunk.getBlockState(pos);
            report(level);
            return state;
        } catch (final IllegalArgumentException | IndexOutOfBoundsException race) {
            // The one hole in the "a concurrent read is safe" argument, and it is narrow but real:
            // PalettedContainer.readPalette indexes the palette's LIVE backing array
            // (LinearPalette.moonrise$getRawPalette returns `this.values`), and a writer appends the
            // new entry to that array before writing the storage index. Both are plain writes, so an
            // unsynchronised reader can observe the new index while the palette slot still reads
            // null - which readPalette turns into "Palette index out of bounds". It can only happen
            // while this very section gains a block type it did not have.
            //
            // Falling through is not swallowing it: the caller then hits the stock ownership check
            // and fails exactly as it does today, loudly and with a message that names the world and
            // position. Trading an unattributable exception for the platform's own is the point.
            //
            // A stale read cannot be silently wrong, only stale: palette entries are append-only
            // within one Data, and a resize publishes a whole new Data, so an old index always still
            // maps to the value it mapped to.
            LOGGER.warn("[Lecithin] Cross-region block read raced a palette update at {} in {}; "
                    + "falling through to the stock ownership check", pos, level.getWorld().getName(), race);
            return null;
        }
    }

    /**
     * The chunk is not resident, so load it - which is what Paper's own block read does. Restricted
     * to threads that are ticking <b>no region at all</b>.
     *
     * <h2>Which threads, and the defect that had to be fixed first</h2>
     * Every tick thread, including a region thread reading another world - but only after
     * {@link LecithinForeignWorldTicketUpdates}, without which that case is not merely slow but
     * actively harmful.
     *
     * <p>{@code syncLoadNonFull} calls {@code processTicketUpdates()} on the <b>target</b> world's
     * holder manager, and on a tick thread that used to end in {@code processPendingFullUpdate()},
     * which asserts the region's world against the manager's. From a region of another world it threw
     * {@code World check failed} - after the chunk ticket had been added and before it was removed,
     * with no {@code finally} to save it, so every such read leaked one permanently pinned chunk. The
     * leak is what hid it: the chunk stayed resident, so the second read of the same block worked.
     *
     * <p>Measured on the A/B pair, a player's own region thread reading nether2 at (200000, 200000),
     * before the guard: first read {@code THREW}, second read {@code AIR}, still resident twenty
     * seconds later with nothing holding it. After the guard the first read answers and the ticket is
     * released normally.
     *
     * <p>The other two threads this exists for tick no region at all, and were always safe because
     * {@code getCurrentRegionData()} answers null for them rather than comparing worlds:
     *
     * <ul>
     *   <li>the <b>bootstrap thread</b> during startup. Paper guarantees the spawn area is in memory
     *       before any plugin is enabled - {@code prepareLevel} activates the world's tickets and
     *       spins on {@code executeModerately()} until none are pending - and Folia deletes both, so
     *       reading the world spawn in {@code onEnable} has no legal outcome without this;</li>
     *   <li>the <b>global region thread</b>, which by construction owns no chunks in any world, so a
     *       read from it has no other thread the caller could have used. Creating a world at runtime
     *       lands in {@code MinecraftServer.setInitialSpawn} there, and upstream's own
     *       {@code PlayerSpawnFinder.getLevelRespawnPos} calls this same {@code syncLoadNonFull} from
     *       it - so the platform already pays this cost on this thread.</li>
     * </ul>
     *
     * <h2>What it costs, stated plainly</h2>
     * The calling thread runs nothing else until the load finishes; on the global region that stalls
     * the global tick and the watchdog logs "Global region has not responded" past 5s. Paper stalls
     * every region for the same read, because it has only one.
     *
     * <p>Follow-up reads are cheaper but not free. A chunk brought in below FULL is never published
     * into the full-chunk cache, so {@link #readIfResident} still cannot see it and every later read
     * comes back through here; {@code syncLoadNonFull} then answers from its own first lookup without
     * waiting, so the cost is a ticket add, a ticket-update pass and an area lock rather than a load.
     *
     * <h2>Residual risk, not hidden</h2>
     * The wait inside {@code syncLoadNonFull} is unbounded by construction and has no failure exit:
     * it parks until the chunk reaches the status, and it discards the boolean from
     * {@code beginChunkLoadForNonFullSync}, so a load that was never schedulable is waited on
     * anyway. Holding the destination's {@code levelUnloadStateLock} covers the runtime
     * level-unload case, which is the one that has been exercised - reads aimed at a world being
     * unloaded were all answered and nothing hung.
     *
     * <p>Server shutdown halts chunk systems outside that lock and is NOT covered. An attempt to
     * close it here was reverted: a thread dump of the observed slow shutdown showed the stall in
     * {@code MoonriseCommon.haltExecutors()} waiting on the chunk-system worker pool, not in a
     * reader parked in this method, and the shutdown-aware exit written for it never engaged in any
     * run because region threads are halted before it could. See the handoff for the measurements.
     * A timeout is deliberately not used - it would leave the wait in place and stop reporting it.
     *
     * <p>Kill switch: {@code compat-config.cross-region-block-load=false} narrows
     * {@link #readIfResident} to chunks that are already resident, on every thread.
     */
    private static BlockState readByLoading(final ServerLevel level, final BlockPos pos) {
        if (!CompatConfig.crossRegionBlockLoad) {
            return null;
        }
        // Hold the destination's unload lock for the whole load: Luminol can unload a level at
        // runtime and its chunk system then stops answering, which would leave this wait with
        // nothing to wait for. A world that is already unloading answers no here instead of parking.
        // This does NOT cover server shutdown - see the residual risk note above.
        if (!level.levelUnloadStateLock.acquireRead()) {
            return null;
        }
        try {
            final int chunkX = pos.getX() >> 4;
            final int chunkZ = pos.getZ() >> 4;
            final ChunkAccess loaded = level.moonrise$getChunkTaskScheduler()
                    .syncLoadNonFull(chunkX, chunkZ, ChunkStatus.FULL.getParent());
            if (loaded == null) {
                return null;
            }
            reportLoad(level);
            return loaded.getBlockState(pos);
        } catch (final Throwable failed) {
            // Falling through is not swallowing it: the caller then hits the stock ownership check
            // and fails exactly as it does today, naming the world and position.
            LOGGER.warn("[Lecithin] Chunk load for a block read at {} in {} did not complete; "
                    + "falling through to the stock ownership check", pos, level.getWorld().getName(), failed);
            return null;
        } finally {
            level.levelUnloadStateLock.releaseRead();
        }
    }

    private static void reportLoad(final ServerLevel level) {
        final String where = io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegion() != null
                ? "a region thread"
                : (LecithinStartupGlobalContext.isStartupThread() ? "the startup thread" : "the global region thread");
        if (!REPORTED.add("load " + where + " -> " + level.getWorld().getName())) {
            return;
        }
        LOGGER.info("""
                        [Lecithin] Loaded a chunk on {} to answer a block read in world '{}'
                          why     : Paper answers a block read by loading its chunk synchronously, so a plugin                         inspecting another world - a teleport destination above all - always gets an answer.                         Here that chunk belongs to a region this thread does not own and may not even exist                         yet, so without this the read throws and the plugin's whole operation fails.
                          how     : ChunkTaskScheduler.syncLoadNonFull at FULL.getParent(). A below-FULL chunk                         is not region-owned, not ticked and not published into any region, and the platform's                         own implementation has a region-threading branch that waits through managedBlock -                         Folia calls it the same way from PlayerSpawnFinder.
                          cost    : the calling region does not tick anything else until the load finishes.                         Paper stalls every region for the same read, because it has only one.
                          disable : compat-config.cross-region-block-load=false""",
                where, level.getWorld().getName());
    }

    private static void report(final ServerLevel level) {
        final boolean global = io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegion() == null;
        final String key = (global ? "global region" : "a region thread") + " -> " + level.getWorld().getName();
        if (REPORTED.add(key)) {
            LOGGER.info("""
                    [Lecithin] Served a cross-region block read from a resident chunk: {}
                      why     : every world has its own regioniser, so a tick thread can never own another \
                    world's chunks - this read has no legal thread and would otherwise be impossible, not \
                    merely misplaced.
                      safety  : resident chunks only (an absent chunk still throws, so nothing loads here), \
                    reads only (writes are unchanged), and PalettedContainer is already concurrent-read \
                    safe upstream. Worst case is a one-tick-stale block state.
                      disable : compat-config.cross-region-block-read=false""", key);
        }
    }
}
