package fun.bm.lecithin.compatsuite.fixture.layer;

import fun.bm.lecithin.compatsuite.fixture.Harness;
import fun.bm.lecithin.compatsuite.fixture.Layer;
import fun.bm.lecithin.compatsuite.fixture.Scenario;
import org.bukkit.Material;

/**
 * Only compiles against a pre-1.13 API: {@code Material.WOOL} does not exist after the flattening. A
 * plugin without {@code api-version} referencing it is rewritten by the server's legacy-plugin support
 * (Commodore) at class load. No absolute expectation: the reference server defines the answer.
 */
public final class PreFlatteningLayer implements Layer {

    @Override
    public void install(final Harness h) {
        h.add(new Scenario("legacy.pre_flattening_material_constant", "loading", Scenario.SERVER,
                "a pre-1.13 Material constant (WOOL) referenced by a plugin without api-version resolves the same way as on Paper") {
            @Override
            public void onEnable(final Harness harness) {
                try {
                    this.result.observe("materialName", Material.WOOL.name());
                } catch (final Throwable t) {
                    this.result.observe("materialName", "threw " + t.getClass().getSimpleName());
                }
            }
        });
    }
}
