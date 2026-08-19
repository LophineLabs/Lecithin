package fun.bm.lecithin.config.modules;

import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.config.flags.HotReloadUnsupported;
import me.earthme.luminol.enums.EnumConfigCategory;

@ConfigClassInfo(name = "compat-config", category = EnumConfigCategory.ROOT)
public class CompatConfig implements IConfigModule {
    @HotReloadUnsupported
    @ConfigInfo(name = "restore-async-scheduler")
    public static boolean restoreAsyncScheduler = true;

    @HotReloadUnsupported
    @ConfigInfo(name = "economy-serialization")
    public static boolean economySerialization = true;

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

    /**
     * Return a player's stored respawn location, instead of throwing {@code World mismatch}, when
     * the calling thread may not read the respawn world to validate the spawn block. Turning this
     * off restores the unconditional validating read. See {@link
     * fun.bm.lecithin.compat.LecithinRespawnLocationLookup}.
     */
    @HotReloadUnsupported
    @ConfigInfo(name = "respawn-location-lookup")
    public static boolean respawnLocationLookup = true;

    @HotReloadUnsupported
    @ConfigInfo(name = "teleport-refusal-diagnostics")
    public static boolean teleportRefusalDiagnostics = false;

    @HotReloadUnsupported
    @ConfigInfo(name = "teleport-handover")
    public static boolean teleportHandover = true;

    @HotReloadUnsupported
    @ConfigInfo(name = "async-context-inheritance")
    public static boolean asyncContextInheritance = true;

    /**
     * Let an asynchronous Bukkit event that names exactly one player stand as the execution
     * provenance for legacy sync scheduler calls made from inside its listeners. Turning this off
     * restores stock Folia rejection for those calls; it does not affect any other dispatch path.
     */
    @HotReloadUnsupported
    @ConfigInfo(name = "async-event-provenance")
    public static boolean asyncEventProvenance = true;

    /**
     * The weaker half of {@link #asyncEventProvenance}: an asynchronous event the platform defines
     * but which names no entity at all - the connection phase above all - is treated as server-scope
     * and its legacy sync calls go to the global region. Separate from the flag above so the strong
     * claim (an event that names one player belongs to that player) can be kept while the weaker one
     * is turned off.
     */
    @HotReloadUnsupported
    @ConfigInfo(name = "async-platform-event-global-scope")
    public static boolean asyncPlatformEventGlobalScope = true;

    @HotReloadUnsupported
    @ConfigInfo(name = "command-dispatch-handover")
    public static boolean commandDispatchHandover = true;

    @HotReloadUnsupported
    @ConfigInfo(name = "server-current-tick")
    public static boolean serverCurrentTick = true;

    @HotReloadUnsupported
    @ConfigInfo(name = "scoreboard-api")
    public static boolean scoreboardApi = true;

    @HotReloadUnsupported
    @ConfigInfo(name = "cross-region-block-read")
    public static boolean crossRegionBlockRead = true;

    @HotReloadUnsupported
    @ConfigInfo(name = "startup-global-context")
    public static boolean startupGlobalContext = true;

    @HotReloadUnsupported
    @ConfigInfo(name = "startup-context-dispatch")
    public static boolean startupContextDispatch = true;
}
