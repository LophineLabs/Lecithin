package fun.bm.lecithin.compat;

import com.mojang.logging.LogUtils;
import fun.bm.lecithin.config.modules.CompatConfig;
import io.papermc.paper.threadedregions.RegionizedServer;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.clock.WorldClock;
import org.bukkit.craftbukkit.CraftWorld;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lecithin: ownership-safe compatibility boundary for World#setTime and World#setFullTime.
 *
 * <h2>The gap</h2>
 * Folia requires that {@code World#setTime} and {@code World#setFullTime} be called on the Global Region
 * thread. When a player executes a command such as EssentialsX {@code /time set day}, the command executes
 * on the caller's Region Tick Thread. Calling {@code world.setTime(...)} throws an {@link IllegalStateException}.
 * Furthermore, legacy commands like EssentialsX immediately query {@code world.getTime()} in the very next
 * bytecode instruction to preserve relative player time offsets.
 *
 * <h2>Ownership-safe architecture</h2>
 * 1. <b>Canonical Global Mutation</b>: The real mutation of {@code ServerClockManager} and the global
 *    invalidation of {@code EnvironmentAttributeSystem} samplers are 100% executed on the Global Region
 *    Thread via {@link RegionizedServer#addTask(Runnable)}. No foreign threads ever mutate {@code ServerClockManager}.
 * 2. <b>Optimistic Staged View</b>: To satisfy the immediate synchronous read expectation of calling plugins
 *    without blocking worker threads (which would risk thread pool starvation deadlocks), the target time
 *    is staged optimistically in {@code STAGED_TIMES}. Subsequent {@code getTime()} calls on any thread
 *    observe the target time immediately until the Global Region task applies and clears it.
 * 3. <b>Player Ownership Preservation</b>: Player state modifications (such as {@code Player#setPlayerTime})
 *    are dispatched to each player's owning region thread via {@code taskScheduler.scheduleOrExecute}.
 *
 * <p>Kill switch: {@code compat-config.world-time-boundary=false} restores stock Folia refusal.
 */
public final class LecithinWorldTimeSupport {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Long> STAGED_TIMES = new ConcurrentHashMap<>();

    private LecithinWorldTimeSupport() {
    }

    public static boolean isEnabled() {
        return CompatConfig.worldTimeBoundary;
    }

    public static boolean isAllowedRegionCaller() {
        return isEnabled() && io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegion() != null;
    }

    public static Long getStagedTime(final CraftWorld world) {
        if (!isEnabled()) {
            return null;
        }
        return STAGED_TIMES.get(world.getUID());
    }

    public static void stageAndDispatch(final CraftWorld craftWorld, final Holder<WorldClock> clock, final long targetTotalTicks) {
        final UUID worldUid = craftWorld.getUID();
        final ServerLevel level = craftWorld.getHandle();

        // Stage the optimistic time immediately so subsequent getTime() sees it synchronously
        STAGED_TIMES.put(worldUid, targetTotalTicks);

        report(craftWorld, targetTotalTicks);

        // Dispatch canonical mutation to Global Region without blocking caller thread
        RegionizedServer.getInstance().addTask(() -> {
            try {
                level.clockManager().setTotalTicks(clock, targetTotalTicks);
            } finally {
                STAGED_TIMES.remove(worldUid, targetTotalTicks);
            }
        });
    }

    private static void report(final CraftWorld world, final long targetTime) {
        if (!CompatConfig.diagnostics) {
            return;
        }
        final String worldName = world.getName();
        if (REPORTED.add(worldName + ":" + targetTime)) {
            LOGGER.info("[Lecithin] World time mutation to '{}' on world '{}' staged from thread '{}' and dispatched to global region.",
                    targetTime, worldName, Thread.currentThread().getName());
        }
    }
}
