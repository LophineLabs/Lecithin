package fun.bm.lecithin.compatsuite.fixture.scenario;

import fun.bm.lecithin.compatsuite.fixture.Harness;
import fun.bm.lecithin.compatsuite.fixture.Probe;
import fun.bm.lecithin.compatsuite.fixture.Scenario;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GriefPrevention archetype: plugin callbacks triggered by two players in two different regions touch
 * the same plugin-owned, unsynchronized state - exactly GP 16.18.7's
 * {@code recentLoginLogoutNotifications} ({@code ArrayList<Long>}, {@code add} + {@code remove(0)}).
 *
 * <p>On Paper every plugin callback runs on the one main thread, so a plugin never observes two of its
 * own callbacks inside the same critical section; plugins rely on that without knowing it. The
 * observable here is exactly that property: does any thread enter the section while another is inside?
 *
 * <p>To make the observation deterministic rather than luck, each entry holds the section open for a
 * bounded window (and leaves early once overlap is seen). On a single main thread the second callback
 * cannot start until the first returns, so {@code concurrentEntry} is {@code false} regardless of
 * timing; on a platform that runs the two callbacks in parallel it is {@code true} as soon as the two
 * windows overlap. The runner places the players 4096 blocks apart so they cannot share a region.
 */
public final class GriefPreventionArchetype {

    private GriefPreventionArchetype() {
    }

    public static void install(final Harness h) {
        h.add(new RegionCallbacksSharedState());
        h.add(new LegacyRepeatingPerPlayerSharedState());
    }

    /** A plugin-global "critical section" with a GP-shaped plain ArrayList inside. */
    static final class SharedState {
        final AtomicInteger inside = new AtomicInteger();
        final AtomicInteger maxInside = new AtomicInteger();
        final AtomicInteger faults = new AtomicInteger();
        final List<Long> recent = new ArrayList<Long>();

        /** Enter, hold for at most {@code windowMs} unless overlap is seen, leave. */
        void section(final long windowMs, final String label) {
            record(this.inside.incrementAndGet());
            try {
                final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(windowMs);
                do {
                    mutate(label);
                    record(this.inside.get());
                    if (this.maxInside.get() > 1) {
                        break;
                    }
                    Probe.sleep(5);
                } while (System.nanoTime() < deadline);
            } finally {
                this.inside.decrementAndGet();
            }
        }

        private void record(final int now) {
            int max;
            do {
                max = this.maxInside.get();
            } while (now > max && !this.maxInside.compareAndSet(max, now));
        }

        /** GP's shouldSilenceNotification(): add a timestamp, trim the oldest. Unsynchronized on purpose. */
        private void mutate(final String label) {
            try {
                this.recent.add(System.currentTimeMillis());
                if (this.recent.size() > 2) {
                    this.recent.remove(0);
                }
            } catch (final Throwable t) {
                this.faults.incrementAndGet();
                Bukkit.getLogger().fine("[compat-suite] " + label + " shared list fault " + t);
            }
        }
    }

    static final class RegionCallbacksSharedState extends Scenario {
        private final SharedState state = new SharedState();
        private final AtomicInteger handlers = new AtomicInteger();

        RegionCallbacksSharedState() {
            super("gp.region_callbacks_shared_state", "griefprevention", PLAYERS,
                    "two players' command-preprocess callbacks (different regions) touching one plugin-owned list never overlap, as on one main thread");
        }

        @Override
        public void onPhase(final Harness h, final String phase) {
            if (PLAYERS.equals(phase)) {
                this.result.expect("handlers", 2).expect("concurrentEntry", false);
            }
        }

        @Override
        public void onTrigger(final Harness h, final Player player, final String role, final String trigger) {
            if (!"gp".equals(trigger)) {
                return;
            }
            this.handlers.incrementAndGet();
            this.result.diag(role + ".thread", Probe.thread());
            this.state.section(400, this.id);
        }

        @Override
        public void finish(final Harness h) {
            this.result.observe("handlers", this.handlers.get())
                    .observe("concurrentEntry", this.state.maxInside.get() > 1)
                    .diag("maxInside", this.state.maxInside.get())
                    .diag("listFaults", this.state.faults.get());
        }
    }

    static final class LegacyRepeatingPerPlayerSharedState extends Scenario {
        private static final int RUNS_PER_PLAYER = 10;
        private final SharedState state = new SharedState();
        private final AtomicInteger totalRuns = new AtomicInteger();
        private final Map<String, Object> scheduleFailures = new ConcurrentHashMap<String, Object>();

        LegacyRepeatingPerPlayerSharedState() {
            super("gp.legacy_repeating_per_player_shared_state", "griefprevention", PLAYERS,
                    "a legacy sync repeating task per player, scheduled from each player's callback, runs 10x each and never overlaps on plugin-owned state");
        }

        @Override
        public void onPhase(final Harness h, final String phase) {
            if (PLAYERS.equals(phase)) {
                this.result.expect("A.scheduleException", Probe.NONE).expect("B.scheduleException", Probe.NONE)
                        .expect("totalRuns", 2 * RUNS_PER_PLAYER).expect("concurrentEntry", false);
            }
        }

        @Override
        public void onTrigger(final Harness h, final Player player, final String role, final String trigger) {
            if (!"gp".equals(trigger)) {
                return;
            }
            final AtomicInteger id = new AtomicInteger(-1);
            final AtomicInteger mine = new AtomicInteger();
            try {
                id.set(Bukkit.getScheduler().scheduleSyncRepeatingTask(h.plugin, new Runnable() {
                    @Override
                    public void run() {
                        final int n = mine.incrementAndGet();
                        if (n > RUNS_PER_PLAYER) {
                            return;
                        }
                        LegacyRepeatingPerPlayerSharedState.this.totalRuns.incrementAndGet();
                        if (n == 1) {
                            LegacyRepeatingPerPlayerSharedState.this.result.diag(role + ".taskThread", Probe.thread());
                        }
                        // The first run of each task holds the section long enough for the other player's task
                        // to arrive if it can run in parallel; later runs are GP-sized (a few list operations).
                        LegacyRepeatingPerPlayerSharedState.this.state.section(n == 1 ? 300 : 0, role);
                        if (n == RUNS_PER_PLAYER) {
                            Bukkit.getScheduler().cancelTask(id.get());
                        }
                    }
                }, 1L, 1L));
                this.scheduleFailures.put(role, Probe.NONE);
            } catch (final Throwable t) {
                this.scheduleFailures.put(role, Probe.exName(t));
                this.result.diag(role + ".scheduleMessage", Probe.exMessage(t));
            }
        }

        @Override
        public void finish(final Harness h) {
            this.result.observe("A.scheduleException", Probe.orNotRun(this.scheduleFailures.get("A")))
                    .observe("B.scheduleException", Probe.orNotRun(this.scheduleFailures.get("B")))
                    .observe("totalRuns", this.totalRuns.get())
                    .observe("concurrentEntry", this.state.maxInside.get() > 1)
                    .diag("maxInside", this.state.maxInside.get())
                    .diag("listFaults", this.state.faults.get());
        }
    }
}
