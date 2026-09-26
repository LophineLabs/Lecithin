package fun.bm.lecithin.compat.legacy;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;

/**
 * Lecithin: where a legacy plugin's work is physically anchored, independent of when it runs and of
 * which plugin's domain it runs in.
 *
 * <p>Three answers, all facts established by the platform at the moment work is created:
 * <ul>
 *   <li>{@link Kind#ENTITY} - the work was created while the platform was running the plugin on
 *       behalf of one entity (a player's event, a task already bound to that entity). Folia's
 *       {@code EntityScheduler} follows that entity across regions and worlds.</li>
 *   <li>{@link Kind#REGION} - the work was created on a region thread, or for a block, with no single
 *       entity to name. The anchor is a chunk; whichever region owns it when the work runs is the
 *       owner.</li>
 *   <li>{@link Kind#NONE} - no world anchor at all: startup, the global tick, a plugin's own timer or
 *       async thread. This is <b>not</b> "global": such work runs in the plugin's own domain on the
 *       legacy lane, which owns no world state, and any world access inside it fails at the access
 *       exactly as it would on any other thread that does not own the target.</li>
 * </ul>
 */
public record LegacyAffinity(Kind kind, Entity entity, World world, int chunkX, int chunkZ) {

    public enum Kind {
        ENTITY,
        REGION,
        NONE
    }

    public static final LegacyAffinity NONE = new LegacyAffinity(Kind.NONE, null, null, 0, 0);

    public static LegacyAffinity entity(final Entity entity) {
        return entity == null ? NONE : new LegacyAffinity(Kind.ENTITY, entity, null, 0, 0);
    }

    public static LegacyAffinity region(final World world, final int chunkX, final int chunkZ) {
        return world == null ? NONE : new LegacyAffinity(Kind.REGION, null, world, chunkX, chunkZ);
    }

    public static LegacyAffinity at(final Location location) {
        if (location == null || location.getWorld() == null) {
            return NONE;
        }
        return region(location.getWorld(), location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    public String describe() {
        return switch (this.kind) {
            case ENTITY -> "entity " + this.entity.getType() + ' ' + this.entity.getUniqueId();
            case REGION -> "region owning " + this.world.getName() + " chunk [" + this.chunkX + ", " + this.chunkZ + ']';
            case NONE -> "no world anchor (legacy lane)";
        };
    }
}
