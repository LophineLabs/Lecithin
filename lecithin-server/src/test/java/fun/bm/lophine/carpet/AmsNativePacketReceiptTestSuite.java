package fun.bm.lophine.carpet;

import carpet.script.external.ScarpetNativeWork;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.concurrent.DefaultPromise;
import io.papermc.paper.configuration.GlobalConfiguration;
import net.minecraft.SharedConstants;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * Direct unit test suite for {@link AmsNativeCommandEffects#packet(ServerPlayer, Packet)}.
 * Verifies that a disconnected recipient discards packets normally instead of failing natively.
 */
class AmsNativePacketReceiptTestSuite {

    private EmbeddedChannel channel;
    private Connection connection;
    private ServerGamePacketListenerImpl listener;
    private ServerPlayer player;
    private MinecraftServer server;
    private final java.util.List<Runnable> expiries = new java.util.ArrayList<>();
    private final java.util.List<Long> bounds = new java.util.ArrayList<>();
    private AmsNativeCommandEffects.ReceiptTimer realTimer;

    @BeforeAll
    static void initGlobalConfig() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        GlobalConfiguration config = new GlobalConfiguration();
        config.misc = config.new Misc();
        Field instanceField = GlobalConfiguration.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        instanceField.set(null, config);
    }

    @BeforeEach
    void setUp() throws Exception {
        channel = new EmbeddedChannel();
        connection = mock(Connection.class);
        connection.channel = channel;
        when(connection.isConnected()).thenAnswer(inv -> channel.isOpen());

        listener = mock(ServerGamePacketListenerImpl.class);
        Field connectionField = ServerCommonPacketListenerImpl.class.getDeclaredField("connection");
        connectionField.setAccessible(true);
        connectionField.set(listener, connection);

        server = mock(MinecraftServer.class);
        player = mock(ServerPlayer.class);
        player.connection = listener;
        when(player.carpetSpawnServer()).thenReturn(server);

        realTimer = AmsNativeCommandEffects.receiptTimer;
        AmsNativeCommandEffects.receiptTimer = (millis, expire) -> {
            bounds.add(millis);
            expiries.add(expire);
        };
    }

    @AfterEach
    void restoreTimer() {
        AmsNativeCommandEffects.receiptTimer = realTimer;
    }

    /** Fires every receipt expiry scheduled so far, as the real timer would after the bound. */
    private void expireReceipts() {
        var due = java.util.List.copyOf(expiries);
        expiries.clear();
        due.forEach(Runnable::run);
    }

    /**
     * (a) Multiple packet() calls followed by channel close:
     * Before fix: all complete exceptionally with ClosedChannelException.
     * After fix: all complete normally (null) exactly once, with no lingering listeners on closeFuture.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void channelClosedAfterMultiplePacketsCompletesNormallyWithoutResidualListeners() {
        Packet<?> packet = mock(Packet.class);

        CompletableFuture<Void> f1 = AmsNativeCommandEffects.packet(player, packet);
        CompletableFuture<Void> f2 = AmsNativeCommandEffects.packet(player, packet);
        CompletableFuture<Void> f3 = AmsNativeCommandEffects.packet(player, packet);

        AtomicInteger f1Completions = new AtomicInteger();
        AtomicInteger f2Completions = new AtomicInteger();
        AtomicInteger f3Completions = new AtomicInteger();
        f1.whenComplete((v, t) -> f1Completions.incrementAndGet());
        f2.whenComplete((v, t) -> f2Completions.incrementAndGet());
        f3.whenComplete((v, t) -> f3Completions.incrementAndGet());

        assertFalse(f1.isDone(), "f1 should be pending before channel closes");
        assertFalse(f2.isDone(), "f2 should be pending before channel closes");
        assertFalse(f3.isDone(), "f3 should be pending before channel closes");

        CompletableFuture<Void> idleBeforeClose = ScarpetNativeWork.whenIdle(server);
        assertFalse(idleBeforeClose.isDone(), "ScarpetNativeWork must not be idle while packets are pending");

        channel.close().awaitUninterruptibly();

        assertTrue(f1.isDone(), "f1 should be done after channel close");
        assertFalse(f1.isCompletedExceptionally(), "f1 must not fail exceptionally on disconnect");
        assertNull(f1.join(), "f1 result should be null (discarded)");
        assertEquals(1, f1Completions.get(), "f1 should complete exactly once");

        assertTrue(f2.isDone(), "f2 should be done after channel close");
        assertFalse(f2.isCompletedExceptionally(), "f2 must not fail exceptionally on disconnect");
        assertNull(f2.join(), "f2 result should be null (discarded)");
        assertEquals(1, f2Completions.get(), "f2 should complete exactly once");

        assertTrue(f3.isDone(), "f3 should be done after channel close");
        assertFalse(f3.isCompletedExceptionally(), "f3 must not fail exceptionally on disconnect");
        assertNull(f3.join(), "f3 result should be null (discarded)");
        assertEquals(1, f3Completions.get(), "f3 should complete exactly once");

        assertFalse(hasListeners(channel.closeFuture()), "closeFuture must have no residual listeners");
        assertTrue(idleBeforeClose.isDone(), "idle future captured before disconnect must complete once channel closes");
        assertTrue(ScarpetNativeWork.whenIdle(server).isDone(), "native tracking on server must be idle after channel close");
    }

    /**
     * (b) Channel already closed at entry:
     * Before fix: completes exceptionally with ClosedChannelException.
     * After fix: completes normally (null) immediately.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void channelAlreadyClosedAtEntryCompletesNormally() {
        channel.close().awaitUninterruptibly();
        Packet<?> packet = mock(Packet.class);

        CompletableFuture<Void> future = AmsNativeCommandEffects.packet(player, packet);

        assertTrue(future.isDone(), "future should be done immediately at entry");
        assertFalse(future.isCompletedExceptionally(), "future must not fail exceptionally when channel is closed");
        assertNull(future.join(), "future result should be null (discarded)");
        assertTrue(ScarpetNativeWork.whenIdle(server).isDone(), "native tracking on server must be idle/cleared after immediate packet discard");
    }

    /**
     * (c) Counter-example: channel is open but send callback fails with IOException:
     * Both before and after fix: must fail exceptionally with IOException (must not be swallowed).
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void sendFailureWhileChannelOpenFailsExceptionally() {
        Packet<?> packet = mock(Packet.class);
        IOException ioException = new IOException("Simulated network IO failure");

        doAnswer(invocation -> {
            ChannelFutureListener callback = invocation.getArgument(1);
            ChannelFuture failedFuture = channel.newFailedFuture(ioException);
            callback.operationComplete(failedFuture);
            return null;
        }).when(listener).send(any(), any());

        CompletableFuture<Void> future = AmsNativeCommandEffects.packet(player, packet);

        assertTrue(future.isDone(), "future should be done");
        assertTrue(future.isCompletedExceptionally(), "future must fail exceptionally when channel is open and send fails");

        ExecutionException ex = assertThrows(ExecutionException.class, future::get);
        assertSame(ioException, ex.getCause(), "cause must be the original IOException");
    }

    /**
     * (d) thenCompose tail after packet():
     * Before fix: tail does not execute when client disconnects (packet future failed).
     * After fix: tail executes exactly once.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void thenComposeTailExecutesOnceWhenChannelCloses() {
        Packet<?> packet = mock(Packet.class);
        CompletableFuture<Void> packetFuture = AmsNativeCommandEffects.packet(player, packet);

        AtomicInteger tailInvocations = new AtomicInteger();
        CompletableFuture<String> tail = packetFuture.thenCompose(ignored -> {
            tailInvocations.incrementAndGet();
            return CompletableFuture.completedFuture("tail-completed");
        });

        assertFalse(packetFuture.isDone(), "packet future should be pending before channel closes");
        assertEquals(0, tailInvocations.get(), "tail should not have run before channel closes");

        channel.close().awaitUninterruptibly();

        assertTrue(packetFuture.isDone(), "packet future should be done after channel close");
        assertFalse(packetFuture.isCompletedExceptionally(), "packet future must complete normally");

        assertTrue(tail.isDone(), "tail future should be done after packet future completes");
        assertFalse(tail.isCompletedExceptionally(), "tail future must complete normally");
        assertEquals(1, tailInvocations.get(), "tail should execute exactly once");
        assertEquals("tail-completed", tail.join());
    }

    /**
     * (e) Channel stays open, but the write promise is never completed by the pipeline.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void openChannelNeverAckedStillTerminates() {
        CompletableFuture<Void> future = AmsNativeCommandEffects.packet(player, mock(Packet.class));

        assertFalse(future.isDone(), "the receipt still waits for the write before its bound");
        assertFalse(ScarpetNativeWork.whenIdle(server).isDone());
        assertEquals(java.util.List.of(fun.bm.lecithin.config.modules.CompatConfig.packetReceiptTimeoutMillis), bounds,
                "exactly one expiry, at the configured bound");
        expireReceipts();

        assertTrue(channel.isOpen(), "the channel stays open throughout");
        assertTrue(future.isDone(), "a receipt whose write is never acknowledged must still reach a terminal state");
        assertNull(future.join());
        assertFalse(future.isCompletedExceptionally());
        assertFalse(hasListeners(channel.closeFuture()), "closeFuture must have no residual listeners");
        assertTrue(ScarpetNativeWork.whenIdle(server).isDone(), "native tracking must be idle once the receipt is terminal");
    }

    /**
     * (f) The explosion barrier over two recipients, one of which is never acknowledged.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void fanOutResumesWhenOneRecipientNeverAcks() throws Exception {
        doAnswer(invocation -> {
            ChannelFutureListener callback = invocation.getArgument(1);
            callback.operationComplete(channel.newSucceededFuture());
            return null;
        }).when(listener).send(any(), any());
        ServerPlayer silent = recipient(new EmbeddedChannel());

        CompletableFuture<Void> barrier = fanOut(java.util.List.of(player, silent));

        assertFalse(barrier.isDone(), "the barrier still waits for the unacknowledged recipient before its bound");
        assertEquals(1, expiries.size(), "only the unacknowledged recipient is bounded");
        expireReceipts();

        assertTrue(barrier.isDone(), "the barrier must resume although one recipient never acknowledges");
        assertFalse(barrier.isCompletedExceptionally());
    }

    /**
     * (h) The interceptor defers the packet and re-sends it later without the original callback
     * (an outbound listener that must run on another thread), so the original promise is lost.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void deferredResendWithoutOriginalCallbackStillTerminates() {
        var deferred = new java.util.ArrayList<Runnable>();
        doAnswer(invocation -> {
            Packet<?> sent = invocation.getArgument(0);
            deferred.add(() -> channel.writeAndFlush(sent)); // re-sent with a fresh promise
            return null;
        }).when(listener).send(any(), any());

        CompletableFuture<Void> future = AmsNativeCommandEffects.packet(player, mock(Packet.class));
        deferred.forEach(Runnable::run);

        assertTrue(channel.isOpen());
        assertEquals(1, channel.outboundMessages().size(), "the deferred packet is still written");
        assertFalse(future.isDone(), "the lost callback alone never completes the receipt");
        expireReceipts();

        assertTrue(future.isDone(), "the bound completes a receipt whose callback the interceptor dropped");
        assertFalse(future.isCompletedExceptionally());
        assertFalse(hasListeners(channel.closeFuture()), "closeFuture must have no residual listeners");
        assertTrue(ScarpetNativeWork.whenIdle(server).isDone());
    }

    /**
     * (i) An outbound interceptor cancels the packet for every recipient: no promise ever
     * completes and every channel stays open. With the bound removed (the code before this fix)
     * the barrier never resumes; with the bound it does.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void fanOutCompletesWhenEveryRecipientCancels() throws Exception {
        var channels = java.util.List.of(channel, new EmbeddedChannel(), new EmbeddedChannel());
        var recipients = new java.util.ArrayList<ServerPlayer>();
        recipients.add(player);
        recipients.add(recipient(channels.get(1)));
        recipients.add(recipient(channels.get(2)));
        for (ServerPlayer target : recipients)
            doAnswer(invocation -> null).when(target.connection).send(any(), any()); // cancelled: callback dropped, nothing written

        long bound = fun.bm.lecithin.config.modules.CompatConfig.packetReceiptTimeoutMillis;
        CompletableFuture<Void> unbounded;
        try {
            fun.bm.lecithin.config.modules.CompatConfig.packetReceiptTimeoutMillis = 0;
            unbounded = fanOut(recipients);
        } finally {
            fun.bm.lecithin.config.modules.CompatConfig.packetReceiptTimeoutMillis = bound;
        }
        assertTrue(expiries.isEmpty());
        assertFalse(unbounded.isDone(), "without the bound the barrier never resumes while every channel stays open");

        CompletableFuture<Void> bounded = fanOut(recipients);
        assertFalse(bounded.isDone());
        assertEquals(3, expiries.size(), "one bound per cancelled recipient");
        expireReceipts();

        channels.forEach(open -> assertTrue(open.isOpen()));
        assertTrue(bounded.isDone(), "the barrier resumes once every cancelled receipt reaches its bound");
        assertFalse(bounded.isCompletedExceptionally());
        assertFalse(unbounded.isDone(), "the unbounded receipts still only end on disconnect");
        channels.forEach(open -> open.close().awaitUninterruptibly());
        assertTrue(unbounded.isDone());
        channels.forEach(closed -> assertFalse(hasListeners(closed.closeFuture())));
    }

    /**
     * (j) The ender dragon death sound (world event 1028) waits on the same bounded receipt.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void dragonDeathSoundReceiptIsBounded() throws Exception {
        var send = Class.forName("carpet.script.external.ScarpetDragonDeathContinuations")
                .getDeclaredMethod("send", ServerPlayer.class, net.minecraft.core.BlockPos.class);
        send.setAccessible(true);

        CompletableFuture<Object> death = ScarpetNativeWork.observeNative(player, () -> {
            try {
                return send.invoke(null, player, net.minecraft.core.BlockPos.ZERO);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        });

        verify(listener).send(argThat(sent -> sent instanceof net.minecraft.network.protocol.game.ClientboundLevelEventPacket event
                && event.getType() == net.minecraft.world.level.block.LevelEvent.SOUND_DRAGON_DEATH), any());
        assertFalse(death.isDone(), "the death phase waits for its sound receipt");
        assertEquals(1, expiries.size(), "the dragon death sound receipt is bounded");
        expireReceipts();

        assertTrue(channel.isOpen());
        assertTrue(death.isDone(), "the death phase resumes although the sound write is never acknowledged");
        assertFalse(death.isCompletedExceptionally());
        assertFalse(hasListeners(channel.closeFuture()));
    }

    /**
     * (g) An acknowledgement, failure or close arriving after the bound expired, and an expiry
     * arriving after the acknowledgement, neither complete twice nor throw.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void lateAckAfterTerminalIsIgnored() throws Exception {
        var callbacks = new java.util.ArrayList<ChannelFutureListener>();
        doAnswer(invocation -> {
            callbacks.add(invocation.getArgument(1));
            return null;
        }).when(listener).send(any(), any());

        CompletableFuture<Void> expired = AmsNativeCommandEffects.packet(player, mock(Packet.class));
        AtomicInteger expiredCompletions = new AtomicInteger();
        expired.whenComplete((v, t) -> expiredCompletions.incrementAndGet());
        CompletableFuture<Void> acked = AmsNativeCommandEffects.packet(player, mock(Packet.class));
        AtomicInteger ackedCompletions = new AtomicInteger();
        acked.whenComplete((v, t) -> ackedCompletions.incrementAndGet());
        assertEquals(2, callbacks.size());

        // Second receipt: its write is acknowledged first, then its expiry fires.
        callbacks.get(1).operationComplete(channel.newSucceededFuture());
        assertTrue(acked.isDone());

        // First receipt: its expiry fires first, then a failure, a success and a close arrive.
        expireReceipts();
        assertTrue(expired.isDone());
        assertDoesNotThrow(() -> callbacks.get(0).operationComplete(channel.newFailedFuture(new IOException("late failure"))));
        assertDoesNotThrow(() -> callbacks.get(0).operationComplete(channel.newSucceededFuture()));
        assertDoesNotThrow(() -> channel.close().awaitUninterruptibly());

        assertFalse(expired.isCompletedExceptionally(), "a failure arriving after the bound does not replace the terminal state");
        assertNull(expired.join());
        assertEquals(1, expiredCompletions.get(), "the expired receipt completes exactly once");
        assertFalse(acked.isCompletedExceptionally());
        assertEquals(1, ackedCompletions.get(), "the acknowledged receipt completes exactly once");
        assertFalse(hasListeners(channel.closeFuture()), "closeFuture must have no residual listeners");
        assertTrue(ScarpetNativeWork.whenIdle(server).isDone());
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<Void> fanOut(java.util.List<ServerPlayer> players) throws Exception {
        Class<?> barrier = Class.forName("carpet.script.external.ScarpetExplosionPacketBarrier");
        var method = barrier.getDeclaredMethod("fanOut", CompletableFuture.class, java.util.function.Function.class);
        method.setAccessible(true);
        java.util.function.Function<ServerPlayer, CompletableFuture<?>> send =
                target -> AmsNativeCommandEffects.packet(target, mock(Packet.class));
        return (CompletableFuture<Void>) method.invoke(null, CompletableFuture.completedFuture(players), send);
    }

    private ServerPlayer recipient(EmbeddedChannel ownChannel) throws Exception {
        Connection ownConnection = mock(Connection.class);
        ownConnection.channel = ownChannel;
        when(ownConnection.isConnected()).thenAnswer(inv -> ownChannel.isOpen());
        ServerGamePacketListenerImpl ownListener = mock(ServerGamePacketListenerImpl.class);
        Field connectionField = ServerCommonPacketListenerImpl.class.getDeclaredField("connection");
        connectionField.setAccessible(true);
        connectionField.set(ownListener, ownConnection);
        ServerPlayer recipient = mock(ServerPlayer.class);
        recipient.connection = ownListener;
        when(recipient.carpetSpawnServer()).thenReturn(server);
        return recipient;
    }

    private static boolean hasListeners(ChannelFuture future) {
        try {
            Field listenersField = DefaultPromise.class.getDeclaredField("listeners");
            listenersField.setAccessible(true);
            Object listeners = listenersField.get(future);
            return listeners != null;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
