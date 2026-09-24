package fun.bm.lecithin.compatsuite.runner;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Compares every target against the reference, case by case, on the {@code observed} fields only.
 *
 * <h2>Classifications</h2>
 * <ul>
 *   <li>{@code PASS} - every observed field equals the reference.</li>
 *   <li>{@code REGRESSION} - a field differs and nothing in {@code expectations.json} explains it.</li>
 *   <li>{@code MISSING} - the reference produced the case, the target did not (plugin not loaded,
 *       phase never completed, server died).</li>
 *   <li>{@code HARNESS_ERROR} - the scenario code itself threw on the target.</li>
 *   <li>registered differences, from {@code expectations.json}: {@code KNOWN_FAIL},
 *       {@code EXPECTED_FOLIA_DIFFERENCE}, {@code LECITHIN_EXTENSION}, {@code UNSUPPORTED_BY_DESIGN},
 *       {@code SEMANTICALLY_EQUIVALENT}. An entry may name the {@code fields} it covers; a difference in
 *       any other field is still a REGRESSION.</li>
 *   <li>{@code UNEXPECTED_PASS} - a registered difference no longer differs; update the registry.</li>
 * </ul>
 * The reference itself is checked against the scenario's own Paper contract: a reference FAIL/ERROR is
 * {@code REFERENCE_INVALID}, meaning the testcase, not the platform, is wrong.
 */
final class Differential {

    static final List<String> REGISTERED = List.of("KNOWN_FAIL", "EXPECTED_FOLIA_DIFFERENCE", "LECITHIN_EXTENSION",
            "UNSUPPORTED_BY_DESIGN", "SEMANTICALLY_EQUIVALENT");
    /** Verdicts that make the run fail. */
    static final List<String> FAILING = List.of("REGRESSION", "MISSING", "HARNESS_ERROR", "REFERENCE_INVALID");

    /**
     * @param fields   observed fields this entry explains; empty = any field
     * @param optional the difference may or may not appear (diagnostic variants such as a feature switched
     *                 off); when it does not appear the case is plain PASS instead of UNEXPECTED_PASS
     */
    record Expectation(String target, String fixture, String caseId, String classification, String category,
                       List<String> fields, boolean optional, String reason, String tracking) {

        boolean covers(final List<FieldDiff> diffs) {
            return this.fields.isEmpty() || diffs.stream().allMatch(d -> this.fields.contains(d.field()));
        }

        boolean matches(final String t, final String f, final String c) {
            return glob(this.target, t) && glob(this.fixture, f) && glob(this.caseId, c);
        }

        private static boolean glob(final String pattern, final String value) {
            return value.matches(("\\Q" + pattern + "\\E").replace("*", "\\E.*\\Q"));
        }
    }

    record FieldDiff(String field, JsonElement reference, JsonElement target) {
    }

    record Verdict(String target, String fixture, String caseId, String archetype, String description,
                   String classification, String targetStatus, List<FieldDiff> diffs, Expectation expectation) {
    }

    static List<Expectation> loadExpectations(final Path file) throws IOException {
        final List<Expectation> out = new ArrayList<>();
        if (!Files.isRegularFile(file)) {
            return out;
        }
        final JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        for (final JsonElement e : root.getAsJsonArray("expectations")) {
            final JsonObject o = e.getAsJsonObject();
            final String classification = o.get("classification").getAsString();
            if (!REGISTERED.contains(classification)) {
                throw new IOException("expectations.json: classification must be one of " + REGISTERED + ", got " + classification);
            }
            final List<String> fields = new ArrayList<>();
            if (o.has("fields")) {
                o.getAsJsonArray("fields").forEach(x -> fields.add(x.getAsString()));
            }
            out.add(new Expectation(o.get("target").getAsString(), o.has("fixture") ? o.get("fixture").getAsString() : "*",
                    o.get("case").getAsString(), classification,
                    o.has("category") ? o.get("category").getAsString() : null, fields,
                    o.has("optional") && o.get("optional").getAsBoolean(),
                    o.get("reason").getAsString(), o.has("tracking") ? o.get("tracking").getAsString() : null));
        }
        return out;
    }

    /** Contract self-check of the reference: every reference case must PASS or be OBSERVED. */
    static List<Verdict> referenceCheck(final TargetRun reference) {
        final List<Verdict> out = new ArrayList<>();
        for (final Map.Entry<String, JsonObject> e : reference.cases.entrySet()) {
            final JsonObject rec = e.getValue();
            final String status = rec.get("status").getAsString();
            final String cls = "PASS".equals(status) || "OBSERVED".equals(status) ? "REFERENCE_OK" : "REFERENCE_INVALID";
            final List<FieldDiff> diffs = new ArrayList<>();
            if (!"REFERENCE_OK".equals(cls)) {
                final JsonObject exp = rec.getAsJsonObject("expected");
                final JsonObject obs = rec.getAsJsonObject("observed");
                for (final String k : exp.keySet()) {
                    if (!exp.get(k).equals(obs.get(k))) {
                        diffs.add(new FieldDiff(k, exp.get(k), orNull(obs.get(k))));
                    }
                }
            }
            out.add(new Verdict(reference.id, str(rec, "fixture"), str(rec, "case"), str(rec, "archetype"),
                    str(rec, "description"), cls, status, diffs, null));
        }
        return out;
    }

    static List<Verdict> compare(final TargetRun reference, final TargetRun target, final List<Expectation> expectations) {
        final List<Verdict> out = new ArrayList<>();
        final TreeSet<String> keys = new TreeSet<>(reference.cases.keySet());
        keys.addAll(target.cases.keySet());
        for (final String key : keys) {
            final JsonObject ref = reference.cases.get(key);
            final JsonObject tgt = target.cases.get(key);
            final JsonObject any = ref != null ? ref : tgt;
            final String fixture = str(any, "fixture");
            final String caseId = str(any, "case");
            final List<Expectation> matching = expectations.stream().filter(x -> x.matches(target.id, fixture, caseId)).toList();
            final List<FieldDiff> diffs = new ArrayList<>();
            Expectation exp = null;
            String cls;
            if (tgt == null) {
                cls = "MISSING";
            } else if (ref == null) {
                cls = "REFERENCE_INVALID";
            } else {
                final JsonObject ro = ref.getAsJsonObject("observed");
                final JsonObject to = tgt.getAsJsonObject("observed");
                final TreeSet<String> fields = new TreeSet<>(ro.keySet());
                fields.addAll(to.keySet());
                for (final String f : fields) {
                    if (!orNull(ro.get(f)).equals(orNull(to.get(f)))) {
                        diffs.add(new FieldDiff(f, orNull(ro.get(f)), orNull(to.get(f))));
                    }
                }
                if ("ERROR".equals(str(tgt, "status"))) {
                    cls = "HARNESS_ERROR";
                } else if (diffs.isEmpty()) {
                    exp = matching.stream().filter(x -> !x.optional()).findFirst().orElse(null);
                    cls = exp != null ? "UNEXPECTED_PASS" : "PASS";
                } else {
                    // The first entry that explains every differing field wins; a partial explanation is none.
                    exp = matching.stream().filter(x -> x.covers(diffs)).findFirst().orElse(null);
                    cls = exp != null ? exp.classification() : "REGRESSION";
                }
            }
            if ("MISSING".equals(cls)) {
                exp = matching.stream().filter(x -> x.fields().isEmpty()).findFirst().orElse(null);
                if (exp != null) {
                    cls = exp.classification();
                }
            }
            out.add(new Verdict(target.id, fixture, caseId, str(any, "archetype"), str(any, "description"), cls,
                    tgt == null ? "MISSING" : str(tgt, "status"), diffs,
                    List.of("PASS", "REGRESSION", "HARNESS_ERROR", "MISSING").contains(cls) ? null : exp));
        }
        return out;
    }

    static JsonObject toJson(final Verdict v) {
        final JsonObject o = new JsonObject();
        o.addProperty("target", v.target());
        o.addProperty("fixture", v.fixture());
        o.addProperty("case", v.caseId());
        o.addProperty("archetype", v.archetype());
        o.addProperty("classification", v.classification());
        o.addProperty("targetStatus", v.targetStatus());
        final JsonArray diffs = new JsonArray();
        for (final FieldDiff d : v.diffs()) {
            final JsonObject x = new JsonObject();
            x.addProperty("field", d.field());
            x.add("reference", d.reference());
            x.add("target", d.target());
            diffs.add(x);
        }
        o.add("diffs", diffs);
        if (v.expectation() != null) {
            o.addProperty("reason", v.expectation().reason());
            o.addProperty("tracking", v.expectation().tracking());
            o.addProperty("category", v.expectation().category());
        }
        return o;
    }

    static JsonElement orNull(final JsonElement e) {
        return e == null ? JsonNull.INSTANCE : e;
    }

    static String str(final JsonObject o, final String k) {
        return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }
}
