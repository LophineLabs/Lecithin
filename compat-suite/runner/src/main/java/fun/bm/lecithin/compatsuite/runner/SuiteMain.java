package fun.bm.lecithin.compatsuite.runner;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Entry point.
 *
 * <pre>
 * run    --suite suite.properties --fixtures build/fixtures --out build/compat-runs
 *        [--targets paper,lecithin] [--lecithinJar path] [--paperJar path] [--java path] [--runId id]
 * report --suite suite.properties --run build/compat-runs/&lt;id&gt;
 * </pre>
 * Exit code 0 when no case is REGRESSION / MISSING / HARNESS_ERROR / REFERENCE_INVALID and every target
 * booted; registered differences (KNOWN_FAIL, ...) do not fail the run - they are listed.
 */
public final class SuiteMain {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping().create();

    private SuiteMain() {
    }

    public static void main(final String[] argv) throws Exception {
        if (argv.length == 0) {
            usage();
        }
        final Map<String, String> opts = new HashMap<>();
        for (int i = 1; i < argv.length; i++) {
            if (!argv[i].startsWith("--") || i + 1 >= argv.length) {
                usage();
            }
            opts.put(argv[i].substring(2), argv[++i]);
        }
        final SuiteConfig config = SuiteConfig.load(Path.of(opts.getOrDefault("suite", "suite.properties")));
        final int exit = switch (argv[0]) {
            case "run" -> run(config, opts);
            case "report" -> report(config, Path.of(required(opts, "run")));
            default -> {
                usage();
                yield 2;
            }
        };
        System.exit(exit);
    }

    private static int run(final SuiteConfig config, final Map<String, String> opts) throws Exception {
        final Path fixturesDir = Path.of(opts.getOrDefault("fixtures", "build/fixtures")).toAbsolutePath();
        final List<Path> pluginJars = new ArrayList<>();
        final List<String> fixtureIds = new ArrayList<>();
        try (Stream<Path> s = Files.list(fixturesDir)) {
            for (final Path jar : s.sorted().toList()) {
                final String name = jar.getFileName().toString();
                if (name.startsWith("CompatFixture-") && name.endsWith(".jar")) {
                    final String id = name.substring("CompatFixture-".length(), name.length() - 4);
                    final Path victim = fixturesDir.resolve("CfxVictim-" + id + ".jar");
                    if (!Files.isRegularFile(victim)) {
                        throw new IOException("fixture " + id + " has no victim jar " + victim);
                    }
                    fixtureIds.add(id);
                    pluginJars.add(jar);
                    pluginJars.add(victim);
                }
            }
        }
        if (fixtureIds.isEmpty()) {
            throw new IOException("no fixture jars in " + fixturesDir + " - run the 'fixtures' task first");
        }

        List<SuiteConfig.Target> targets = config.select(SuiteConfig.split(opts.get("targets")));
        targets = new ArrayList<>(targets.stream().map(t -> {
            if (t.id().equals("lecithin") && opts.containsKey("lecithinJar")) {
                return config.withJar(t, Path.of(opts.get("lecithinJar")).toAbsolutePath().toString());
            }
            if (t.id().equals(config.reference) && opts.containsKey("paperJar")) {
                return config.withJar(t, Path.of(opts.get("paperJar")).toAbsolutePath().toString());
            }
            return t;
        }).toList());
        final Map<String, SuiteConfig.Target> byId = new HashMap<>(config.targets);
        targets.forEach(t -> byId.put(t.id(), t));

        final String runId = opts.getOrDefault("runId", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        final Path runDir = Path.of(opts.getOrDefault("out", "build/compat-runs")).toAbsolutePath().resolve(runId);
        Files.createDirectories(runDir);
        final String java = opts.getOrDefault("java", ProcessHandle.current().info().command().orElse("java"));
        final ProtocolProfile protocol = ProtocolProfile.load(config.protocol);
        final JarSource jars = new JarSource(config.baseDir);
        final Orchestrator orchestrator = new Orchestrator(protocol, fixtureIds, SuiteMain::log);

        log("run " + runId + ": fixtures " + fixtureIds + ", targets " + targets.stream().map(SuiteConfig.Target::id).toList());
        final List<TargetRun> runs = new ArrayList<>();
        for (final SuiteConfig.Target target : targets) {
            final TargetRun run = new TargetRun(target.id(), target.label());
            runs.add(run);
            final Path targetDir = runDir.resolve("targets").resolve(target.id());
            ServerInstance server = null;
            try {
                final JarSource.Resolved jar = jars.resolve(target.jar(), byId);
                run.jarSource = jar.source();
                run.jarSha256 = jar.sha256();
                log(target.id() + ": " + jar.path() + " (sha256 " + jar.sha256() + ")");
                server = new ServerInstance(target, config.baseDir.resolve(".cache/instances").resolve(target.id()),
                        jar.path(), targetDir.resolve("console.log"), java,
                        Arrays.asList(config.javaOpts.split("\\s+")), SuiteMain::log);
                server.prepare(pluginJars);
                server.start();
                orchestrator.drive(server, target, run);
            } catch (final Exception e) {
                run.error = e.toString();
                log(target.id() + " FAILED: " + e);
            } finally {
                if (server != null) {
                    if (!server.stop(Duration.ofSeconds(120))) {
                        run.harnessNotes.add("server did not stop within 120 s and was killed");
                    }
                    run.readResults(server.resultsDir());
                    run.scanLog(server.consoleLines());
                    copyResults(server.resultsDir(), targetDir.resolve("results"));
                }
                Files.createDirectories(targetDir);
                Files.writeString(targetDir.resolve("target.json"), GSON.toJson(run.toJson()), StandardCharsets.UTF_8);
            }
        }
        final JsonObject meta = new JsonObject();
        meta.addProperty("runId", runId);
        meta.addProperty("suiteCommit", gitCommit(config.baseDir));
        final JsonArray order = new JsonArray();
        runs.forEach(r -> order.add(r.id));
        meta.add("targets", order);
        Files.writeString(runDir.resolve("run.json"), GSON.toJson(meta), StandardCharsets.UTF_8);
        Files.writeString(runDir.getParent().resolve("latest.txt"), runId + "\n", StandardCharsets.UTF_8);
        return evaluate(config, runDir, runId, meta.get("suiteCommit").getAsString(), runs);
    }

    private static int report(final SuiteConfig config, final Path runDir) throws IOException {
        final JsonObject meta = JsonParser.parseString(Files.readString(runDir.resolve("run.json"))).getAsJsonObject();
        final List<TargetRun> runs = new ArrayList<>();
        for (final var id : meta.getAsJsonArray("targets")) {
            runs.add(TargetRun.fromJson(JsonParser.parseString(Files.readString(
                    runDir.resolve("targets").resolve(id.getAsString()).resolve("target.json"))).getAsJsonObject()));
        }
        return evaluate(config, runDir, meta.get("runId").getAsString(), meta.get("suiteCommit").getAsString(), runs);
    }

    private static int evaluate(final SuiteConfig config, final Path runDir, final String runId, final String commit,
                                final List<TargetRun> runs) throws IOException {
        final List<Differential.Expectation> expectations = Differential.loadExpectations(config.baseDir.resolve("expectations.json"));
        final TargetRun reference = runs.get(0);
        final List<Differential.Verdict> referenceCheck = Differential.referenceCheck(reference);
        final List<Differential.Verdict> verdicts = new ArrayList<>();
        for (final TargetRun r : runs.subList(1, runs.size())) {
            verdicts.addAll(Differential.compare(reference, r, expectations));
        }

        final JsonObject results = new JsonObject();
        results.addProperty("schema", 1);
        results.addProperty("runId", runId);
        results.addProperty("suiteCommit", commit);
        results.addProperty("minecraft", config.minecraft);
        results.addProperty("reference", reference.id);
        final JsonArray targets = new JsonArray();
        runs.forEach(r -> targets.add(r.toJson()));
        results.add("targets", targets);
        final JsonArray refJson = new JsonArray();
        referenceCheck.forEach(v -> refJson.add(Differential.toJson(v)));
        results.add("referenceCheck", refJson);
        final JsonArray diffJson = new JsonArray();
        verdicts.forEach(v -> diffJson.add(Differential.toJson(v)));
        results.add("differential", diffJson);
        Files.writeString(runDir.resolve("results.json"), GSON.toJson(results), StandardCharsets.UTF_8);
        final Path summary = runDir.resolve("summary.md");
        Files.writeString(summary, Report.render(runId, config, commit, runs, referenceCheck, verdicts), StandardCharsets.UTF_8);

        final Map<String, Integer> counts = new java.util.TreeMap<>();
        verdicts.forEach(v -> counts.merge(v.target() + " " + v.classification(), 1, Integer::sum));
        referenceCheck.stream().filter(v -> v.classification().equals("REFERENCE_INVALID"))
                .forEach(v -> counts.merge(v.target() + " REFERENCE_INVALID", 1, Integer::sum));
        log("verdicts: " + counts);
        log("summary: " + summary);
        log("results: " + runDir.resolve("results.json"));
        final boolean failed = runs.stream().anyMatch(r -> r.error != null)
                || verdicts.stream().anyMatch(v -> Differential.FAILING.contains(v.classification()))
                || referenceCheck.stream().anyMatch(v -> v.classification().equals("REFERENCE_INVALID"));
        final long unexpectedPass = verdicts.stream().filter(v -> v.classification().equals("UNEXPECTED_PASS")).count();
        if (unexpectedPass > 0) {
            log(unexpectedPass + " registered difference(s) no longer differ - update expectations.json");
        }
        return failed ? 1 : 0;
    }

    private static void copyResults(final Path from, final Path to) throws IOException {
        if (!Files.isDirectory(from)) {
            return;
        }
        Files.createDirectories(to);
        try (Stream<Path> s = Files.list(from)) {
            for (final Path p : s.toList()) {
                Files.copy(p, to.resolve(p.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static String gitCommit(final Path dir) {
        try {
            final Process p = new ProcessBuilder("git", "-C", dir.toString(), "describe", "--always", "--dirty", "--abbrev=10")
                    .redirectErrorStream(true).start();
            final String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0 ? out : "unknown";
        } catch (final Exception e) {
            return "unknown";
        }
    }

    private static String required(final Map<String, String> opts, final String key) {
        final String v = opts.get(key);
        if (v == null) {
            System.err.println("missing --" + key);
            System.exit(2);
        }
        return v;
    }

    static void log(final String message) {
        System.out.println("[compat-suite " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "] " + message);
    }

    private static void usage() {
        System.err.println("usage: run|report [--suite f] [--fixtures d] [--out d] [--targets a,b] [--lecithinJar f] [--paperJar f] [--run d]");
        System.exit(2);
    }
}
