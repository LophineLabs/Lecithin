package fun.bm.lecithin.config.modules;

import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.config.flags.HotReloadUnsupported;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(name = "compat-config", category = EnumConfigCategory.ROOT)
public class CompatConfig {
    @HotReloadUnsupported
    @ConfigInfo(name = "restore-async-scheduler")
    public static boolean restoreAsyncScheduler = true;

    @HotReloadUnsupported
    @ConfigInfo(name = "economy-serialization")
    public static boolean economySerialization = true;

    @HotReloadUnsupported
    @ConfigInfo(name = "plugin-scheduler-dispatch")
    public static boolean pluginSchedulerDispatch = true;

    @HotReloadUnsupported
    @ConfigInfo(name = "diagnostics")
    public static boolean diagnostics = true;

    @HotReloadUnsupported
    @ConfigInfo(name = "teleport-semantics")
    public static boolean teleportSemantics = true;

    @HotReloadUnsupported
    @ConfigInfo(name = "caller-context-dispatch")
    public static boolean callerContextDispatch = true;

    @HotReloadUnsupported
    @ConfigInfo(name = "paper-lib-environment")
    public static boolean paperLibEnvironment = true;

    @HotReloadUnsupported
    @ConfigInfo(name = "permission-locking")
    public static boolean permissionLocking = true;

    @HotReloadUnsupported
    @ConfigInfo(name = "region-read-diagnostics")
    public static boolean regionReadDiagnostics = true;

    @HotReloadUnsupported
    @ConfigInfo(name = "riding-teleport")
    public static boolean ridingTeleport = true;
}
