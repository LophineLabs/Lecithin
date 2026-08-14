package fun.bm.lophinya.compat;

import fun.bm.lecithin.config.modules.CompatConfig;

/**
 * Lophinya: restores Paper's answer to "teleport something that is riding something else".
 *
 * <h2>The gap</h2>
 * {@code Entity#teleportAsync} refuses outright when the target is a passenger:
 *
 * <pre>
 *   if ((teleportFlags &amp; TELEPORT_FLAG_TELEPORT_PASSENGERS) != 0L) {
 *       if (this.isPassenger()) { return false; }
 *   }
 * </pre>
 * <p>
 * and {@code CraftEntity#teleportAsync} always passes that flag. So every Bukkit API teleport of a
 * riding entity returns {@code false} and does nothing - boats, minecarts, horses, GSit seats,
 * Citizens mounts. Measured exposure in the delivery set: 25 {@code teleport(...)Z} callsites across
 * 9 jars, <b>15 of which discard the return value</b>, so most callers cannot even notice.
 *
 * <h2>What Paper does, measured rather than assumed</h2>
 * Same probe, same jar, Paper 26.2 vs this fork:
 *
 * <table>
 *   <tr><th>case</th><th>Paper</th><th>stock Folia</th></tr>
 *   <tr><td>teleport the passenger, same region</td>
 *       <td>{@code true}, moved, <b>dismounted</b></td><td>{@code false}, unmoved, still riding</td></tr>
 *   <tr><td>teleport the passenger, cross region</td>
 *       <td>{@code true}, moved, dismounted</td><td>{@code false}, unmoved</td></tr>
 *   <tr><td>{@code teleportAsync} the passenger</td>
 *       <td>future {@code true}, dismounted</td><td>future {@code false}</td></tr>
 *   <tr><td>event cancelled</td>
 *       <td>{@code false}, unmoved, <b>still mounted</b></td><td>same - already matches</td></tr>
 *   <tr><td>teleport the <i>vehicle</i> that has a passenger</td>
 *       <td>{@code true}, both move, passenger <b>stays mounted</b></td>
 *       <td>{@code true}, both move, passenger <b>stays mounted</b> - see note</td></tr>
 * </table>
 *
 * <p><b>Note on that last row.</b> It was first recorded as "passenger dropped here". That reading
 * came from an inline, same-tick read on the calling thread, and it was wrong: the platform detaches
 * the tree, moves it, and re-attaches it, so a read taken immediately after {@code teleport()}
 * returns catches the gap. Reading the same case on the owning region two ticks later shows the
 * passenger mounted, at the destination, with the tree intact - identical to Paper, including for
 * nested passengers, players, cross region, cross world, {@code teleportAsync}, cancellation and a
 * platform refusal. What genuinely does differ is that the carried passengers get no teleport event;
 * that is {@link LophinyaPassengerTeleportEvents}.
 *
 * <p>So Paper's single-target semantic is exactly: <b>dismount that target, then teleport it</b>.
 * The vehicle is left alone; other passengers are left alone.
 *
 * <h2>Scope, deliberately narrow</h2>
 * This restores <b>only</b> that single-target semantic, at the Bukkit API boundary, and nothing
 * else:
 * <ul>
 *   <li>It does not change what {@code TELEPORT_FLAG_TELEPORT_PASSENGERS} means, and does not assume
 *       the platform's flag condition is inverted - that would be a guess about upstream intent.</li>
 *   <li>It does not move vehicle trees, and does not need to: the platform already moves them the
 *       way Paper does (see the note on the last row of the table).</li>
 *   <li>It does not touch cancellation - that row already matches Paper.</li>
 * </ul>
 *
 * <p>Grouped by API symbol, not by plugin: the gap is {@code Entity#teleport} /
 * {@code Entity#teleportAsync}, and every plugin that teleports a rider hits it identically.
 *
 * <h2>The cancellation case, and why {@link #restoreMount} exists</h2>
 * The dismount below happens before the platform decides whether the teleport goes ahead, and that
 * decision includes a handler cancelling the event. Without putting the mount back, a cancelled
 * teleport would leave the target unmounted <i>and</i> not teleported, where Paper leaves it mounted
 * - a half-broken passenger graph, which DEC-59's acceptance forbids. Measured after adding the
 * restore: all four single-target cases (same region, cross region, {@code teleportAsync},
 * cancelled) match Paper.
 *
 * <p>Kill switch: {@code -Dlophinya.compat.ridingTeleport=false} restores the stock refusal, and was
 * measured to roll all four back to their pre-patch answers exactly.
 */
public final class LophinyaRidingTeleport {

    /**
     * Dismounts {@code entity} from whatever it is riding, if anything.
     *
     * <p>Called from {@code CraftEntity#teleportAsync} on the thread that owns the entity, right
     * before the platform's own teleport - which is where Paper's dismount happens too, and the only
     * place where the vehicle relationship can be touched safely.
     *
     * @return the vehicle it was riding, so the caller can put it back if the teleport is refused,
     * or {@code null} if it was not riding anything
     */
    public static net.minecraft.world.entity.Entity dismountForTeleport(final net.minecraft.world.entity.Entity entity) {
        if (!CompatConfig.ridingTeleport || !entity.isPassenger()) {
            return null;
        }
        final net.minecraft.world.entity.Entity vehicle = entity.getVehicle();
        // stopRiding() is what the vanilla /tp command already does before teleporting, and what
        // Paper's Entity#teleport does internally. It detaches this entity from its vehicle and
        // leaves the vehicle and its other passengers untouched.
        entity.stopRiding();
        return vehicle;
    }

    /**
     * Puts the entity back on the vehicle after a teleport the platform refused.
     *
     * <p>Needed because the refusal can come from a handler cancelling the event, and that decision
     * is made inside the platform's {@code teleportAsync} - after the dismount above. Without this,
     * a cancelled teleport leaves the target unmounted with the teleport not performed, where Paper
     * leaves it mounted: a half-broken passenger graph, and measurably different from Paper (case M4
     * of the vehicle matrix).
     *
     * <p>Does nothing when there was no vehicle, or when the entity has since become a passenger of
     * something else - re-seating over a newer relationship would be worse than leaving it alone.
     */
    public static void restoreMount(final net.minecraft.world.entity.Entity entity,
                                    final net.minecraft.world.entity.Entity vehicle) {
        if (vehicle == null || entity.isPassenger() || entity.isRemoved() || vehicle.isRemoved()) {
            return;
        }
        // force=true because the original mount already passed whatever checks applied; the third
        // argument is false so putting the entity back does not fire a second mount event - nothing
        // observable actually changed from a plugin's point of view.
        entity.startRiding(vehicle, true, false);
    }
}
