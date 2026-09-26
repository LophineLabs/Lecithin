package fun.bm.lecithin.compat.legacy;

import com.mojang.logging.LogUtils;
import fun.bm.lecithin.compat.LecithinExecutionProvenance;
import fun.bm.lecithin.config.modules.CompatConfig;
import io.papermc.paper.threadedregions.RegionizedServer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.BlockEvent;
import org.bukkit.event.entity.EntityEvent;
import org.bukkit.event.hanging.HangingEvent;
import org.bukkit.event.inventory.InventoryEvent;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.event.vehicle.VehicleEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lecithin: the Legacy Paper Runtime - managed execution for plugins that never declared Folia
 * support.
 *
 * <h2>Who is managed</h2>
 * A plugin is managed exactly when its own descriptor does not declare {@code folia-supported: true}.
 * That is the plugin's statement about which threading contract it was written against, read from
 * the artifact the server loaded - not a name, class, hash or version table. A plugin that declares
 * Folia support is on its own native path and is never routed through anything in this package; the
 * only cost it pays is one cached lookup per listener call.
 *
 * <h2>The contract managed plugins get back</h2>
 * <ol>
 *   <li><b>Serial entry with memory visibility.</b> Every synchronous entry the platform makes into a
 *       managed plugin - sync listener, sync scheduler task, command, tab completion, a service call
 *       on a provider it registered, enable and disable - runs inside that plugin's
 *       {@link LegacyExecutionDomain}. Two regions can no longer be inside the same plugin at once,
 *       and each entry sees what the previous one wrote. Different plugins still run in parallel.</li>
 *   <li><b>Timing, logical execution and world ownership are separate.</b> {@link LegacyScheduler}
 *       takes timing from the global clock, runs the body in the plugin's domain, and puts it on the
 *       thread that owns its world anchor ({@link LegacyAffinity}) - an entity, a region - or, when it
 *       has none, on the {@link LegacyLane}, which owns nothing. Unknown never means global.</li>
 *   <li><b>Plugin lifetime.</b> Repeating and long-delay tasks belong to the plugin, not to the
 *       entity that happened to be in context when they were created. They survive that entity
 *       logging out, being removed or crossing regions, and stop when the plugin is disabled.</li>
 *   <li><b>Owner fast path.</b> A short one-shot continuation created on behalf of an entity or a
 *       region goes straight to that owner's Folia scheduler, as before, now inside the domain.</li>
 *   <li><b>Synchronous calls stay synchronous.</b> Events, service calls and dependency calls are
 *       never deferred: they run on the calling thread, entering the callee's domain, so return
 *       values, exceptions, cancellation and the atomicity of the caller's operation are exactly
 *       what the caller sees on Paper.</li>
 *   <li><b>Ownership is never relaxed.</b> World, entity and chunk state is only touched on the
 *       thread that owns it; lane work that reaches for world state fails at the access, attributably.</li>
 * </ol>
 *
 * <p>Kill switch: {@code compat-config.legacy-managed-execution=false} turns every path in this
 * package into a no-op; the previous caller-context dispatch then handles legacy sync tasks again.
 */
public final class LegacyPluginRuntime {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Plugin -> state, or {@link #NATIVE} for a plugin that declared Folia support. Entries are
     * removed when the plugin is disabled so a reloaded plugin's old instance (and class loader) is
     * not retained.
     */
    private static final Map<Plugin, Object> PLUGINS = new ConcurrentHashMap<>();
    private static final Object NATIVE = new Object();

    /**
     * One platform-established execution frame: which plugin's domain this thread is inside, where
     * the work is anchored, and whether the thread is the legacy lane.
     */
    public static final class Frame {
        final LegacyPluginState state;
        final LegacyAffinity affinity;
        final boolean lane;
        final Frame parent;

        Frame(final LegacyPluginState state, final LegacyAffinity affinity, final boolean lane, final Frame parent) {
            this.state = state;
            this.affinity = affinity;
            this.lane = lane;
            this.parent = parent;
        }
    }

    private static final ThreadLocal<Frame> FRAME = new ThreadLocal<>();

    private LegacyPluginRuntime() {
    }

    // ------------------------------------------------------------------ classification

    /**
     * The managed state of {@code plugin}, or {@code null} when it is native (declares Folia
     * support), when the runtime is off, or when there is no plugin.
     */
    public static LegacyPluginState stateOf(final Plugin plugin) {
        if (plugin == null || !CompatConfig.legacyManagedExecution) {
            return null;
        }
        final Object known = PLUGINS.get(plugin);
        if (known != null) {
            return known == NATIVE ? null : (LegacyPluginState) known;
        }
        final Object created = PLUGINS.computeIfAbsent(plugin, LegacyPluginRuntime::classify);
        return created == NATIVE ? null : (LegacyPluginState) created;
    }

    public static boolean isManaged(final Plugin plugin) {
        return stateOf(plugin) != null;
    }

    private static Object classify(final Plugin plugin) {
        boolean folia;
        try {
            folia = plugin.getPluginMeta().isFoliaSupported();
        } catch (final Throwable t) {
            // No readable descriptor: nothing states Folia support, so the Paper contract is the
            // only one the plugin can have been written against.
            folia = false;
        }
        if (folia) {
            return NATIVE;
        }
        LOGGER.info("[Lecithin] {} does not declare folia-supported; running it under the Legacy Paper "
                + "Runtime (serial plugin domain, legacy lane for unanchored work)", plugin.getName());
        return new LegacyPluginState(plugin);
    }

    // ------------------------------------------------------------------ frames

    static Frame enter(final LegacyPluginState state, final LegacyAffinity affinity, final boolean lane) {
        state.domain.enter();
        final Frame parent = FRAME.get();
        final Frame frame = new Frame(state, affinity, lane || (parent != null && parent.lane), parent);
        FRAME.set(frame);
        if (RegionizedServer.isGlobalTickThread()) {
            state.entriesOnGlobalThread.increment();
        }
        return frame;
    }

    /**
     * Leave a frame returned by one of the {@code enter*} methods. A {@code null} frame is a no-op,
     * which keeps the call sites to a single unconditional {@code finally}.
     */
    public static void exit(final Frame frame) {
        if (frame == null) {
            return;
        }
        if (frame.parent == null) {
            FRAME.remove();
        } else {
            FRAME.set(frame.parent);
        }
        frame.state.domain.exit();
    }

    /**
     * {@code true} while this thread is the legacy lane running a managed plugin's work. That work is
     * the plugin's logical main thread - the one place its sync tasks run - so Bukkit's
     * {@code isPrimaryThread()} answers {@code true} for it. Without that, the common legacy idiom
     * "if not on the main thread, reschedule myself with runTask" would re-queue forever. It changes
     * only that API answer: every ownership check still asks whether the thread owns the target, and a
     * lane thread owns nothing.
     */
    public static boolean isLegacyLane() {
        final Frame frame = FRAME.get();
        return frame != null && frame.lane;
    }

    /**
     * The affinity a sync task created right now inherits.
     */
    static LegacyAffinity affinityForNewTask() {
        final Frame frame = FRAME.get();
        if (frame != null) {
            if (frame.affinity.kind() != LegacyAffinity.Kind.NONE) {
                return frame.affinity;
            }
            if (frame.lane) {
                return LegacyAffinity.NONE;
            }
        }
        return affinityOfThread();
    }

    /**
     * The world anchor observable from the calling thread, through the execution provenance model.
     * The global region is deliberately mapped to {@link LegacyAffinity#NONE}: it owns global state,
     * not the plugin's logic.
     */
    static LegacyAffinity affinityOfThread() {
        final LecithinExecutionProvenance.Owner owner = LecithinExecutionProvenance.effectiveOwner();
        if (owner == null) {
            return LegacyAffinity.NONE;
        }
        return switch (owner.kind()) {
            case REGION -> LegacyAffinity.region(owner.world(), owner.chunkX(), owner.chunkZ());
            case ENTITY -> usableEntity(owner.entity()) ? LegacyAffinity.entity(owner.entity()) : LegacyAffinity.NONE;
            case GLOBAL -> LegacyAffinity.NONE;
        };
    }

    /**
     * An entity whose scheduler will actually run work: a player who is online, or an entity already
     * in a world. An entity that was never added (a cancelled spawn) would never retire its scheduler
     * either, and work anchored to it would be lost.
     */
    static boolean usableEntity(final Entity entity) {
        if (entity == null) {
            return false;
        }
        try {
            if (entity instanceof Player player) {
                return player.isOnline();
            }
            return entity.isValid();
        } catch (final Throwable t) {
            return false;
        }
    }

    static LegacyAffinity affinityOf(final Event event) {
        try {
            final Entity entity;
            if (event instanceof PlayerEvent playerEvent) {
                entity = playerEvent.getPlayer();
            } else if (event instanceof EntityEvent entityEvent) {
                entity = entityEvent.getEntity();
            } else if (event instanceof InventoryEvent inventoryEvent) {
                entity = inventoryEvent.getView().getPlayer();
            } else if (event instanceof VehicleEvent vehicleEvent) {
                entity = vehicleEvent.getVehicle();
            } else if (event instanceof HangingEvent hangingEvent) {
                entity = hangingEvent.getEntity();
            } else if (event instanceof BlockEvent blockEvent) {
                return LegacyAffinity.at(blockEvent.getBlock().getLocation());
            } else {
                entity = null;
            }
            if (usableEntity(entity)) {
                return LegacyAffinity.entity(entity);
            }
            if (entity != null) {
                return LegacyAffinity.at(entity.getLocation());
            }
        } catch (final Throwable ignored) {
            // Fall back to the thread's own answer.
        }
        final Frame frame = FRAME.get();
        return frame != null && frame.affinity.kind() != LegacyAffinity.Kind.NONE ? frame.affinity : affinityOfThread();
    }

    // ------------------------------------------------------------------ entry points

    /**
     * Enter the domain of the plugin behind {@code registration} for one synchronous listener call.
     *
     * @return the frame to {@link #exit}, or {@code null} when no domain applies (native plugin,
     * asynchronous event, runtime off)
     */
    public static Frame enterListener(final RegisteredListener registration, final Event event) {
        if (event.isAsynchronous()) {
            // Paper ran asynchronous listeners concurrently with the main thread; so do we.
            return null;
        }
        final LegacyPluginState state = stateOf(registration.getPlugin());
        if (state == null) {
            return null;
        }
        state.listenerCalls.increment();
        return enter(state, affinityOf(event), false);
    }

    /**
     * Enter the domain of the plugin that owns {@code command}, for execution or tab completion.
     */
    public static Frame enterCommand(final Command command, final CommandSender sender) {
        if (!(command instanceof PluginIdentifiableCommand identifiable)) {
            return null;
        }
        final LegacyPluginState state = stateOf(identifiable.getPlugin());
        if (state == null) {
            return null;
        }
        state.commandCalls.increment();
        final LegacyAffinity affinity = sender instanceof Entity entity && usableEntity(entity)
                ? LegacyAffinity.entity(entity) : affinityOfThread();
        return enter(state, affinity, false);
    }

    /**
     * Enter {@code plugin}'s domain for an enable or disable, so a lifecycle transition never
     * overlaps the plugin's own running work.
     */
    public static Frame enterLifecycle(final Plugin plugin) {
        final LegacyPluginState state = stateOf(plugin);
        if (state == null) {
            return null;
        }
        state.lifecycleCalls.increment();
        return enter(state, affinityOfThread(), false);
    }

    /**
     * Enter a provider plugin's domain for one service call - from a synchronous context only.
     *
     * <p>Paper serialised a service call with the provider's own work only when both ran on the main
     * thread; a call from an asynchronous thread always ran concurrently with it. Keeping that exact
     * boundary matters beyond fidelity: making an async caller wait here would add a wait Paper never
     * had, and an async caller holding its own lock while it waits could then deadlock against the
     * provider calling back into it. So an async caller gets the provider directly, as on Paper.
     *
     * <p>{@code serialiseAsyncCallers} is the exception for an economy provider: see
     * {@link LegacyServiceBoundary}.
     *
     * @return the frame to {@link #exit}, or {@code null} for an asynchronous caller
     */
    static Frame enterService(final LegacyPluginState state, final boolean serialiseAsyncCallers) {
        if (!serialiseAsyncCallers && !ca.spottedleaf.moonrise.common.util.TickThread.isTickThread() && !isLegacyLane()) {
            return null;
        }
        state.serviceCalls.increment();
        final Frame parent = FRAME.get();
        return enter(state, parent != null ? parent.affinity : affinityOfThread(), false);
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * The plugin finished enabling: open its lane (it may be a re-enable after a reload).
     */
    public static void enabled(final Plugin plugin) {
        final LegacyPluginState state = stateOf(plugin);
        if (state != null) {
            state.lane.reopen();
        }
    }

    /**
     * The plugin is disabled: close its lane and forget it, so a later instance of the same plugin
     * starts clean and the old one can be collected. Its tasks are cancelled by the scheduler's
     * disable path through {@code LecithinDispatchedTasks}.
     */
    public static void disabled(final Plugin plugin) {
        final Object removed = PLUGINS.remove(plugin);
        if (removed instanceof LegacyPluginState state) {
            state.lane.close();
        }
    }

    // ------------------------------------------------------------------ diagnostics

    public static List<LegacyPluginState> managed() {
        final List<LegacyPluginState> out = new ArrayList<>();
        for (final Object value : PLUGINS.values()) {
            if (value instanceof LegacyPluginState state) {
                out.add(state);
            }
        }
        out.sort((a, b) -> a.plugin.getName().compareToIgnoreCase(b.plugin.getName()));
        return out;
    }

    public static List<Plugin> nativePlugins() {
        final List<Plugin> out = new ArrayList<>();
        for (final Map.Entry<Plugin, Object> entry : PLUGINS.entrySet()) {
            if (entry.getValue() == NATIVE) {
                out.add(entry.getKey());
            }
        }
        out.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return out;
    }
}
