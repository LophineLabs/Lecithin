package fun.bm.lecithin.command;

import com.mojang.brigadier.StringReader;
import io.papermc.paper.threadedregions.RegionizedWorldData;
import io.papermc.paper.threadedregions.ThreadedRegionizer;
import io.papermc.paper.threadedregions.TickRegionScheduler;
import io.papermc.paper.threadedregions.TickRegions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;

import java.util.ArrayList;
import java.util.List;

/**
 * Lecithin: builds the text of the {@code /lecithin} diagnostic reports.
 *
 * <h2>What this exists to settle</h2>
 * Two separate claims are regularly made about extra worlds created or imported at runtime: that
 * their region work is somehow charged to the main world, and that vanilla selectors resolve
 * against the wrong world. Neither can be argued from outside, because the identities involved -
 * a {@code ServerLevel}, its {@link ThreadedRegionizer}, the region a thread is currently ticking,
 * and the world a {@code CommandSourceStack} carries - have no Bukkit API at all.
 *
 * <p>This prints them. Every line is a read of a field the platform already maintains; nothing is
 * derived, inferred or cached, and no state is written. Run the same command from a player, from a
 * command block and from the console and the three answers can be compared directly.
 *
 * <h2>Reading the output</h2>
 * <ul>
 *   <li>Every world's {@code level} and {@code regioniser} identity hash differ from every other
 *       world's, and each regioniser's {@code world} back-reference points at its own level. That
 *       is what "each world has its own regioniser" means, and it is checked on every
 *       {@code Level#getCurrentWorldData()} call at runtime anyway - a shared regioniser would
 *       make the server throw {@code World mismatch} constantly rather than mis-attribute
 *       anything.</li>
 *   <li>A command source's {@code level} and {@code position} are what a selector measures from.
 *       For a player they are that player's; for the console they are the primary world's respawn
 *       dimension and spawn point, which is vanilla behaviour and not a property of this fork.</li>
 *   <li>{@code @p} is not world-limited unless the selector says so, so it searches every world by
 *       raw coordinate distance from that position. The report shows both the source position and
 *       the world of the player it picked, which is what makes the two facts add up.</li>
 * </ul>
 */
public final class LecithinContextReport {

    private LecithinContextReport() {
    }

    private static String id(final Object o) {
        return o == null ? "null" : "0x" + Integer.toHexString(System.identityHashCode(o));
    }

    private static String pos(final Vec3 v) {
        return v == null ? "null" : String.format("%.2f, %.2f, %.2f", v.x, v.y, v.z);
    }

    private static String worldOf(final ServerLevel level) {
        return level == null ? "null" : level.getWorld().getName();
    }

    /**
     * The execution context of the calling thread and of the command source it was given.
     */
    public static List<String> context(final CommandSourceStack source) {
        final List<String> out = new ArrayList<>();
        final Thread thread = Thread.currentThread();

        out.add("== thread ==");
        out.add("  name          : " + thread.getName());
        out.add("  tick thread   : " + ca.spottedleaf.moonrise.common.util.TickThread.isTickThread());
        out.add("  global region : " + io.papermc.paper.threadedregions.RegionizedServer.isGlobalTickThread());

        out.add("== current tick region ==");
        final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region =
                TickRegionScheduler.getCurrentRegion();
        if (region == null) {
            out.add("  region        : none (this thread is not ticking a region)");
        } else {
            final ThreadedRegionizer<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> regioniser = region.regioniser;
            out.add("  region        : " + id(region));
            out.add("  regioniser    : " + id(regioniser));
            out.add("  regioniser.world : " + worldOf(regioniser.world) + " " + id(regioniser.world));
        }
        final RegionizedWorldData worldData = TickRegionScheduler.getCurrentRegionizedWorldData();
        out.add("  worldData     : " + id(worldData)
                + (worldData == null ? "" : " world=" + worldOf(worldData.world) + " " + id(worldData.world)));

        out.add("== command source ==");
        out.add("  sender        : " + source.getBukkitSender().getName()
                + " [" + source.getBukkitSender().getClass().getName() + "]");
        out.add("  displayName   : " + source.getTextName());
        out.add("  level         : " + worldOf(source.getLevel()) + " " + id(source.getLevel()));
        out.add("  position      : " + pos(source.getPosition()));
        out.add("  rotation      : " + source.getRotation());

        final Entity entity = source.getEntity();
        out.add("== source entity ==");
        if (entity == null) {
            out.add("  entity        : none (this source is not an entity)");
        } else {
            final ServerLevel entityLevel = (ServerLevel) entity.level();
            out.add("  entity        : " + entity.getScoreboardName() + " [" + entity.getType().toShortString() + "] " + id(entity));
            out.add("  level         : " + worldOf(entityLevel) + " " + id(entityLevel));
            out.add("  position      : " + pos(entity.position()));
            out.add("  owned by this thread : "
                    + ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(entity));
            out.add("  level == source level : " + (entityLevel == source.getLevel()));
        }

        out.add("== selectors, resolved from this source ==");
        out.add("  @p            : " + resolveSingle(source, "@p"));
        out.add("  @p[distance=..] : " + resolveSingle(source, "@p[distance=..1000000]"));
        out.add("  @a            : " + resolveAll(source, "@a"));
        return out;
    }

    /**
     * Level and regioniser identity for every loaded world, so a world created or imported at
     * runtime can be compared directly against one loaded at startup.
     */
    public static List<String> worlds() {
        final List<String> out = new ArrayList<>();
        out.add("== worlds ==");
        for (final World bukkitWorld : Bukkit.getWorlds()) {
            final ServerLevel level = ((CraftWorld) bukkitWorld).getHandle();
            final ThreadedRegionizer<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> regioniser = level.regioniser;
            final List<ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData>> regions = new ArrayList<>();
            regioniser.computeForAllRegions(regions::add);
            out.add("  " + bukkitWorld.getName());
            out.add("    level        : " + id(level) + " dimension=" + level.dimension().identifier());
            out.add("    regioniser   : " + id(regioniser));
            out.add("    regioniser.world : " + worldOf(regioniser.world) + " " + id(regioniser.world)
                    + (regioniser.world == level ? " (own)" : " *** NOT ITS OWN LEVEL ***"));
            out.add("    tickRegions  : " + id(level.tickRegions) + ", live regions=" + regions.size());
            out.add("    players      : " + bukkitWorld.getPlayers().size()
                    + ", loaded chunks=" + bukkitWorld.getLoadedChunks().length);
        }
        return out;
    }

    private static String resolveSingle(final CommandSourceStack source, final String selector) {
        try {
            final EntitySelector parsed = new EntitySelectorParser(new StringReader(selector), true).parse();
            final ServerPlayer player = parsed.findSinglePlayer(source);
            return describe(player, source);
        } catch (final Exception e) {
            return "<" + e.getClass().getSimpleName() + ": " + e.getMessage() + ">";
        }
    }

    private static String resolveAll(final CommandSourceStack source, final String selector) {
        try {
            final EntitySelector parsed = new EntitySelectorParser(new StringReader(selector), true).parse();
            final List<ServerPlayer> players = parsed.findPlayers(source);
            if (players.isEmpty()) {
                return "<none>";
            }
            final StringBuilder sb = new StringBuilder();
            for (final ServerPlayer player : players) {
                if (!sb.isEmpty()) {
                    sb.append("; ");
                }
                sb.append(describe(player, source));
            }
            return sb.toString();
        } catch (final Exception e) {
            return "<" + e.getClass().getSimpleName() + ": " + e.getMessage() + ">";
        }
    }

    private static String describe(final ServerPlayer player, final CommandSourceStack source) {
        if (player == null) {
            return "<none>";
        }
        final ServerLevel level = player.level();
        final double distance = Math.sqrt(player.position().distanceToSqr(source.getPosition()));
        return player.getScoreboardName()
                + " in " + worldOf(level) + " " + id(level)
                + " at " + pos(player.position())
                + String.format(", raw distance from source position=%.1f", distance)
                + ", same world as source=" + (level == source.getLevel());
    }
}
