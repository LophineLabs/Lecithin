package fun.bm.lecithin.compat.legacy;

import ca.spottedleaf.moonrise.common.util.TickThread;
import io.papermc.paper.threadedregions.RegionizedServer;
import io.papermc.paper.threadedregions.TickRegionScheduler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.bukkit.Bukkit;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Synchronous owner operations for platform-established managed frames. API bodies run only under
 * real Folia ownership, with unchanged TickThread checks. Results and exceptions cross the boundary
 * after completion. Managed event callbacks return to the original JVM thread through LegacyCalls.
 *
 * Idle regions may be exclusively acquired without consuming an EDF worker. Busy owners service
 * requests while waiting in a managed call. Acquisition is always try-only; no region lock is held
 * while blocking on a second region lock. Native plugin entrypoints clear the managed frame.
 *
 * <p>An operation whose target has no owner that may run it - the entity is retired or between
 * owners (teleport, respawn, logout handover), its world is unloading, the plugin is disabled or
 * the server is stopping - is cancelled before its body starts and throws {@link
 * OwnerOperationCancelledException}. Cancellation is an answer about this one call, never about the
 * target's state; a failure inside a body that did run is rethrown unchanged.
 */
public final class LegacyOwnerOperations {
    private static final Set<Request<?>> PENDING = ConcurrentHashMap.newKeySet();
    private static final ExecutorService CARRIERS = Executors.newCachedThreadPool(TickRegionScheduler::newLegacyOwnerThread);
    private static volatile boolean closing;
    static { LegacyCalls.progress = LegacyOwnerOperations::poll; }

    private LegacyOwnerOperations() {}

    public static void beginShutdown() {
        closing = true;
        for (final Request<?> request : PENDING) request.attempt();
    }

    /** Unloading from a callback cannot wait for the very operation which invoked that callback. */
    public static boolean isCallingOperationIn(final ServerLevel world) {
        final LegacyCalls.Channel caller = LegacyCalls.current();
        for (final Request<?> request : PENDING) {
            if (request.callback == caller && request.claimed.get() && !request.result.isDone() && request.target.world() == world) return true;
        }
        return false;
    }

    public static boolean needed(final Entity entity) {
        return LegacyPluginRuntime.currentFrame() != null && !TickThread.isTickThreadFor(entity);
    }
    public static boolean needed(final ServerLevel world, final int chunkX, final int chunkZ) {
        return LegacyPluginRuntime.currentFrame() != null && !TickThread.isTickThreadFor(world, chunkX, chunkZ);
    }

    /**
     * Standard entity events retain their declared owner's native listener context. An entity with
     * no owner right now (retired, or between owners during a teleport, respawn or logout handover)
     * has no such context: the event is dispatched where it was called, as the platform dispatches
     * the transition events around it. The event itself is never dropped.
     */
    public static boolean dispatchEvent(final org.bukkit.event.Event event, final Runnable dispatch) {
        if (event.isAsynchronous() || LegacyPluginRuntime.currentFrame() == null) return false;
        final org.bukkit.entity.Entity owner;
        if (event instanceof org.bukkit.event.player.PlayerEvent player) owner = player.getPlayer();
        else if (event instanceof org.bukkit.event.entity.EntityEvent entity) owner = entity.getEntity();
        else return false;
        final Entity handle = ((org.bukkit.craftbukkit.entity.CraftEntity) owner).getHandleRaw();
        if (handle.isRemoved() || !needed(handle)) return false;
        return dispatchOnOwner(new EntityTarget(handle), dispatch);
    }

    static boolean dispatchOnOwner(final Target owner, final Runnable dispatch) {
        final boolean[] started = {false};
        try {
            call(new Request<>(owner, () -> { started[0] = true; dispatch.run();return null; }));
        } catch (final OwnerOperationCancelledException cancelled) {
            // Retired while queued: no listener ran, so dispatching here delivers it exactly once.
            if (started[0] || cancelled.reason() != OwnerOperationCancelledException.Reason.TARGET_RETIRED) throw cancelled;
            return false;
        }
        return true;
    }

    public static <T> T entity(final Entity entity, final Supplier<T> body) {
        return call(new Request<>(new EntityTarget(entity), body));
    }
    public static <T> T region(final ServerLevel world, final int chunkX, final int chunkZ, final Supplier<T> body) {
        return call(new Request<>(new ChunkTarget(world, chunkX, chunkZ), body));
    }

    static <T> T call(final Target target, final Supplier<T> body) {
        return call(new Request<>(target, body));
    }

    private static <T> T call(final Request<T> request) {
        if (request.frame == null) throw new IllegalStateException("Cross-owner operation without a managed execution frame");
        PENDING.add(request);
        Runnable cancel = () -> {};
        try {
            cancel = request.target.queue(request::attempt);
            // A lane has no owner. Its carrier only executes an API body after acquiring a real one.
            // Tick callers can make the same try-only acquisition themselves inside await's pump.
            CARRIERS.execute(() -> {
                try { LegacyCalls.await(request.result, request::attempt, () -> null); }
                catch (final Throwable failure) { /* The synchronous caller receives this same failure. */ }
            });
            return LegacyCalls.await(request.result, request::attempt, () -> request.executor);
        } finally {
            cancel.run();
            PENDING.remove(request);
        }
    }

    private static void poll() {
        // ponytail: linear in outstanding synchronous calls; shard by region if contention warrants it.
        for (final Request<?> request : PENDING) {
            if (request.attempt()) return;
        }
    }

    /**
     * What an operation needs from the owner of its target. {@link EntityTarget} and {@link
     * ChunkTarget} are the regionised server; tests stand in for it without one.
     */
    interface Target {
        /** The world an unload of which must not wait for this operation. */
        ServerLevel world();
        boolean serverStopped();
        boolean worldUnloaded();
        boolean retired();
        /** Whether the current thread owns the target now. */
        boolean owned();
        /** Acquire the target's owner try-only and run {@code operation} under it, or return without running it. */
        void tryAcquire(Runnable operation);
        /** Queue {@code attempt} on the owner's tick queue; the result removes it again. */
        Runnable queue(Runnable attempt);
        String describe();
    }

    private abstract static class ServerTarget implements Target {
        abstract int chunkX();
        abstract int chunkZ();
        abstract String describeTarget();

        @Override public boolean serverStopped() { return MinecraftServer.getServer().isStopped(); }
        @Override public boolean worldUnloaded() {
            final ServerLevel world = this.world();
            return world.levelUnloadStateLock.isReadReferencingBlocked()
                    || Bukkit.getWorld(world.getWorld().getUID()) != world.getWorld();
        }
        @Override public void tryAcquire(final Runnable operation) {
            TickRegionScheduler.tryLegacyOwnerOperation(this.world(), this.chunkX(), this.chunkZ(), operation);
        }
        @Override public Runnable queue(final Runnable attempt) {
            final var task = RegionizedServer.getInstance().taskQueue.queueTickTaskQueue(this.world(), this.chunkX(), this.chunkZ(), attempt);
            return () -> task.cancel();
        }
        @Override public String describe() { return this.describeTarget() + " in '" + this.world().getWorld().getName() + "'"; }
    }

    private static final class EntityTarget extends ServerTarget {
        final Entity entity;
        EntityTarget(final Entity entity) { this.entity = entity; }
        @Override public ServerLevel world() { return (ServerLevel) this.entity.level(); }
        @Override int chunkX() { return this.entity.chunkPosition().x(); }
        @Override int chunkZ() { return this.entity.chunkPosition().z(); }
        @Override public boolean retired() { return this.entity.isRemoved(); }
        @Override public boolean owned() { return TickThread.isTickThreadFor(this.entity); }
        @Override String describeTarget() {
            return net.minecraft.world.entity.EntityType.getKey(this.entity.getType()) + " " + this.entity.getUUID()
                    + (this.entity.getRemovalReason() == null ? "" : " (removed: " + this.entity.getRemovalReason() + ")");
        }
    }

    private static final class ChunkTarget extends ServerTarget {
        final ServerLevel world;
        final int x, z;
        ChunkTarget(final ServerLevel world, final int x, final int z) { this.world = world; this.x = x; this.z = z; }
        @Override public ServerLevel world() { return this.world; }
        @Override int chunkX() { return this.x; }
        @Override int chunkZ() { return this.z; }
        @Override public boolean retired() { return false; }
        @Override public boolean owned() { return TickThread.isTickThreadFor(this.world, this.x, this.z); }
        @Override String describeTarget() { return "chunk [" + this.x + ", " + this.z + "]"; }
    }

    private static final class Request<T> {
        final Target target;
        final Supplier<T> body;
        final LegacyPluginRuntime.Frame frame = LegacyPluginRuntime.currentFrame();
        final LegacyCalls.Channel callback = LegacyCalls.RETURN_TO.get() == null ? LegacyCalls.current() : LegacyCalls.RETURN_TO.get();
        final CompletableFuture<T> result = new CompletableFuture<>();
        final AtomicBoolean claimed = new AtomicBoolean();
        // The thread running the body, while it runs: the requester's wait-for edge.
        volatile LegacyCalls.Channel executor;
        T value;
        Throwable failure;

        Request(final Target target, final Supplier<T> body) { this.target = target; this.body = body; }

        OwnerOperationCancelledException.Reason cancellation() {
            if (closing || this.target.serverStopped()) return OwnerOperationCancelledException.Reason.SERVER_STOPPING;
            if (!this.frame.state.plugin.isEnabled()) return OwnerOperationCancelledException.Reason.PLUGIN_DISABLED;
            if (this.target.worldUnloaded()) return OwnerOperationCancelledException.Reason.WORLD_UNLOADED;
            if (this.target.retired()) return OwnerOperationCancelledException.Reason.TARGET_RETIRED;
            return null;
        }

        String describe() {
            return this.target.describe() + " for " + this.frame.state.plugin.getName() + " from " + Thread.currentThread().getName();
        }

        boolean attempt() {
            if (this.claimed.get()) return false;
            final OwnerOperationCancelledException.Reason cancelled = this.cancellation();
            if (cancelled != null) {
                if (this.claimed.compareAndSet(false,true)) this.result.completeExceptionally(
                        new OwnerOperationCancelledException(cancelled, this.describe()));
                return false;
            }
            if (this.target.owned()) {
                if (!this.execute()) return false;
                this.finish();
                return true;
            }
            final boolean[] executed = {false};
            try {
                this.target.tryAcquire(() -> executed[0] = this.execute());
            } catch (final Throwable failed) {
                if (executed[0]) this.failure = failed;
                else if (this.claimed.compareAndSet(false,true)) this.result.completeExceptionally(failed);
            }
            if (executed[0]) this.finish();
            return executed[0];
        }

        boolean execute() {
            // Routing coordinates can become stale while an entity moves. Check the actual owner
            // again AFTER acquisition, before claiming the request or touching its mutable state.
            if (!this.target.owned() || !this.claimed.compareAndSet(false,true)) return false;
            final var previous = LegacyCalls.RETURN_TO.get();
            LegacyCalls.RETURN_TO.set(this.callback);
            this.executor = LegacyCalls.current();
            try {
                this.value = LegacyPluginRuntime.withFrame(this.frame, this.body);
            } catch (final Throwable failed) {
                this.failure = failed;
            } finally {
                this.executor = null;
                if (previous == null) LegacyCalls.RETURN_TO.remove(); else LegacyCalls.RETURN_TO.set(previous);
            }
            return true;
        }

        void finish() {
            if (this.failure == null) this.result.complete(this.value);
            else this.result.completeExceptionally(this.failure);
        }
    }

    /**
     * An owner operation that was cancelled before its body started: nothing of it ran and no state
     * was read or changed. It is still an {@link IllegalStateException}, as the rejection was before.
     * A platform lookup may answer for that one call from it (a player between owners is not visible
     * to it); nothing may treat it as the target's actual state.
     */
    public static final class OwnerOperationCancelledException extends java.util.concurrent.CancellationException {
        public enum Reason { SERVER_STOPPING, PLUGIN_DISABLED, WORLD_UNLOADED, TARGET_RETIRED }

        private final Reason reason;

        OwnerOperationCancelledException(final Reason reason, final String detail) {
            super("Legacy owner operation cancelled before execution (" + reason + "): " + detail);
            this.reason = reason;
        }

        public Reason reason() { return this.reason; }
    }
}
