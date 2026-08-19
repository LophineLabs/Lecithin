package fun.bm.lecithin.compat;

import fun.bm.lecithin.config.modules.CompatConfig;
import net.minecraft.world.entity.Entity;

/**
 * Lecithin: diagnostic output for D-40 - the same-tick second teleport the platform refuses.
 *
 * <h2>Why this exists at all</h2>
 * A plugin that calls {@code Entity#teleport} twice in one tick gets {@code true} then
 * {@code false}, and the second teleport silently does not happen. Paper performs both. Four
 * mechanism hypotheses were written for it and all four were killed by measurement rather than by
 * argument - chunk loading, then {@code isValid()}, then {@code hasNullCallback()}, then chunk
 * readiness - each time by printing one more value instead of reasoning harder.
 *
 * <p>The refusal comes from {@code Entity#canTeleportAsync()}, which is the conjunction of four
 * predicates. Every hypothesis so far guessed <b>which</b> one was false from outside, because
 * {@code hasNullCallback()} has no Bukkit API and cannot be observed by a probe. This class prints
 * them, so the next statement about D-40 is a reading rather than another story.
 *
 * <h2>🔴 Diagnostics only</h2>
 * Nothing here changes lifecycle, scheduling, return values or the outcome of any teleport. It is
 * called from a point where the platform has <i>already</i> decided to refuse, and its return type
 * is {@code void} so it cannot influence that decision even by accident. It is
 * <b>disabled by default</b> and prints only on a refusal or on a callback transition, never per
 * tick.
 *
 * <p>Switch: {@code compat-config.teleport-refusal-diagnostics=true}. Default off.
 */
public final class LecithinTeleportRefusalDiagnostics {

    private static final org.slf4j.Logger LOGGER =
            com.mojang.logging.LogUtils.getLogger();

    /**
     * Prints why {@code canTeleportAsync()} said no, plus the state DEC-59 asks for.
     *
     * <p>The four predicates are printed individually rather than as the conjunction the platform
     * evaluates, because "canTeleportAsync returned false" is what is already known and is exactly
     * the thing that has produced four wrong explanations.
     */
    public static void reportRefusal(final Entity entity, final net.minecraft.server.level.ServerLevel destination,
                                     final net.minecraft.world.phys.Vec3 pos) {
        if (!CompatConfig.teleportRefusalDiagnostics) {
            return;
        }
        try {
            final StringBuilder sb = new StringBuilder(512);
            sb.append("[Lecithin][D-40] teleportAsync refused by canTeleportAsync()")
                    .append("\n  entity           : ").append(entity.getType().toShortString())
                    .append(" id=").append(entity.getId()).append(" uuid=").append(entity.getUUID())
                    // The four predicates of canTeleportAsync(), separately.
                    .append("\n  hasNullCallback  : ").append(entity.hasNullCallback())
                    .append("   <- no Bukkit API for this; it is why the patch exists")
                    .append("\n  isRemoved        : ").append(entity.isRemoved())
                    .append("\n  isAlive          : ").append(entity.isAlive())
                    .append("\n  isSleeping       : ")
                    .append(entity instanceof net.minecraft.world.entity.LivingEntity living
                            ? String.valueOf(living.isSleeping()) : "n/a (not a LivingEntity)")
                    .append("\n  removalReason    : ").append(entity.getRemovalReason())
                    .append("\n  levelCallback    : ").append(describeCallback(entity))
                    // Ownership and position: the two things previously assumed rather than read.
                    .append("\n  ownedByCurrent   : ").append(ownedByCurrentRegion(entity))
                    .append("\n  from             : ").append(String.valueOf(entity.level().dimension()))
                    .append(' ').append(fmt(entity.position()))
                    .append("\n  to               : ").append(String.valueOf(destination.dimension()))
                    .append(' ').append(fmt(pos))
                    .append("\n  fromChunk        : ").append(chunkState(entity.level(), entity.position()))
                    .append("\n  toChunk          : ").append(chunkState(destination, pos))
                    // The vehicle graph, because a teleport of a rider and a teleport of a vehicle reach
                    // this refusal by different routes.
                    .append("\n  isPassenger      : ").append(entity.isPassenger())
                    .append("  isVehicle=").append(entity.isVehicle())
                    .append("  passengers=").append(entity.getPassengers().size());
            LOGGER.warn(sb.toString());
        } catch (final Throwable t) {
            // A diagnostic that can break the thing it observes is worse than no diagnostic.
            LOGGER.warn("[Lecithin][D-40] diagnostic itself failed: {}", t.toString());
        }
    }

    /**
     * Prints transitions of the level callback between NULL and non-NULL.
     *
     * <p>This is the other half DEC-59 asks for: the <i>time points</i> at which the entity leaves
     * and rejoins the level's lookup. The surviving hypothesis is that a same-region teleport leaves
     * the entity untracked for the rest of the tick and {@code canTeleportAsync()} refuses on that;
     * pairing these lines with the refusal above either shows that ordering or does not.
     *
     * <p>Only transitions are printed. Every entity gets a callback set when it is added to a world,
     * so printing every call would flood the log on any real server and drown the signal.
     */
    public static void reportCallbackTransition(final Entity entity,
                                                final net.minecraft.world.level.entity.EntityInLevelCallback before,
                                                final net.minecraft.world.level.entity.EntityInLevelCallback after) {
        if (!CompatConfig.teleportRefusalDiagnostics) {
            return;
        }
        final boolean wasNull = before == net.minecraft.world.level.entity.EntityInLevelCallback.NULL;
        final boolean isNull = after == net.minecraft.world.level.entity.EntityInLevelCallback.NULL;
        if (wasNull == isNull) {
            return;
        }
        try {
            LOGGER.warn("[Lecithin][D-40] levelCallback {} -> {}  entity={} id={} at={} thread={}{}",
                    wasNull ? "NULL" : "set", isNull ? "NULL" : "set",
                    entity.getType().toShortString(), entity.getId(), fmt(entity.position()),
                    Thread.currentThread().getName(), stackSample());
        } catch (final Throwable t) {
            LOGGER.warn("[Lecithin][D-40] diagnostic itself failed: {}", t.toString());
        }
    }

    /**
     * How many callback transitions still get a stack sample; after that, only the one-liner.
     */
    private static final java.util.concurrent.atomic.AtomicInteger STACK_SAMPLES_LEFT =
            new java.util.concurrent.atomic.AtomicInteger(24);

    /**
     * The call path of a callback transition, for the first few transitions only.
     *
     * <p>The one-liner above says <i>when</i> the callback changed; it never said <i>who</i>. That
     * left "who sets the callback to NULL, and is it deliberate" answerable only by argument, which
     * is what four wrong D-40 explanations were made of. A stack is a reading.
     *
     * <p>Bounded on purpose: every entity added to a world produces a transition, so an unbounded
     * stack per transition would cost more than the answer is worth on anything but a probe run.
     */
    private static String stackSample() {
        if (STACK_SAMPLES_LEFT.getAndDecrement() <= 0) {
            return "";
        }
        final StringBuilder sb = new StringBuilder(512).append("\n  called from:");
        StackWalker.getInstance().walk(frames -> {
            frames.skip(2).limit(12).forEach(f -> sb.append("\n    ").append(f));
            return null;
        });
        return sb.toString();
    }

    private static String describeCallback(final Entity entity) {
        final Object callback = entity.levelCallback;
        return callback == net.minecraft.world.level.entity.EntityInLevelCallback.NULL
                ? "NULL" : callback.getClass().getName();
    }

    private static String ownedByCurrentRegion(final Entity entity) {
        try {
            return String.valueOf(io.papermc.paper.threadedregions.RegionizedServer.isGlobalTickThread()
                    ? "global-region-thread"
                    : ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(entity));
        } catch (final Throwable t) {
            return "<unavailable: " + t.getClass().getSimpleName() + '>';
        }
    }

    /**
     * Loaded-ness of the chunk holding {@code pos}, read without forcing it to load.
     */
    private static String chunkState(final net.minecraft.world.level.Level level,
                                     final net.minecraft.world.phys.Vec3 pos) {
        try {
            final int cx = ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkX(pos);
            final int cz = ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkZ(pos);
            return cx + "," + cz + " loaded=" + (level.getChunkIfLoadedImmediately(cx, cz) != null);
        } catch (final Throwable t) {
            return "<unavailable: " + t.getClass().getSimpleName() + '>';
        }
    }

    private static String fmt(final net.minecraft.world.phys.Vec3 pos) {
        return String.format(java.util.Locale.ROOT, "(%.2f,%.2f,%.2f)", pos.x, pos.y, pos.z);
    }
}
