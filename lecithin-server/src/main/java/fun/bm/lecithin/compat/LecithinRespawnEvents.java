package fun.bm.lecithin.compat;

import fun.bm.lecithin.config.modules.EventConfig;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Location;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerRespawnEvent.RespawnReason;
import org.jspecify.annotations.Nullable;

/**
 * Lecithin: fire {@link PlayerRespawnEvent}, which this platform never fires at all.
 *
 * <h2>The gap</h2>
 * Paper fires the event from {@code ServerPlayer#findRespawnPositionAndUseSpawnBlock0}, the one
 * place that turns a player's stored respawn data into the position they are about to be placed at.
 * Folia does not call that method for a respawn: {@code ServerPlayer#respawn} is a rewrite that
 * resolves the position itself - {@code findRespawnAndUseSpawnBlock} for a bed or anchor,
 * {@code fudgeSpawnLocation} otherwise - and hands it straight to {@code placeInAsync}. The Paper
 * method survives only as the {@code EndPortalBlock} helper, so on this fork
 * {@code PlayerRespawnEvent} is dead API: it is never constructed, and a plugin that relies on it to
 * place a player after death - which is what every hub, warp and per-world spawn plugin does - is
 * simply not called.
 *
 * <h2>Where it is fired, and why exactly there</h2>
 * At the head of the waiter Folia attaches to its {@code spawnPosComplete}, i.e. the instant the
 * respawn position is known and before anything is done with it. That point has the four properties
 * the event needs, and none of them is an accident of the current code:
 *
 * <ul>
 *   <li><b>The position is final.</b> Both of Folia's resolution paths - the bed/anchor branch and
 *       the {@code fudgeSpawnLocation} branch - end at exactly this completion, so one hook covers
 *       bed, respawn anchor, missing respawn block and plain world spawn.</li>
 *   <li><b>Nothing has been applied yet.</b> The waiter's own first statement is {@code setPosRaw};
 *       firing above it means a handler's {@code setRespawnLocation} is honoured simply by using the
 *       returned location, with no state to unwind.</li>
 *   <li><b>It is a tick thread, and the right one.</b> {@code CallbackCompletable} runs its waiters
 *       on the thread that completes it, and both paths complete from a chunk-load callback.
 *       {@code ChunkTaskScheduler#scheduleChunkLoad} re-dispatches to the region that owns the
 *       chunk when the caller does not own it, so the completing thread always owns the respawn
 *       position. That is what makes the respawn-anchor write below legal, and it is why the event
 *       is fired here rather than where the respawn was requested.</li>
 *   <li><b>A cross-world redirect already works.</b> The waiter reads the destination world back out
 *       of the location it is given ({@code spawnLoc.getWorld()}) and passes it to
 *       {@code placeInAsync}, which queues onto the destination region's own task queue. So a
 *       handler moving the player to another world needs no extra plumbing.</li>
 * </ul>
 *
 * <h2>Respawn anchor charge</h2>
 * Paper consumes the anchor's charge <em>after</em> the event and only if a handler did not move the
 * player (SPIGOT-5989). Folia builds the same {@code consumeAnchorCharge} runnable inside
 * {@code findRespawnAndUseSpawnBlock} and then drops it on the floor - it is never run, so on stock
 * an anchor is never depleted even though the depletion sound is played. Restoring the event
 * restores the rule that governs it, on the thread that owns the anchor.
 *
 * <h2>The bounded divergence, stated rather than hidden</h2>
 * Paper aborts the respawn when the player disconnected while handlers ran
 * ({@code if (this.connection.isDisconnected()) return null;}). It can, because at that point the
 * player has not been removed from the world yet. Here they have: {@code respawn} calls
 * {@code removePlayerImmediately} before the position is resolved, so refusing to place them would
 * strand the entity outside every world. The placement therefore always completes, and a player who
 * left mid-event is cleaned up by the normal disconnect path instead.
 *
 * <p>Grouped by API symbol, not by plugin: nothing here knows a plugin name, jar hash or call site.
 *
 * <p>Kill switch: {@code event-config.respawn-event=false} restores stock behaviour, which is that
 * the event is never fired and the anchor is never consumed.
 */
public final class LecithinRespawnEvents {

    private LecithinRespawnEvents() {
    }

    /**
     * Fires {@link PlayerRespawnEvent} for a respawn position the platform has just resolved.
     *
     * @param resolved            the position Folia resolved; also the event's default
     * @param consumeAnchorCharge the platform's own charge-consumption runnable, or {@code null}
     *                            when the respawn did not come from a charged anchor
     * @return the location to respawn at - the handler's, or {@code resolved} when untouched
     */
    public static Location callRespawnEvent(final ServerPlayer player, final Location resolved,
                                            final RespawnReason reason, final boolean isBedSpawn,
                                            final boolean isAnchorSpawn, final boolean missingRespawnBlock,
                                            final @Nullable Runnable consumeAnchorCharge) {
        if (!EventConfig.respawnEvent) {
            return resolved; // stock: no event, and no charge consumed either
        }

        final PlayerRespawnEvent event = new PlayerRespawnEvent(
                player.getBukkitEntity(), resolved.clone(), isBedSpawn, isAnchorSpawn, missingRespawnBlock, reason);
        event.callEvent(); // not cancellable

        final Location chosen = event.getRespawnLocation();
        if (chosen == null || chosen.getWorld() == null) {
            // A handler handed back something unusable. Placing the player at the platform's own
            // answer is the only outcome that leaves them in a world at all.
            runAnchorCharge(consumeAnchorCharge);
            return resolved;
        }
        if (chosen.equals(resolved)) {
            runAnchorCharge(consumeAnchorCharge); // SPIGOT-5989: only when the location is unchanged
            return resolved;
        }
        return chosen;
    }

    private static void runAnchorCharge(final @Nullable Runnable consumeAnchorCharge) {
        if (consumeAnchorCharge != null) {
            consumeAnchorCharge.run();
        }
    }
}
