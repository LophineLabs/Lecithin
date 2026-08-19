package fun.bm.lecithin.command.sub;

import fun.bm.lecithin.command.LecithinCommand;
import fun.bm.lecithin.command.LecithinContextReport;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.command.CommandContext;
import org.leavesmc.leaves.command.LiteralNode;

/**
 * Lecithin: {@code /lecithin context} - report the execution context of this very command.
 *
 * <p>Read-only. It answers, for the source that ran it: which thread, which tick region, which
 * regioniser and which world that region belongs to, which {@code ServerLevel} and position the
 * command source carries, and which player a vanilla selector picks from there. Running it as a
 * player, from a command block and from the console gives the three answers that together explain
 * every "the selector used the wrong world" report.
 */
public class ContextCommand extends LiteralNode {

    public ContextCommand() {
        super("context");
    }

    @Override
    public boolean requires(@NotNull CommandSourceStack source) {
        return LecithinCommand.hasPermission(source.getSender(), this.name);
    }

    @Override
    protected boolean execute(@NotNull CommandContext context) {
        final net.minecraft.commands.CommandSourceStack nms =
                ((io.papermc.paper.command.brigadier.PaperCommandSourceStack) context.getSource()).getHandle();
        for (final String line : LecithinContextReport.context(nms)) {
            context.getSender().sendMessage(Component.text(line, NamedTextColor.GRAY));
        }
        return true;
    }
}
