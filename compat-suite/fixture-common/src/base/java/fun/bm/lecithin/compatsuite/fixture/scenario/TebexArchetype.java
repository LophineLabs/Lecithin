package fun.bm.lecithin.compatsuite.fixture.scenario;

import fun.bm.lecithin.compatsuite.fixture.Harness;
import fun.bm.lecithin.compatsuite.fixture.Probe;
import fun.bm.lecithin.compatsuite.fixture.Scenario;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Tebex archetype: work that arrives on a thread the platform knows nothing about (an HTTP client
 * callback, a {@link CompletableFuture} stage, a nested async task) and asks for the legacy main thread
 * to do server-control work - dispatch a console command, message online players.
 *
 * <p>Real shape (tebex-bukkit): an async poll finds a pending purchase, the HTTP client's callback calls
 * {@code Bukkit.getScheduler().runTask(plugin, ...)} and that task runs
 * {@code Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)}. There is no entity and no region
 * anywhere in that chain. No store account is involved; the callback threads are synthetic.
 *
 * <p>The server-control body dispatches this fixture's own {@code sink} sub-command through the console,
 * so "the command really ran" is observed by the plugin command executor rather than inferred from a
 * boolean return value.
 */
public final class TebexArchetype {

    private TebexArchetype() {
    }

    public static void install(final Harness h) {
        h.add(new ForeignThreadCallback());
        h.add(new CompletableFutureCallback());
        h.add(new BukkitAsyncOneHop());
        h.add(new BukkitAsyncTwoHops());
    }

    /**
     * One case: some origin eventually calls {@link #scheduleServerControl}, and the body records what the
     * main-thread work could do.
     */
    abstract static class ServerControlCase extends Scenario {
        private final AtomicInteger runs = new AtomicInteger();
        private final AtomicReference<String> scheduleFailure = new AtomicReference<String>();
        private final AtomicReference<Object> primary = new AtomicReference<Object>();
        private final AtomicReference<Object> dispatchReturned = new AtomicReference<Object>();
        private final AtomicReference<String> dispatchFailure = new AtomicReference<String>();
        private final AtomicReference<String> messageFailure = new AtomicReference<String>();
        private final String tag;

        ServerControlCase(final String id, final String description) {
            super(id, "tebex", PLAYERS, description);
            this.tag = id.substring(id.indexOf('.') + 1);
        }

        @Override
        public final void onPhase(final Harness h, final String phase) throws Exception {
            if (!PLAYERS.equals(phase)) {
                return;
            }
            this.result.expect("scheduleException", Probe.NONE).expect("runs", 1).expect("primaryThread", true)
                    .expect("dispatchReturned", true).expect("sinkInvoked", true)
                    .expect("dispatchException", Probe.NONE).expect("messageException", Probe.NONE);
            start(h);
        }

        /** Begin the origin chain. Runs on the console command thread. */
        abstract void start(Harness h) throws Exception;

        /** The legacy call Tebex makes from its callback: "run this on the main thread". */
        final void scheduleServerControl(final Harness h) {
            this.result.diag("scheduleThread", Probe.thread());
            try {
                Bukkit.getScheduler().runTask(h.plugin, new Runnable() {
                    @Override
                    public void run() {
                        serverControl(h);
                    }
                });
                this.scheduleFailure.set(Probe.NONE);
            } catch (final Throwable t) {
                this.scheduleFailure.set(Probe.exName(t));
                this.result.diag("scheduleMessage", Probe.exMessage(t));
            }
        }

        final void recordOriginFailure(final String step, final Throwable t) {
            this.scheduleFailure.set(step + ":" + Probe.exName(t));
            this.result.diag("originMessage", Probe.exMessage(t));
        }

        private void serverControl(final Harness h) {
            this.runs.incrementAndGet();
            this.primary.compareAndSet(null, Probe.primary());
            this.result.diag("bodyThread", Probe.thread());
            try {
                this.dispatchReturned.set(Bukkit.dispatchCommand(Bukkit.getConsoleSender(), h.command + " sink " + this.tag));
                this.dispatchFailure.set(Probe.NONE);
            } catch (final Throwable t) {
                this.dispatchFailure.set(Probe.exName(t));
                this.result.diag("dispatchMessage", Probe.exMessage(t));
            }
            try {
                int online = 0;
                for (final Player p : Bukkit.getOnlinePlayers()) {
                    p.sendMessage("[compat-suite] delivered " + this.tag);
                    online++;
                }
                this.result.diag("onlinePlayersMessaged", online);
                this.messageFailure.set(Probe.NONE);
            } catch (final Throwable t) {
                this.messageFailure.set(Probe.exName(t));
                this.result.diag("messageMessage", Probe.exMessage(t));
            }
        }

        @Override
        public final void finish(final Harness h) {
            this.result.observe("scheduleException", Probe.orNotRun(this.scheduleFailure.get()))
                    .observe("runs", this.runs.get())
                    .observe("primaryThread", Probe.orNotRun(this.primary.get()))
                    .observe("dispatchReturned", Probe.orNotRun(this.dispatchReturned.get()))
                    .observe("sinkInvoked", h.sinkSeen(this.tag))
                    .observe("dispatchException", Probe.orNotRun(this.dispatchFailure.get()))
                    .observe("messageException", Probe.orNotRun(this.messageFailure.get()))
                    .diag("sinkThread", h.sinkThread(this.tag));
        }
    }

    static ThreadFactory named(final String name) {
        return new ThreadFactory() {
            @Override
            public Thread newThread(final Runnable r) {
                final Thread t = new Thread(r, name);
                t.setDaemon(true);
                return t;
            }
        };
    }

    /** An HTTP client's own callback thread, created by the plugin. */
    static final class ForeignThreadCallback extends ServerControlCase {
        ForeignThreadCallback() {
            super("tebex.foreign_thread_callback_to_legacy_sync",
                    "a callback on a plugin-created thread (HTTP client) calls runTask; the task runs once on the main thread and can dispatch a console command");
        }

        @Override
        void start(final Harness h) {
            named("cfx-http-callback-" + h.fixtureId).newThread(new Runnable() {
                @Override
                public void run() {
                    Probe.sleep(50);
                    scheduleServerControl(h);
                }
            }).start();
        }
    }

    /** A CompletableFuture stage completing on a plugin-owned executor, as OkHttp/async HTTP clients do. */
    static final class CompletableFutureCallback extends ServerControlCase {
        CompletableFutureCallback() {
            super("tebex.completable_future_callback_to_legacy_sync",
                    "a CompletableFuture.thenAccept stage on a plugin-owned executor calls runTask; the task runs once on the main thread");
        }

        @Override
        void start(final Harness h) {
            final ExecutorService http = Executors.newSingleThreadExecutor(named("cfx-http-client-" + h.fixtureId));
            CompletableFuture.supplyAsync(new Supplier<String>() {
                @Override
                public String get() {
                    Probe.sleep(50);
                    return "{\"payments\":[1]}";
                }
            }, http).thenAccept(new Consumer<String>() {
                @Override
                public void accept(final String body) {
                    scheduleServerControl(h);
                }
            }).whenComplete((ignored, error) -> http.shutdown());
        }
    }

    /** The poll itself is a Bukkit async task started from a server-scope command; one hop back. */
    static final class BukkitAsyncOneHop extends ServerControlCase {
        BukkitAsyncOneHop() {
            super("tebex.bukkit_async_poll_to_legacy_sync",
                    "a Bukkit async task (started from a console command) calls runTask; the task runs once on the main thread");
        }

        @Override
        void start(final Harness h) {
            try {
                Bukkit.getScheduler().runTaskAsynchronously(h.plugin, new Runnable() {
                    @Override
                    public void run() {
                        Probe.sleep(50);
                        scheduleServerControl(h);
                    }
                });
            } catch (final Throwable t) {
                recordOriginFailure("async", t);
            }
        }
    }

    /** Tebex's executeAsync nested inside an async callback: two async hops before the sync request. */
    static final class BukkitAsyncTwoHops extends ServerControlCase {
        BukkitAsyncTwoHops() {
            super("tebex.nested_async_to_legacy_sync",
                    "an async task that starts another async task, which calls runTask; the task runs once on the main thread");
        }

        @Override
        void start(final Harness h) {
            try {
                Bukkit.getScheduler().runTaskAsynchronously(h.plugin, new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Bukkit.getScheduler().runTaskAsynchronously(h.plugin, new Runnable() {
                                @Override
                                public void run() {
                                    Probe.sleep(50);
                                    scheduleServerControl(h);
                                }
                            });
                        } catch (final Throwable t) {
                            recordOriginFailure("inner-async", t);
                        }
                    }
                });
            } catch (final Throwable t) {
                recordOriginFailure("outer-async", t);
            }
        }
    }
}
