package fun.bm.lecithin.config.modules;

import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.config.flags.DoNotLoad;
import me.earthme.luminol.enums.EnumConfigCategory;
import me.earthme.luminol.enums.EnumLoadType;

@ConfigClassInfo(name = "event-config", category = EnumConfigCategory.ROOT)
public class EventConfig {
    @DoNotLoad(when = EnumLoadType.RELOAD)
    @ConfigInfo(name = "teleport-events")
    public static boolean teleportEvents = true;

    @DoNotLoad(when = EnumLoadType.RELOAD)
    @ConfigInfo(name = "passenger-teleport-events")
    public static boolean passengerTeleportEvents = true;

    @DoNotLoad(when = EnumLoadType.RELOAD)
    @ConfigInfo(name = "passenger-teleport-cross-world-offset")
    public static boolean passengerTeleportCrossWorldOffset = true;

    /**
     * Fire the Bukkit portal and teleport events for a vanilla portal. The platform routes a portal
     * around {@code Entity#teleportAsync} and around {@code Portal#getPortalDestination}, so it
     * reaches neither the teleport hook nor {@code CraftEventFactory.handlePortalEvents}, and a
     * player crosses worlds through a portal with no Bukkit event at all. See {@link
     * fun.bm.lecithin.compat.LecithinPortalEvents}.
     */
    @DoNotLoad(when = EnumLoadType.RELOAD)
    @ConfigInfo(name = "portal-events")
    public static boolean portalEvents = true;

    /**
     * Fire {@code PlayerRespawnEvent}. The platform rewrote {@code ServerPlayer#respawn} and no
     * longer routes it through the Paper method that fires the event, so on stock the event is never
     * constructed at all and a respawn-placing plugin is never called. Turning this off also restores
     * the platform's behaviour of never consuming a respawn anchor's charge, because Paper's rule for
     * that is defined in terms of this event. See {@link
     * fun.bm.lecithin.compat.LecithinRespawnEvents}.
     */
    @DoNotLoad(when = EnumLoadType.RELOAD)
    @ConfigInfo(name = "respawn-event")
    public static boolean respawnEvent = true;

    /**
     * Fire the legacy {@code PlayerSpawnLocationEvent} during the configuration phase. The platform
     * short-circuits the only block that constructs it, which makes it dead API even though the
     * plumbing it needs is still present and still used. Paper's newer
     * {@code AsyncPlayerSpawnLocationEvent} is unaffected either way.
     */
    @DoNotLoad(when = EnumLoadType.RELOAD)
    @ConfigInfo(name = "spawn-location-event")
    public static boolean spawnLocationEvent = true;

    /**
     * Put a player back with a position correction, not a teleport, after a cancelled
     * {@code PlayerMoveEvent} - which is what Paper does. Turning this off restores the platform's
     * rollback, which fires a {@code PLUGIN} {@code PlayerTeleportEvent} that another plugin can
     * cancel to defeat the rollback. See {@link fun.bm.lecithin.compat.LecithinMoveRollback}.
     */
    @DoNotLoad(when = EnumLoadType.RELOAD)
    @ConfigInfo(name = "move-rollback-without-teleport")
    public static boolean moveRollbackWithoutTeleport = true;
}
