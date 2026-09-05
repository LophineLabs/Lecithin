package fun.bm.lecithin.config.modules;

import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.config.flags.DoNotLoad;
import me.earthme.luminol.enums.EnumConfigCategory;
import me.earthme.luminol.enums.EnumLoadType;

@ConfigClassInfo(name = "compat-config", category = EnumConfigCategory.ROOT)
public class CompatConfig {
    @DoNotLoad(when = EnumLoadType.RELOAD)
    @ConfigInfo(name = "restore-async-scheduler")
    public static boolean restoreAsyncScheduler = true;

    @DoNotLoad(when = EnumLoadType.RELOAD)
    @ConfigInfo(name = "economy-serialization")
    public static boolean economySerialization = true;

    @DoNotLoad(when = EnumLoadType.RELOAD)
    @ConfigInfo(name = "diagnostics")
    public static boolean diagnostics = true;

    @DoNotLoad(when = EnumLoadType.RELOAD)
    @ConfigInfo(name = "teleport-semantics")
    public static boolean teleportSemantics = true;

    @DoNotLoad(when = EnumLoadType.RELOAD)
    @ConfigInfo(name = "caller-context-dispatch")
    public static boolean callerContextDispatch = true;

    @DoNotLoad(when = EnumLoadType.RELOAD)
    @ConfigInfo(name = "paper-lib-environment")
    public static boolean paperLibEnvironment = true;

    @DoNotLoad(when = EnumLoadType.RELOAD)
    @ConfigInfo(name = "permission-locking")
    public static boolean permissionLocking = true;

    @DoNotLoad(when = EnumLoadType.RELOAD)
    @ConfigInfo(name = "region-read-diagnostics")
    public static boolean regionReadDiagnostics = true;

    @DoNotLoad(when = EnumLoadType.RELOAD)
    @ConfigInfo(name = "riding-teleport")
    public static boolean ridingTeleport = true;

    /**
     * Return a player's stored respawn location, instead of throwing {@code World mismatch}, when
     * the calling thread may not read the respawn world to validate the spawn block. Turning this
     * off restores the unconditional validating read. See {@link
     * fun.bm.lecithin.compat.LecithinRespawnLocationLookup}.
     */
    @DoNotLoad(when = EnumLoadType.RELOAD)
    @ConfigInfo(name = "respawn-location-lookup")
    public static boolean respawnLocationLookup = true;

    @DoNotLoad(when = EnumLoadType.RELOAD)
    @ConfigInfo(name = "teleport-refusal-diagnostics")
    public static boolean teleportRefusalDiagnostics = false;

    @DoNotLoad(when = EnumLoadType.RELOAD)
    @ConfigInfo(name = "teleport-handover")
    public static boolean teleportHandover = true;

    @DoNotLoad(when = EnumLoadType.RELOAD)
    @ConfigInfo(name = "async-context-inheritance")
    public static boolean asyncContextInheritance = true;

    /**
     * Let an asynchronous Bukkit event that names exactly one player stand as the execution
     * provenance for legacy sync scheduler calls made from inside its listeners. Turning this off
     * restores stock Folia rejection for those calls; it does not affect any other dispatch path.
     */
    @DoNotLoad(when = EnumLoadType.RELOAD)
    @ConfigInfo(name = "async-event-provenance")
    public static boolean asyncEventProvenance = true;

    /**
     * The weaker half of {@link #asyncEventProvenance}: an asynchronous event the platform defines
     * but which names no entity at all - the connection phase above all - is treated as server-scope
     * and its legacy sync calls go to the global region. Separate from the flag above so the strong
     * claim (an event that names one player belongs to that player) can be kept while the weaker one
     * is turned off.
     */
    @DoNotLoad(when = EnumLoadType.RELOAD)
    @ConfigInfo(name = "async-platform-event-global-scope")
    public static boolean asyncPlatformEventGlobalScope = true;

    @DoNotLoad(when = EnumLoadType.RELOAD)
    @ConfigInfo(name = "command-dispatch-handover")
    public static boolean commandDispatchHandover = true;

    @DoNotLoad(when = EnumLoadType.RELOAD)
    @ConfigInfo(name = "server-current-tick")
    public static boolean serverCurrentTick = true;

    @DoNotLoad(when = EnumLoadType.RELOAD)
    @ConfigInfo(name = "scoreboard-api")
    public static boolean scoreboardApi = true;

    @DoNotLoad(when = EnumLoadType.RELOAD)
    @ConfigInfo(name = "cross-region-block-read")
    public static boolean crossRegionBlockRead = true;

    /**
     * Load the chunk a read-only block lookup needs, instead of refusing the read because nobody
     * had asked for that chunk yet. Paper's block read loads its own chunk synchronously; this is
     * the same answer, through the platform's own below-FULL sync load. It stalls the calling
     * region until the load finishes - Paper stalls the whole server for the same read. Turning it
     * off narrows {@link #crossRegionBlockRead} back to resident chunks only. See {@link
     * fun.bm.lecithin.compat.LecithinCrossRegionBlockRead}.
     */
    @DoNotLoad(when = EnumLoadType.RELOAD)
    @ConfigInfo(name = "cross-region-block-load")
    public static boolean crossRegionBlockLoad = true;

    /**
     * Do not try to drain another world's pending chunk full-status updates.
     * {@code ChunkHolderManager#processTicketUpdates} guards that drain with a bare "is this a tick
     * thread" test where it needs "is this a tick thread of this world", so a cross-world chunk load
     * throws {@code World check failed} after adding its chunk ticket and before removing it -
     * leaking that ticket permanently. See {@link
     * fun.bm.lecithin.compat.LecithinForeignWorldTicketUpdates}.
     */
    @DoNotLoad(when = EnumLoadType.RELOAD)
    @ConfigInfo(name = "foreign-world-ticket-updates")
    public static boolean foreignWorldTicketUpdates = true;

    /**
     * Answer {@code Server#getTPS()} and {@code Server#getAverageTickTime()} at server scope - the
     * worst world region - when the caller is not inside a world region, instead of reporting the
     * global tick's own figures (a constant 20.00, which is what console and RCON callers get) or
     * throwing (which is what every other thread gets, the fork's own console GUI included).
     * Callers that <em>are</em> on a world region thread keep the platform's per-region answer. See
     * {@link fun.bm.lecithin.compat.LecithinServerTps}.
     */
    @DoNotLoad(when = EnumLoadType.RELOAD)
    @ConfigInfo(name = "server-tps-off-region")
    public static boolean serverTpsOffRegion = true;

    @DoNotLoad(when = EnumLoadType.RELOAD)
    @ConfigInfo(name = "startup-global-context")
    public static boolean startupGlobalContext = true;

    @DoNotLoad(when = EnumLoadType.RELOAD)
    @ConfigInfo(name = "startup-context-dispatch")
    public static boolean startupContextDispatch = true;

    /**
     * Accommodate World#setTime and World#setFullTime calls made from a region tick thread by
     * staging the target time for immediate synchronous reads and dispatching canonical mutation
     * to the global region, instead of throwing "Cannot modify time off of the global region".
     * Arbitrary async callers remain rejected. See {@link fun.bm.lecithin.compat.LecithinWorldTimeSupport}.
     */
    @DoNotLoad(when = EnumLoadType.RELOAD)
    @ConfigInfo(name = "world-time-boundary")
    public static boolean worldTimeBoundary = true;
}
