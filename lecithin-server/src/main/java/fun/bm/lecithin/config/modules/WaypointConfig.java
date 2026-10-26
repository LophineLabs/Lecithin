package fun.bm.lecithin.config.modules;

import me.earthme.luminol.api.config.annotation.ConfigClassInfo;
import me.earthme.luminol.api.config.annotation.ConfigInfo;
import me.earthme.luminol.api.config.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.ROOT, name = "waypoint")
public class WaypointConfig {
    @ConfigInfo(name = "remake_connections")
    public static boolean remakeConnections = true;
}
