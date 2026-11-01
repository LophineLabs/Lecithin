package carpet.script.external;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A disconnected player's removal is the snapshot here: work that never finishes must not keep it
 * from running, and it must never run twice when that work finishes after all.
 */
@Timeout(30)
class ScarpetOwnerWorkGateTestSuite {
    private final List<Runnable> timers = new ArrayList<>();

    /**
     * The shape of {@code ScarpetPlayerInventoryGate.boundRemovalWait}, with the timer in the test's hands.
     */
    private CompletableFuture<Void> bounded(CompletableFuture<Void> pending) {
        if (pending.isDone()) return pending;
        var waited = new CompletableFuture<Void>();
        pending.whenComplete((ignored, failure) -> waited.complete(null));
        timers.add(() -> waited.complete(null));
        return waited;
    }

    private static CompletableFuture<Integer> run(AtomicInteger runs) {
        return CompletableFuture.completedFuture(runs.incrementAndGet());
    }

    @Test
    void removalWaitsForAcceptedWorkThatFinishes() {
        var gate = new ScarpetOwnerWorkGate();
        var work = new CompletableFuture<Void>();
        gate.track(work);
        var runs = new AtomicInteger();

        var removal = gate.<Integer>whenIdle(snapshot -> run(runs), () -> 0, true, false, this::bounded);

        assertEquals(0, runs.get(), "accepted work still runs first when it does finish");
        work.complete(null);
        assertEquals(1, runs.get());
        assertTrue(removal.isDone());
    }

    @Test
    void workThatNeverFinishesHoldsTheRemovalWithoutABound() {
        var gate = new ScarpetOwnerWorkGate();
        gate.track(new CompletableFuture<Void>());
        var runs = new AtomicInteger();

        gate.<Integer>whenIdle(snapshot -> run(runs), () -> 0, true, false);

        assertEquals(0, runs.get(), "this is the ghost: hasDisconnected is true and the player stays listed");
    }

    @Test
    void theBoundRunsTheRemovalWithoutTheStuckWork() {
        var gate = new ScarpetOwnerWorkGate();
        gate.track(new CompletableFuture<Void>());
        var runs = new AtomicInteger();

        var removal = gate.<Integer>whenIdle(snapshot -> run(runs), () -> 0, true, false, this::bounded);

        assertEquals(0, runs.get());
        timers.forEach(Runnable::run);
        assertEquals(1, runs.get(), "expiry only ends the wait; the removal itself still happens");
        assertTrue(removal.isDone());
        assertFalse(gate.paused(), "the gate reopens once the removal finished");
    }

    @Test
    void stuckWorkFinishingLateDoesNotRunTheRemovalAgain() {
        var gate = new ScarpetOwnerWorkGate();
        var work = new CompletableFuture<Void>();
        gate.track(work);
        var runs = new AtomicInteger();

        gate.<Integer>whenIdle(snapshot -> run(runs), () -> 0, true, false, this::bounded);
        timers.forEach(Runnable::run);
        work.complete(null);
        timers.forEach(Runnable::run);

        assertEquals(1, runs.get(), "retiring a player's scheduler twice throws");
    }

    @Test
    void nothingPendingStaysSynchronous() {
        var gate = new ScarpetOwnerWorkGate();
        var runs = new AtomicInteger();

        var removal = gate.<Integer>whenIdle(snapshot -> run(runs), () -> 0, true, false, this::bounded);

        assertTrue(removal.isDone(), "a plain logout keeps its original synchronous cleanup");
        assertEquals(1, runs.get());
        assertTrue(timers.isEmpty(), "no timer when nothing is stuck");
    }

    @Test
    void ordinarySnapshotsKeepWaitingForever() {
        var gate = new ScarpetOwnerWorkGate();
        gate.track(new CompletableFuture<Void>());
        var runs = new AtomicInteger();

        gate.<Integer>whenIdle(snapshot -> run(runs), () -> 0);
        timers.forEach(Runnable::run);

        assertEquals(0, runs.get(), "only the mandatory removal gets a bound");
    }

    @Test
    void describesOnlyUnfinishedWork() {
        var gate = new ScarpetOwnerWorkGate();
        var finished = new CompletableFuture<Void>();
        gate.track(finished);
        gate.track(new CompletableFuture<Void>());
        finished.complete(null);

        assertEquals(1, gate.describeAccepted().size());
    }
}
