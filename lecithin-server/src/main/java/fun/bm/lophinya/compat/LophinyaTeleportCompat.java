package fun.bm.lophinya.compat;

import com.mojang.logging.LogUtils;
import io.papermc.paper.entity.TeleportFlag;
import org.bukkit.Location;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lophinya: restores {@code Entity#teleport(Location, ...)} at the Bukkit API boundary.
 *
 * <h2>The gap</h2>
 * On Paper, {@code Entity#teleport(Location)} moves the entity there and then, on the calling
 * thread, and returns whether it worked. Folia replaces the whole method body with an
 * unconditional {@code throw new UnsupportedOperationException("Must use teleportAsync while in
 * region threading")} - not "throws when it would be unsafe", but <b>always</b>, from every thread,
 * even when the destination is a loaded chunk the calling thread already owns and the move is
 * therefore trivially safe.
 *
 * <p>This is grouped by API symbol, not by plugin, and deliberately so: every Paper plugin that
 * teleports anything calls this method, and there is nothing plugin-specific about the gap or the
 * fix. Anything keyed on plugin names or jar hashes would make "runs any Paper plugin" mean "runs
 * the plugins someone remembered to add to a list".
 *
 * <h2>What this does</h2>
 * Delegate to the platform's own {@link CraftEntity#teleportAsync}, which already resolves region
 * ownership correctly, loads the destination chunk, and hands the entity over between regions and
 * worlds. Then read the resulting future <b>without waiting on it</b>:
 *
 * <ul>
 *   <li><b>Already completed</b> - Folia took its same-region fast path
 *       ({@code Entity#teleportAsync}: same world, this thread owns the destination chunk, chunks
 *       loaded) and performed the move inline. The returned value is the platform's real answer, so
 *       this is Paper's observable behaviour exactly, <i>including</i> {@code false} when the
 *       platform refused (dead entity, has passengers, outside world bounds).</li>
 *   <li><b>Not completed</b> - the destination belongs to another region or another world, or its
 *       chunks are not loaded. Folia cannot move the entity on this thread, and this method must
 *       not wait: the caller <i>is</i> its region's tick, so blocking here stops that region. The
 *       teleport has been accepted and will complete on the owning region's thread, so this reports
 *       {@code true}, and logs once per callsite that the result was deferred.</li>
 * </ul>
 *
 * <h2>What it does not do</h2>
 * No ownership or thread-safety check is disabled, bypassed or swallowed - the work is done by the
 * platform's async teleport, which performs all of them. Nothing is dispatched to the global region
 * scheduler, and no owner is guessed from a contextless runnable: the entity itself is the context.
 *
 * <h2>The one bounded semantic difference</h2>
 * In the deferred case the entity is not yet at the destination when the call returns. A caller
 * that teleports and then immediately reads the entity's position sees the old position for up to
 * one tick of the owning region. That difference is the reason for the log line. It is strictly
 * better than the alternative it replaces, which is that the call never works at all: the previous
 * behaviour was not "deferred", it was an exception, and for callers that wrap the teleport in a
 * future (EssentialsX does) the exception was swallowed and the player simply did not move.
 *
 * <p>{@link TeleportFlag}s are passed on to {@code teleportAsync}, which - on Folia - ignores them
 * and always uses {@code TELEPORT_FLAG_LOAD_CHUNK | TELEPORT_FLAG_TELEPORT_PASSENGERS}. That is a
 * pre-existing property of the platform's async teleport, not something introduced here; relative
 * teleport flags are therefore not honoured on this path.
 *
 * <p>Kill switch: {@code -Dlophinya.compat.teleportSemantics=false} restores the stock Folia
 * behaviour, an unconditional throw from both {@code CraftEntity} and {@code CraftPlayer}.
 */
public final class LophinyaTeleportCompat {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Stock Folia's message, kept verbatim so the kill switch is a true revert. */
    public static final String STOCK_MESSAGE = "Must use teleportAsync while in region threading";

    /** One log line per distinct callsite; a per-tick teleport would otherwise flood the log. */
    private static final Set<String> DEFERRED_REPORTED = ConcurrentHashMap.newKeySet();

    private LophinyaTeleportCompat() {
    }

    /**
     * @param entity   the entity being teleported, already validated by the caller
     * @param location destination, already validated by the caller
     * @param cause    teleport cause to report to events
     * @param flags    Paper teleport flags (see the class note: not honoured on the async path)
     * @return true if the teleport happened or was accepted, false if the platform refused it
     */
    public static boolean teleport(final CraftEntity entity, final Location location,
                                   final TeleportCause cause, final TeleportFlag... flags) {
        final CompletableFuture<Boolean> result = entity.teleportAsync(location, cause, flags);
        if (result.isDone()) {
            // Same region and loaded: the platform already did it, so hand back its real answer.
            // getNow cannot block here - isDone() is true - and a completed-exceptionally future
            // rethrows, which is what we want: a genuine platform failure must not read as success.
            return result.join();
        }
        reportDeferred(entity, location);
        return true;
    }

    private static void reportDeferred(final CraftEntity entity, final Location location) {
        // Diagnostics must never be the reason a teleport fails.
        try {
            final Plugin plugin = io.papermc.paper.util.StackWalkerUtil.getFirstPluginCaller();
            final String callsite = callsite();
            if (!DEFERRED_REPORTED.add((plugin == null ? "<unknown>" : plugin.getName()) + '|' + callsite)) {
                return;
            }
            LOGGER.info("""
                [Lophinya] Entity#teleport deferred to the owning region
                  plugin   : {}
                  callsite : {}
                  entity   : {} in {}
                  target   : {} at {}, {}, {}
                  why      : the destination is in another region or world, or its chunks are not
                             loaded, so Folia cannot move the entity on this thread. The teleport
                             was accepted via the platform's async path and completes on the owning
                             region's thread; teleport() returned true before the entity moved.
                             Paper would have moved it before returning.""",
                plugin == null ? "<unknown>" : plugin.getName() + " v" + version(plugin),
                callsite,
                entity.getType(), entity.getWorld().getName(),
                location.getWorld() == null ? "<null world>" : location.getWorld().getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ());
        } catch (final Throwable t) {
            LOGGER.warn("[Lophinya] teleport diagnostics failed (harmless)", t);
        }
    }

    /** Innermost frame that is neither this class nor the CraftBukkit entity plumbing. */
    private static String callsite() {
        return StackWalker.getInstance().walk(frames -> frames
            .filter(f -> !f.getClassName().startsWith("fun.bm.lophinya.compat."))
            .filter(f -> !f.getClassName().startsWith("org.bukkit.craftbukkit.entity."))
            .findFirst()
            .map(f -> f.getClassName() + '.' + f.getMethodName() + '(' + f.getFileName() + ':' + f.getLineNumber() + ')')
            .orElse("<unknown>"));
    }

    private static String version(final Plugin plugin) {
        try {
            return plugin.getPluginMeta().getVersion();
        } catch (final Throwable ignored) {
            return "<unknown>";
        }
    }
}
