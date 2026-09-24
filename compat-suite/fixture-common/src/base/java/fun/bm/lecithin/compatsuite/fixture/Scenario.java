package fun.bm.lecithin.compatsuite.fixture;

import org.bukkit.entity.Player;

/**
 * One testcase. A scenario reacts to the hooks it needs and writes what it saw into {@link #result};
 * the harness finalizes it when its {@link #phase} ends, calling {@link #finish} on the watchdog
 * thread.
 *
 * <p>Hooks run on whatever thread the platform uses for them - that is the point of the suite - so
 * scenario state must be thread-safe. A hook that throws is recorded as a scenario ERROR, which is
 * reserved for bugs in the scenario itself: when the API under test throws, catch it and
 * {@link Result#observe observe} the exception class name instead.
 *
 * <p>Never use the Bukkit scheduler for bookkeeping (timeouts, "wait a bit", finalization). It is the
 * thing under test; use {@link Harness#watchdog()}.
 */
public abstract class Scenario {

    /** Phase names the runner drives, in order. */
    public static final String SERVER = "server";
    public static final String PLAYERS = "players";
    public static final String LIFECYCLE_LIVENESS = "lifecycle-liveness";
    public static final String LIFECYCLE_DISABLE = "lifecycle-disable";

    public final String id;
    public final String archetype;
    public final String phase;
    public final String description;
    protected final Result result = new Result();

    /**
     * @param id          stable case id, identical in every era, e.g. {@code sched.legacy_sync_delayed}
     * @param archetype   grouping for the report: {@code scheduler}, {@code lifecycle}, {@code tebex}, ...
     * @param phase       the phase at whose end this case is finalized and written
     * @param description one sentence: which Paper contract this checks
     */
    protected Scenario(final String id, final String archetype, final String phase, final String description) {
        this.id = id;
        this.archetype = archetype;
        this.phase = phase;
        this.description = description;
    }

    /** During {@code onEnable}, on the thread the platform enables plugins on. */
    public void onEnable(final Harness h) throws Throwable {
    }

    /** A phase started; runs on the thread the console command was dispatched on. */
    public void onPhase(final Harness h, final String phase) throws Throwable {
    }

    /** A test player chatted; runs inside the platform's async chat event dispatch. */
    public void onAsyncChat(final Harness h, final Player player, final String role, final String message,
                            final boolean eventAsync, final String eventType) throws Throwable {
    }

    /** A test player sent {@code /cfxtrigger <trigger>}; runs inside PlayerCommandPreprocessEvent. */
    public void onTrigger(final Harness h, final Player player, final String role, final String trigger) throws Throwable {
    }

    /** Called once on the watchdog thread when {@link #phase} ends. Take final samples here. */
    public void finish(final Harness h) throws Throwable {
    }
}
