package fun.bm.lecithin.compat;

import fun.bm.lecithin.config.modules.CompatConfig;
import io.papermc.paper.threadedregions.TickRegionScheduler;
import io.papermc.paper.threadedregions.TickRegions;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.player.PlayerEvent;

/**
 * Lecithin: the one model of "on whose behalf is this thread currently executing".
 *
 * <h2>Why this exists</h2>
 * Paper's legacy scheduler API ({@code scheduleSyncDelayedTask}, {@code runTask}, {@code runTaskLater},
 * {@code callSyncMethod}) means "the main thread, later". Folia has no main thread, so every one of
 * those calls is rejected. The rejection is not a diagnostic - it propagates out of whatever the
 * plugin was doing, so a single legacy call aborts a whole event handler or a whole {@code onEnable}.
 *
 * <p>The only honest way to answer "which Folia scheduler did the plugin actually mean" without
 * reading the plugin's mind is to answer <b>on whose behalf the calling thread is running right
 * now</b>. That is a fact the platform already knows; this class is where that fact is defined,
 * captured, propagated and cleared, so that no other class has to re-derive it.
 *
 * <h2>Owner types</h2>
 * <ul>
 *   <li>{@link OwnerKind#REGION} - the thread is ticking a region. The task belongs to that region,
 *       identified by a chunk that region owns. Paper promised "the thread you are on, later"; on
 *       Folia the nearest true statement is "the region you are on, later".</li>
 *   <li>{@link OwnerKind#GLOBAL} - the thread is the global region tick thread, or the server
 *       bootstrap thread during startup (whose successor for server-wide state is the global
 *       region). The task belongs to the global region.</li>
 *   <li>{@link OwnerKind#ENTITY} - the platform is currently running plugin code on behalf of one
 *       named entity, on a thread that owns nothing. Today the only source of this is an
 *       asynchronous Bukkit event that names exactly one {@link Player}; see
 *       {@link #ownerOfAsyncEvent(Event)}. The task belongs to that entity, and Folia's own
 *       {@code EntityScheduler} is the API for exactly that.</li>
 * </ul>
 * <p>
 * There is deliberately no "unknown" owner. Absence is represented by {@code null} and means
 * <b>fail closed</b>: the caller falls through to stock Folia rejection rather than guessing.
 *
 * <h2>Capture</h2>
 * Two, and only two, ways an owner comes into existence:
 * <ol>
 *   <li><b>Observed from the thread</b> ({@link #threadOwner()}). Region tick thread, global tick
 *       thread and bootstrap thread are asked directly, at the moment of the question. Nothing is
 *       remembered and nothing can go stale.</li>
 *   <li><b>Established by the platform for the duration of a call</b> ({@link #runWithScope}). Used
 *       where the platform itself knows whose work this is even though the thread does not - an
 *       async event dispatch, and the body of an async task that inherited its scheduler's owner.</li>
 * </ol>
 * The thread's own answer always wins ({@link #effectiveOwner()}): a scope is only ever consulted
 * when the thread has no context of its own, which is precisely the case stock Folia refuses today.
 *
 * <h2>Lifetime and clearing</h2>
 * A scope lasts exactly the dynamic extent of one {@link #runWithScope} call and is restored in a
 * {@code finally}. This matters because every thread that can carry a scope is pooled and reused:
 * Paper dispatches {@code AsyncPlayerChatEvent} on {@code "Async Chat Thread - #N"}, a cached pool
 * shared by every player on the server. A leaked scope would hand the next chat message a region
 * belonging to a different player, which is the exact inference this class exists to prevent - so
 * the ThreadLocal is a plain {@link ThreadLocal} (never inheritable) and is always removed, not
 * merely overwritten, when no outer scope was present.
 *
 * <h2>Inheritance across an async hop</h2>
 * {@link #captureForAsyncHandoff()} is called on the thread that <i>creates</i> an async task, so
 * the owner it records is a fact at that moment rather than something deduced later. It refuses to
 * capture an owner that was itself inherited, which bounds propagation to <b>one</b> hop:
 *
 * <pre>
 *   region/global/entity context  --hop 1-->  async task body       (inherits)
 *   async task body               --hop 2-->  another async task    (does NOT inherit, fails closed)
 * </pre>
 * <p>
 * The bound is not arbitrary. Each hop is an unbounded amount of wall-clock time during which the
 * captured owner becomes less likely to still be the right answer, and nothing in the chain would
 * ever restore accuracy - so the chain is cut where it can still be justified.
 *
 * <h2>What an owner does not promise</h2>
 * An owner says where a task should run, not that the task is safe. A task that reaches into
 * another region's state still hits {@code TickThread.ensureTickThread} and still fails, loudly, at
 * the real access, with the plugin and callsite named. Nothing here weakens an ownership check;
 * what changes is only that a task which was never given a chance to run now gets one.
 */
public final class LecithinExecutionProvenance {

    /**
     * Whose work a thread is doing. See the class javadoc for what each one means and why.
     */
    public enum OwnerKind {
        REGION,
        GLOBAL,
        ENTITY
    }

    /**
     * @param kind        which of the three owner types this is
     * @param world       {@link OwnerKind#REGION} only: the world the chunk below belongs to
     * @param chunkX      {@link OwnerKind#REGION} only: a chunk X the owning region owns
     * @param chunkZ      {@link OwnerKind#REGION} only: a chunk Z the owning region owns
     * @param entity      {@link OwnerKind#ENTITY} only: the entity whose work this is
     * @param description human-readable provenance, for the one log line per distinct callsite
     * @param inherited   {@code true} once this owner has crossed an async hop; such an owner is
     *                    still usable for dispatch but may not be captured again, which is what
     *                    bounds propagation to a single hop
     */
    public record Owner(OwnerKind kind, World world, int chunkX, int chunkZ, Entity entity,
                        String description, boolean inherited) {

        static Owner ofRegion(final World world, final int chunkX, final int chunkZ) {
            return new Owner(OwnerKind.REGION, world, chunkX, chunkZ, null,
                    "region owning " + world.getName() + " chunk [" + chunkX + ", " + chunkZ + ']', false);
        }

        static Owner ofGlobal(final String why) {
            return new Owner(OwnerKind.GLOBAL, null, 0, 0, null, "global region (" + why + ')', false);
        }

        static Owner ofEntity(final Entity entity, final String why) {
            return new Owner(OwnerKind.ENTITY, null, 0, 0, entity,
                    "the scheduler of " + entity.getType() + ' ' + entity.getName() + " (" + why + ')', false);
        }

        /**
         * The same owner, marked as having crossed one async hop.
         */
        Owner asInherited() {
            return this.inherited ? this
                    : new Owner(this.kind, this.world, this.chunkX, this.chunkZ, this.entity,
                    this.description + ", inherited from the thread that scheduled this async task", true);
        }
    }

    /**
     * The owner the platform established for the current call, or {@code null}.
     *
     * <p>A plain {@link ThreadLocal} on purpose: {@link InheritableThreadLocal} would hand a newly
     * created thread an owner nobody asked it to carry, with no scope to ever clear it.
     */
    private static final ThreadLocal<Owner> SCOPE = new ThreadLocal<>();

    private LecithinExecutionProvenance() {
    }

    /**
     * The owner observable from the calling thread itself, asked right now, or {@code null} when
     * this thread is not ticking anything.
     */
    public static Owner threadOwner() {
        try {
            final Owner region = regionOwner();
            if (region != null) {
                return region;
            }
            if (Bukkit.isGlobalTickThread()) {
                return Owner.ofGlobal("the caller was the global region");
            }
            if (CompatConfig.startupContextDispatch && callerIsStartupThread()) {
                // The bootstrap thread has no region, but its successor for server-wide state is the
                // global region: Paper runs what this thread schedules on the main thread as init
                // completes, and the global region is that thread's counterpart here.
                return Owner.ofGlobal("scheduled during startup on the bootstrap thread");
            }
            return null;
        } catch (final Throwable t) {
            return null;
        }
    }

    /**
     * The owner in force for this call: the thread's own answer when it has one, otherwise whatever
     * the platform established for the current scope. {@code null} means nothing is known, which
     * callers must treat as "reject", never as "assume something safe".
     */
    public static Owner effectiveOwner() {
        final Owner direct = threadOwner();
        return direct != null ? direct : SCOPE.get();
    }

    /**
     * The owner to record on an async task being created on this thread, or {@code null} for none.
     * Called on the scheduling thread, which is the only moment the answer is a fact.
     *
     * <p>Returns {@code null} for an already-inherited owner - see the class javadoc on why
     * propagation stops after one hop.
     */
    public static Owner captureForAsyncHandoff() {
        if (!CompatConfig.callerContextDispatch || !CompatConfig.asyncContextInheritance) {
            return null;
        }
        final Owner owner = effectiveOwner();
        if (owner == null || owner.inherited()) {
            return null;
        }
        return owner.asInherited();
    }

    /**
     * Runs {@code body} with {@code owner} visible to {@link #effectiveOwner()}, then restores the
     * previous scope. A {@code null} owner is a plain call, so the common path costs nothing.
     */
    public static void runWithScope(final Owner owner, final Runnable body) {
        if (owner == null) {
            body.run();
            return;
        }
        final Owner previous = SCOPE.get();
        SCOPE.set(owner);
        try {
            body.run();
        } finally {
            if (previous == null) {
                SCOPE.remove();
            } else {
                SCOPE.set(previous);
            }
        }
    }

    /**
     * The owner to establish while dispatching {@code event} to its listeners, or {@code null} to
     * dispatch with no scope at all.
     *
     * <h2>The rule, and why it is not an inference</h2>
     * An asynchronous Bukkit event is dispatched on a thread that owns nothing, so a legacy
     * {@code scheduleSyncDelayedTask} from inside such a listener has, today, nowhere to go and is
     * rejected - taking the rest of the listener with it. But the platform is not in the dark about
     * whose work it is: an {@link PlayerEvent} names exactly one {@link Player}, and that naming
     * comes from the platform's own dispatch, not from anything inside the plugin's runnable. What
     * this method asserts is only that the plugin code about to run was invoked on behalf of that
     * player - which is what {@code PlayerEvent} means.
     *
     * <p>The target that follows from it is Folia's own {@code EntityScheduler}, whose documented
     * purpose is scheduling for one entity "from any thread context", surviving the entity moving
     * between regions and worlds, and reporting failure rather than running when the entity is gone.
     * So this is not a shim standing in for a missing API; it is the API Folia provides for exactly
     * this question, finally reachable from the legacy call that was asking it.
     *
     * <h2>The other half: an async platform event that names nobody</h2>
     * Some asynchronous events the platform dispatches have no entity to name at all - the clearest
     * case being the connection phase, where {@code AsyncPlayerConnectionConfigureEvent} fires
     * before a {@link Player} object exists. On Paper a legacy sync call from such a listener meant
     * "the main thread"; here it was refused, which aborts the listener. Measured: EssentialsX's
     * backup timer is started from that event, so every single join logged
     * {@code Could not pass event AsyncPlayerConnectionConfigureEvent to Essentials}.
     *
     * <p>These events are dispatched on behalf of the <b>server</b>, not of a region, and the global
     * region is Folia's scheduler for server-scope work - the same reasoning already used for the
     * bootstrap thread in {@link #threadOwner()}, whose successor for server-wide state is likewise
     * the global region. It is a weaker claim than the entity case and is recorded as such: it says
     * where the work belongs, not that the work is region-safe. A task that does touch a region's
     * state still fails at the access, exactly as before.
     *
     * <p>This is <b>not</b> the "route any rejected sync task to the global region" shim that was
     * tested and rejected. It applies only inside a platform-dispatched asynchronous event, only
     * when the thread owns nothing of its own, and only when there is no entity to name - so it can
     * never relocate a task that had a region to run on.
     *
     * <h2>What it deliberately does not cover</h2>
     * <ul>
     *   <li>A synchronous event: the dispatching thread already owns the work, and
     *       {@link #threadOwner()} is the better and more precise answer.</li>
     *   <li>An async event for a player who is no longer online: their scheduler is retired or about
     *       to be, so naming them would promise a run that cannot happen.</li>
     *   <li>An event type the platform does not define. A plugin firing its own asynchronous event
     *       from its own thread is telling us nothing the platform can stand behind, so nothing is
     *       claimed and the call keeps failing closed. That package check is what keeps the
     *       server-scope branch from becoming "any async caller gets the global region".</li>
     * </ul>
     */
    public static Owner ownerOfAsyncEvent(final Event event) {
        if (!CompatConfig.callerContextDispatch || !CompatConfig.asyncEventProvenance) {
            return null;
        }
        try {
            if (!event.isAsynchronous()) {
                return null;
            }
            if (threadOwner() != null) {
                // This thread already owns something; its own context is more precise than anything
                // the event could tell us, and establishing a scope would only shadow it.
                return null;
            }
            if (event instanceof PlayerEvent playerEvent) {
                final Player player = playerEvent.getPlayer();
                if (player == null || !player.isOnline()) {
                    return null;
                }
                return Owner.ofEntity(player, "async " + event.getEventName() + " names exactly this player");
            }
            if (CompatConfig.asyncPlatformEventGlobalScope && isPlatformEvent(event)) {
                return Owner.ofGlobal("the caller was a server-scope async " + event.getEventName());
            }
            return null;
        } catch (final Throwable t) {
            return null;
        }
    }

    /**
     * Event types the platform itself defines, and therefore the only ones it can make a scope claim
     * about. A plugin's own event class lives in the plugin's own package and is excluded.
     */
    private static boolean isPlatformEvent(final Event event) {
        final String name = event.getClass().getName();
        return name.startsWith("org.bukkit.event.")
                || name.startsWith("io.papermc.paper.event.")
                || name.startsWith("com.destroystokyo.paper.event.");
    }

    /**
     * True when the calling thread is the server bootstrap thread. On this fork that identity means
     * "during startup", because once startup finishes nothing plugin-facing runs on it.
     */
    private static boolean callerIsStartupThread() {
        final net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
        return server != null && Thread.currentThread() == server.getRunningThread();
    }

    /**
     * A chunk the calling thread's region owns, or {@code null} if this thread is not ticking one.
     *
     * <p>Region membership in Folia is section-granular, so any coordinate inside a section this
     * region owns is owned by this region - which makes the first owned section as good an answer as
     * the geometric middle one, and far cheaper to obtain. The unsynchronised iterator is the
     * documented accessor for a thread that is ticking the region, and
     * {@link TickRegionScheduler#getCurrentRegion()} being non-null is exactly that condition.
     */
    private static Owner regionOwner() {
        final var region = TickRegionScheduler.getCurrentRegion();
        if (region == null) {
            return null;
        }
        final var worldData = TickRegionScheduler.getCurrentRegionizedWorldData();
        if (worldData == null) {
            return null;
        }
        final var sections = region.getOwnedSectionsUnsynchronised();
        if (!sections.hasNext()) {
            // A region mid-teardown can own no sections. Nothing to key a task on.
            return null;
        }
        final long sectionKey = sections.nextLong();
        final int shift = TickRegions.getRegionChunkShift();
        return Owner.ofRegion(
                worldData.world.getWorld(),
                ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkX(sectionKey) << shift,
                ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkZ(sectionKey) << shift
        );
    }
}
