package fun.bm.lecithin.compatsuite.fixture.scenario;

import fun.bm.lecithin.compatsuite.fixture.Harness;
import fun.bm.lecithin.compatsuite.fixture.Scenario;

/**
 * Loading contract: a plugin that says nothing about Folia is still a plugin. Folia refuses such plugins;
 * Lecithin must not, because {@code folia-supported} is a capability hint, not a load gate.
 */
public final class LoadingScenarios {

    private LoadingScenarios() {
    }

    public static void install(final Harness h) {
        h.add(new EnabledWithoutFoliaFlag());
    }

    static final class EnabledWithoutFoliaFlag extends Scenario {
        EnabledWithoutFoliaFlag() {
            super("loading.enabled_without_folia_supported", "loading", SERVER,
                    "a plugin.yml without folia-supported is loaded and enabled");
        }

        @Override
        public void onEnable(final Harness h) {
            this.result.expect("enabled", true).expect("declaresFoliaSupported", false)
                    .observe("enabled", h.plugin.isEnabled())
                    .observe("declaresFoliaSupported", h.descriptorDeclares("folia-supported"))
                    .observe("declaresApiVersion", h.descriptorDeclares("api-version"))
                    .diag("classFileMajor", h.classFileMajor());
        }
    }
}
