package fun.bm.lecithin.compat;

import ca.spottedleaf.common.time.TickData;
import fun.bm.lecithin.config.modules.CompatConfig;
import io.papermc.paper.threadedregions.ThreadedRegionizer;
import io.papermc.paper.threadedregions.TickRegionScheduler;
import io.papermc.paper.threadedregions.TickRegions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Lecithin: answer {@code Server#getTPS()} and {@code Server#getAverageTickTime()} from every thread,
 * and answer them with the <em>server</em> when the caller is not inside a world region.
 *
 * <h2>The two gaps, and why the second one is worse</h2>
 *
 * <p><b>It throws.</b> {@code CraftServer#getTPS()} resolves the region the calling thread is ticking
 * and, failing that, throws {@code UnsupportedOperationException("Not on any region")}. Paper's
 * contract has no such failure - it is an ordinary getter over a statistic, touching no world state
 * and needing no ownership - so every caller that is not on a tick thread dies. That is not
 * hypothetical, and it is not only plugins: the fork's own console GUI calls it from a Swing timer
 * on the AWT event thread, unguarded, twice ({@code RAMDetails#update} and
 * {@code StatsComponent#tick}), so a server started with a GUI throws twice a second.
 *
 * <p><b>It lies.</b> The bigger problem is the case that does <i>not</i> throw. Folia's global tick
 * handle is a {@code RegionScheduleHandle} like any other, so a caller on the global region thread
 * resolves it and gets <i>the global tick's</i> TPS - and the global tick only advances weather, the
 * world border and connections. It sits at 20.00 no matter how far behind the worlds are. Console and
 * RCON commands run on the global region, so an operator running any plugin's {@code /tps} from the
 * console is told 20.00 while every region crawls, and a plugin polling from the global scheduler -
 * the "restart the server when TPS drops" pattern - never fires. A number that is always healthy is
 * worse than an exception, because nothing reports it as broken.
 *
 * <h2>What this changes</h2>
 * Only the answer given to a caller that is <b>not</b> ticking a world region:
 *
 * <ul>
 *   <li><b>On a world region thread:</b> unchanged. The caller gets that region's own figures, which
 *       is Folia's deliberate design and is the most useful answer to a player-scoped question.</li>
 *   <li><b>Anywhere else</b> - the global region thread, the shutdown thread with no region, the GUI
 *       thread, a plugin's own pool: the worst figure across every world region, which is the honest
 *       server-scope answer and the one Folia's own {@code /tps} prints first as "Lowest Region
 *       TPS". A mean or a median would hide exactly the region an operator is asking about.</li>
 * </ul>
 *
 * <p>A region with no tick report yet is skipped rather than counted as 20.00 - counting it is how
 * {@code /tps} can currently print a healthy median for a server that has not ticked. When there is
 * no world region at all, the configured tick rate is returned: nothing is behind, because nothing
 * is running.
 *
 * <p>Nothing here is per-plugin, and the per-region question keeps its own API: {@code
 * Server#getRegionTPS(World, int, int)} and its {@code Location}/{@code Chunk} overloads already
 * answer "how is that region doing" from any thread, so no information is lost by making the
 * server-scope method answer at server scope.
 *
 * <p>Kill switch: {@code compat-config.server-tps-off-region=false} restores stock behaviour, which
 * is the global tick's figures on the global region thread and an exception everywhere else.
 */
public final class LecithinServerTps {

    private LecithinServerTps() {
    }

    /**
     * @return the 1m/5m/15m TPS to report, or {@code null} to let the caller apply stock behaviour
     */
    public static double @Nullable [] getTPS() {
        if (!shouldAnswer()) {
            return null;
        }
        final List<TickRegionScheduler.RegionScheduleHandle> handles = worldRegionHandles();
        if (handles.isEmpty()) {
            final double idle = tickRate();
            return new double[] { idle, idle, idle };
        }
        final long now = System.nanoTime();
        return new double[] {
                worstTps(handles, now, 1),
                worstTps(handles, now, 5),
                worstTps(handles, now, 15),
        };
    }

    /**
     * @return the average tick time in nanoseconds to report, or {@code null} for stock behaviour
     */
    public static @Nullable Long getAverageTickTimeNanos() {
        if (!shouldAnswer()) {
            return null;
        }
        final List<TickRegionScheduler.RegionScheduleHandle> handles = worldRegionHandles();
        if (handles.isEmpty()) {
            return Long.valueOf(0L);
        }
        final long now = System.nanoTime();
        double worst = 0.0;
        for (final TickRegionScheduler.RegionScheduleHandle handle : handles) {
            final TickData.TickReportData report = handle.getTickReport15s(now);
            if (report == null) {
                continue;
            }
            worst = Math.max(worst, report.timePerTickData().segmentAll().average());
        }
        return Long.valueOf((long) worst);
    }

    /**
     * Whether this call should be answered at server scope.
     *
     * <p>{@code getCurrentRegion()} is the exact question: it is non-null only on a thread ticking a
     * <em>world</em> region, and null on the global region thread even though the global tick handle
     * would satisfy the platform's own {@code instanceof RegionScheduleHandle} test. That difference
     * is the whole point - resolving the global handle is what produces the constant 20.00.
     */
    private static boolean shouldAnswer() {
        return CompatConfig.serverTpsOffRegion && TickRegionScheduler.getCurrentRegion() == null;
    }

    private static double worstTps(final List<TickRegionScheduler.RegionScheduleHandle> handles,
                                   final long now, final int minutes) {
        double worst = Double.MAX_VALUE;
        for (final TickRegionScheduler.RegionScheduleHandle handle : handles) {
            final TickData.TickReportData report = switch (minutes) {
                case 1 -> handle.getTickReport1m(now);
                case 5 -> handle.getTickReport5m(now);
                default -> handle.getTickReport15m(now);
            };
            if (report == null) {
                continue; // no sample yet: skipped, never counted as healthy
            }
            worst = Math.min(worst, report.tpsData().segmentAll().average());
        }
        return worst == Double.MAX_VALUE ? tickRate() : worst;
    }

    /**
     * Every world region's scheduling handle. Collected the same way {@code /tps} collects it, which
     * is also from off-region, so the synchronised regioniser walk is the platform's own pattern
     * rather than a new one.
     */
    private static List<TickRegionScheduler.RegionScheduleHandle> worldRegionHandles() {
        final List<TickRegionScheduler.RegionScheduleHandle> handles = new ArrayList<>();
        for (final World bukkitWorld : Bukkit.getWorlds()) {
            final ServerLevel level = ((CraftWorld) bukkitWorld).getHandle();
            level.regioniser.computeForAllRegions(
                    (final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region) ->
                            handles.add(region.getData().getRegionSchedulingHandle()));
        }
        return handles;
    }

    private static double tickRate() {
        final MinecraftServer server = MinecraftServer.getServer();
        return server == null ? 20.0 : 1.0E9 / (double) server.tickRateManager().nanosecondsPerTick();
    }
}
