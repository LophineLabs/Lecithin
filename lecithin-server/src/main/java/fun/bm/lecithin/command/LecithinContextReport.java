package fun.bm.lecithin.command;

import com.mojang.brigadier.StringReader;
import io.papermc.paper.threadedregions.RegionizedServer;
import io.papermc.paper.threadedregions.RegionizedWorldData;
import io.papermc.paper.threadedregions.ThreadedRegionizer;
import io.papermc.paper.threadedregions.TickRegionScheduler;
import io.papermc.paper.threadedregions.TickRegions;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;

import java.util.ArrayList;
import java.util.List;

/**
 * Lecithin: builds rich Adventure Component diagnostic reports for {@code /lecithin context} and {@code /lecithin worlds}.
 *
 * <p>Uses the standard Folia/Lecithin hex color palette with structured bullets, status badges,
 * and explanatory hover tooltips so administrators and developers can clearly understand the multithreaded
 * execution context, regioniser ownership, and selector resolution semantics.
 */
public final class LecithinContextReport {

    public static final TextColor HEADER = TextColor.color(79, 164, 240);       // #4FA4F0 (Bright sky blue)
    public static final TextColor PRIMARY = TextColor.color(48, 145, 237);      // #3091ED (Medium blue)
    public static final TextColor SECONDARY = TextColor.color(104, 177, 240);   // #68B1F0 (Light blue)
    public static final TextColor INFORMATION = TextColor.color(180, 220, 255); // #B4DCFF (Soft pale blue)
    public static final TextColor LIST = TextColor.color(33, 97, 188);          // #2161BC (Royal blue bullet)
    public static final TextColor MUTED = TextColor.color(120, 140, 160);       // #788CA0 (Muted gray-blue for pointers)
    public static final TextColor SUCCESS = TextColor.color(85, 255, 85);       // #55FF55 (Green)
    public static final TextColor WARNING = TextColor.color(255, 255, 85);      // #FFFF55 (Yellow)
    public static final TextColor ERROR = TextColor.color(255, 85, 85);         // #FF5555 (Red)

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

    private static Component section(final String title, final String tooltip) {
        final TextComponent.Builder builder = Component.text();
        builder.append(Component.text("== ", SECONDARY));
        builder.append(Component.text(title, HEADER, TextDecoration.BOLD));
        builder.append(Component.text(" ==", SECONDARY));
        if (tooltip != null && !tooltip.isEmpty()) {
            builder.hoverEvent(HoverEvent.showText(Component.text(tooltip, SECONDARY)));
        }
        return builder.build();
    }

    private static Component bullet(final String label, final Component value, final String tooltip) {
        final TextComponent.Builder builder = Component.text();
        builder.append(Component.text(" - ", LIST, TextDecoration.BOLD));
        builder.append(Component.text(label + ": ", PRIMARY));
        builder.append(value);
        if (tooltip != null && !tooltip.isEmpty()) {
            builder.hoverEvent(HoverEvent.showText(Component.text(tooltip, SECONDARY)));
        }
        return builder.build();
    }

    private static Component subBullet(final String label, final Component value, final String tooltip) {
        final TextComponent.Builder builder = Component.text();
        builder.append(Component.text("   - ", SECONDARY));
        builder.append(Component.text(label + ": ", PRIMARY));
        builder.append(value);
        if (tooltip != null && !tooltip.isEmpty()) {
            builder.hoverEvent(HoverEvent.showText(Component.text(tooltip, SECONDARY)));
        }
        return builder.build();
    }

    /**
     * Builds the diagnostic report for the execution context of the calling thread and command source.
     */
    public static List<Component> context(final CommandSourceStack source) {
        final List<Component> out = new ArrayList<>();
        final Thread thread = Thread.currentThread();
        final boolean isTickThread = ca.spottedleaf.moonrise.common.util.TickThread.isTickThread();
        final boolean isGlobalTick = RegionizedServer.isGlobalTickThread();

        // 1. Title Header
        out.add(Component.text()
                .append(Component.text("Lecithin Context Report", HEADER, TextDecoration.BOLD))
                .append(Component.text(" (Thread & Execution Diagnostics)", SECONDARY))
                .build());

        // 2. Section: Thread Environment
        out.add(section("Thread Environment", "當前執行此指令的 Java 執行緒角色與排程狀態"));
        out.add(bullet("Thread Name", Component.text(thread.getName(), INFORMATION), "呼叫端所在的 Java Thread 識別名稱"));

        final Component roleBadge;
        if (isTickThread) {
            if (isGlobalTick) {
                roleBadge = Component.text("[Global Region Thread]", WARNING, TextDecoration.BOLD)
                        .hoverEvent(HoverEvent.showText(Component.text("全域 Tick 執行緒：處理連線封包、天氣、世界邊界、主控台/RCON 指令與全域任務（不包含具體世界區塊）", SECONDARY)));
            } else {
                roleBadge = Component.text("[World Region Tick Thread]", SUCCESS, TextDecoration.BOLD)
                        .hoverEvent(HoverEvent.showText(Component.text("世界區域 Tick 執行緒：正在執行某個世界的具體區域分區（擁有該區域內的區塊與實體）", SECONDARY)));
            }
        } else {
            roleBadge = Component.text("[Off-Region / Async Thread]", MUTED)
                    .hoverEvent(HoverEvent.showText(Component.text("非同步或非區域執行緒：未持有任何世界的 tick 鎖，無法直接進行同步的區塊/實體操作", SECONDARY)));
        }
        out.add(bullet("Thread Role", roleBadge, "此執行緒在 Folia 多執行緒區域化架構中的職責角色"));

        final Component flags = Component.text()
                .append(Component.text("Tick Thread: ", PRIMARY))
                .append(isTickThread ? Component.text("[Yes]", SUCCESS) : Component.text("[No]", MUTED))
                .append(Component.text(" | Global Region: ", SECONDARY))
                .append(isGlobalTick ? Component.text("[Yes]", WARNING) : Component.text("[No]", MUTED))
                .build();
        out.add(bullet("Flags", flags, "TickThread 標記與是否為全域 Region 執行緒"));

        // 3. Section: Current Tick Region
        out.add(section("Current Tick Region", "此執行緒當前正在 Tick 的世界分區（ThreadedRegion）"));
        final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region =
                TickRegionScheduler.getCurrentRegion();
        if (region == null) {
            out.add(bullet("Active Region", Component.text("None (This thread is not ticking a region)", MUTED),
                    "此執行緒目前未綁定任何世界分區（由主控台、RCON 或非同步排程呼叫時為正常現象）"));
        } else {
            final ThreadedRegionizer<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> regioniser = region.regioniser;
            final ChunkPos centerChunk = region.getCenterChunk();
            final String centerCoord = centerChunk == null ? "unknown" : ((centerChunk.x() << 4) | 7) + ", " + ((centerChunk.z() << 4) | 7);
            final boolean isOwn = regioniser.world == regioniser.world.getWorld().getHandle();

            final Component regionInfo = Component.text()
                    .append(Component.text(worldOf(regioniser.world) + " (" + centerCoord + ")", INFORMATION))
                    .append(Component.text(" " + id(region), MUTED))
                    .build();
            out.add(bullet("Region Center", regionInfo, "當前分區中心區塊座標與實例 ID"));

            final Component regioniserInfo = Component.text()
                    .append(Component.text(worldOf(regioniser.world), INFORMATION))
                    .append(Component.text(" " + id(regioniser), MUTED))
                    .append(Component.text(" "))
                    .append(isOwn
                            ? Component.text("[Dedicated Regioniser]", SUCCESS)
                                    .hoverEvent(HoverEvent.showText(Component.text("該世界擁有獨立的 ThreadedRegionizer，區域排程完全隔離", SECONDARY)))
                            : Component.text("[SHARED WITH OTHER WORLD]", ERROR, TextDecoration.BOLD)
                                    .hoverEvent(HoverEvent.showText(Component.text("錯誤：Regioniser 與其他世界共用！", ERROR))))
                    .build();
            out.add(bullet("Regioniser", regioniserInfo, "該世界專屬的 ThreadedRegionizer 排程器實例"));

            final RegionizedWorldData worldData = TickRegionScheduler.getCurrentRegionizedWorldData();
            if (worldData != null) {
                final Component worldDataInfo = Component.text()
                        .append(Component.text(worldOf(worldData.world), INFORMATION))
                        .append(Component.text(" " + id(worldData), MUTED))
                        .build();
                out.add(bullet("Region WorldData", worldDataInfo, "該分區對應的 RegionizedWorldData 視圖"));
            }
        }

        // 4. Section: Command Source
        out.add(section("Command Source", "執行此指令的來源物件與錨定座標"));
        final Component senderInfo = Component.text()
                .append(Component.text(source.getBukkitSender().getName(), INFORMATION))
                .append(Component.text(" [" + source.getBukkitSender().getClass().getSimpleName() + "]", SECONDARY))
                .build();
        out.add(bullet("Sender", senderInfo, "指令發送者實體或主控台介面"));

        final Component sourceWorldInfo = Component.text()
                .append(Component.text(worldOf(source.getLevel()), INFORMATION))
                .append(Component.text(" (Dim: " + source.getLevel().dimension().identifier() + ")", SECONDARY))
                .append(Component.text(" " + id(source.getLevel()), MUTED))
                .build();
        out.add(bullet("Source World", sourceWorldInfo, "指令來源綁定的世界（選擇器與相對座標以此世界為基準）"));

        final Component posInfo = Component.text()
                .append(Component.text(pos(source.getPosition()), INFORMATION))
                .append(Component.text(" | Rotation: " + String.format("%.1f, %.1f", source.getRotation().x, source.getRotation().y), SECONDARY))
                .build();
        out.add(bullet("Source Position", posInfo, "指令來源的執行座標 (X, Y, Z) 與視角朝向"));

        // 5. Section: Source Entity & Ownership
        out.add(section("Source Entity & Ownership", "指令綁定的實體及其區域執行緒所有權"));
        final Entity entity = source.getEntity();
        if (entity == null) {
            out.add(bullet("Entity", Component.text("None (Source is not an entity)", MUTED), "指令來源為非實體（例如控制台、RCON 或函數）"));
        } else {
            final ServerLevel entityLevel = (ServerLevel) entity.level();
            final boolean isOwner = ca.spottedleaf.moonrise.common.util.TickThread.isTickThreadFor(entity);
            final boolean sameWorld = entityLevel == source.getLevel();

            final Component entityInfo = Component.text()
                    .append(Component.text(entity.getScoreboardName(), INFORMATION))
                    .append(Component.text(" [" + entity.getType().toShortString() + "]", SECONDARY))
                    .append(Component.text(" " + id(entity), MUTED))
                    .build();
            out.add(bullet("Entity", entityInfo, "指令綁定的實體名稱與類型"));

            final Component entityLoc = Component.text()
                    .append(Component.text(worldOf(entityLevel), INFORMATION))
                    .append(Component.text(" (" + pos(entity.position()) + ")", SECONDARY))
                    .build();
            out.add(bullet("Entity Position", entityLoc, "實體的實際世界與精確空間座標"));

            final Component ownership = isOwner
                    ? Component.text("[Owned by Thread (Safe Sync Access)]", SUCCESS, TextDecoration.BOLD)
                            .hoverEvent(HoverEvent.showText(Component.text("當前執行緒擁有該實體所在區域的所有權，可直接進行同步 Bukkit API 存取", SECONDARY)))
                    : Component.text("[Cross-Region / Not Owned (Requires Scheduler)]", WARNING, TextDecoration.BOLD)
                            .hoverEvent(HoverEvent.showText(Component.text("當前執行緒不擁有該實體區域！若要存取此實體，必須透過 entity.getScheduler() 排程轉派", SECONDARY)));
            out.add(bullet("Thread Ownership", ownership, "當前執行緒是否直接管轄該實體所在的區域"));

            final Component worldAlign = sameWorld
                    ? Component.text("[Same World as Source]", SUCCESS)
                    : Component.text("[World Mismatch with Source]", ERROR, TextDecoration.BOLD);
            out.add(bullet("World Alignment", worldAlign, "實體所在世界是否與指令來源世界一致"));
        }

        // 6. Section: Target Selector Resolution
        out.add(section("Target Selector Resolution", "原版選擇器以此來源座標為基準解析出的目標玩家（說明為何跨世界時 @p 可能選中其他世界的玩家）"));
        out.add(bullet("Selector @p (Nearest)", resolveSingle(source, "@p"), "原版 @p 依三維歐氏幾何距離在全服搜尋最近玩家"));
        out.add(bullet("Selector @p[distance=..1M]", resolveSingle(source, "@p[distance=..1000000]"), "帶有顯式距離範圍約束的 @p 搜尋結果"));
        out.add(bullet("Selector @a (All)", resolveAll(source, "@a"), "符合條件的所有在線玩家清單"));

        return out;
    }

    /**
     * Builds the diagnostic report for level and regioniser identity of every loaded world.
     */
    public static List<Component> worlds() {
        final List<Component> out = new ArrayList<>();
        final List<World> bukkitWorlds = Bukkit.getWorlds();

        long totalChunks = 0;
        int totalRegions = 0;

        for (final World bukkitWorld : bukkitWorlds) {
            final ServerLevel level = ((CraftWorld) bukkitWorld).getHandle();
            final List<ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData>> regions = new ArrayList<>();
            level.regioniser.computeForAllRegions(regions::add);
            totalRegions += regions.size();
            totalChunks += bukkitWorld.getLoadedChunks().length;
        }

        // 1. Title Header
        out.add(Component.text()
                .append(Component.text("Lecithin Loaded Worlds Report", HEADER, TextDecoration.BOLD))
                .append(Component.text(" (" + bukkitWorlds.size() + " loaded worlds)", SECONDARY))
                .build());

        // 2. Summary Row
        final Component summary = Component.text()
                .append(Component.text("Total Worlds: ", PRIMARY))
                .append(Component.text(bukkitWorlds.size(), INFORMATION))
                .append(Component.text(" | Online Players: ", SECONDARY))
                .append(Component.text(Bukkit.getOnlinePlayers().size(), INFORMATION))
                .append(Component.text(" | Active Regions: ", SECONDARY))
                .append(Component.text(totalRegions, INFORMATION))
                .append(Component.text(" | Loaded Chunks: ", SECONDARY))
                .append(Component.text(totalChunks, INFORMATION))
                .build();
        out.add(bullet("Summary", summary, "全服載入的世界、在線玩家與區域總覽"));

        // 3. Per-World Breakdown
        for (final World bukkitWorld : bukkitWorlds) {
            final ServerLevel level = ((CraftWorld) bukkitWorld).getHandle();
            final ThreadedRegionizer<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> regioniser = level.regioniser;
            final List<ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData>> regions = new ArrayList<>();
            regioniser.computeForAllRegions(regions::add);

            final boolean isOwn = regioniser.world == level;

            final Component worldHeader = Component.text()
                    .append(Component.text(" - World: ", LIST, TextDecoration.BOLD))
                    .append(Component.text(bukkitWorld.getName(), HEADER, TextDecoration.BOLD))
                    .append(Component.text(" (" + level.dimension().identifier() + ")", SECONDARY))
                    .append(Component.text(" " + id(level), MUTED))
                    .build();
            out.add(worldHeader);

            final Component regioniserInfo = Component.text()
                    .append(Component.text(id(regioniser), MUTED))
                    .append(Component.text(" "))
                    .append(isOwn
                            ? Component.text("[Dedicated Regioniser]", SUCCESS)
                                    .hoverEvent(HoverEvent.showText(Component.text("此世界擁有獨立專屬的 ThreadedRegionizer，分區排程完全獨立", SECONDARY)))
                            : Component.text("[SHARED WITH " + worldOf(regioniser.world) + "]", ERROR, TextDecoration.BOLD)
                                    .hoverEvent(HoverEvent.showText(Component.text("警告：此世界與其他世界共用 Regioniser！可能導致跨世界執行緒斷言崩潰", ERROR))))
                    .build();
            out.add(subBullet("Regioniser", regioniserInfo, "該世界持有的 ThreadedRegionizer 實例與獨立性驗證"));

            final Component regionsInfo = Component.text()
                    .append(Component.text(regions.size(), INFORMATION))
                    .append(Component.text(" live regions", PRIMARY))
                    .append(Component.text(" (ID: " + id(level.tickRegions) + ")", MUTED))
                    .build();
            out.add(subBullet("Active Regions", regionsInfo, "當前世界中由玩家或 chunk tickets 活躍 tick 的獨立分區數量"));

            final Component chunksAndPlayers = Component.text()
                    .append(Component.text("Loaded Chunks: ", PRIMARY))
                    .append(Component.text(bukkitWorld.getLoadedChunks().length, INFORMATION))
                    .append(Component.text(" | Players: ", SECONDARY))
                    .append(Component.text(bukkitWorld.getPlayers().size(), INFORMATION))
                    .build();
            out.add(subBullet("Chunks & Players", chunksAndPlayers, "該世界已載入的區塊數量與在線玩家數"));
        }

        return out;
    }

    private static Component resolveSingle(final CommandSourceStack source, final String selector) {
        try {
            final EntitySelector parsed = new EntitySelectorParser(new StringReader(selector), true).parse();
            final ServerPlayer player = parsed.findSinglePlayer(source);
            return describe(player, source);
        } catch (final Exception e) {
            return Component.text("<" + e.getClass().getSimpleName() + ": " + e.getMessage() + ">", ERROR);
        }
    }

    private static Component resolveAll(final CommandSourceStack source, final String selector) {
        try {
            final EntitySelector parsed = new EntitySelectorParser(new StringReader(selector), true).parse();
            final List<ServerPlayer> players = parsed.findPlayers(source);
            if (players.isEmpty()) {
                return Component.text("<none>", MUTED);
            }
            final TextComponent.Builder builder = Component.text();
            for (int i = 0; i < players.size(); i++) {
                if (i > 0) {
                    builder.append(Component.text("; ", SECONDARY));
                }
                builder.append(describe(players.get(i), source));
            }
            return builder.build();
        } catch (final Exception e) {
            return Component.text("<" + e.getClass().getSimpleName() + ": " + e.getMessage() + ">", ERROR);
        }
    }

    private static Component describe(final ServerPlayer player, final CommandSourceStack source) {
        if (player == null) {
            return Component.text("<none>", MUTED);
        }
        final ServerLevel level = player.level();
        final double distance = Math.sqrt(player.position().distanceToSqr(source.getPosition()));
        final boolean sameWorld = level == source.getLevel();

        final TextComponent.Builder builder = Component.text();
        builder.append(Component.text(player.getScoreboardName(), INFORMATION, TextDecoration.BOLD));
        builder.append(Component.text(" in ", PRIMARY));
        builder.append(Component.text(worldOf(level), INFORMATION));
        builder.append(Component.text(" (" + pos(player.position()) + ")", SECONDARY));
        builder.append(Component.text(" | dist: ", PRIMARY));
        builder.append(Component.text(String.format("%.1f", distance), INFORMATION));
        builder.append(Component.text(" | ", SECONDARY));
        builder.append(sameWorld
                ? Component.text("[✓ Same World]", SUCCESS)
                : Component.text("[⚠ Cross-World]", WARNING)
                        .hoverEvent(HoverEvent.showText(Component.text("注意：此玩家位於不同世界！原版 @p 搜尋是跨世界歐氏幾何距離比較", WARNING))));
        return builder.build();
    }
}

