package fun.bm.lecithin.compat;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.craftbukkit.scheduler.CraftTask;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lecithin: the registry of Folia tasks created on a plugin's behalf by the legacy-scheduler shim,
 * and the bridge that makes them cancellable through the Bukkit API the plugin actually used.
 *
 * <h2>Why a registry is needed at all</h2>
 * When {@link LecithinCallerContextDispatch} redispatches a rejected {@code scheduleSyncDelayedTask}
 * onto a region, the global region or an entity, the plugin is handed back the same
 * {@link org.bukkit.scheduler.BukkitTask} it would have got on Paper - but the thing that will
 * actually run is a Folia {@link ScheduledTask} that lives in none of {@code CraftScheduler}'s
 * queues. Two Paper guarantees break as a result, and both are restored here:
 *
 * <ol>
 *   <li><b>{@code BukkitTask.cancel()} stops the task.</b> {@code CraftScheduler.cancelTask(int)}
 *       looks the id up in its {@code runners} map, which a redispatched task was never in, so the
 *       cancel silently did nothing and a repeating task ran forever. {@link #cancelById(int)} and
 *       {@link #cancelAll(Plugin)} give the scheduler the missing lookup.</li>
 *   <li><b>Disabling a plugin stops its tasks.</b> Paper cancels a plugin's Bukkit tasks on disable;
 *       without this registry a redispatched repeating task would keep running a disabled plugin's
 *       code until the server stopped - the same bug already fixed once for the restored async
 *       scheduler.</li>
 * </ol>
 *
 * <p>Both cancel entry points return the {@link CraftTask}s they cancelled rather than cancelling
 * them outright, because {@code CraftTask.cancel0()} - the call that makes
 * {@code BukkitTask.isCancelled()} report the truth - is package-private to
 * {@code org.bukkit.craftbukkit.scheduler}. The scheduler patch that calls into here finishes the
 * job on the returned tasks.
 */
public final class LecithinDispatchedTasks {

    /**
     * @param craftTask the Bukkit-side task handed back to the plugin
     * @param scheduled the Folia task that will actually run it
     */
    private record Entry(CraftTask craftTask, ScheduledTask scheduled) {
    }

    private static final Map<Integer, Entry> BY_ID = new ConcurrentHashMap<>();
    private static final Map<Plugin, Map<Integer, Boolean>> BY_PLUGIN = new ConcurrentHashMap<>();

    private LecithinDispatchedTasks() {
    }

    /**
     * Register a redispatched task so it can be cancelled by id and stopped on plugin disable.
     */
    public static void track(final Plugin plugin, final CraftTask craftTask, final ScheduledTask scheduled) {
        final int id = craftTask.getTaskId();
        BY_ID.put(id, new Entry(craftTask, scheduled));
        BY_PLUGIN.computeIfAbsent(plugin, p -> new ConcurrentHashMap<>()).put(id, Boolean.TRUE);
    }

    /**
     * Forget a task that has finished on its own, so a one-shot task's id does not accumulate.
     */
    public static void forget(final Plugin plugin, final int taskId) {
        BY_ID.remove(taskId);
        final Map<Integer, Boolean> ids = BY_PLUGIN.get(plugin);
        if (ids != null) {
            ids.remove(taskId);
        }
    }

    /**
     * True when {@code taskId} belongs to a redispatched task that has not finished or been
     * cancelled. Lets {@code CraftScheduler.isQueued} answer honestly for these ids.
     */
    public static boolean isTracked(final int taskId) {
        return BY_ID.containsKey(taskId);
    }

    /**
     * Cancel the Folia task behind {@code taskId}, if it is one of ours.
     *
     * @return the Bukkit-side task to mark cancelled, or {@code null} if this id is not ours
     */
    public static CraftTask cancelById(final int taskId) {
        final Entry entry = BY_ID.remove(taskId);
        if (entry == null) {
            return null;
        }
        for (final Map<Integer, Boolean> ids : BY_PLUGIN.values()) {
            ids.remove(taskId);
        }
        cancelQuietly(entry.scheduled());
        return entry.craftTask();
    }

    /**
     * Cancel everything redispatched for {@code plugin}.
     *
     * @return the Bukkit-side tasks to mark cancelled; never {@code null}
     */
    public static List<CraftTask> cancelAll(final Plugin plugin) {
        final Map<Integer, Boolean> ids = BY_PLUGIN.remove(plugin);
        if (ids == null) {
            return List.of();
        }
        final List<CraftTask> cancelled = new ArrayList<>(ids.size());
        for (final Integer id : ids.keySet()) {
            final Entry entry = BY_ID.remove(id);
            if (entry == null) {
                continue;
            }
            cancelQuietly(entry.scheduled());
            cancelled.add(entry.craftTask());
        }
        return cancelled;
    }

    private static void cancelQuietly(final ScheduledTask scheduled) {
        try {
            scheduled.cancel();
        } catch (final Throwable ignored) {
            // Already finished or already cancelled - nothing to do either way.
        }
    }
}
