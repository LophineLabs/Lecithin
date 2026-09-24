package fun.bm.lecithin.compatsuite.fixture;

/**
 * A source layer of scenarios. Each fixture era compiles the layers its {@code fixture.properties}
 * lists against its own API baseline; a layer is only listed where its API exists in that era.
 *
 * <p>Implementation class naming: layer {@code since_1_13} is
 * {@code fun.bm.lecithin.compatsuite.fixture.layer.Since113Layer} - see {@link Harness#layerClassName}.
 */
public interface Layer {

    /** Register scenarios (and any era-specific listeners) with the harness. */
    void install(Harness harness);
}
