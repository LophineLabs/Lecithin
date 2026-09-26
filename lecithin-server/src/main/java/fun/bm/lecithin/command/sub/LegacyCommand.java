package fun.bm.lecithin.command.sub;

import fun.bm.lecithin.command.LecithinCommand;
import fun.bm.lecithin.compat.legacy.LegacyRuntimeReport;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.command.CommandContext;
import org.leavesmc.leaves.command.LiteralNode;

/**
 * Lecithin: {@code /lecithin legacy} - what the Legacy Paper Runtime is doing.
 *
 * <p>Read-only. Lists which loaded plugins are managed and which run natively, and for each managed
 * plugin how often each entry point entered its domain, where its sync tasks were routed and where
 * their bodies actually ran, how much its domain was contended, and whether any scheduled body ran on
 * the global region thread (it should not).
 */
public class LegacyCommand extends LiteralNode {

    public LegacyCommand() {
        super("legacy");
    }

    @Override
    public boolean requires(@NotNull CommandSourceStack source) {
        return LecithinCommand.hasPermission(source.getSender(), this.name);
    }

    @Override
    protected boolean execute(@NotNull CommandContext context) {
        for (final String line : LegacyRuntimeReport.lines()) {
            context.getSender().sendMessage(Component.text(line));
        }
        return true;
    }
}
