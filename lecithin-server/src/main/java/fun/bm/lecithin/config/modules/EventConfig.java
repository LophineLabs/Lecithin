package fun.bm.lecithin.config.modules;

import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.config.flags.HotReloadUnsupported;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(name = "event-config", category = EnumConfigCategory.ROOT)
public class EventConfig {
    @HotReloadUnsupported
    @ConfigInfo(name = "teleport-events")
    public static boolean teleportEvents = true;

    @HotReloadUnsupported
    @ConfigInfo(name = "passenger-teleport-events")
    public static boolean passengerTeleportEvents = true;
}
