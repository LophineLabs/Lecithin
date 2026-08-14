package fun.bm.lophinya.compat;

import com.mojang.brigadier.ParseResults;
import com.mojang.logging.LogUtils;
import fun.bm.lecithin.config.modules.CompatConfig;
import io.papermc.paper.threadedregions.RegionizedServer;
import io.papermc.paper.threadedregions.TickRegionScheduler;
import net.minecraft.commands.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.craftbukkit.command.VanillaCommandWrapper;
import org.slf4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lophinya: hand a console-sender {@code Bukkit.dispatchCommand} back to the region that sender
 * belongs to, instead of refusing it where it was called.
 *
 * <h2>The gap, in its exact shape</h2>
 * Folia's {@code CraftServer.dispatchCommand} branches on the <b>sender</b>: an {@code Entity}
 * sender requires that entity's region thread, and a console / RCON sender requires the global
 * region. So {@code dispatchCommand(player, ...)} from that player's own event handler is already
 * legal and was measured legal - the 4x4 sender x context matrix has
 * {@code C1 player region x S1 player sender = ACCEPTED returned=true}.
 *
 * <p>What is <i>not</i> legal is the other one: a plugin handling a player event (region thread)
 * dispatching a command <b>as the console</b>, which is how reward commands are almost always
 * written. That throws out of the event handler, and everything after the dispatch line is skipped.
 * Measured on this server: LycoQuest's reward grant failed 7/7 that way.
 *
 * <h2>Why the global region here is not "route anything to the global scheduler"</h2>
 * Hard boundary 4 / {@code DEC-19} B2 forbids sending <i>arbitrary</i> sync work to the global
 * scheduler, because the owner would be a guess and the failure would move from load time to a
 * random point at run time. Neither applies here:
 *
 * <ul>
 *   <li>The owner is not inferred. {@code RegionizedServer.ensureGlobalTickThread} on the line
 *       below this one is the platform stating, in its own code, that the global region is where a
 *       console sender executes. This class sends it exactly there.</li>
 *   <li>Nothing about the work changes context. The command runs in the one place stock Folia
 *       already demands for this sender, so a command that reaches into a region it does not own
 *       fails at that access, loudly, exactly as it would have.</li>
 *   <li>It is not "all commands". An {@code Entity} sender is untouched - stock Folia's check still
 *       runs, and it still throws, because for an entity sender the honest owner is that entity's
 *       region and a caller on the wrong entity's region is a real mistake worth reporting.</li>
 * </ul>
 *
 * <h2>What survives and what does not</h2>
 * The synchronous {@code boolean} is <b>half</b> preserved, and the preserved half is the only half
 * Paper ever varied: {@code false} means "no such command", decided by the parse, and {@code true}
 * means "handed off for execution". So the parse still happens on the calling thread and a
 * {@code false} still comes back verbatim; only the execution is deferred, and it always reports
 * {@code true}, which is what Paper reports for every command that exists.
 *
 * <p>Parsing on a region thread is not a new exposure. {@code Commands} guards the dispatcher with
 * no thread check of any kind, {@code parse} is a read-only walk of the Brigadier tree, and
 * {@code VanillaCommandWrapper.tabComplete} does this same {@code getListener} + {@code parse} pair
 * off the global thread today. Building the console source stack reads the server-level respawn
 * dimension and position ({@code MinecraftServer.createCommandSourceStack}), not chunk or block
 * state, so no ownership check is on that path.
 *
 * <p><b>Not preserved, and not papered over:</b> an exception thrown while the command executes no
 * longer propagates out of the {@code dispatchCommand} call - it surfaces on the global region, on a
 * later tick. A caller that wrapped the dispatch in {@code try/catch} will no longer see it. In this
 * plugin set that changes nothing observable (of 62 dispatch callsites, 57 discard the return value
 * outright and the only real branch on it is in a BungeeCord proxy class the server never loads),
 * but the semantics did change and callers relying on them would notice.
 *
 * <p>Kill switch: {@code -Dlophinya.compat.commandDispatchHandover=false} restores stock Folia
 * refusal for everything this class would have handed off.
 */
public final class LophinyaCommandDispatch {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    /**
     * Whether a console-family dispatch on this thread should be handed to the global region.
     *
     * <p>Only a thread that is <b>ticking a region</b> qualifies. A thread with no observable
     * context is the case {@code DEC-19} B1 is about, and it stays closed: stock Folia refuses it
     * and so do we.
     */
    public static boolean shouldHandOff() {
        return CompatConfig.commandDispatchHandover
            && !RegionizedServer.isGlobalTickThread()
            && TickRegionScheduler.getCurrentRegion() != null;
    }

    /**
     * Parse here, execute on the global region.
     *
     * @param sender      the console-family sender the caller passed in
     * @param commandLine the command line, exactly as the caller passed it
     * @param redispatch  re-runs the original {@code dispatchCommand}, on the global region
     * @return {@code false} if the command does not exist (verbatim Paper behaviour), else
     *         {@code true} once the execution has been queued
     */
    public static boolean handOff(final CommandSender sender, final String commandLine,
                                  final Runnable redispatch) {
        final String command = org.apache.commons.lang3.StringUtils.normalizeSpace(commandLine.trim());
        if (!parseFindsSomething(sender, command)) {
            // Paper's only false. Kept on the calling thread so the caller still gets it synchronously.
            return false;
        }
        RegionizedServer.getInstance().addTask(redispatch);
        report(command);
        return true;
    }

    /**
     * {@code true} if the dispatcher has a node for this command.
     *
     * <p>A parse that <i>fails</i> for any other reason must not be reported as "no such command" -
     * that would turn an internal error into a silent {@code false} and the caller would read it as
     * "the command does not exist". So anything unexpected falls through to {@code true}, which
     * hands the line to the global region where the real dispatch reports the real error.
     */
    private static boolean parseFindsSomething(final CommandSender sender, final String command) {
        try {
            final CommandSourceStack source = VanillaCommandWrapper.getListener(sender);
            final ParseResults<CommandSourceStack> results = net.minecraft.server.MinecraftServer.getServer()
                .getCommands().getDispatcher().parse(command, source);
            return !results.getContext().getNodes().isEmpty();
        } catch (final Throwable t) {
            LOGGER.warn("[Lophinya] could not pre-parse '{}' off the global region; handing it off "
                + "anyway so the real dispatch reports the real error", command, t);
            return true;
        }
    }

    private static void report(final String command) {
        if (!CompatConfig.diagnostics) {
            return;
        }
        final String root = command.split(" ", 2)[0];
        if (!REPORTED.add(root)) {
            return;
        }
        LOGGER.info("[Lophinya] console-sender dispatch of '{}' was handed to the global region, "
            + "which is where stock Folia requires a console sender to execute. The parse still ran "
            + "here, so an unknown command still returns false synchronously; only the execution is "
            + "deferred, and an exception thrown by it now surfaces on the global region instead of "
            + "propagating out of this call.", root);
    }
}
