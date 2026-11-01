package fun.bm.lophine.carpet;

import fun.bm.lecithin.config.modules.CompatConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Work that never completes must not keep a stop request from reaching the region shutdown thread,
 * on either a live server or one whose tick threads a failed region already halted.
 */
@Timeout(30)
class CarpetShutdownGateTestSuite {
    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);

    private final ArrayDeque<Runnable> due = new ArrayDeque<>();
    private long now = 1_000 * SECOND;
    private boolean failed;
    private MinecraftServer server;

    private CarpetShutdownGate.Timer realTimer;
    private LongSupplier realClock;
    private BooleanSupplier realFailed;
    private long realBound;

    @BeforeEach
    void setUp() {
        realTimer = CarpetShutdownGate.timer;
        realClock = CarpetShutdownGate.clock;
        realFailed = CarpetShutdownGate.tickThreadsFailed;
        realBound = CompatConfig.shutdownGateTimeoutSeconds;
        CarpetShutdownGate.timer = (nanos, expire) -> due.add(() -> {
            now += nanos;
            expire.run();
        });
        CarpetShutdownGate.clock = () -> now;
        CarpetShutdownGate.tickThreadsFailed = () -> failed;
        CompatConfig.shutdownGateTimeoutSeconds = 60;
        server = mock(MinecraftServer.class);
        PlayerList players = mock(PlayerList.class);
        when(server.getPlayerList()).thenReturn(players);
        when(players.getPlayers()).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        CarpetShutdownGate.timer = realTimer;
        CarpetShutdownGate.clock = realClock;
        CarpetShutdownGate.tickThreadsFailed = realFailed;
        CompatConfig.shutdownGateTimeoutSeconds = realBound;
    }

    /**
     * Runs the armed timers until the view ends or there is nothing left to run.
     */
    private long runUntilDone(CompletableFuture<?> view) {
        long start = now;
        while (!view.isDone() && !due.isEmpty()) due.removeFirst().run();
        return (now - start) / SECOND;
    }

    @Test
    void workThatNeverCompletesIsReleasedAtTheBound() {
        var gate = CarpetShutdownGate.of(server);
        var view = gate.bounded(new CompletableFuture<Void>(), CarpetShutdownGate.Stage.NATIVE_WORK);

        assertFalse(view.isDone(), "the gate still waits while the bound has time left");
        long waited = runUntilDone(view);

        assertTrue(view.isDone(), "without a bound this wait is the hang: the shutdown thread never starts");
        assertFalse(view.isCompletedExceptionally(), "the shutdown continues normally after the bound");
        assertEquals(51, waited, "the native stage ends at its share, leaving player registries the rest of the minute");
    }

    @Test
    void stagesShareOneDeadlineFromTheStopRequest() {
        var gate = CarpetShutdownGate.of(server);
        now += 40 * SECOND; // a stuck earlier stage already used this
        var view = gate.bounded(new CompletableFuture<Void>(), CarpetShutdownGate.Stage.PLAYER_REGISTRIES);

        assertEquals(20, runUntilDone(view), "the last stage only gets what is left of the same 60 seconds");
    }

    @Test
    void anExpiredStageReleasesImmediately() {
        var gate = CarpetShutdownGate.of(server);
        now += 120 * SECOND;

        assertTrue(gate.bounded(new CompletableFuture<Void>(), CarpetShutdownGate.Stage.LOGINS).isDone());
    }

    @Test
    void aFailedRegionDoesNotWaitForWorkThatCanNoLongerRun() {
        failed = true;
        var view = CarpetShutdownGate.of(server).bounded(new CompletableFuture<Void>(), CarpetShutdownGate.Stage.NATIVE_WORK);

        assertEquals(5, runUntilDone(view), "tick threads are halted; only an off-region tail can still finish");
    }

    @Test
    void aRegionFailingDuringTheWaitShortensIt() {
        var view = CarpetShutdownGate.of(server).bounded(new CompletableFuture<Void>(), CarpetShutdownGate.Stage.NATIVE_WORK);
        for (int tick = 0; tick < 10; tick++) due.removeFirst().run();
        assertFalse(view.isDone());

        failed = true;
        assertEquals(6, runUntilDone(view), "noticed at the next one-second recheck, then the same few seconds of grace");
        assertTrue(view.isDone());
    }

    @Test
    void finishedWorkKeepsItsSynchronousPath() {
        var done = CompletableFuture.<Void>completedFuture(null);

        assertSame(done, CarpetShutdownGate.of(server).bounded(done, CarpetShutdownGate.Stage.NATIVE_WORK), "no timer and no extra hop when nothing is stuck");
        assertTrue(due.isEmpty());
    }

    @Test
    void workFinishingInTimeEndsTheWaitWithItsOwnResult() {
        var work = new CompletableFuture<String>();
        var view = CarpetShutdownGate.of(server).bounded(work, CarpetShutdownGate.Stage.NATIVE_WORK);

        work.complete("saved");

        assertEquals("saved", view.join());
        runUntilDone(view);
    }

    @Test
    void aFailureReachesTheCallerInsteadOfWaitingOut() {
        var work = new CompletableFuture<Void>();
        var view = CarpetShutdownGate.of(server).bounded(work, CarpetShutdownGate.Stage.PLAYER_REGISTRIES);

        work.completeExceptionally(new IllegalStateException("removal failed"));

        assertTrue(view.isCompletedExceptionally());
    }

    @Test
    void zeroRemovesTheBound() {
        CompatConfig.shutdownGateTimeoutSeconds = 0;
        var view = CarpetShutdownGate.of(server).bounded(new CompletableFuture<Void>(), CarpetShutdownGate.Stage.NATIVE_WORK);

        for (int tick = 0; tick < 1_000; tick++) due.removeFirst().run();

        assertFalse(view.isDone());
    }
}
