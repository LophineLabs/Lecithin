package fun.bm.lecithin.compat.legacy;

import fun.bm.lecithin.config.modules.CompatConfig;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Lecithin: the text behind {@code /lecithin legacy}. Plain lines so the same report reads the same
 * from a player, the console and RCON.
 */
public final class LegacyRuntimeReport {

    private LegacyRuntimeReport() {
    }

    public static List<String> lines() {
        final List<String> out = new ArrayList<>();
        if (!CompatConfig.legacyManagedExecution) {
            out.add("Legacy Paper Runtime: OFF (compat-config.legacy-managed-execution=false)");
            return out;
        }
        // Classify everything loaded, not only what has been touched so far.
        for (final Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            if (plugin.isEnabled()) {
                LegacyPluginRuntime.stateOf(plugin);
            }
        }
        final List<LegacyPluginState> managed = LegacyPluginRuntime.managed();
        final List<Plugin> natives = LegacyPluginRuntime.nativePlugins();
        out.add("Legacy Paper Runtime: " + managed.size() + " managed, " + natives.size() + " native");
        out.add("native: " + String.join(", ", natives.stream().map(Plugin::getName).toList()));
        for (final LegacyPluginState s : managed) {
            final LegacyExecutionDomain d = s.domain;
            out.add(s.plugin.getName()
                    + " | entries listener=" + s.listenerCalls.sum()
                    + " command=" + s.commandCalls.sum()
                    + " service=" + s.serviceCalls.sum()
                    + " lifecycle=" + s.lifecycleCalls.sum()
                    + " | routed entityCont=" + s.continuationsOnEntity.sum()
                    + " regionCont=" + s.continuationsOnRegion.sum()
                    + " clockTimed=" + s.clockTimedTasks.sum()
                    + " | ran entity=" + s.ranOnEntityOwner.sum()
                    + " region=" + s.ranOnRegionOwner.sum()
                    + " lane=" + s.ranInLane.sum()
                    + " (retired->lane=" + s.retiredToLane.sum()
                    + " unloaded->lane=" + s.unloadedToLane.sum()
                    + " coalesced=" + s.coalescedFirings.sum() + ')'
                    + " | globalThread entries=" + s.entriesOnGlobalThread.sum()
                    + " scheduledBodies=" + s.scheduledBodiesOnGlobalThread.sum()
                    + " | domain acq=" + d.acquisitions.sum()
                    + " contended=" + d.contended.sum()
                    + " callbacks=" + d.callbacks.sum()
                    + " waitMs=" + TimeUnit.NANOSECONDS.toMillis(d.waitNanos.sum())
                    + " maxWaitMs=" + TimeUnit.NANOSECONDS.toMillis(d.maxWaitNanos)
                    + " holder=" + d.describeOwner()
                    + " laneQueued=" + s.lane.queued());
        }
        return out;
    }
}
