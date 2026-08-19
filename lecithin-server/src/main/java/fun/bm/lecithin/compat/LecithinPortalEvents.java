package fun.bm.lecithin.compat;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Location;
import org.bukkit.PortalType;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.craftbukkit.event.PortalEventResult;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

/**
 * Lecithin: fires the Bukkit portal and teleport events for a vanilla portal, which this platform
 * routes around entirely.
 *
 * <h2>The gap</h2>
 * On Paper a portal is a teleport like any other: {@code Entity#handlePortal} asks the portal block
 * for a {@code TeleportTransition} - and {@code NetherPortalBlock#getPortalDestination} /
 * {@code EndPortalBlock#getPortalDestination} fire {@code PlayerPortalEvent} or
 * {@code EntityPortalEvent} while computing it - and then calls {@code Entity#teleport(transition)},
 * which for a player is {@code ServerPlayer#teleport} and fires {@code PlayerTeleportEvent}.
 *
 * <p>Folia rewrote {@code handlePortal} to call {@code portalProcess.portalAsync(...)} instead, which
 * reaches {@code Entity#portalToAsync} and from there {@code Entity#placeInAsync} directly. It never
 * calls {@code Entity#teleportAsync}, so it never reaches the hook
 * {@link LecithinTeleportEvents} installed there, and it never calls {@code getPortalDestination},
 * so it never reaches {@code CraftEventFactory.handlePortalEvents} either. Both
 * {@code getPortalDestination} bodies survive in the source with no live caller.
 *
 * <p>The measurable consequence is that on this platform a nether or end portal fires <b>no</b>
 * {@code PlayerPortalEvent}, <b>no</b> {@code EntityPortalEvent} and <b>no</b>
 * {@code PlayerTeleportEvent} - {@code PlayerPortalEvent} is dead API here - while the player still
 * crosses into the other world. Every Paper plugin that gates or observes world entry through those
 * events is silently bypassed on the single most common way a player changes world. A plugin that
 * enforces per-world access permissions believes it is enforcing them and is not.
 *
 * <h2>Why this is grouped by API symbol, not by plugin</h2>
 * The gap is three Bukkit event symbols. Nothing here knows or can know which plugins listen for
 * them, and the fix is identical for all of them.
 *
 * <h2>Where the events are fired</h2>
 * {@code Entity#portalToAsync} calls this after its own validity checks
 * ({@code canPortalAsync}, Luminol's {@code PreEntityPortalEvent}) and before it acquires the
 * destination's unload lock, detaches passengers or transforms anything. That point has the same
 * four properties the {@code teleportAsync} hook relies on: {@code portalToAsync} opens with
 * {@code TickThread.ensureTickThread(this, ...)} so the handler runs on the region that owns the
 * entity; nothing has been mutated so a cancel is a plain {@code return false}, the same answer
 * {@code portalToAsync} already gives for its own refusals; it is above the whole portal machinery
 * so one hook covers nether and end; and no lock is held, so a cancel cannot leak one.
 *
 * <p>The portal event is fired first and the teleport event second, which is Paper's order.
 *
 * <h2>What is honoured</h2>
 * <ul>
 *   <li><b>Cancellation</b> of either event aborts the portal. The entity stays where it is, with
 *       its portal cooldown set - which is what Paper does when {@code getPortalDestination}
 *       returns {@code null}.</li>
 *   <li><b>{@code setTo}</b> on either event: the destination world, and the position the portal
 *       search centres on, are both taken from the redirected location.</li>
 *   <li><b>{@code setSearchRadius}</b>, <b>{@code setCreationRadius}</b> and
 *       <b>{@code setCanCreatePortal}</b> from {@code PlayerPortalEvent} are passed to the
 *       platform's own asynchronous portal search.</li>
 * </ul>
 *
 * <h2>The one bounded divergence</h2>
 * Paper fires {@code PlayerPortalEvent} with the <i>approximate</i> exit - the entity's position
 * scaled by the dimension ratio and clamped to the world border - because the portal has not been
 * searched for yet. This fires it with exactly the same value, so that event is at parity.
 *
 * <p>{@code PlayerTeleportEvent} is different: Paper fires it <i>after</i> the search, from
 * {@code ServerPlayer#teleport}, so its {@code to} is the resolved portal exit. Here it is fired
 * before the search, with the approximate exit, because the search is asynchronous and completes on
 * another region after the entity has already been detached and transformed - at which point a
 * cancel could no longer be honoured without unwinding a half-finished handover. The world, the
 * cause and the cancellation are exact; the position can be off by up to the portal search radius.
 * That is a strictly better answer than the alternative it replaces, which is no event at all.
 *
 * <p>Kill switch: {@code event-config.portal-events=false} restores stock behaviour, which is
 * that none of these events is ever fired for a portal.
 */
public final class LecithinPortalEvents {

    /**
     * What the handlers left behind. The platform's portal search reads all five.
     *
     * @param destination     world to portal into, after any {@code setTo}
     * @param position        position the portal search centres on, after any {@code setTo}
     * @param searchRadius    blocks to search for an existing portal
     * @param createRadius    blocks to search for somewhere to build one
     * @param canCreatePortal whether building one is allowed at all
     */
    public record Outcome(ServerLevel destination, Vec3 position, int searchRadius, int createRadius,
                          boolean canCreatePortal) {
    }

    /**
     * Fires {@code PlayerPortalEvent}/{@code EntityPortalEvent} and then
     * {@code PlayerTeleportEvent}/{@code EntityTeleportEvent} for a portal.
     *
     * @param entity          the entity entering the portal, already validated by the caller
     * @param destination     the world the platform intends to portal into
     * @param approximateExit the exit the platform would use before searching for a portal - the
     *                        same value Paper passes to {@code handlePortalEvents}
     * @param portalType      Bukkit's portal type, mapped by the caller from the platform's own
     * @param cause           teleport cause to report, mapped by the caller from the same
     * @param searchRadius    the platform's own search radius, offered to the handlers
     * @param createRadius    the platform's own creation radius, offered to the handlers
     * @return what to portal into, or {@code null} if a handler cancelled
     */
    public static Outcome callPortalEvents(final Entity entity, final ServerLevel destination,
                                           final Vec3 approximateExit, final PortalType portalType,
                                           final TeleportCause cause,
                                           final int searchRadius, final int createRadius) {
        final Location to = new Location(destination.getWorld(), approximateExit.x, approximateExit.y,
                approximateExit.z, entity.getBukkitYaw(), entity.getXRot());

        // Upstream's own event construction, reached through the same factory method Paper uses.
        // It returns null for a cancelled event, a null destination, or a dead entity - the three
        // things Paper also treats as "do not portal".
        final PortalEventResult portalResult =
                CraftEventFactory.handlePortalEvents(entity, to, portalType, searchRadius, createRadius);
        if (portalResult == null) {
            return null;
        }
        final Location portalTo = portalResult.to();
        if (portalTo == null || portalTo.getWorld() == null) {
            return null;
        }

        // Then the teleport event, on the destination the portal event settled on. Paper fires
        // these in this order too.
        ServerLevel level = ((CraftWorld) portalTo.getWorld()).getHandle();
        Vec3 position = new Vec3(portalTo.getX(), portalTo.getY(), portalTo.getZ());
        final LecithinTeleportEvents.Destination teleportResult = LecithinTeleportEvents.callTeleportEvent(
                entity, level, position, Float.valueOf(portalTo.getYaw()), Float.valueOf(portalTo.getPitch()), cause);
        if (teleportResult == null) {
            return null;
        }
        level = teleportResult.level();
        position = teleportResult.pos();

        final World redirected = level.getWorld();
        if (redirected == null) {
            return null;
        }
        return new Outcome(level, position, portalResult.searchRadius(), portalResult.createRadius(),
                portalResult.canCreatePortal());
    }

    private LecithinPortalEvents() {
    }
}
