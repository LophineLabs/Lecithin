package fun.bm.lophinya.compat;

import fun.bm.lecithin.config.modules.CompatConfig;
import org.bukkit.craftbukkit.entity.CraftEntity;

/**
 * Lophinya: makes a teleport wait for the entity's previous teleport to finish handing it over
 * (D-40), instead of being silently refused.
 *
 * <h2>The gap, as measured</h2>
 * A cross-region {@code teleport()} does not move the entity. {@code Entity#teleportAsync} calls
 * {@code transformForAsyncTeleport}, which builds a <b>copy</b> of the entity for the destination
 * world and hands it over; the destination region adds that copy to its own lookup on a later tick
 * ({@code placeInAsync}). Between those two points the copy is in no level's lookup at all.
 *
 * <p>{@code Entity#canTeleportAsync()} refuses in exactly that window, through
 * {@code hasNullCallback()}. So a plugin that teleports the same entity twice in one tick gets
 * {@code true} then {@code false}, and the second teleport silently does not happen. Paper has no
 * handover and performs both.
 *
 * <p><b>Nothing sets the callback to NULL.</b> The copy is <i>born</i> with it - it is the field
 * initialiser of {@code Entity#levelCallback} - and the first thing ever to set it is the
 * destination region's add. Four earlier explanations of D-40 looked for the code that nulls it;
 * there is none. This is not a lifecycle bug inside the platform, it is the caller-visible tail of
 * the same handover that patch 0028 already reports as "deferred", showing up one call later.
 *
 * <h2>What this does</h2>
 * {@code CraftEntity#teleportAsync} already has two paths: run inline when this thread owns the
 * entity, or schedule on the entity's own {@code EntityScheduler} when it does not. This widens the
 * second path to cover the handover window, which the first path currently claims because
 * {@code isOwnedByCurrentRegion} is still true - the copy has not moved yet, only changed hands.
 *
 * <p>{@code EntityScheduler}'s own javadoc describes it as the way to "run tasks only when an
 * entity is contained in a world, on the owning thread for the region", and it survives the entity
 * object being replaced by a handover. So: no owner is guessed, no ownership check is bypassed,
 * weakened or swallowed, no callback or validity is faked, nothing is dispatched to the global
 * region scheduler, and the calling thread does not wait. The teleport's returned future completes
 * with the platform's real answer once it runs.
 *
 * <h2>Why this only widens, never narrows</h2>
 * Inline, this window can only produce {@code false}: {@code canTeleportAsync()} is checked first
 * and {@code hasNullCallback()} is already true. Every other refusal it makes - removed, dead,
 * sleeping - leaves the callback set, so those still take the inline path and still return
 * {@code false}. A properly removed entity does not qualify either: {@code EntityLookup} sets
 * {@code NoOpCallback}, not NULL, on removal.
 *
 * <p>Residual risk, stated rather than engineered around: an entity that is never added to any
 * world would keep the scheduled teleport forever, since nothing would ever tick or retire it. No
 * Bukkit API hands out such an entity - {@code World#spawnEntity} adds before it returns - so this
 * is a note, not a case that is defended against.
 *
 * <p>Kill switch: {@code -Dlophinya.compat.teleportHandover=false} restores the plain refusal.
 */
public final class LophinyaTeleportHandover {

    /**
     * Whether a previous teleport is still handing this entity over to another region.
     *
     * <p>Reading the callback here is a plain field read, and in this window it can only go
     * NULL -&gt; set, so losing the race costs a refusal the caller would have got anyway.
     */
    public static boolean isMidHandover(final CraftEntity entity) {
        if (!CompatConfig.teleportHandover) {
            return false;
        }
        final net.minecraft.world.entity.Entity handle = entity.getHandleRaw();
        return handle != null && !handle.isRemoved() && handle.hasNullCallback();
    }
}
