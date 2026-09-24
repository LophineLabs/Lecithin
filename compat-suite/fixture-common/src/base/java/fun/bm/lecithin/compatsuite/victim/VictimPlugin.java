package fun.bm.lecithin.compatsuite.victim;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The plugin the lifecycle scenarios disable. It only schedules counting tasks and reports the counts;
 * the fixture reaches it by reflection (different classloader), so every public method speaks JDK types.
 */
public final class VictimPlugin extends JavaPlugin {

    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<String, AtomicLong>();
    private final Map<String, Integer> ids = new ConcurrentHashMap<String, Integer>();
    private final Map<String, String> errors = new ConcurrentHashMap<String, String>();

    @Override
    public void onEnable() {
        try {
            startRepeating("enable-sync");
        } catch (final Throwable t) {
            this.errors.put("enable-sync", t.getClass().getSimpleName());
        }
        try {
            this.ids.put("enable-delayed-long",
                    getServer().getScheduler().scheduleSyncDelayedTask(this, counter("enable-delayed-long"), 20L * 60 * 30));
        } catch (final Throwable t) {
            this.errors.put("enable-delayed-long", t.getClass().getSimpleName());
        }
        try {
            this.ids.put("enable-async",
                    getServer().getScheduler().runTaskTimerAsynchronously(this, counter("enable-async"), 1L, 1L).getTaskId());
        } catch (final Throwable t) {
            this.errors.put("enable-async", t.getClass().getSimpleName());
        }
    }

    /** Schedule a sync repeating counter from the calling thread's context. Throws what the scheduler throws. */
    public int startRepeating(final String key) {
        final int id = getServer().getScheduler().scheduleSyncRepeatingTask(this, counter(key), 1L, 1L);
        this.ids.put(key, id);
        return id;
    }

    public Map<String, Long> snapshot() {
        final Map<String, Long> out = new LinkedHashMap<String, Long>();
        for (final Map.Entry<String, AtomicLong> e : this.counters.entrySet()) {
            out.put(e.getKey(), e.getValue().get());
        }
        return out;
    }

    public Map<String, Integer> taskIds() {
        return new HashMap<String, Integer>(this.ids);
    }

    public Map<String, String> errors() {
        return new HashMap<String, String>(this.errors);
    }

    private Runnable counter(final String key) {
        final AtomicLong n = new AtomicLong();
        this.counters.put(key, n);
        return new Runnable() {
            @Override
            public void run() {
                n.incrementAndGet();
            }
        };
    }
}
