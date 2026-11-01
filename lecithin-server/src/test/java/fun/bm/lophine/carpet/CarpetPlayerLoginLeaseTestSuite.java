package fun.bm.lophine.carpet;

import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * A login that never finishes holds its UUID's file lease, and the account's next login queues behind it.
 */
@Timeout(30)
class CarpetPlayerLoginLeaseTestSuite {
    private MinecraftServer server;
    private UUID player;

    @BeforeEach
    void setUp() {
        server = mock(MinecraftServer.class);
        player = UUID.randomUUID();
    }

    private CarpetPlayerLoginLease stuckLogin() {
        var login = new CarpetPlayerLoginLease(server, player);
        login.start(CompletableFuture::new);
        assertTrue(OrgPlayerFileLease.busy(server, player));
        return login;
    }

    @Test
    void aStuckLoginLocksTheAccountOut() {
        stuckLogin();
        var second = new AtomicBoolean();

        new CarpetPlayerLoginLease(server, player).start(() -> {
            second.set(true);
            return CompletableFuture.completedFuture(null);
        });

        assertFalse(second.get(), "the next login queues behind the first login's lease");
    }

    @Test
    void abandoningEndsTheCustodySoTheNextLoginRuns() {
        var login = stuckLogin();
        var second = new AtomicBoolean();
        new CarpetPlayerLoginLease(server, player).start(() -> {
            second.set(true);
            return CompletableFuture.completedFuture(null);
        });

        login.abandon();

        assertTrue(second.get(), "same account can log in again");
        assertTrue(login.completion().isDone(), "the shutdown gate no longer waits for it");
    }

    @Test
    void aPhaseFinishingAfterTheAbandonChangesNothing() {
        var phase = new CompletableFuture<Void>();
        var login = new CarpetPlayerLoginLease(server, player);
        login.start(() -> phase);

        login.abandon();

        assertDoesNotThrow(() -> phase.complete(null), "a late phase must not see a negative phase count");
        assertFalse(OrgPlayerFileLease.busy(server, player));
    }

    @Test
    void abandoningAFinishedLoginDoesNothing() {
        var login = new CarpetPlayerLoginLease(server, player);
        login.start(() -> CompletableFuture.completedFuture(null));
        login.close();
        assertFalse(OrgPlayerFileLease.busy(server, player));

        assertDoesNotThrow(login::abandon);
    }

    @Test
    void anAdmittedPhaseStillHoldsCustodyAfterADisconnect() {
        var phase = new CompletableFuture<Void>();
        var login = new CarpetPlayerLoginLease(server, player);
        login.start(() -> phase);

        login.close();

        assertTrue(OrgPlayerFileLease.busy(server, player), "disconnect ends a login only after its admitted read finished");
        phase.complete(null);
        assertFalse(OrgPlayerFileLease.busy(server, player));
    }
}
