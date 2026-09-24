package fun.bm.lecithin.compatsuite.fixture.scenario;

import fun.bm.lecithin.compatsuite.fixture.Harness;
import fun.bm.lecithin.compatsuite.fixture.Probe;
import fun.bm.lecithin.compatsuite.fixture.Scenario;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Essentials archetype: an asynchronous {@code PlayerEvent} (a real client's chat) whose handler asks the
 * legacy scheduler to continue on the main thread and then works on that player - reads location and
 * the block under them, writes and reads back player state.
 *
 * <p>Both test players chat, so every case records roles {@code A} (near spawn) and {@code B} (far away,
 * a different region on a region-threaded server) separately.
 */
public final class EssentialsArchetype {

    private EssentialsArchetype() {
    }

    public static void install(final Harness h) {
        h.add(new RunTaskContinuation());
        h.add(new DelayedContinuation());
        h.add(new CallSyncMethodContinuation());
    }

    /** The main-thread work: touch the player the event named. Returns "ok" or what went wrong. */
    public static String touchPlayer(final Player player) {
        try {
            final Location loc = player.getLocation();
            loc.getBlock().getRelative(BlockFace.DOWN).getType().name();
            player.setLevel(7);
            return player.getLevel() == 7 ? "ok" : "readback-mismatch";
        } catch (final Throwable t) {
            return Probe.exName(t);
        }
    }

    /**
     * A continuation case for a player-named async event. Subclasses pick the legacy API used to hop.
     * Public so era layers with newer event types (Paper's AsyncChatEvent) can reuse it.
     */
    public abstract static class PlayerContinuation extends Scenario {
        private final Map<String, Object> obs = new ConcurrentHashMap<String, Object>();
        private final Map<String, AtomicInteger> runs = new ConcurrentHashMap<String, AtomicInteger>();
        private final String chatKeyword;

        protected PlayerContinuation(final String id, final String description, final String chatKeyword) {
            super(id, "essentials", PLAYERS, description);
            this.chatKeyword = chatKeyword;
        }

        @Override
        public void onPhase(final Harness h, final String phase) {
            if (!PLAYERS.equals(phase)) {
                return;
            }
            for (final String role : new String[]{"A", "B"}) {
                this.result.expect(role + ".eventAsync", true)
                        .expect(role + ".scheduleException", Probe.NONE)
                        .expect(role + ".runs", 1)
                        .expect(role + ".primaryThread", true)
                        .expect(role + ".playerAccess", "ok");
                this.runs.put(role, new AtomicInteger());
            }
        }

        @Override
        public void onAsyncChat(final Harness h, final Player player, final String role, final String message,
                                final boolean eventAsync, final String eventType) {
            if (acceptsEventType(eventType) && this.chatKeyword.equals(message)) {
                handle(h, player, role, eventAsync);
            }
        }

        protected boolean acceptsEventType(final String eventType) {
            return "AsyncPlayerChatEvent".equals(eventType);
        }

        /** Entry point from the event handler thread. */
        public final void handle(final Harness h, final Player player, final String role, final boolean eventAsync) {
            this.obs.put(role + ".eventAsync", eventAsync);
            this.result.diag(role + ".eventThread", Probe.thread());
            final Runnable body = new Runnable() {
                @Override
                public void run() {
                    PlayerContinuation.this.runs.get(role).incrementAndGet();
                    if (!PlayerContinuation.this.obs.containsKey(role + ".primaryThread")) {
                        PlayerContinuation.this.obs.put(role + ".primaryThread", Probe.primary());
                        PlayerContinuation.this.obs.put(role + ".playerAccess", touchPlayer(player));
                        PlayerContinuation.this.result.diag(role + ".bodyThread", Probe.thread());
                    }
                }
            };
            try {
                hop(h, player, body);
                this.obs.put(role + ".scheduleException", Probe.NONE);
            } catch (final Throwable t) {
                this.obs.put(role + ".scheduleException", Probe.exName(t));
                this.result.diag(role + ".scheduleMessage", Probe.exMessage(t));
            }
        }

        /** Hand {@code body} to the main thread with the legacy API this case is about. */
        protected abstract void hop(Harness h, Player player, Runnable body) throws Exception;

        @Override
        public void finish(final Harness h) {
            for (final String role : new String[]{"A", "B"}) {
                for (final String key : new String[]{"eventAsync", "scheduleException", "primaryThread", "playerAccess"}) {
                    this.result.observe(role + "." + key, Probe.orNotRun(this.obs.get(role + "." + key)));
                }
                final AtomicInteger n = this.runs.get(role);
                this.result.observe(role + ".runs", n == null ? 0 : n.get());
            }
        }
    }

    static final class RunTaskContinuation extends PlayerContinuation {
        RunTaskContinuation() {
            super("ess.async_chat_to_legacy_runtask",
                    "AsyncPlayerChatEvent handler calls runTask; the task runs once on the main thread and can read/write that player",
                    "ess");
        }

        @Override
        protected void hop(final Harness h, final Player player, final Runnable body) {
            Bukkit.getScheduler().runTask(h.plugin, body);
        }
    }

    static final class DelayedContinuation extends PlayerContinuation {
        DelayedContinuation() {
            super("ess.async_chat_to_legacy_delayed",
                    "AsyncPlayerChatEvent handler calls scheduleSyncDelayedTask(task, 2); the task runs once on the main thread and can touch that player",
                    "ess");
        }

        @Override
        protected void hop(final Harness h, final Player player, final Runnable body) {
            final int id = Bukkit.getScheduler().scheduleSyncDelayedTask(h.plugin, body, 2L);
            if (id == -1) {
                throw new IllegalStateException("scheduleSyncDelayedTask returned -1");
            }
        }
    }

    static final class CallSyncMethodContinuation extends PlayerContinuation {
        CallSyncMethodContinuation() {
            super("ess.async_chat_to_call_sync_method",
                    "AsyncPlayerChatEvent handler blocks on callSyncMethod(...).get(); the callable runs on the main thread and can touch that player",
                    "ess");
        }

        @Override
        protected void hop(final Harness h, final Player player, final Runnable body) throws Exception {
            final Future<Boolean> f = Bukkit.getScheduler().callSyncMethod(h.plugin, new Callable<Boolean>() {
                @Override
                public Boolean call() {
                    body.run();
                    return Boolean.TRUE;
                }
            });
            f.get(5, TimeUnit.SECONDS);
        }
    }
}
