package fun.bm.lecithin.compatsuite.fixture.layer;

import fun.bm.lecithin.compatsuite.fixture.Harness;
import fun.bm.lecithin.compatsuite.fixture.Layer;
import fun.bm.lecithin.compatsuite.fixture.scenario.EssentialsArchetype;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * A modern Paper plugin that is still not Folia-aware: it listens to Paper's Adventure
 * {@link AsyncChatEvent} instead of the deprecated Bukkit chat event, and still hops with the legacy
 * scheduler.
 */
public final class PaperModernLayer implements Layer, Listener {

    private Harness harness;

    @Override
    public void install(final Harness h) {
        this.harness = h;
        h.add(new EssentialsArchetype.PlayerContinuation("ess.paper_async_chat_to_legacy_runtask",
                "Paper AsyncChatEvent handler calls runTask; the task runs once on the main thread and can read/write that player",
                "ess") {
            @Override
            protected boolean acceptsEventType(final String eventType) {
                return "AsyncChatEvent".equals(eventType);
            }

            @Override
            protected void hop(final Harness harness, final Player player, final Runnable body) {
                Bukkit.getScheduler().runTask(harness.plugin, body);
            }
        });
        Bukkit.getPluginManager().registerEvents(this, h.plugin);
    }

    @EventHandler
    public void onChat(final AsyncChatEvent event) {
        this.harness.onAsyncChat(event.getPlayer(), PlainTextComponentSerializer.plainText().serialize(event.message()),
                event.isAsynchronous(), "AsyncChatEvent");
    }
}
