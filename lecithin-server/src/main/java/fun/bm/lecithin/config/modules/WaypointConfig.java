package fun.bm.lecithin.config.modules;

import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(category = EnumConfigCategory.ROOT, name = "waypoint")
public class WaypointConfig implements IConfigModule {
    @ConfigInfo(name = "remake_connections")
    public static boolean remakeConnections = true;
}
