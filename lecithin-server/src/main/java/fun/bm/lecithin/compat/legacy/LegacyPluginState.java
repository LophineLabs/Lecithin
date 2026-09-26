package fun.bm.lecithin.compat.legacy;

import org.bukkit.plugin.Plugin;

import java.util.concurrent.atomic.LongAdder;

/**
 * Lecithin: everything the legacy runtime keeps for one managed plugin - its domain, its lane and
 * the counters {@code /lecithin legacy} reports.
 */
public final class LegacyPluginState {

    final Plugin plugin;
    final LegacyExecutionDomain domain;
    final LegacyLane lane;

    // Entries into the domain, by entry point.
    final LongAdder listenerCalls = new LongAdder();
    final LongAdder commandCalls = new LongAdder();
    final LongAdder serviceCalls = new LongAdder();
    final LongAdder lifecycleCalls = new LongAdder();

    // Sync scheduler routing decisions.
    final LongAdder continuationsOnEntity = new LongAdder();
    final LongAdder continuationsOnRegion = new LongAdder();
    final LongAdder clockTimedTasks = new LongAdder();

    // Where scheduled bodies actually ran.
    final LongAdder ranOnEntityOwner = new LongAdder();
    final LongAdder ranOnRegionOwner = new LongAdder();
    final LongAdder ranInLane = new LongAdder();
    final LongAdder retiredToLane = new LongAdder();
    final LongAdder unloadedToLane = new LongAdder();
    final LongAdder coalescedFirings = new LongAdder();

    /**
     * Any domain entry observed on the global region thread. Listeners and console commands are
     * legitimately there; scheduled bodies of this runtime never are, which is what the number is for.
     */
    final LongAdder entriesOnGlobalThread = new LongAdder();
    final LongAdder scheduledBodiesOnGlobalThread = new LongAdder();

    LegacyPluginState(final Plugin plugin) {
        this.plugin = plugin;
        this.domain = new LegacyExecutionDomain(plugin.getName());
        this.lane = new LegacyLane(this);
    }

    public Plugin plugin() {
        return this.plugin;
    }

    /**
     * Run one lane body inside this plugin's domain. Called by the lane's drain loop only.
     */
    void runInLane(final Runnable body) {
        try {
            LegacyPluginRuntime.call(this, LegacyAffinity.NONE, true, () -> {
                this.ranInLane.increment();
                body.run();
                return null;
            });
        } catch (final Throwable t) {
            this.plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "[Lecithin] legacy lane work for " + this.plugin.getName() + " generated an exception", t);
        }
    }
}
