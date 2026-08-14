package fun.bm.lophinya.compat;

import io.papermc.paper.threadedregions.RegionizedServer;
import net.minecraft.server.MinecraftServer;

/**
 * Folia-conformant Bukkit scoreboard support.
 *
 * <p>Folia blocks five scoreboard API methods with an unconditional throw. The implementations
 * behind them are intact; this switch opens them under region-architecture rules instead of
 * single-main-thread rules:
 *
 * <ul>
 *   <li>Plugin-created boards are plain objects. Creation and structural changes broadcast through
 *       netty and a copy-on-write player list, both thread-safe, so any tick thread may do them.
 *   <li>The main scoreboard is global shared state and is persisted. Structural changes to it must
 *       run on the global tick thread - the same thread the vanilla /scoreboard command uses.
 *   <li>Assigning a board to a player is per-player display state and must run on the thread that
 *       owns that player.
 * </ul>
 *
 * <p>Neither check guesses an owner; both only verify the current thread and fail loudly with
 * attribution when it is the wrong one.
 *
 * <pre>
 *   -Dlophinya.compat.scoreboardApi=false   # revert to Folia stock: the five methods throw again
 * </pre>
 */
public final class LophinyaScoreboardSupport {
    /**
     * True when this handle is the persisted main scoreboard shared by every world.
     */
    public static boolean isMainBoard(final net.minecraft.world.scores.Scoreboard board) {
        return board == MinecraftServer.getServer().getScoreboard();
    }

    /**
     * Structural changes to the main scoreboard follow the vanilla /scoreboard command's thread
     * rule: global region only. Off-thread callers fail loudly instead of corrupting a persisted
     * shared structure.
     */
    public static void ensureMainBoardStructuralChange(final String what) {
        if (!RegionizedServer.isGlobalTickThread()) {
            throw new IllegalStateException(
                "Lophinya: " + what + " on the main scoreboard requires the global region thread"
                    + " (current thread: " + Thread.currentThread().getName() + ")."
                    + " Schedule it with Bukkit.getGlobalRegionScheduler(), or use a plugin-created"
                    + " scoreboard (ScoreboardManager#getNewScoreboard).");
        }
    }
}
