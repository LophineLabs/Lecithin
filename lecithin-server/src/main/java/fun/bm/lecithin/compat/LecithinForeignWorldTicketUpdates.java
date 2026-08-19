package fun.bm.lecithin.compat;

import fun.bm.lecithin.config.modules.CompatConfig;
import io.papermc.paper.threadedregions.ThreadedRegionizer;
import io.papermc.paper.threadedregions.TickRegionScheduler;
import io.papermc.paper.threadedregions.TickRegions;
import net.minecraft.server.level.ServerLevel;

/**
 * Lecithin: do not try to drain <em>another</em> world's pending full-status updates.
 *
 * <h2>The defect</h2>
 * {@code ChunkHolderManager#processTicketUpdates()} ends with
 *
 * <pre>if (isTickThread) { ret |= this.processPendingFullUpdate(); }</pre>
 *
 * and {@code processPendingFullUpdate} resolves the region this thread is ticking through
 * {@code getCurrentRegionData()}, which asserts that the region's world is <em>this manager's</em>
 * world and throws {@code IllegalStateException: World check failed} when it is not.
 *
 * <p>{@code isTickThread} is the wrong question. It asks "is this a tick thread", when the thing
 * being done needs "is this a tick thread <em>of this world</em>". The neighbouring method already
 * asks the right one: {@code addChangedStatuses} compares
 * {@code this.world.regioniser.getRegionAtUnsynchronised(...) == thisRegion} and routes anything
 * that is not this region's through {@code scheduleChunkTaskEventually}. This guard makes the last
 * line of {@code processTicketUpdates} consistent with the line above it.
 *
 * <h2>Why it matters, and what it was costing</h2>
 * Nothing in the platform's own code reaches {@code processTicketUpdates} for a foreign world, so
 * upstream never trips it. A cross-world chunk load does: {@code ChunkTaskScheduler#syncLoadNonFull}
 * adds a ticket, calls {@code processTicketUpdates()} on the target world, then waits. Called from a
 * region thread of another world, that sequence
 *
 * <ol>
 *   <li>adds a {@code NON_FULL_CHUNK_LOAD} ticket to the target world,</li>
 *   <li>throws out of {@code processTicketUpdates} on the world check,</li>
 *   <li>and never reaches its own {@code removeTicketAtLevel}, because it has no {@code finally}.</li>
 * </ol>
 *
 * <p>That ticket is registered with no timeout, so it is permanent. Every distinct chunk read this
 * way leaked one pinned chunk holder - and through {@code onChunkHolderCreate}, one region section -
 * in the target world, for the lifetime of the server. The leak also hid the bug: the chunk stayed
 * resident, so the <em>second</em> read of the same block succeeded and everything looked fine.
 *
 * <p>Measured before this guard, a player's own region thread reading nether2 at (200000, 200000):
 * first read threw {@code World check failed} and returned nothing, the second returned {@code AIR},
 * and twenty seconds later the chunk was still resident with nothing holding it but the leak.
 *
 * <h2>Why skipping is correct, not merely quieter</h2>
 * The queue being drained is {@code region.getData().getHolderManagerRegionData().pendingFullLoadUpdate}
 * - the pending updates of the region <em>this thread is ticking</em>, which belongs to the other
 * world. Draining it through this manager would apply one world's queue with another world's
 * bookkeeping, which is exactly what the assertion exists to prevent. The target world's own regions
 * drain their own queues when they tick, so nothing is dropped and nothing is deferred that would
 * not have been deferred anyway; this thread simply stops trying to do another world's work.
 *
 * <p>No ownership check is disabled: the assertion still fires for anything that genuinely reaches it
 * with mismatched state. What changes is that a caller which never had any business processing that
 * queue no longer walks into it.
 *
 * <p>Kill switch: {@code compat-config.foreign-world-ticket-updates=false} restores the bare
 * {@code isTickThread} condition, and with it the throw and the ticket leak.
 */
public final class LecithinForeignWorldTicketUpdates {

    private LecithinForeignWorldTicketUpdates() {
    }

    /**
     * Whether the calling thread may drain {@code level}'s pending full-status updates.
     *
     * @return {@code true} when this thread ticks no region at all - where
     *         {@code getCurrentRegionData()} answers {@code null} and the drain is a no-op - or ticks
     *         a region of {@code level} itself; {@code false} when it ticks a region of some other
     *         world, which is the case that would throw
     */
    public static boolean mayProcessPendingFullUpdate(final ServerLevel level) {
        if (!CompatConfig.foreignWorldTicketUpdates) {
            return true;
        }
        final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region =
                TickRegionScheduler.getCurrentRegion();
        return region == null || level == null || region.getData().world == level;
    }
}
