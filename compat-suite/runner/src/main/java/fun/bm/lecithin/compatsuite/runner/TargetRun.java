package fun.bm.lecithin.compatsuite.runner;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Everything one target produced: fixture records, case records, what the harness itself noticed, and
 * what the server log says about the fixtures.
 */
final class TargetRun {

    final String id;
    final String label;
    String jarSource;
    String jarSha256;
    long bootMillis = -1;
    String error;
    final List<String> harnessNotes = new ArrayList<>();
    final Map<String, JsonObject> fixtures = new TreeMap<>();
    final Map<String, JsonObject> cases = new TreeMap<>();
    final JsonObject logFindings = new JsonObject();

    TargetRun(final String id, final String label) {
        this.id = id;
        this.label = label;
    }

    static String key(final String fixture, final String caseId) {
        return fixture + "|" + caseId;
    }

    /** Read every {@code compat-results/*.jsonl} the fixtures wrote. Partial last lines are ignored. */
    void readResults(final Path resultsDir) throws IOException {
        if (!Files.isDirectory(resultsDir)) {
            return;
        }
        try (Stream<Path> files = Files.list(resultsDir)) {
            for (final Path f : files.filter(p -> p.toString().endsWith(".jsonl")).sorted().toList()) {
                for (final String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) {
                        continue;
                    }
                    final JsonObject rec;
                    try {
                        rec = JsonParser.parseString(line).getAsJsonObject();
                    } catch (final RuntimeException e) {
                        this.harnessNotes.add("unparseable result line in " + f.getFileName() + ": " + line);
                        continue;
                    }
                    switch (rec.get("type").getAsString()) {
                        case "fixture" -> this.fixtures.put(rec.get("fixture").getAsString(), rec);
                        case "case" -> this.cases.put(key(rec.get("fixture").getAsString(), rec.get("case").getAsString()), rec);
                        default -> {
                        }
                    }
                }
            }
        }
    }

    private static final String PLUGIN = "((?:CompatFixture|CfxVictim)-[A-Za-z0-9_.-]+)";
    private static final Pattern COULD_NOT_PASS = Pattern.compile("Could not pass event (\\S+) to " + PLUGIN);
    private static final Pattern TASK_FAILED = Pattern.compile(PLUGIN + ".*generated an exception");
    private static final Pattern REDISPATCH = Pattern.compile(
            "\\[Lecithin\\] " + PLUGIN + ": sync scheduler task (\\S+) \\(delay=\\d+, period=-?\\d+\\) redispatched to (.+?)\\. No ownership");
    private static final Pattern LOAD_FAILED = Pattern.compile(
            "(?:Could not load|Error occurred while enabling|Could not enable|not marked as supporting).*" + PLUGIN);

    /** Server-log facts about the suite's plugins. Diagnostic only; never part of the differential. */
    void scanLog(final List<String> lines) {
        final JsonArray couldNotPass = new JsonArray();
        final JsonArray redispatch = new JsonArray();
        final JsonArray loadFailures = new JsonArray();
        int taskFailures = 0;
        int exceptionLines = 0;
        for (final String line : lines) {
            Matcher m = COULD_NOT_PASS.matcher(line);
            if (m.find()) {
                couldNotPass.add(m.group(2) + " <- " + m.group(1));
            }
            if (TASK_FAILED.matcher(line).find()) {
                taskFailures++;
            }
            m = REDISPATCH.matcher(line);
            if (m.find()) {
                redispatch.add(m.group(1) + " " + m.group(2).replaceAll(".*\\.", "") + " -> " + m.group(3));
            }
            if (LOAD_FAILED.matcher(line).find()) {
                loadFailures.add(line.trim());
            }
            if (line.contains("Exception") && (line.contains("CompatFixture-") || line.contains("CfxVictim-")
                    || line.contains("fun.bm.lecithin.compatsuite"))) {
                exceptionLines++;
            }
        }
        this.logFindings.add("couldNotPassEvent", couldNotPass);
        this.logFindings.addProperty("taskGeneratedException", taskFailures);
        this.logFindings.addProperty("exceptionLinesNamingFixtures", exceptionLines);
        this.logFindings.add("lecithinRedispatch", redispatch);
        this.logFindings.add("pluginLoadFailures", loadFailures);
    }

    JsonObject toJson() {
        final JsonObject o = new JsonObject();
        o.addProperty("id", this.id);
        o.addProperty("label", this.label);
        o.addProperty("jarSource", this.jarSource);
        o.addProperty("jarSha256", this.jarSha256);
        o.addProperty("bootMillis", this.bootMillis);
        o.addProperty("error", this.error);
        final JsonArray notes = new JsonArray();
        this.harnessNotes.forEach(notes::add);
        o.add("harnessNotes", notes);
        final JsonObject fx = new JsonObject();
        this.fixtures.forEach(fx::add);
        o.add("fixtures", fx);
        final JsonObject cs = new JsonObject();
        this.cases.forEach(cs::add);
        o.add("cases", cs);
        o.add("logFindings", this.logFindings);
        return o;
    }

    static TargetRun fromJson(final JsonObject o) {
        final TargetRun t = new TargetRun(o.get("id").getAsString(), o.get("label").getAsString());
        t.jarSource = str(o, "jarSource");
        t.jarSha256 = str(o, "jarSha256");
        t.bootMillis = o.get("bootMillis").getAsLong();
        t.error = str(o, "error");
        o.getAsJsonArray("harnessNotes").forEach(e -> t.harnessNotes.add(e.getAsString()));
        o.getAsJsonObject("fixtures").entrySet().forEach(e -> t.fixtures.put(e.getKey(), e.getValue().getAsJsonObject()));
        o.getAsJsonObject("cases").entrySet().forEach(e -> t.cases.put(e.getKey(), e.getValue().getAsJsonObject()));
        o.getAsJsonObject("logFindings").entrySet().forEach(e -> t.logFindings.add(e.getKey(), e.getValue()));
        return t;
    }

    private static String str(final JsonObject o, final String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }
}
