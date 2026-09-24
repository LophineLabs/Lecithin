package fun.bm.lecithin.compatsuite.fixture.layer;

import fun.bm.lecithin.compatsuite.fixture.Harness;
import fun.bm.lecithin.compatsuite.fixture.Layer;
import fun.bm.lecithin.compatsuite.fixture.Probe;
import fun.bm.lecithin.compatsuite.fixture.Scenario;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Scheduler API added after 1.8.8: the {@code Consumer<BukkitTask>} overloads, which hand the running task
 * to the body so it can cancel itself without holding an id.
 */
public final class Since113Layer implements Layer {

    @Override
    public void install(final Harness h) {
        h.add(new ConsumerTimerSelfCancel());
    }

    static final class ConsumerTimerSelfCancel extends Scenario {
        private final AtomicInteger runs = new AtomicInteger();
        private final AtomicReference<Object> primary = new AtomicReference<Object>();

        ConsumerTimerSelfCancel() {
            super("sched.consumer_timer_self_cancel", "scheduler", SERVER,
                    "runTaskTimer(plugin, Consumer<BukkitTask>, 1, 1) whose body cancels the handed task on run 3 runs exactly 3 times");
        }

        @Override
        public void onPhase(final Harness h, final String phase) {
            if (!SERVER.equals(phase)) {
                return;
            }
            this.result.expect("scheduleException", Probe.NONE).expect("runs", 3).expect("primaryThread", true);
            try {
                Bukkit.getScheduler().runTaskTimer(h.plugin, new Consumer<BukkitTask>() {
                    @Override
                    public void accept(final BukkitTask task) {
                        ConsumerTimerSelfCancel.this.primary.compareAndSet(null, Probe.primary());
                        if (ConsumerTimerSelfCancel.this.runs.incrementAndGet() == 3) {
                            task.cancel();
                        }
                    }
                }, 1L, 1L);
                this.result.observe("scheduleException", Probe.NONE);
            } catch (final Throwable t) {
                this.result.observe("scheduleException", Probe.exName(t)).diag("scheduleMessage", Probe.exMessage(t));
            }
        }

        @Override
        public void finish(final Harness h) {
            this.result.observe("runs", this.runs.get()).observe("primaryThread", Probe.orNotRun(this.primary.get()));
        }
    }
}
