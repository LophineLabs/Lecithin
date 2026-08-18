package fun.bm.lecithin.compat;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

/**
 * Lecithin: fires the Bukkit teleport events that Folia left as a TODO.
 *
 * <h2>The gap</h2>
 * On Paper every semantic teleport goes through {@code Entity#teleport(TeleportTransition)} or
 * {@code ServerPlayer#teleport(TeleportTransition)}, and those two methods are where
 * {@code EntityTeleportEvent}, {@code PlayerTeleportEvent} and {@code PlayerChangedWorldEvent} are
 * fired. Folia replaces both method bodies with an unconditional
 * {@code if (true) throw new UnsupportedOperationException("Must use teleportAsync while in region
 * threading")} and routes everything through {@code Entity#teleportAsync} instead - where, at the
 * point the events used to be fired, it leaves the literal comment
 * {@code // TODO any events that can modify go HERE}. Luminol later added its own
 * {@code EntityTeleportAsyncEvent} at exactly that comment, but did not restore the Bukkit ones.
 *
 * <p>Measured consequence (Paper 26.2 vs this fork, same script, same counting plugin, same vanilla
 * commands): Paper fires {@code PlayerTeleportEvent} 3x and {@code PlayerChangedWorldEvent} 2x; the
 * fork fires 0 and 0, while the teleports themselves happen. Plugins that veto teleports
 * (GriefPrevention) or observe them (anti-cheat) are simply never told.
 *
 * <h2>Why this is grouped by API symbol, not by plugin</h2>
 * The gap is three Bukkit event symbols, not a plugin. Every Paper plugin that listens for a
 * teleport is affected identically, and nothing here knows or can know which plugins those are.
 *
 * <h2>Where the events are fired</h2>
 * {@code Entity#teleportAsync} calls this <b>after</b> its validity checks (spawnable bounds,
 * {@code canTeleportAsync}, passenger constraints) and <b>before</b> it acquires the destination's
 * unload lock or mutates any state. That single point has all four properties the event needs:
 *
 * <ul>
 *   <li><b>Correct thread.</b> {@code teleportAsync} opens with
 *       {@code TickThread.ensureTickThread(this, ...)}, so the caller already owns this entity's
 *       region. Reading {@code from} and firing the handler is done on the thread Bukkit's
 *       synchronous event contract expects. Nothing blocks and nothing waits on another region.</li>
 *   <li><b>Cancellation is free.</b> Nothing has been mutated yet, so a cancelled event is a plain
 *       {@code return false} - the same value {@code teleportAsync} already returns for its own
 *       refusals. Upstream does the same thing a few methods away with Luminol's
 *       {@code PreEntityPortalEvent}.</li>
 *   <li><b>Before the branch.</b> It sits above the same-region/cross-region split, so one hook
 *       covers same region, cross region and cross world.</li>
 *   <li><b>No lock to unwind.</b> Firing before {@code levelUnloadStateLock.acquireRead()} means a
 *       cancel cannot leak that read lock.</li>
 * </ul>
 *
 * <h2>Which teleports fire an event</h2>
 * Every {@code teleportAsync} call whose {@link TeleportCause} is not {@code UNKNOWN}.
 * {@code UNKNOWN} is what Folia and Luminol pass for their own internal repositioning, which has no
 * Paper counterpart and must not look like a teleport to plugins:
 *
 * <ul>
 *   <li>{@code Entity#fixPassengerDesync} - a Folia-only correction of a desynced passenger.</li>
 *   <li>{@code Level#guardEntityTick} - Luminol's recovery when an entity moved itself out of its
 *       region mid-tick.</li>
 *   <li>{@code TamableAnimal#maybeTeleportTo} - fires {@code EntityTeleportEvent} itself and
 *       <i>then</i> schedules the {@code teleportAsync}; firing again here would double it.</li>
 * </ul>
 * <p>
 * Everything with a real cause is a teleport Paper would also have reported: {@code COMMAND}
 * (vanilla {@code /tp}), {@code ENDER_PEARL}, {@code END_GATEWAY}, {@code NETHER_PORTAL},
 * {@code END_PORTAL}, and {@code PLUGIN} (the Bukkit API, whose {@code teleport}/{@code teleportAsync}
 * overloads all default to {@code PLUGIN}).
 *
 * <p><b>The one divergence this rule creates:</b> a plugin that explicitly passes
 * {@code TeleportCause.UNKNOWN} to {@code teleportAsync} gets no event, where Paper would fire one.
 * No API overload produces {@code UNKNOWN} by default, so this requires the caller to ask for it.
 *
 * <h2>What it does not do</h2>
 * No ownership check is disabled, bypassed or swallowed; nothing is dispatched to the global region
 * scheduler; no owner is guessed. The event handler runs on the region that owns the entity, exactly
 * like every other event Folia fires. A handler that reaches into the <i>destination</i> region from
 * here will fail its own ownership check - that is the platform's existing rule, not a new one, and
 * it is the same failure Paper handlers never see because Paper has one thread.
 *
 * <p>Kill switch: {@code -Dlecithin.compat.teleportEvents=false} restores stock behaviour, which is
 * that none of these three events is ever fired.
 */
public final class LecithinTeleportEvents {

    /**
     * Destination after the event handlers have had their say. The nullable rotation fields carry
     * {@code teleportAsync}'s own "keep the entity's current rotation" convention, so an untouched
     * event hands the platform back exactly what it was given.
     */
    public record Destination(ServerLevel level, Vec3 pos, Float yaw, Float pitch) {
    }

    /**
     * Fires {@code PlayerTeleportEvent} (players) or {@code EntityTeleportEvent} (everything else).
     *
     * @return the destination to use, or {@code null} if a handler cancelled the teleport
     */
    public static Destination callTeleportEvent(final Entity entity, final ServerLevel destination,
                                                final Vec3 pos, final Float yaw, final Float pitch,
                                                final TeleportCause cause) {
        final Destination unchanged = new Destination(destination, pos, yaw, pitch);
        if (cause == null || cause == TeleportCause.UNKNOWN) {
            return unchanged; // internal reposition, see class doc
        }

        final org.bukkit.entity.Entity bukkitEntity = entity.getBukkitEntity();
        final Location from = bukkitEntity.getLocation();
        final Location to = new Location(
                destination.getWorld(), pos.x, pos.y, pos.z,
                yaw == null ? from.getYaw() : yaw.floatValue(),
                pitch == null ? from.getPitch() : pitch.floatValue());

        final Location redirected;
        if (bukkitEntity instanceof org.bukkit.entity.Player player) {
            final PlayerTeleportEvent event = new PlayerTeleportEvent(player, from, to.clone(), cause);
            if (!event.callEvent()) {
                return null;
            }
            redirected = event.getTo();
        } else {
            final EntityTeleportEvent event = new EntityTeleportEvent(bukkitEntity, from, to.clone());
            if (!event.callEvent()) {
                return null;
            }
            redirected = event.getTo();
        }

        // Paper treats a null destination from an uncancelled event as a refusal.
        if (redirected == null) {
            return null;
        }
        final World world = redirected.getWorld();
        if (world == null) {
            return null;
        }
        if (redirected.equals(to)) {
            return unchanged; // untouched - keep the platform's own null rotations
        }
        return new Destination(
                ((CraftWorld) world).getHandle(),
                new Vec3(redirected.getX(), redirected.getY(), redirected.getZ()),
                Float.valueOf(redirected.getYaw()), Float.valueOf(redirected.getPitch()));
    }

    /**
     * Fires {@code PlayerChangedWorldEvent} once a cross-world teleport has actually completed.
     *
     * <p>Called from {@code Entity#placeInAsync}'s completion, which runs on the destination
     * region's thread with the placed entity - the same ordering Paper has, where the event is the
     * last thing {@code ServerPlayer#teleport} does. {@code entity} is the post-teleport instance:
     * a cross-world teleport replaces the {@code ServerPlayer} object.
     *
     * @param originWorld the world the player came from; the caller only calls this when it differs
     *                    from the destination, since {@code placeInAsync} also handles same-world
     *                    cross-region moves
     */
    public static void callChangedWorld(final Entity entity, final ServerLevel originWorld) {
        if (!(entity instanceof ServerPlayer player)) {
            return;
        }
        new PlayerChangedWorldEvent(player.getBukkitEntity(), originWorld.getWorld()).callEvent();
    }
}
