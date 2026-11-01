package carpet.script.external;

import fun.bm.lecithin.config.modules.CompatConfig;
import io.papermc.paper.configuration.GlobalConfiguration;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * {@link ScarpetPlayerInventoryGate#boundRemovalWait}: what stands between a stuck tail and a player
 * who stays listed, refusing every login of that account as a duplicate.
 */
@Timeout(30)
class ScarpetPlayerRemovalBoundTestSuite {
    private final List<Runnable> expiries = new ArrayList<>();
    private final List<Long> bounds = new ArrayList<>();
    private ScarpetPlayerInventoryGate.RemovalTimer realTimer;
    private long realBound;
    private ServerPlayer player;

    @BeforeAll
    static void bootstrap() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        GlobalConfiguration config = new GlobalConfiguration();
        config.misc = config.new Misc();
        Field instance = GlobalConfiguration.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, config);
    }

    @BeforeEach
    void setUp() {
        realTimer = ScarpetPlayerInventoryGate.removalTimer;
        realBound = CompatConfig.playerRemovalTimeoutSeconds;
        ScarpetPlayerInventoryGate.removalTimer = (seconds, expire) -> {
            bounds.add(seconds);
            expiries.add(expire);
        };
        CompatConfig.playerRemovalTimeoutSeconds = 60;
        player = mock(ServerPlayer.class);
    }

    @AfterEach
    void tearDown() {
        ScarpetPlayerInventoryGate.removalTimer = realTimer;
        CompatConfig.playerRemovalTimeoutSeconds = realBound;
    }

    @Test
    void workThatNeverFinishesIsReleasedAtTheConfiguredBound() {
        var waited = ScarpetPlayerInventoryGate.boundRemovalWait(player, new CompletableFuture<>());

        assertFalse(waited.isDone());
        assertEquals(List.of(60L), bounds);
        expiries.forEach(Runnable::run);
        assertTrue(waited.isDone());
    }

    @Test
    void workFinishingInTimeEndsTheWaitWithoutTheTimer() {
        var pending = new CompletableFuture<Void>();
        var waited = ScarpetPlayerInventoryGate.boundRemovalWait(player, pending);

        pending.complete(null);

        assertTrue(waited.isDone());
        assertDoesNotThrow(() -> expiries.forEach(Runnable::run), "the armed timer finds the wait over");
    }

    @Test
    void aPlainLogoutArmsNoTimerAndStaysSynchronous() {
        var done = CompletableFuture.<Void>completedFuture(null);

        assertSame(done, ScarpetPlayerInventoryGate.boundRemovalWait(player, done));
        assertTrue(expiries.isEmpty());
    }

    @Test
    void zeroRemovesTheBound() {
        CompatConfig.playerRemovalTimeoutSeconds = 0;
        var pending = new CompletableFuture<Void>();

        assertSame(pending, ScarpetPlayerInventoryGate.boundRemovalWait(player, pending));
        assertTrue(expiries.isEmpty());
    }
}
