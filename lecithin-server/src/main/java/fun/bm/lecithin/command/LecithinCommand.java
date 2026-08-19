package fun.bm.lecithin.command;

import fun.bm.lecithin.command.sub.ContextCommand;
import fun.bm.lecithin.command.sub.WorldsCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import org.leavesmc.leaves.command.RootNode;

/**
 * Lecithin: the fork's own diagnostic command root.
 *
 * <p>Everything under it is read-only reporting of facts the platform already knows but exposes
 * through no API a server operator can reach - which world a {@code ServerLevel} really is, which
 * regioniser owns it, which region the calling thread is ticking, and what a vanilla selector
 * actually resolves to from the command source in hand. Nothing here changes server state.
 */
public class LecithinCommand extends RootNode {

    public static final String PERM_BASE = "lecithin.commands";

    public LecithinCommand() {
        super("lecithin", PERM_BASE);
        children(
                new ContextCommand(),
                new WorldsCommand()
        );
    }

    public static boolean hasPermission(@NotNull CommandSender sender, String... subcommand) {
        return hasPermission(PERM_BASE, sender, subcommand);
    }

    @Override
    public boolean requires(@NotNull CommandSourceStack source) {
        return source.getSender().isOp() || super.requires(source);
    }
}
