package fun.bm.lecithin.compat;

import fun.bm.lecithin.config.modules.CompatConfig;
import net.minecraft.server.MinecraftServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lecithin: during startup the server bootstrap thread <em>is</em> the owner of global server state,
 * so {@code RegionizedServer.ensureGlobalTickThread} must not reject it.
 *
 * <p>Why this is not "bypassing an ownership check": that check exists to serialise writes to global
 * server state against the region threads. On this fork those threads do not exist yet at the point
 * this branch can be reached. {@code MinecraftServer.runServer} is explicit about the order:
 * <pre>
 *   initServer()                          // world loading AND plugin onLoad/onEnable happen here
 *   RegionizedServer.getInstance().init() // region ticking starts only here
 *   LOGGER.info("Done (...)")
 *   for (;;) Thread.sleep(Long.MAX_VALUE) // the bootstrap thread never runs anything again
 * </pre>
 * So "the caller is the bootstrap thread" can only mean startup, and during startup it is the only
 * thread touching this state - exactly the situation Paper has on its main thread, where these same
 * plugin calls are the normal, supported thing to do.
 *
 * <p>It also removes a real inconsistency rather than creating one. The bootstrap thread is itself a
 * {@code TickThread} ({@code MinecraftServer} constructs it as one), so on that very thread
 * {@code Bukkit.isPrimaryThread()} - which this platform answers with
 * {@code TickThread.isTickThread()} - already returns {@code true}. A plugin that asks the standard
 * Bukkit question "may I touch server state directly?" is told yes, and was then rejected anyway.
 * That gap is the compatibility bug; this closes it.
 *
 * <p>Nothing here is per-plugin: there is no plugin name, jar hash or call-site list. Any plugin
 * configuring server state from {@code onEnable} - a very common Bukkit idiom - is covered, and any
 * call made after startup still fails exactly as before.
 *
 * <p>Kill switch: {@code -Dlecithin.compat.startupGlobalContext=false} restores the stock rejection.
 */
public final class LecithinStartupGlobalContext {

    private static final Logger LOGGER = LogManager.getLogger(LecithinStartupGlobalContext.class);

    /**
     * One diagnostic line per distinct reason, so the behaviour is observable without flooding.
     */
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private LecithinStartupGlobalContext() {
    }

    /**
     * True when the calling thread is the bootstrap thread, i.e. the server is still starting up and
     * owns its global state outright.
     */
    public static boolean isStartupThread() {
        final MinecraftServer server = MinecraftServer.getServer();
        return server != null && Thread.currentThread() == server.getRunningThread();
    }

    /**
     * Whether a global-state operation rejected by the region check should be allowed to proceed on
     * the calling thread. Reports the first occurrence of each distinct reason.
     *
     * @param reason the message the stock check would have thrown with
     * @return {@code true} to proceed on this thread, {@code false} to let the caller throw
     */
    public static boolean allow(final String reason) {
        if (!CompatConfig.startupGlobalContext || !isStartupThread()) {
            return false;
        }
        if (REPORTED.add(reason)) {
            LOGGER.info("""
                            [Lecithin] Allowed a global-state call on the startup thread: {}
                              context : thread={}, startup (region ticking has not begun - RegionizedServer.init() \
                            runs after initServer(), which is where plugin onEnable happens)
                              why     : during startup the bootstrap thread is the sole owner of this state, which \
                            is what Paper's main thread is at the same point. Bukkit.isPrimaryThread() already \
                            reports true on this thread. Calls made after startup are rejected exactly as before.
                              disable : -Dlecithin.compat.startupGlobalContext=false""",
                    reason, Thread.currentThread().getName());
        }
        return true;
    }
}
