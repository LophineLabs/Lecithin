package fun.bm.lecithin.compat;

import fun.bm.lecithin.config.modules.EventConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;

/**
 * Lecithin: put a player back where they were after a cancelled {@code PlayerMoveEvent} without
 * firing a teleport, which is what Paper does.
 *
 * <h2>The gap</h2>
 * Paper's rollback is {@code this.internalTeleport(from)} - a position correction and a position
 * packet, no Bukkit event. Verified against the Paper 26.2 server jar rather than from memory:
 * in both movement handlers the {@code PlayerMoveEvent.isCancelled} branch calls
 * {@code internalTeleport(Location)}, and only the neighbouring "a handler changed the
 * destination" branch calls {@code CraftPlayer.teleport(Location, PLUGIN)}.
 *
 * <p>Folia replaced the cancel branch with
 * {@code getBukkitEntity().teleportAsync(from, TeleportCause.PLUGIN)}. That is a real teleport, so
 * it fires {@code PlayerTeleportEvent} - and Lecithin's own teleport-event patch makes it visible
 * where on stock Folia it would have been silent. Two things follow, and both are observable:
 *
 * <ul>
 *   <li>Every cancelled move now reports a {@code PLUGIN} teleport that Paper never reported. A
 *       movement-watching plugin sees a teleport per rejected packet.</li>
 *   <li>Worse, that teleport is itself cancellable. A second plugin cancelling {@code PLUGIN}
 *       teleports - a common anti-abuse rule - defeats the rollback entirely, and the player keeps
 *       the movement the first plugin refused. On Paper there is no event to cancel.</li>
 * </ul>
 *
 * <h2>Why the platform's own primitive is usable here</h2>
 * {@code internalTeleport} ends in {@code Entity#teleportSetPosition}, which only writes position
 * and rotation and re-derives the entity's chunk section; it performs no region handover, so it is
 * correct exactly when the destination belongs to the region already ticking this player. A
 * rollback destination is the player's own previous position, so that is the normal case rather
 * than a lucky one - but it is not guaranteed, because a region may have split since. This asks
 * the platform instead of assuming: {@code TickThread.isTickThreadFor}, the same question every
 * other Lecithin compat asks, and on a no the stock {@code teleportAsync} path is used unchanged.
 *
 * <p>Nothing here is per-plugin, and nothing widens what a thread may touch: the check only ever
 * narrows the set of rollbacks that take the Paper path.
 *
 * <p>Kill switch: {@code event-config.move-rollback-without-teleport=false} restores the stock
 * rollback, which always fires a {@code PLUGIN} teleport.
 */
public final class LecithinMoveRollback {

    private LecithinMoveRollback() {
    }

    /**
     * Rolls the player back the way Paper does, if this thread may.
     *
     * @return {@code true} if the rollback was performed; {@code false} to use the stock path
     */
    public static boolean rollback(final ServerGamePacketListenerImpl connection,
                                   final ServerPlayer player, final Location from) {
        if (!EventConfig.moveRollbackWithoutTeleport) {
            return false;
        }
        final World world = from.getWorld();
        if (world == null || !(player.level() instanceof ServerLevel level) || ((CraftWorld) world).getHandle() != level) {
            return false; // a cross-world rollback is a real teleport; leave it to the stock path
        }
        if (!ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(level, BlockPos.containing(from.getX(), from.getY(), from.getZ()))) {
            return false;
        }
        connection.internalTeleport(from);
        return true;
    }
}
