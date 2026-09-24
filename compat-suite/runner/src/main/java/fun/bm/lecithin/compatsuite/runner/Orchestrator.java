package fun.bm.lecithin.compatsuite.runner;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Drives one target through the phases. The order below is the protocol the fixtures expect; see
 * {@code Harness} in fixture-common.
 *
 * <ol>
 *   <li>boot, wait for "Done"</li>
 *   <li>{@code server} phase: scheduler cases that need no player</li>
 *   <li>two headless players join; B is moved 4096 blocks away so A and B can never share a region</li>
 *   <li>{@code players} phase: Tebex (server-control callbacks), Essentials (both chat), GriefPrevention
 *       (both trigger at once), and B starts the lifecycle tasks from its own contexts</li>
 *   <li>B disconnects; {@code lifecycle-liveness}, then {@code lifecycle-disable}</li>
 *   <li>stop</li>
 * </ol>
 */
final class Orchestrator {

    static final String PLAYER_A = "CompatA";
    static final String PLAYER_B = "CompatB";
    static final double FAR = 4096.5;

    private static final Duration BOOT_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration PHASE_SLACK = Duration.ofSeconds(30);

    private final ProtocolProfile protocol;
    private final List<String> fixtureIds;
    private final Consumer<String> log;

    Orchestrator(final ProtocolProfile protocol, final List<String> fixtureIds, final Consumer<String> log) {
        this.protocol = protocol;
        this.fixtureIds = fixtureIds;
        this.log = log;
    }

    void drive(final ServerInstance server, final SuiteConfig.Target target, final TargetRun run) throws Exception {
        server.awaitDone(BOOT_TIMEOUT);
        run.bootMillis = server.bootMillis();
        this.log.accept(target.id() + " booted in " + run.bootMillis + " ms");

        phase(server, run, "server", "", Duration.ofSeconds(6));

        try (MiniClient a = new MiniClient("127.0.0.1", target.port(), PLAYER_A, this.protocol, this.log);
             MiniClient b = new MiniClient("127.0.0.1", target.port(), PLAYER_B, this.protocol, this.log)) {
            a.connect(Duration.ofSeconds(90));
            b.connect(Duration.ofSeconds(90));
            this.log.accept("both players joined; A at " + (int) a.x() + "," + (int) a.z());
            server.command("tp " + PLAYER_B + " " + FAR + " -60 " + FAR);
            if (!b.awaitPositionNear(FAR, FAR, Duration.ofSeconds(30))) {
                run.harnessNotes.add("player B was not observed at " + FAR + "," + FAR
                        + " (last " + (int) b.x() + "," + (int) b.z() + "); GP cases may not span two regions");
            }
            Thread.sleep(2000); // let the far region form and tick before anything depends on it

            phaseStart(server, "players", PLAYER_A + " " + PLAYER_B);
            final long armed = System.nanoTime();
            Thread.sleep(1000);
            a.chat("cfx ess");
            b.chat("cfx ess");
            Thread.sleep(1500);
            a.command("cfxtrigger gp");
            b.command("cfxtrigger gp");
            Thread.sleep(500);
            b.chat("cfx life");
            b.command("cfxtrigger life");
            awaitPhase(server, run, "players", Duration.ofSeconds(20).plus(PHASE_SLACK).minusNanos(System.nanoTime() - armed));
            if (!a.connected() || !b.connected()) {
                run.harnessNotes.add("a player was disconnected during the players phase: A="
                        + a.disconnectReason() + " B=" + b.disconnectReason());
            }

            b.close();
            Thread.sleep(3000); // quit processed, B's entity retired
            phase(server, run, "lifecycle-liveness", "", Duration.ofSeconds(3));
            phase(server, run, "lifecycle-disable", "", Duration.ofSeconds(3));
        }
    }

    private void phase(final ServerInstance server, final TargetRun run, final String phase, final String args,
                       final Duration fixtureDeadline) throws IOException, InterruptedException {
        phaseStart(server, phase, args);
        awaitPhase(server, run, phase, fixtureDeadline.plus(PHASE_SLACK));
    }

    private void phaseStart(final ServerInstance server, final String phase, final String args) throws IOException {
        for (final String f : this.fixtureIds) {
            server.command("cfx-" + f + " phase " + phase + (args.isEmpty() ? "" : " " + args));
        }
    }

    /** Wait until every fixture wrote {@code phase <name> done}; record the ones that did not. */
    private void awaitPhase(final ServerInstance server, final TargetRun run, final String phase, final Duration timeout)
            throws IOException, InterruptedException {
        final long deadline = System.nanoTime() + Math.max(timeout.toNanos(), Duration.ofSeconds(5).toNanos());
        Set<String> missing = new HashSet<>(this.fixtureIds);
        while (System.nanoTime() < deadline && server.alive()) {
            missing = missingPhase(server.resultsDir(), phase);
            if (missing.isEmpty()) {
                this.log.accept(run.id + ": phase " + phase + " done for all fixtures");
                return;
            }
            Thread.sleep(250);
        }
        run.harnessNotes.add("phase " + phase + " did not complete for " + missing
                + (server.alive() ? " within " + timeout.toSeconds() + "s" : " (server process exited)"));
        this.log.accept(run.id + ": phase " + phase + " incomplete for " + missing);
    }

    private Set<String> missingPhase(final Path resultsDir, final String phase) throws IOException {
        final Set<String> missing = new HashSet<>(this.fixtureIds);
        if (!Files.isDirectory(resultsDir)) {
            return missing;
        }
        try (Stream<Path> files = Files.list(resultsDir)) {
            for (final Path f : files.filter(p -> p.toString().endsWith(".jsonl")).toList()) {
                for (final String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    if (!line.contains("\"type\":\"phase\"")) {
                        continue;
                    }
                    try {
                        final JsonObject rec = JsonParser.parseString(line).getAsJsonObject();
                        if (phase.equals(rec.get("phase").getAsString()) && "done".equals(rec.get("status").getAsString())) {
                            missing.remove(rec.get("fixture").getAsString());
                        }
                    } catch (final RuntimeException ignored) {
                        // a line still being written
                    }
                }
            }
        }
        return missing;
    }
}
