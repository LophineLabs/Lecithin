package fun.bm.lophinya.compat;

import com.mojang.logging.LogUtils;
import fun.bm.lecithin.config.modules.CompatConfig;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.scheduler.CraftTask;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Lophinya: generic, table-driven scheduler redispatch for plugins whose {@code onEnable()} (or
 * later runtime behavior) is blocked by a sync scheduler call Folia rejects outright.
 *
 * <h2>What this is, precisely</h2>
 * A registry of {@link Rule}s, keyed by the exact SHA-256 of one plugin jar. Each rule says
 * "when THIS task class is about to be rejected, dispatch it THIS way instead" - where the class is
 * either named, or (for the lambdas and method references modern plugins schedule) identified by the
 * one class that declared it, see {@link #matches(Rule, String)} - either to
 * {@link io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler} (for tasks with no
 * single player/location context) or to a specific player's own
 * {@link io.papermc.paper.threadedregions.scheduler.EntityScheduler} (read via reflection on a
 * named field, for tasks scoped to exactly one known player).
 *
 * <h2>What this is <b>not</b></h2>
 * This is <b>not</b> a general "route any rejected sync task to GlobalRegionScheduler" shim - that
 * idea was tested and rejected (project-docs {@code CORE_COMPAT_INVESTIGATION.md}, {@code DEC-05}'s
 * S-3): a bytecode scan of all 35 s01 plugins found it would fully unlock exactly one of them,
 * because almost every plugin's sync scheduler calls are a mix of genuinely-global tasks and
 * tasks that implicitly assume they're running on a specific region's thread. Blindly relocating
 * the second kind doesn't make it safe - it either still fails somewhere inside, or worse, silently
 * touches state from the wrong thread if some code path doesn't hit an explicit ownership check.
 * <p>
 * What actually distinguishes a safe rule from an unsafe guess is that <b>a human (or an agent,
 * under the same evidentiary bar) read the real source of that exact class</b> and confirmed it
 * has no such implicit assumption - see the evidence file cited next to each entry in
 * {@link #RULES}. Adding a plugin here without that research is exactly the mistake this design
 * exists to prevent; the table only makes the <i>mechanical</i> part (wiring a verified rule into
 * the scheduler) cheap to repeat, not the verification itself.
 * <p>
 * Every rule is also version-locked to one exact jar SHA-256 - a plugin update requires a new
 * entry with fresh research, never inherits the previous version's rules.
 *
 * <h2>Fail-closed by construction</h2>
 * Any reflection failure (a field renamed/removed upstream) makes {@link #tryDispatch} return
 * {@code null} - the call falls through to stock Folia rejection, exactly as if no rule existed.
 * This class never guesses; an unreadable field means "unknown," not "assume the safe case."
 *
 * <h2>Cleanup on plugin disable</h2>
 * Every task this class successfully reschedules is tracked per-plugin and cancelled by
 * {@link #cancelAll(Plugin)}, called from the same plugin-disable hook patch 0024 added for the
 * restored async scheduler - otherwise a disabled plugin's redispatched tasks would keep running
 * forever, the same bug class that hardening round fixed for async tasks ({@code DEC-17}).
 *
 * <p>Kill switch: {@code -Dlophinya.compat.pluginSchedulerDispatch=true} - defaults to
 * <b>off</b>. This mechanism reflects into third-party plugin internals; it should never be a
 * silent default.
 */
public final class LophinyaPluginSchedulerDispatch {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** How to redispatch a matched task. */
    public enum Strategy {
        /** No single player/location context - always dispatch to the global region. */
        GLOBAL,
        /**
         * Two variants of one class, distinguished only by a reflected {@link Player} field's
         * value: {@code null} means the global-scope variant, non-null means a single known
         * player - dispatched to that player's own scheduler.
         */
        GLOBAL_OR_PLAYER_FIELD,
        /** Always a single known player (the field is never null for this class) - dispatched to that player's own scheduler. */
        PLAYER_FIELD,
        /**
         * A lambda that captures exactly one player, reached by scanning the synthesised capture
         * fields rather than by name. Lambda captures are named positionally ({@code arg$1},
         * {@code arg$2}, ...), so {@link #PLAYER_FIELD}'s name lookup cannot address them - that is
         * the gap this covers. See {@link #findCapturedPlayer}.
         */
        PLAYER_IN_CAPTURE,
        /**
         * The task belongs to one chunk, and the region that owns that chunk is the only correct
         * thread for it. {@code playerField} carries an accessor path from the task to an object
         * exposing {@code getWorld()}, {@code getChunkX()} and {@code getChunkZ()} - see
         * {@link #findChunkTarget}.
         *
         * <p>This exists because {@link #GLOBAL} is not merely suboptimal for such a task, it is
         * wrong in a way that takes the server down: a chunk-scoped task scheduled from inside a
         * chunk-load callback throws when Folia rejects it, the throw escapes
         * {@code LevelChunk.loadCallback} into the region tick, and Folia's response to a failed
         * region tick is to stop the server.
         */
        REGION_OF_CHUNK
    }

    /**
     * Suffix that turns a {@link Rule#className()} into a lambda rule - see
     * {@link #matches(Rule, String)}.
     */
    private static final String LAMBDA_SUFFIX = "$$Lambda";

    /**
     * @param className    fully-qualified name of the task class this rule matches. A name ending in
     *                     {@code $$Lambda} matches every JVM-generated lambda or method-reference
     *                     class declared by that one class instead - see {@link #matches(Rule, String)}
     * @param strategy     how to dispatch a match
     * @param playerField  for {@link Strategy#GLOBAL_OR_PLAYER_FIELD} and {@link Strategy#PLAYER_FIELD},
     *                     the name of the reflected {@link Player} field. For
     *                     {@link Strategy#PLAYER_IN_CAPTURE}, the name of a zero-argument accessor to
     *                     call on a captured object to reach its {@link Player} ({@code null} when the
     *                     captured value is itself a {@link Player}). Unused for {@link Strategy#GLOBAL}
     */
    public record Rule(String className, Strategy strategy, String playerField) {
        public Rule(final String className, final Strategy strategy) {
            this(className, strategy, null);
        }
    }

    /**
     * One entry per (plugin, exact jar SHA-256). Each {@link Rule} list is the product of reading
     * that plugin's real official source (not decompiled) and confirming the dispatch target
     * doesn't guess at region ownership - see the cited evidence file for the actual research.
     */
    private static final Map<String, List<Rule>> RULES = Map.of(
        // GriefPrevention 16.18.7 (s01-installed version). Research:
        // evidence/20260728-t09-griefprevention-callsite-analysis.md,
        // evidence/20260728-t09b-griefprevention-per-callsite-dispatch-feasibility.md,
        // evidence/20260728-griefprevention-core-shim-implementation-plan.md.
        // SiegeCheckupTask and CleanupUnusedClaimPreTask->CleanupUnusedClaimTask are deliberately
        // NOT here - both need a genuine redesign (two-hop dispatch; chunk-scoped handoff) that
        // this Strategy set can't express safely, not a rule of this shape.
        "45E9907C61222E559ED2E099FFEE0EDE706A21243A31D7DB549BAF678708FDF0", List.of(
            new Rule("me.ryanhamshire.GriefPrevention.EntityCleanupTask", Strategy.GLOBAL),
            new Rule("me.ryanhamshire.GriefPrevention.FindUnusedClaimsTask", Strategy.GLOBAL),
            new Rule("me.ryanhamshire.GriefPrevention.DeliverClaimBlocksTask", Strategy.GLOBAL_OR_PLAYER_FIELD, "player"),
            new Rule("me.ryanhamshire.GriefPrevention.PvPImmunityValidationTask", Strategy.PLAYER_FIELD, "player")
        ),
        // Shopkeepers 2.27.0 (s01-installed version). Research:
        // evidence/20260728-t12-shopkeepers-callsite-analysis.md,
        // evidence/20260728-t15-shopkeepers-entityai-scheduling-feasibility.md,
        // evidence/20260728-shopkeepers-gate-only-l2-test.md (the L2 run that identified which
        // callsite actually aborts onEnable()).
        //
        // EntityAI$TickTask is the AI/gravity heartbeat, registered unconditionally and without a
        // try/catch from onEnable() (EntityAI.java:383 -> :225 -> BaseEntityShops:25 ->
        // SKShopkeepersPlugin:398), so stock Folia's rejection kills the whole enable chain before
        // any of the ~96 event handlers register. The scheduling call itself touches no world
        // state, and the task body was read line by line against the official v2.27.0 source: it
        // returns immediately while no shop entities are tracked, and once entities do exist it
        // aborts at the first ownership check inside updateChunkActivations() - after the
        // deactivate-everything pass, never mid-write. Same shape as GriefPrevention's
        // EntityCleanupTask: the *dispatch* is safe, the *behaviour* is a known, documented
        // degradation (shop gravity and look-at-player stop working once shops exist), not
        // something this class pretends to fix.
        //
        // NOT a claim that Shopkeepers' AI subsystem works across regions - T-12 classified that
        // as structurally infeasible (EntityAI.java:114-115 share static mutable Location/
        // ChunkCoords across what would become several region threads) and this entry does not
        // touch that conclusion. Nothing here re-dispatches per entity; the whole loop stays on
        // one thread, so those shared statics keep exactly the single-threaded access pattern the
        // plugin assumes.
        //
        // CitizensShops$DelayedSetupTask is the next abort in the same chain once EntityAI is
        // handled (CitizensShops.java:183 -> :95 -> SKShopkeepersPlugin:402, runTaskLater delay=3,
        // one-shot), reached only when Citizens is installed - which it is on s01. T-12 4.1/8-B
        // classifies it as a global-but-one-shot startup scan. It has no player or location
        // context at schedule time (it walks the whole shopkeeper registry), so GLOBAL is the only
        // honest target. Its body reads the Citizens NPC registry and the plugin's own in-memory
        // maps; with delete-invalid-citizen-shopkeepers defaulting to false (Settings.java:258,
        // and s01's config does not override it) it mutates nothing outside those maps.
        "4481DF8E0B5642A2835150103E951F0BB360B8158E33C2151781B3BD4A7260A4", List.of(
            new Rule("com.nisovin.shopkeepers.shopobjects.entity.base.EntityAI$TickTask", Strategy.GLOBAL),
            new Rule("com.nisovin.shopkeepers.shopobjects.citizens.CitizensShops$DelayedSetupTask", Strategy.GLOBAL),
            // ShopkeeperSpawnQueue$SpawnerTask - the spawn queue's drain timer, started from
            // ShopkeeperSpawner.onEnable() (TaskQueue.java:191 -> :99 -> ShopkeeperSpawner:95 ->
            // SKShopkeeperRegistry:157 -> SKShopkeepersPlugin:427, runTaskTimer delay=1 period=3),
            // the third abort in the chain. T-12 4.1 lists it as a global heartbeat; T-12 8-D's
            // open question was whether its throttling still holds if the queue were fanned out
            // per region - this rule does NOT fan it out, the whole drain loop keeps running on
            // one thread, so the "workUnitsPerExecution per tick" budget is unchanged.
            // TaskQueue.execute() polls a work unit and calls process(), which sets the
            // shopkeeper's spawn state to DESPAWNED *before* attempting the spawn
            // (ShopkeeperSpawnQueue.java:93-97) - so an ownership rejection inside the spawn
            // leaves the in-memory state consistent with reality (not spawned) rather than
            // half-written, and nothing here is persisted.
            new Rule("com.nisovin.shopkeepers.shopkeeper.spawning.ShopkeeperSpawnQueue$SpawnerTask", Strategy.GLOBAL),
            // ShopkeeperSpawner$CheckUnspawnableShopkeepersTask - the fourth abort, immediately
            // after the queue starts (ShopkeeperSpawner.java:99, runTaskLater delay=5, one-shot).
            // T-12 4.1: "global, but a one-shot startup scan". Its body walks the shopkeeper
            // registry and only reads each shop object's in-memory lastSpawnFailed flag
            // (ShopkeeperSpawner.java:808-855); it deletes anything only when
            // delete-unspawnable-shopkeepers is on, which is false by default and explicitly false
            // in s01's config.yml:102. At 5 ticks after enable no spawn attempt has happened yet,
            // so the flag is false everywhere and this scan is a pure no-op at this callsite.
            new Rule("com.nisovin.shopkeepers.shopkeeper.spawning.ShopkeeperSpawner$CheckUnspawnableShopkeepersTask", Strategy.GLOBAL),
            // ShopkeeperTicker$ShopkeeperTickTask - the fifth and (as of this entry) last abort in
            // onEnable() (ShopkeeperTicker.java:229 -> :221 -> :111 -> SKShopkeeperRegistry:158 ->
            // SKShopkeepersPlugin:427, a BukkitRunnable so rTask is the task itself, runTaskTimer
            // delay=5 period=5). T-12 8-B called this "B, but real work" - that assessment is
            // about making the per-shop tick correct across regions, which needs the batched
            // dirty/save accounting rewritten. This rule does not attempt that: the loop stays
            // whole on one thread, exactly as today. Notably its per-shop step already wraps
            // shopkeeper.tick() in try/catch Throwable (ShopkeeperTicker.java:271-276), so an
            // ownership rejection is logged per shop and the loop still resets currentlyTicking
            // and drains pendingTickingChanges - no stuck flag, no half-applied registration.
            new Rule("com.nisovin.shopkeepers.shopkeeper.ticking.ShopkeeperTicker$ShopkeeperTickTask", Strategy.GLOBAL),
            // ShopkeeperChunkActivator$DelayedChunkActivationTask (ShopkeeperChunkActivator.java:306,
            // runTaskLater delay=20). Added 2026-07-30 after it took the whole server down, which is
            // worth spelling out because the earlier record of this callsite got the severity wrong:
            // evidence 20260730-manual-l3 item 4 filed it as "shop chunk activation just is not
            // running", a degradation. It is not. It is scheduled from inside ChunkLoadEvent, so the
            // rejection throws out of LevelChunk.loadCallback -> onChunkBorder -> the region tick,
            // and Folia stops the server on a failed region tick. Measured: an OP player joining and
            // loading chunks near a shop shut the server down mid-join
            // (evidence/20260730c-mirror-runL-op-join-region-tick-crash-console.log).
            //
            // GLOBAL would be wrong, not just suboptimal - the task activates the shopkeepers in one
            // specific chunk, so the region owning that chunk is the only correct thread. Read off
            // the real artifact with javap (no source available for this version): the task holds
            // `private final ChunkData chunkData`, ChunkData exposes `getChunkCoords()`, and
            // ChunkCoords exposes getWorld() / getChunkX() / getChunkZ() - which is exactly the
            // contract REGION_OF_CHUNK needs. Note the scheduling call already happens on the
            // owning region's thread, so this dispatches the task back to the thread it was always
            // implicitly written for.
            new Rule("com.nisovin.shopkeepers.shopkeeper.activation.ShopkeeperChunkActivator$DelayedChunkActivationTask",
                Strategy.REGION_OF_CHUNK, "chunkData.getChunkCoords"),
            // ShopkeeperChunkActivator$ActivatePendingNearbyChunksTask (:324). Same subsystem, but
            // javap shows it holds `private final Player player`, so it is a single-player task and
            // the existing named-field strategy addresses it - no new mechanism needed. It activates
            // the pending chunks around that one player, so that player's region is the right owner.
            new Rule("com.nisovin.shopkeepers.shopkeeper.activation.ShopkeeperChunkActivator$ActivatePendingNearbyChunksTask",
                Strategy.PLAYER_FIELD, "player")
        ),
        // EssentialsX 2.22.1-dev+12-776f709 (the exact artifact installed on s01). Research:
        // evidence/20260729d-t01-essentialsx-callsite-analysis.md (the full callsite inventory),
        // evidence/20260730b-essentialsx-economy-serialization.md section 5.6 (the runtime blocker
        // order, found by cold-starting once per rule), evidence/20260730c-essentialsx-delivery.md
        // (the per-rule source reading below, and the L2/L3 runs).
        //
        // These four are the whole set of Bukkit *sync* scheduler calls this jar makes on a path
        // that s01's own configuration actually reaches during enable and first join. Three are
        // lambdas, which is why this table needed the $$Lambda prefix form at all - see matches().
        // Every one of them is a server-scope startup or housekeeping task with no single player or
        // location context at schedule time, so GLOBAL is not a fallback here, it is the correct
        // target; nothing below is a per-region task being relocated.
        "ED0C4432BB286CE06820BA5A162FFAC91E34E02EBA644CFCF66AEC4FDA86AF42", List.of(
            // EconomyLayers.onEnable (EconomyLayers.java:35) <- Essentials.onEnable(:330). The
            // earliest hard block in the enable chain - earlier than T-01 predicted. EconomyLayers
            // declares exactly one lambda in the entire file, so the prefix is unambiguous for this
            // jar. Body: sets a static boolean, walks getPluginManager().getPlugins(), and calls
            // VaultLayer.enable/onServerLoad, whose only non-trivial statement is
            // Bukkit.getServicesManager().load(Economy.class) (VaultLayer.java:57). No world, block,
            // chunk or entity access anywhere in it - the global region is exactly where a
            // server-wide service lookup belongs.
            new Rule("com.earth2me.essentials.economy.EconomyLayers$$Lambda", Strategy.GLOBAL),
            // Settings.reloadConfig(:850, :876, :891) <- Essentials.reload(:602) <- onEnable(:429).
            // Three scheduler-bound lambdas (the third is a syncCommandsProvider::syncCommands
            // method reference, which the JVM also names <declaringClass>$$Lambda). This is the
            // case where a prefix rule covers more than one call site, so the claim has to hold for
            // all three: all three are inside "if (reloadCount.get() < 2)", i.e. the two startup
            // reloads only - at runtime (/ess reload) the else-branches run inline and never reach
            // the scheduler at all. All three do command-map work: re-registering plugin command
            // aliases and rebuilding the command tree. None touches world state. Settings.java's
            // other lambdas (:705, :738, :2116) are computeIfAbsent/forEach callbacks that never go
            // near a scheduler, so they cannot be caught by this rule.
            new Rule("com.earth2me.essentials.Settings$$Lambda", Strategy.GLOBAL),
            // EssentialsTimer (Essentials.java:439, delay 1000 period 50) - a named class, the
            // third block in the enable chain. This is the one entry whose *behaviour* under GLOBAL
            // is a documented degradation rather than an equivalence, and the degradation is
            // per-player state, so it is spelled out rather than waved at:
            //   - setLastOnlineActivity / checkMuteTimeout / resetInvulnerabilityAfterTeleport all
            //     read and write plain UserData fields and send chat, no world access;
            //   - checkJailTimeout (User.java:771) already uses getAsyncTeleport(), the Folia-safe
            //     path, and is only reachable for a jailed player (s01 has no jail.yml);
            //   - checkActivity -> setAfk(true) (User.java:690-692) is the real one: it calls
            //     CraftPlayer.setSleepingIgnored, which Folia itself reroutes to the global tick
            //     thread (ServerLevel.updateSleepingPlayerList), and reads getLocation() for the
            //     AFK anchor position. That read has no ownership check in Folia, so it does not
            //     throw - it returns a possibly one-tick-stale position, which only shifts when
            //     cancel-afk-on-move fires. s01 runs auto-afk: 300 so this path IS reachable
            //     (auto-afk-timeout: -1 only disables the kick branch, not the AFK branch).
            // Recorded as a bounded, measured C-UX gap, not as "no world access".
            new Rule("com.earth2me.essentials.EssentialsTimer", Strategy.GLOBAL),
            // Backup (Backup.java:51, repeating at backup.interval) - a named class. Not reached at
            // cold start: the constructor only starts the timer when players are online or
            // always-run is set (Backup.java:28, s01 has always-run: false), so it first schedules
            // from onPlayerJoin -> startTask, inside the join event handler and with no try/catch.
            // Left unhandled it aborts PlayerJoinEvent for Essentials halfway. Its run() returns at
            // Backup.java:70 unless a backup command is configured, and s01's config.yml leaves
            // that key commented out - so under this exact tuple the task body is a no-op. If a
            // command were configured it would dispatchCommand("save-all") and fork a process, both
            // of which belong on the global region anyway.
            new Rule("com.earth2me.essentials.Backup", Strategy.GLOBAL),
            // User.updateActivityOnChat (User.java:864) - the only one of these four that is not a
            // startup task, and the only one that is per-player rather than server-scope. It fires
            // on EVERY chat message, because s01 sets cancel-afk-on-chat: true, from inside
            // AsyncPlayerChatEvent (EssentialsPlayerListener.java:230). Unhandled it throws there,
            // which aborts Essentials' chat handler: measured effect is that chat is silently never
            // formatted or delivered at all, with only a stack trace in the console.
            //
            // The comment on the plugin's own callsite says why it schedules at all: "Chat happens
            // async, make sure we have a sync context". On Folia the correct context is the chatting
            // player's own region, which is what PLAYER_IN_CAPTURE resolves - the lambda captures the
            // User, and User.getBase() is the Player (User -> UserData -> PlayerExtension.base).
            // GLOBAL would be wrong here, not merely suboptimal: the task body writes that one
            // player's AFK state and display name.
            //
            // Task body read line by line (User.updateActivity -> setAfk(false, CHAT)): the only
            // world-adjacent call is CraftPlayer.setSleepingIgnored, which Folia itself reroutes to
            // the global tick thread (ServerLevel.updateSleepingPlayerList), and the false branch of
            // setAfk never reads getLocation() - only setAfk(true) does. updateAfkListName() is a
            // no-op under s01's afk-list-name: "none".
            new Rule("com.earth2me.essentials.User$$Lambda", Strategy.PLAYER_IN_CAPTURE, "getBase")
        )
    );

    private static final Map<Path, String> SHA_CACHE = new ConcurrentHashMap<>();
    private static final Map<Plugin, List<ScheduledTask>> DISPATCHED = new ConcurrentHashMap<>();

    /**
     * @param task   the task about to be rejected by stock Folia's {@code CraftScheduler.handle}
     * @param delay  the delay in ticks, as passed to {@code handle}
     * @param period the task's period ({@code CraftTask.getPeriod()} is package-private to
     *               {@code org.bukkit.craftbukkit.scheduler}, so the caller passes it through);
     *               {@code > 0} is treated as repeating, anything else (stock Folia's
     *               {@code NO_REPEATING = -1}) as one-shot
     * @return the same task (already scheduled), or {@code null} if no rule matches and the call
     *         should fall through to the normal rejection path
     */
    public static CraftTask tryDispatch(final CraftTask task, final long delay, final long period) {
        if (!CompatConfig.pluginSchedulerDispatch) {
            return null;
        }
        try {
            final Plugin plugin = task.getOwner();
            if (plugin == null || task.rTask == null) {
                return null;
            }
            final List<Rule> rules = RULES.get(sha256(plugin));
            if (rules == null) {
                return null;
            }

            final String className = task.rTask.getClass().getName();
            Rule matched = null;
            for (final Rule r : rules) {
                if (matches(r, className)) {
                    matched = r;
                    break;
                }
            }
            if (matched == null) {
                return null;
            }

            final boolean repeating = period > 0;
            final long safeDelay = Math.max(delay, 1L);
            final ScheduledTask scheduled;

            if (matched.strategy() == Strategy.GLOBAL) {
                scheduled = repeating
                    ? Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, ignored -> task.run(), safeDelay, period)
                    : Bukkit.getGlobalRegionScheduler().runDelayed(plugin, ignored -> task.run(), safeDelay);
            } else if (matched.strategy() == Strategy.REGION_OF_CHUNK) {
                final ChunkTarget chunk = findChunkTarget(task.rTask, matched.playerField());
                if (chunk == null) {
                    return null;
                }
                scheduled = repeating
                    ? Bukkit.getRegionScheduler().runAtFixedRate(plugin, chunk.world(), chunk.x(), chunk.z(),
                        ignored -> task.run(), safeDelay, period)
                    : Bukkit.getRegionScheduler().runDelayed(plugin, chunk.world(), chunk.x(), chunk.z(),
                        ignored -> task.run(), safeDelay);
            } else if (matched.strategy() == Strategy.PLAYER_IN_CAPTURE) {
                final Player captured = findCapturedPlayer(task.rTask, matched.playerField());
                if (captured == null) {
                    // Ambiguous, absent, or unreadable - fall through to stock rejection. Same
                    // fail-closed contract as the named-field strategies.
                    return null;
                }
                final ScheduledTask s = repeating
                    ? captured.getScheduler().runAtFixedRate(plugin, ignored -> task.run(), null, safeDelay, period)
                    : captured.getScheduler().runDelayed(plugin, ignored -> task.run(), null, safeDelay);
                if (s == null) {
                    // Player already gone by schedule time - nothing to do, and not an error.
                    return task;
                }
                scheduled = s;
            } else {
                // GLOBAL_OR_PLAYER_FIELD or PLAYER_FIELD - both need the reflected field first.
                // Fail closed on any reflection trouble: an unreadable field is "unknown", not
                // "assume the safe case".
                final PlayerFieldResult pf = readPlayerField(task.rTask, matched.playerField());
                if (!pf.readable()) {
                    return null;
                }
                if (pf.player() == null) {
                    if (matched.strategy() == Strategy.PLAYER_FIELD) {
                        // This strategy requires a non-null player; null here means this call
                        // doesn't actually match the researched case after all.
                        return null;
                    }
                    scheduled = repeating
                        ? Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, ignored -> task.run(), safeDelay, period)
                        : Bukkit.getGlobalRegionScheduler().runDelayed(plugin, ignored -> task.run(), safeDelay);
                } else {
                    final ScheduledTask s = repeating
                        ? pf.player().getScheduler().runAtFixedRate(plugin, ignored -> task.run(), null, safeDelay, period)
                        : pf.player().getScheduler().runDelayed(plugin, ignored -> task.run(), null, safeDelay);
                    if (s == null) {
                        // Entity already invalid/removed by schedule time - not an error, the
                        // per-player task simply has nothing to do.
                        return task;
                    }
                    scheduled = s;
                }
            }

            track(plugin, scheduled);
            LOGGER.warn("[Lophinya] {}: redispatched {} ({}, delay={}, period={}) - version-locked "
                + "rule table entry, not a general sync-to-global shim",
                plugin.getName(), className, matched.strategy(), delay, period);
            return task;
        } catch (final Throwable t) {
            // ponytail: any failure here must fall through to stock rejection, never silently
            // "succeed" without actually having scheduled anything.
            LOGGER.warn("[Lophinya] plugin scheduler dispatch failed (falling through to stock rejection)", t);
            return null;
        }
    }

    /**
     * Does this rule match the runtime class name of a task?
     *
     * <p>Named task classes are matched exactly, as before. Lambdas and method references have no
     * source-level name to match: the JVM synthesises one per call site, as
     * {@code <declaringClass>$$Lambda/0x<address>} (JDK 15 and later) or
     * {@code <declaringClass>$$Lambda$<n>} (older). The address part is a different value on every
     * run, so an exact rule can never match one - which is why the rule table could only ever
     * express plugins written in the older named-{@code Runnable} style.
     *
     * <p>What makes the prefix usable as a rule key rather than a wildcard is that
     * <b>the part before {@code $$Lambda} is the fully-qualified name of the one class that declared
     * the lambda</b>, and that part does not vary. So a rule of {@code com.example.Foo$$Lambda} is
     * scoped to exactly the lambdas written inside {@code Foo} - no wider. It is less precise than a
     * named class only in one specific way: a class declaring several <i>scheduler-bound</i> lambdas
     * cannot distinguish between them, so a rule of this shape asserts that every scheduler-bound
     * lambda in that class is safe under the same strategy. That is a claim about the real source of
     * one exact jar, and the evidence next to each entry in {@link #RULES} has to make it explicitly
     * - it is not weaker research, it is research over a slightly larger unit.
     *
     * <p>The character check after the prefix is what stops this being a plain {@code startsWith}:
     * {@code $$} is legal in a Java identifier, so a source-level class literally called
     * {@code FooBar} would otherwise be matched by a rule for {@code Foo}, and a hand-written class
     * called {@code Foo$$LambdaHelper} by a rule for {@code Foo$$Lambda}. Only the JVM's own two
     * separators are accepted.
     */
    private static boolean matches(final Rule rule, final String className) {
        final String want = rule.className();
        if (!want.endsWith(LAMBDA_SUFFIX)) {
            return want.equals(className);
        }
        if (!className.startsWith(want) || className.length() == want.length()) {
            return false;
        }
        final char separator = className.charAt(want.length());
        return separator == '/' || separator == '$';
    }

    /**
     * Register a redispatched task for cancellation on plugin disable. Shared with
     * {@link LophinyaCallerContextDispatch} so both dispatch paths are cleaned up by the same hook -
     * a redispatched task that outlives its plugin is the bug class {@code DEC-17} fixed for async
     * tasks, and it would come straight back if only one path were tracked.
     */
    static void track(final Plugin plugin, final ScheduledTask scheduled) {
        DISPATCHED.computeIfAbsent(plugin, p -> new CopyOnWriteArrayList<>()).add(scheduled);
    }

    /** Called from the plugin-disable path so a disabled plugin's redispatched tasks stop running. */
    public static void cancelAll(final Plugin plugin) {
        final List<ScheduledTask> tasks = DISPATCHED.remove(plugin);
        if (tasks == null) {
            return;
        }
        for (final ScheduledTask t : tasks) {
            try {
                t.cancel();
            } catch (final Throwable ignored) {
            }
        }
    }

    private static String sha256(final Plugin plugin) {
        try {
            final Path jarPath = Path.of(plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
            return SHA_CACHE.computeIfAbsent(jarPath, LophinyaPluginSchedulerDispatch::hash);
        } catch (final Throwable t) {
            return "<unreadable>";
        }
    }

    private static String hash(final Path jarPath) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().withUpperCase().formatHex(md.digest(Files.readAllBytes(jarPath)));
        } catch (final Throwable t) {
            return "<unreadable>";
        }
    }

    /**
     * Reads a named {@link Player} field via reflection. {@code readable=false} means reflection
     * failed (renamed/removed field upstream) - callers must treat that as "unknown, do not
     * guess" and fall through to stock rejection, not assume any particular variant.
     */
    private static PlayerFieldResult readPlayerField(final Runnable rTask, final String fieldName) {
        try {
            final Field f = rTask.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            final Object value = f.get(rTask);
            return new PlayerFieldResult(true, (value instanceof Player p) ? p : null);
        } catch (final Throwable t) {
            return new PlayerFieldResult(false, null);
        }
    }

    private record PlayerFieldResult(boolean readable, Player player) {
    }

    private record ChunkTarget(World world, int x, int z) {
    }

    /**
     * Resolves the chunk a {@link Strategy#REGION_OF_CHUNK} task belongs to.
     *
     * <p>{@code path} is {@code <fieldName>[.<zeroArgMethod>]...}: the first element is a declared
     * field on the task, each later element a zero-argument method call on the previous result. The
     * object it lands on must expose {@code getWorld()}, {@code getChunkX()} and {@code getChunkZ()}.
     * Naming the path per rule is what keeps this method free of any one plugin's type names, and
     * the three final accessors are a documented contract that the rule's research has to check
     * against the real artifact - not a guess this code makes on the plugin's behalf.
     *
     * <p>Anything missing, null, or of the wrong type returns {@code null}, so the call falls through
     * to stock rejection. That matters more here than elsewhere: dispatching a chunk-scoped task to
     * the wrong region would be a silent cross-region write, which is worse than the loud failure.
     */
    private static ChunkTarget findChunkTarget(final Runnable rTask, final String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            final String[] parts = path.split("\\.");
            final Field field = rTask.getClass().getDeclaredField(parts[0]);
            field.setAccessible(true);
            Object current = field.get(rTask);
            for (int i = 1; i < parts.length && current != null; i++) {
                final var accessor = current.getClass().getMethod(parts[i]);
                accessor.setAccessible(true);
                current = accessor.invoke(current);
            }
            if (current == null) {
                return null;
            }
            final Object world = current.getClass().getMethod("getWorld").invoke(current);
            final Object cx = current.getClass().getMethod("getChunkX").invoke(current);
            final Object cz = current.getClass().getMethod("getChunkZ").invoke(current);
            if (world instanceof World w && cx instanceof Integer x && cz instanceof Integer z) {
                return new ChunkTarget(w, x, z);
            }
            return null;
        } catch (final Throwable t) {
            return null;
        }
    }

    /**
     * Finds the single {@link Player} a lambda captured, by scanning its synthesised capture fields.
     *
     * <p>{@code T-14} established why a name lookup cannot work here: the compiler names lambda
     * captures {@code arg$1}, {@code arg$2}, ... in declaration order, so the name carries no meaning
     * and shifts whenever the lambda's surrounding code is edited. Scanning by <i>type</i> is stable
     * across recompiles in a way scanning by name is not.
     *
     * <p>{@code accessor}, when given, is a zero-argument method to call on a captured value to reach
     * its player - for the common case of a plugin's own user-wrapper type holding the
     * {@link Player} rather than being one. It is named per rule, from reading that plugin's source,
     * so this method stays free of any plugin-specific knowledge.
     *
     * <p><b>Ambiguity is failure.</b> If the scan finds two different players (by UUID), it returns
     * {@code null} rather than picking one: two players means the task is not the single-player task
     * the rule claims it is, and guessing which region owns it is exactly {@code DEC-19} B1. Zero
     * matches and reflection errors are likewise {@code null}.
     */
    private static Player findCapturedPlayer(final Runnable rTask, final String accessor) {
        Player found = null;
        try {
            for (final Field f : rTask.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                final Object value = f.get(rTask);
                if (value == null) {
                    continue;
                }
                Player candidate = null;
                if (value instanceof Player p) {
                    candidate = p;
                } else if (accessor != null) {
                    try {
                        final var m = value.getClass().getMethod(accessor);
                        m.setAccessible(true);
                        if (m.invoke(value) instanceof Player p) {
                            candidate = p;
                        }
                    } catch (final Throwable ignored) {
                        // This capture simply isn't the wrapper type; keep scanning.
                    }
                }
                if (candidate == null) {
                    continue;
                }
                if (found != null && !found.getUniqueId().equals(candidate.getUniqueId())) {
                    return null;
                }
                found = candidate;
            }
        } catch (final Throwable t) {
            return null;
        }
        return found;
    }
}
