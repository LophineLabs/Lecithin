package fun.bm.lophinya.compat;

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
 * Lophinya: serve a <em>read-only</em> block lookup from an already-loaded chunk when the calling
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
 *   <li><b>Triggering a chunk load off-thread.</b> Real, and kept: this only answers from
 *       {@code getChunkAtIfLoadedImmediately}, and returns {@code null} - so the caller throws
 *       exactly as before - when the chunk is not already resident. Nothing here can load,
 *       generate, or tick a chunk.</li>
 *   <li><b>Racing a concurrent writer on the block data.</b> Upstream has already made this safe.
 *       {@code PalettedContainer.data} is {@code volatile} and every mutator is {@code synchronized};
 *       a resize publishes a brand new {@code Data} rather than editing the old one; and Paper
 *       deliberately disabled the {@code ThreadingDetector} with the comment "use proper
 *       synchronization". A reader therefore takes one consistent snapshot and indexes into it.
 *       {@code LevelChunk.getBlockStateFinal} adds only a bounds check and a plain int read.</li>
 * </ol>
 * <p>
 * So the worst outcome of a read served here is a block state that is one tick stale - which is the
 * same thing every check-then-act plugin already races against on single-threaded Paper. It cannot
 * throw, cannot deadlock, cannot corrupt, and cannot observe a torn value: entries never span two
 * longs in {@code SimpleBitStorage}, so a concurrent write is seen either wholly or not at all.
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
 * <p>During startup there is a second case, handled by {@link #readByLoadingDuringStartup}: the chunk
 * is not resident because nobody has asked for it yet. Paper guarantees it would be; Folia does not.
 * That one is restricted to the bootstrap thread - see that method for why it cannot be fixed in
 * {@code prepareLevel} and why it does not widen the rule above.
 *
 * <p>Kill switch: {@code -Dlophinya.compat.crossRegionBlockRead=false} restores the stock refusal.
 */
public final class LophinyaCrossRegionBlockRead {

    private static final Logger LOGGER = LogManager.getLogger(LophinyaCrossRegionBlockRead.class);

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
            return readByLoadingDuringStartup(level, pos);
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
            LOGGER.warn("[Lophinya] Cross-region block read raced a palette update at {} in {}; "
                    + "falling through to the stock ownership check", pos, level.getWorld().getName(), race);
            return null;
        }
    }

    /**
     * Startup only: bring the chunk in and read it, instead of refusing because nobody had asked for
     * it yet.
     *
     * <h2>The invariant this restores</h2>
     * <p>
     * On Paper, {@code MinecraftServer.prepareLevel} activates the world's saved tickets inside
     * {@code ChunkLoadCounter.track(...)} and then spins on {@code executeModerately()} until
     * {@code pendingChunks() == 0}. So by the time any plugin is enabled, the chunks the world's
     * tickets asked for - the spawn area among them - are in memory. Folia deletes both the
     * {@code track} call and the {@code executeModerately} body, leaving a loop with nothing to wait
     * for, and the invariant disappears with it.
     *
     * <p>Reading the world spawn's block in {@code onEnable} is an ordinary thing for a plugin to do,
     * and on Paper it is safe precisely because of that invariant. Without it the read has no legal
     * outcome at all: the chunk is absent, so {@link #readIfResident} cannot answer, and the stock
     * ownership check then throws - every boot, identically, with no way for the plugin to avoid it.
     *
     * <h2>Why the wait is restored here and not in {@code prepareLevel}</h2>
     * <p>
     * Because it structurally cannot be restored there. Region tick threads do not exist yet at that
     * point - {@code RegionizedServer.init()} calls {@code TickRegions.start()} and it runs
     * <em>after</em> {@code initServer()}, which is where plugin enable happens - so anything that
     * needs a region thread to complete a callback would wait forever. That is almost certainly why
     * upstream removed the loop rather than adapting it, and it is why
     * {@code ChunkTaskScheduler.syncLoadNonFull} refuses {@code FULL} outright, with the comment
     * "on Folia, it is required that the non-full load can occur completely asynchronously to avoid
     * deadlock between regions".
     *
     * <p>So this uses the one primitive the platform does sanction, at the one status it sanctions:
     * a <b>non-full</b> synchronous load. {@code MinecraftServer.prepareLevel} already calls exactly
     * this, at exactly this status, on exactly this thread, to find a spawn height - so the pattern
     * is upstream's own, not an invention. The load is performed by the chunk system's worker
     * threads, which run independently of region ticking; this thread parks until they publish it.
     *
     * <p>A chunk at {@code FULL.getParent()} has its sections populated, which is all a block-state
     * read needs. It is not ticking and nothing here makes it tick.
     *
     * <h2>Which threads, and why exactly those</h2>
     * <p>
     * The condition is {@code TickRegionScheduler.getCurrentRegion() == null}: the calling thread is
     * not ticking any region. On this platform that is a closed set of exactly two threads, and both
     * of them own no chunks anywhere:
     *
     * <ul>
     *   <li>the <b>bootstrap thread</b> during startup - and it stops running anything at all once
     *       startup ends;</li>
     *   <li>the <b>global region thread</b>, which by construction owns no chunks in any world. The
     *       class doc above already says a read from it "cannot legally read any block anywhere" -
     *       there is no other thread the caller could have used.</li>
     * </ul>
     * <p>
     * A thread that <em>is</em> ticking a region takes none of this: it returns {@code null} and gets
     * the stock refusal, so "let any region thread pull in any chunk" stays exactly as closed as it
     * was. That question is {@code D-52} and this does not answer it.
     *
     * <p>The global region case is not speculative and the wait is not novel there either: creating a
     * world at runtime lands in {@code MinecraftServer.setInitialSpawn} on the global region thread,
     * and upstream's own {@code PlayerSpawnFinder.getLevelRespawnPos} calls this same
     * {@code syncLoadNonFull} from it. Measured 2026-08-04d: it parks about 5s - long enough for the
     * watchdog to log "Global region has not responded" - and then completes. So the platform already
     * pays this cost on this thread; what it does not do is let the plugin's own follow-up read
     * succeed afterwards.
     *
     * <h2>The cost, named</h2>
     * <p>
     * This blocks the calling thread until the chunk is published. On the bootstrap thread that is
     * free - nothing else is running. On the global region thread it is not: the region's tick is
     * stalled for the duration, and the watchdog logs "Global region has not responded" past 5s.
     * The first read of an area pays it and the rest do not, because the chunk is resident
     * afterwards and {@link #readIfResident} answers them - so a plugin scanning a spawn area pays
     * per chunk, not per block.
     *
     * <p>A plugin that scanned a large area of cold chunks from the global region would stall it
     * repeatedly, and there is no cap here. That is deliberate rather than overlooked: the only
     * alternative is the read being impossible, which is where this started. If it ever becomes a
     * real problem the shape of the fix is a budget per tick, not a plugin list.
     *
     * <p>Nothing here is per-plugin, and it does not widen {@link #readIfResident} for writes or for
     * non-tick threads. The same kill switch turns it off.
     *
     * @return the block state, or {@code null} to let the caller apply the stock ownership check
     */
    private static BlockState readByLoadingDuringStartup(final ServerLevel level, final BlockPos pos) {
        if (io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegion() != null) {
            return null;
        }
        final int chunkX = pos.getX() >> 4;
        final int chunkZ = pos.getZ() >> 4;
        try {
            final ChunkAccess loaded = level.moonrise$getChunkTaskScheduler()
                    .syncLoadNonFull(chunkX, chunkZ, ChunkStatus.FULL.getParent());
            if (loaded == null) {
                return null;
            }
            reportStartupLoad(level);
            return loaded.getBlockState(pos);
        } catch (final Throwable failed) {
            // Falling through is not swallowing it: the caller then hits the stock ownership check
            // and fails exactly as it does today, naming the world and position.
            LOGGER.warn("[Lophinya] Startup chunk load for a block read at {} in {} did not complete; "
                    + "falling through to the stock ownership check", pos, level.getWorld().getName(), failed);
            return null;
        }
    }

    private static void reportStartupLoad(final ServerLevel level) {
        final String where = LophinyaStartupGlobalContext.isStartupThread() ? "startup thread" : "global region thread";
        if (REPORTED.add("no-region-load " + where + " -> " + level.getWorld().getName())) {
            LOGGER.info("""
                            [Lophinya] Loaded a chunk on the {} to answer a block read in world '{}'
                              why     : Paper's prepareLevel blocks until the world's ticketed chunks are in \
                            memory before any plugin is enabled; Folia drops that wait. A plugin reading the \
                            world spawn - in onEnable, or right after creating a world - therefore finds no \
                            chunk, and neither of these two threads owns a region it could read from instead.
                              how     : ChunkTaskScheduler.syncLoadNonFull at FULL.getParent(), the same call \
                            prepareLevel and PlayerSpawnFinder already make from these same threads. Non-full \
                            and asynchronous, so it cannot deadlock against a region.
                              scope   : threads that tick no region at all - the bootstrap thread and the global \
                            region thread. A thread ticking a region still refuses, so this does not become a \
                            general off-region chunk load.
                              disable : -Dlophinya.compat.crossRegionBlockRead=false""",
                    where, level.getWorld().getName());
        }
    }

    private static void report(final ServerLevel level) {
        final boolean global = io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegion() == null;
        final String key = (global ? "global region" : "a region thread") + " -> " + level.getWorld().getName();
        if (REPORTED.add(key)) {
            LOGGER.info("""
                    [Lophinya] Served a cross-region block read from a resident chunk: {}
                      why     : every world has its own regioniser, so a tick thread can never own another \
                    world's chunks - this read has no legal thread and would otherwise be impossible, not \
                    merely misplaced.
                      safety  : resident chunks only (an absent chunk still throws, so nothing loads here), \
                    reads only (writes are unchanged), and PalettedContainer is already concurrent-read \
                    safe upstream. Worst case is a one-tick-stale block state.
                      disable : -Dlophinya.compat.crossRegionBlockRead=false""", key);
        }
    }
}
