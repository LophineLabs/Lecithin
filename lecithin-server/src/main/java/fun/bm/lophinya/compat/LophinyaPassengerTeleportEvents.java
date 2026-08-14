package fun.bm.lophinya.compat;

import fun.bm.lecithin.config.modules.EventConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Location;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

/**
 * Lophinya: fires the Bukkit teleport events for passengers carried along by a teleported vehicle.
 *
 * <h2>The gap</h2>
 * Teleporting a vehicle moves its whole passenger tree - here and on Paper alike. But the events
 * only follow on Paper. {@link LophinyaTeleportEvents} restores the event for the entity actually
 * passed to {@code teleportAsync}; the passengers it drags along get nothing, because the platform
 * moves them through {@code teleportSyncSameRegion} / {@code placeInAsync} rather than through
 * another {@code teleportAsync} call.
 *
 * <p>Measured, same probe on both platforms, teleporting a pig that is carrying an armour stand:
 *
 * <table>
 *   <tr><th>case</th><th>Paper 26.2</th><th>this fork, before</th></tr>
 *   <tr><td>vehicle + 1 passenger</td><td>2 events (PIG, ARMOR_STAND)</td><td>1 (PIG)</td></tr>
 *   <tr><td>vehicle + nested passengers</td><td>3</td><td>1</td></tr>
 *   <tr><td>cross region / cross world</td><td>2</td><td>1</td></tr>
 *   <tr><td>vehicle carrying a <b>player</b></td><td>1 {@code PlayerTeleportEvent}, cause PLUGIN</td><td><b>0</b></td></tr>
 *   <tr><td>event cancelled at the vehicle</td><td>1, nothing moves</td><td>1, nothing moves - already matches</td></tr>
 * </table>
 *
 * <p>The player row is the one that matters. A player sitting in a boat, a minecart, a horse or a
 * seat that any plugin teleports crosses the world with <b>no teleport event at all</b>: nothing
 * that vetoes teleports is consulted, and anything watching them sees an unexplained jump instead of
 * a teleport.
 *
 * <h2>🔑 The events are notifications, not veto points - this was measured, not assumed</h2>
 * A handler that cancels a carried passenger's event on Paper is <b>ignored</b>: the passenger still
 * moves and stays mounted. Measured for both event classes separately, because they are different
 * classes and the armour-stand answer does not transfer to the player one:
 *
 * <ul>
 *   <li>armour stand, {@code EntityTeleportEvent} cancelled while the pig's is allowed
 *       ⇒ rider still arrives, still mounted;</li>
 *   <li>player, {@code PlayerTeleportEvent} cancelled ⇒ player still arrives, still mounted.</li>
 * </ul>
 *
 * So this ignores cancellation and {@code setTo}, exactly like Paper. That is not a shortcut: the
 * tree moves as one unit, so honouring a single passenger's veto could only produce a half-moved
 * tree - a passenger left behind while its vehicle leaves - which is both a divergence from Paper
 * and the one outcome DEC-59 forbids outright.
 *
 * <h2>What the events carry</h2>
 * The <b>vehicle's</b> {@code from} and {@code to}, for every entity in the tree. That is what the
 * platform actually does to them: every node is moved to the same destination and
 * {@code adjustRiders} then puts the mount offsets back. It also matches Paper exactly on same-world
 * teleports, same region and cross region.
 *
 * <p>Paper's <i>cross-world</i> path reports each passenger's own offset position instead
 * ({@code from} y −57.1 rather than the pig's −58.0, and the same offset on {@code to}). Patch 0035
 * shipped with that as a registered divergence; {@link #CROSS_WORLD_OFFSET} now matches it.
 *
 * <h2>Where it is fired</h2>
 * In {@code Entity#teleportAsync}, immediately after the root entity's own event hook and
 * <b>before</b> the destination's unload read lock is acquired. At that point the tree is still
 * attached, the vehicle has not moved yet, and the root's event has already had its chance to
 * cancel. Firing before the lock means a handler that throws cannot leak it. The only way the
 * teleport can still be refused afterwards is a concurrent level hot-unload - the same exposure the
 * root's event already has.
 *
 * <p>The thread is right by construction: {@code teleportAsync} opens with
 * {@code TickThread.ensureTickThread} on the vehicle, and a mounted passenger is co-located with its
 * vehicle, so it belongs to the same region. Nothing blocks, nothing waits on another region, no
 * ownership check is touched.
 *
 * <p>One hook covers same region, cross region and cross world, because it sits above that branch.
 * Grouped by API symbol, not by plugin: the gap is {@code Entity#teleport} /
 * {@code Entity#teleportAsync} on a vehicle, and nothing here knows what plugins exist.
 *
 * <p>Kill switch: {@code -Dlophinya.compat.passengerTeleportEvents=false} restores stock behaviour,
 * which is that carried passengers produce no event. Independent of
 * {@code lophinya.compat.teleportEvents}, which governs the root entity's event.
 */
public final class LophinyaPassengerTeleportEvents {

    /**
     * Fires one teleport event per entity carried by {@code vehicle}, in tree order.
     *
     * <p>Return value deliberately absent: there is nothing for the caller to decide. See the class
     * doc for why cancellation is ignored.
     *
     * @param vehicle     the entity being teleported, which may or may not have passengers
     * @param destination the level the whole tree is moving to
     * @param pos         the destination position, before mount offsets are reapplied
     */
    public static void callForPassengers(final Entity vehicle, final ServerLevel destination,
                                         final Vec3 pos, final Float yaw, final Float pitch,
                                         final TeleportCause cause) {
        // UNKNOWN is the platform's own internal repositioning and must not look like a teleport to
        // plugins - same rule, and the same reasons, as LophinyaTeleportEvents.
        if (!EventConfig.passengerTeleportEvents || cause == null || cause == TeleportCause.UNKNOWN) {
            return;
        }
        final java.util.Iterator<Entity> passengers = vehicle.getIndirectPassengers().iterator();
        if (!passengers.hasNext()) {
            return;
        }

        final Location from = vehicle.getBukkitEntity().getLocation();
        final Location to = new Location(
            destination.getWorld(), pos.x, pos.y, pos.z,
            yaw == null ? from.getYaw() : yaw.floatValue(),
            pitch == null ? from.getPitch() : pitch.floatValue());
        // Paper reports the vehicle's from/to on same-world teleports and each passenger's own
        // offset position on cross-world ones. See CROSS_WORLD_OFFSET.
        final boolean perPassenger = EventConfig.passengerTeleportCrossWorldOffset && destination != vehicle.level();
        final Vec3 vehiclePos = vehicle.position();

        while (passengers.hasNext()) {
            final Entity handle = passengers.next();
            final org.bukkit.entity.Entity passenger = handle.getBukkitEntity();
            // Fresh clones per event: an event object hands its Locations to plugins, and a handler
            // that mutates one must not be able to change what the next passenger is told.
            final Location eventFrom;
            final Location eventTo;
            if (perPassenger) {
                // The passenger's current position is not the offset to use, and measuring said so:
                // on this platform a mounted passenger stays at exactly its vehicle's coordinates
                // (offset 0.0 even after two ticks), while Paper's event reports vehicle + 0.9 for a
                // stand on a pig - which is the pig's passenger attachment, not where the stand
                // happens to be. Asking the vehicle where it seats this passenger gives that number
                // on both platforms and does not depend on either one repositioning riders.
                final Vec3 offset = ridingOffset(vehicle, handle);
                eventFrom = from.clone().add(offset.x, offset.y, offset.z);
                eventTo = new Location(
                    destination.getWorld(), pos.x + offset.x, pos.y + offset.y, pos.z + offset.z,
                    yaw == null ? from.getYaw() : yaw.floatValue(),
                    pitch == null ? from.getPitch() : pitch.floatValue());
            } else {
                eventFrom = from.clone();
                eventTo = to.clone();
            }
            if (passenger instanceof org.bukkit.entity.Player player) {
                new PlayerTeleportEvent(player, eventFrom, eventTo, cause).callEvent();
            } else {
                new EntityTeleportEvent(passenger, eventFrom, eventTo).callEvent();
            }
        }
    }

    /**
     * Where {@code passenger} sits relative to the root {@code vehicle}, as a vector.
     *
     * <p>Walks the mount chain and adds one attachment point per level, so a stand riding a stand
     * riding a pig gets both offsets rather than the pig's twice. Each term is asked of the entity
     * that owns the seat, which is the only thing that knows where its seats are.
     */
    private static Vec3 ridingOffset(final Entity vehicle, final Entity passenger) {
        Vec3 offset = Vec3.ZERO;
        for (Entity node = passenger; node != null && node != vehicle; node = node.getVehicle()) {
            final Entity mount = node.getVehicle();
            if (mount == null) {
                break;
            }
            offset = offset.add(mount.getPassengerRidingPosition(node).subtract(mount.position()));
        }
        return offset;
    }
}
