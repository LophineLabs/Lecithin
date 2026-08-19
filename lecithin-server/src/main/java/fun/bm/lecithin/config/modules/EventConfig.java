package fun.bm.lecithin.config.modules;

import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.config.flags.HotReloadUnsupported;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(name = "event-config", category = EnumConfigCategory.ROOT)
public class EventConfig implements IConfigModule {
    @HotReloadUnsupported
    @ConfigInfo(name = "teleport-events")
    public static boolean teleportEvents = true;

    @HotReloadUnsupported
    @ConfigInfo(name = "passenger-teleport-events")
    public static boolean passengerTeleportEvents = true;

    @HotReloadUnsupported
    @ConfigInfo(name = "passenger-teleport-cross-world-offset")
    public static boolean passengerTeleportCrossWorldOffset = true;

    /**
     * Fire the Bukkit portal and teleport events for a vanilla portal. The platform routes a portal
     * around {@code Entity#teleportAsync} and around {@code Portal#getPortalDestination}, so it
     * reaches neither the teleport hook nor {@code CraftEventFactory.handlePortalEvents}, and a
     * player crosses worlds through a portal with no Bukkit event at all. See {@link
     * fun.bm.lecithin.compat.LecithinPortalEvents}.
     */
    @HotReloadUnsupported
    @ConfigInfo(name = "portal-events")
    public static boolean portalEvents = true;}
