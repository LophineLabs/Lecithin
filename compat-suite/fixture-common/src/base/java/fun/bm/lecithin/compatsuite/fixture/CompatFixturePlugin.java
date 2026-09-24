package fun.bm.lecithin.compatsuite.fixture;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.logging.Level;

/**
 * The fixture plugin. The same source is compiled once per API era; nothing in here may use an API newer
 * than the oldest era that lists the {@code base} layer (Bukkit/Spigot 1.8.8).
 *
 * <p>Deliberately declares neither {@code folia-supported} nor anything Folia-specific: the suite tests
 * how an unmodified Bukkit/Paper plugin is treated.
 */
public final class CompatFixturePlugin extends JavaPlugin implements Listener {

    private Harness harness;

    @Override
    public void onEnable() {
        try {
            this.harness = new Harness(this);
            this.harness.installLayers();
        } catch (final Exception e) {
            getLogger().log(Level.SEVERE, "[compat-suite] fixture setup failed", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(this, this);
        this.harness.enable();
        getLogger().info("[compat-suite] fixture " + this.harness.fixtureId + " enabled; control command /"
                + this.harness.command);
    }

    @Override
    public void onDisable() {
        if (this.harness != null) {
            this.harness.disable();
        }
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
        if (this.harness == null || args.length < 2) {
            return false;
        }
        if ("phase".equals(args[0])) {
            final boolean started = this.harness.startPhase(args[1], Arrays.copyOfRange(args, 2, args.length));
            sender.sendMessage("[compat-suite] " + this.harness.fixtureId + " phase " + args[1]
                    + (started ? " started" : " ignored (unknown or already started)"));
            return true;
        }
        if ("sink".equals(args[0])) {
            this.harness.recordSink(args[1]);
            return true;
        }
        return false;
    }

    /** Essentials-shaped entry point: a real client's chat, dispatched asynchronously by the platform. */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onChat(final AsyncPlayerChatEvent event) {
        if (this.harness != null) {
            this.harness.onAsyncChat(event.getPlayer(), event.getMessage(), event.isAsynchronous(),
                    "AsyncPlayerChatEvent");
        }
    }

    /** Region-thread entry point: {@code /cfxtrigger <name>} from a real client. */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onCommandPreprocess(final PlayerCommandPreprocessEvent event) {
        final String message = event.getMessage();
        if (this.harness == null || !message.startsWith(Harness.TRIGGER_PREFIX)) {
            return;
        }
        event.setCancelled(true);
        this.harness.onTrigger(event.getPlayer(), message.substring(Harness.TRIGGER_PREFIX.length()).trim());
    }

    Harness harness() {
        return this.harness;
    }
}
