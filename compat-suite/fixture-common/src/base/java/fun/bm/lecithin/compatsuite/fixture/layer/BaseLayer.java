package fun.bm.lecithin.compatsuite.fixture.layer;

import fun.bm.lecithin.compatsuite.fixture.Harness;
import fun.bm.lecithin.compatsuite.fixture.Layer;
import fun.bm.lecithin.compatsuite.fixture.scenario.EssentialsArchetype;
import fun.bm.lecithin.compatsuite.fixture.scenario.GriefPreventionArchetype;
import fun.bm.lecithin.compatsuite.fixture.scenario.LifecycleScenarios;
import fun.bm.lecithin.compatsuite.fixture.scenario.LoadingScenarios;
import fun.bm.lecithin.compatsuite.fixture.scenario.SchedulerScenarios;
import fun.bm.lecithin.compatsuite.fixture.scenario.TebexArchetype;

/**
 * Scenarios written against the Bukkit 1.8.8 API surface; compiled into every era.
 */
public final class BaseLayer implements Layer {

    @Override
    public void install(final Harness h) {
        LoadingScenarios.install(h);
        SchedulerScenarios.install(h);
        TebexArchetype.install(h);
        EssentialsArchetype.install(h);
        GriefPreventionArchetype.install(h);
        LifecycleScenarios.install(h);
    }
}
