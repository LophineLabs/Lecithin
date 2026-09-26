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
            if (request.callback == caller && request.claimed.get() && !request.result.isDone() && request.world() == world) return true;
        }
        return false;
    }

    public static boolean needed(final Entity entity) {
        return LegacyPluginRuntime.currentFrame() != null && !TickThread.isTickThreadFor(entity);
    }
    public static boolean needed(final ServerLevel world, final int chunkX, final int chunkZ) {
        return LegacyPluginRuntime.currentFrame() != null && !TickThread.isTickThreadFor(world, chunkX, chunkZ);
    }

    /** Standard entity events retain their declared owner's native listener context. */
    public static boolean dispatchEvent(final org.bukkit.event.Event event, final Runnable dispatch) {
        if (event.isAsynchronous() || LegacyPluginRuntime.currentFrame() == null) return false;
        final org.bukkit.entity.Entity owner;
        if (event instanceof org.bukkit.event.player.PlayerEvent player) owner = player.getPlayer();
        else if (event instanceof org.bukkit.event.entity.EntityEvent entity) owner = entity.getEntity();
        else return false;
        final Entity handle = ((org.bukkit.craftbukkit.entity.CraftEntity) owner).getHandleRaw();
        if (!needed(handle)) return false;
        entity(handle, () -> { dispatch.run();return null; });
        return true;
    }

    public static <T> T entity(final Entity entity, final Supplier<T> body) {
        return call(new Request<>(entity, null, 0, 0, body));
    }
    public static <T> T region(final ServerLevel world, final int chunkX, final int chunkZ, final Supplier<T> body) {
        return call(new Request<>(null, world, chunkX, chunkZ, body));
    }

    private static <T> T call(final Request<T> request) {
        if (request.frame == null) throw new IllegalStateException("Cross-owner operation without a managed execution frame");
        PENDING.add(request);
        Runnable cancel = () -> {};
        try {
            final ServerLevel world = request.world();
            final var task = RegionizedServer.getInstance().taskQueue.queueTickTaskQueue(world,
                    request.chunkX(), request.chunkZ(), request::attempt);
            cancel = () -> task.cancel();
            // A lane has no owner. Its carrier only executes an API body after acquiring a real one.
            // Tick callers can make the same try-only acquisition themselves inside await's pump.
            CARRIERS.execute(() -> {
                try { LegacyCalls.await(request.result, request::attempt); }
                catch (final Throwable failure) { /* The synchronous caller receives this same failure. */ }
            });
            return LegacyCalls.await(request.result, request::attempt);
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

    private static final class Request<T> {
        final Entity entity;
        final ServerLevel fixedWorld;
        final int fixedX, fixedZ;
        final Supplier<T> body;
        final LegacyPluginRuntime.Frame frame = LegacyPluginRuntime.currentFrame();
        final LegacyCalls.Channel callback = LegacyCalls.RETURN_TO.get() == null ? LegacyCalls.current() : LegacyCalls.RETURN_TO.get();
        final CompletableFuture<T> result = new CompletableFuture<>();
        final AtomicBoolean claimed = new AtomicBoolean();
        T value;
        Throwable failure;

        Request(final Entity entity, final ServerLevel world, final int x, final int z, final Supplier<T> body) {
            this.entity = entity; this.fixedWorld = world; this.fixedX = x; this.fixedZ = z; this.body = body;
        }
        ServerLevel world() { return this.entity == null ? this.fixedWorld : (ServerLevel) this.entity.level(); }
        int chunkX() { return this.entity == null ? this.fixedX : this.entity.chunkPosition().x(); }
        int chunkZ() { return this.entity == null ? this.fixedZ : this.entity.chunkPosition().z(); }
        boolean owned() { return this.entity == null ? TickThread.isTickThreadFor(this.fixedWorld,this.fixedX,this.fixedZ) : TickThread.isTickThreadFor(this.entity); }

        boolean attempt() {
            if (this.claimed.get()) return false;
            final ServerLevel world = this.world();
            if (closing || MinecraftServer.getServer().isStopped() || !this.frame.state.plugin.isEnabled()
                    || world.levelUnloadStateLock.isReadReferencingBlocked()
                    || Bukkit.getWorld(world.getWorld().getUID()) != world.getWorld()
                    || (this.entity != null && this.entity.isRemoved())) {
                if (this.claimed.compareAndSet(false,true)) this.result.completeExceptionally(
                        new IllegalStateException("Legacy owner operation rejected before execution: plugin disabled, entity retired, world unloaded or server stopped"));
                return false;
            }
            if (this.owned()) {
                if (!this.execute()) return false;
                this.finish();
                return true;
            }
            final boolean[] executed = {false};
            try {
                TickRegionScheduler.tryLegacyOwnerOperation(world, this.chunkX(), this.chunkZ(), () -> executed[0] = this.execute());
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
            if (!this.owned() || !this.claimed.compareAndSet(false,true)) return false;
            final var previous = LegacyCalls.RETURN_TO.get();
            LegacyCalls.RETURN_TO.set(this.callback);
            try {
                this.value = LegacyPluginRuntime.withFrame(this.frame, this.body);
            } catch (final Throwable failed) {
                this.failure = failed;
            } finally {
                if (previous == null) LegacyCalls.RETURN_TO.remove(); else LegacyCalls.RETURN_TO.set(previous);
            }
            return true;
        }

        void finish() {
            if (this.failure == null) this.result.complete(this.value);
            else this.result.completeExceptionally(this.failure);
        }
    }
}
