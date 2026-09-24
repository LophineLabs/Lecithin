package fun.bm.lecithin.compatsuite.runner;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The human-readable differential summary ({@code summary.md}).
 */
final class Report {

    private static final List<String> ARCHETYPES = List.of("loading", "scheduler", "tebex", "essentials",
            "griefprevention", "lifecycle");
    private static final Map<String, String> TITLES = Map.of(
            "loading", "Loading (plugin.yml, legacy plugins)",
            "scheduler", "Legacy scheduler contract",
            "tebex", "Tebex archetype - server-control callback with no entity/region provenance",
            "essentials", "Essentials archetype - async PlayerEvent to player-scoped continuation",
            "griefprevention", "GriefPrevention archetype - different regions, one plugin-owned state",
            "lifecycle", "Task lifetime and plugin disable");

    private Report() {
    }

    static String render(final String runId, final SuiteConfig config, final String suiteCommit,
                         final List<TargetRun> runs, final List<Differential.Verdict> referenceCheck,
                         final List<Differential.Verdict> verdicts) {
        final TargetRun reference = runs.get(0);
        final List<TargetRun> others = runs.subList(1, runs.size());
        final StringBuilder md = new StringBuilder();
        md.append("# Lecithin plugin compatibility suite - run `").append(runId).append("`\n\n");
        md.append("Minecraft ").append(config.minecraft).append(" · protocol profile ").append(config.protocol)
                .append(" · suite commit `").append(suiteCommit).append("` · reference `").append(reference.id).append("`\n\n");

        md.append("## Targets\n\n| target | role | jar | sha256 | server version | boot |\n|---|---|---|---|---|---|\n");
        for (final TargetRun r : runs) {
            final String version = r.fixtures.values().stream().findFirst()
                    .map(f -> Differential.str(f, "serverVersion")).orElse("-");
            md.append("| `").append(r.id).append("` | ").append(r == reference ? "reference" : "candidate").append(" | ")
                    .append(esc(r.jarSource)).append(" | `").append(r.jarSha256 == null ? "-" : r.jarSha256.substring(0, 12))
                    .append("` | ").append(esc(version)).append(" | ")
                    .append(r.bootMillis < 0 ? "**did not boot**" : (r.bootMillis / 1000.0) + " s").append(" |\n");
        }
        for (final TargetRun r : runs) {
            if (r.error != null) {
                md.append("\n> **`").append(r.id).append("` failed:** ").append(esc(r.error)).append('\n');
            }
        }

        md.append("\n## Fixtures (API eras)\n\nEvery fixture is the same scenario source compiled separately against its own API. ");
        md.append("None declares `folia-supported`.\n\n| fixture | compiled against | class file | api-version | layers |");
        runs.forEach(r -> md.append(" loaded on `").append(r.id).append("` |"));
        md.append("\n|---|---|---|---|---|");
        runs.forEach(r -> md.append("---|"));
        md.append('\n');
        final Set<String> fixtureIds = new LinkedHashSet<>();
        runs.forEach(r -> fixtureIds.addAll(r.fixtures.keySet()));
        for (final String f : fixtureIds) {
            final JsonObject rec = runs.stream().map(r -> r.fixtures.get(f)).filter(x -> x != null).findFirst().orElseThrow();
            final String apiVersion = Differential.str(rec, "apiVersionDeclared");
            md.append("| `").append(f).append("` | ").append(esc(Differential.str(rec, "label"))).append("<br>`")
                    .append(Differential.str(rec, "apiArtifact")).append("` | ").append(rec.get("classFileMajor"))
                    .append(" | ").append(apiVersion == null || apiVersion.isEmpty() ? "*(none - legacy)*" : apiVersion)
                    .append(" | ").append(rec.get("layers").toString().replace("\"", "")).append(" |");
            runs.forEach(r -> md.append(r.fixtures.containsKey(f) ? " yes |" : " **no** |"));
            md.append('\n');
        }

        md.append("\n## Overview\n\n");
        final long refInvalid = referenceCheck.stream().filter(v -> v.classification().equals("REFERENCE_INVALID")).count();
        md.append("Reference contract self-check: ").append(referenceCheck.size() - refInvalid).append(" of ")
                .append(referenceCheck.size()).append(" cases hold on `").append(reference.id).append("`")
                .append(refInvalid == 0 ? ".\n\n" : " - **" + refInvalid + " REFERENCE_INVALID** (testcase wrong, see below).\n\n");
        final List<String> classes = new ArrayList<>(List.of("PASS", "SEMANTICALLY_EQUIVALENT", "EXPECTED_FOLIA_DIFFERENCE",
                "LECITHIN_EXTENSION", "UNSUPPORTED_BY_DESIGN", "KNOWN_FAIL", "UNEXPECTED_PASS", "REGRESSION", "MISSING",
                "HARNESS_ERROR", "REFERENCE_INVALID"));
        md.append("| target |");
        classes.forEach(c -> md.append(' ').append(c).append(" |"));
        md.append("\n|---|");
        classes.forEach(c -> md.append("---|"));
        md.append('\n');
        for (final TargetRun r : others) {
            md.append("| `").append(r.id).append("` |");
            for (final String c : classes) {
                final long n = verdicts.stream().filter(v -> v.target().equals(r.id) && v.classification().equals(c)).count();
                md.append(' ').append(n == 0 ? "·" : (Differential.FAILING.contains(c) || c.equals("KNOWN_FAIL") ? "**" + n + "**" : n)).append(" |");
            }
            md.append('\n');
        }

        md.append("\n## Results by archetype\n\nCells: classification against the reference; differing observed fields as ")
                .append("`field: reference → target`. A row labelled *all* means every fixture era got the same verdict.\n");
        for (final String arch : archetypesIn(referenceCheck, verdicts)) {
            md.append("\n### ").append(TITLES.getOrDefault(arch, arch)).append("\n\n| case | fixture | `")
                    .append(reference.id).append("` contract |");
            others.forEach(r -> md.append(" `").append(r.id).append("` |"));
            md.append("\n|---|---|---|");
            others.forEach(r -> md.append("---|"));
            md.append('\n');
            final Map<String, List<String>> fixturesByCase = new TreeMap<>();
            for (final Differential.Verdict v : referenceCheck) {
                if (arch.equals(v.archetype())) {
                    fixturesByCase.computeIfAbsent(v.caseId(), k -> new ArrayList<>()).add(v.fixture());
                }
            }
            verdicts.stream().filter(v -> arch.equals(v.archetype())).forEach(v -> {
                final List<String> fx = fixturesByCase.computeIfAbsent(v.caseId(), k -> new ArrayList<>());
                if (!fx.contains(v.fixture())) {
                    fx.add(v.fixture());
                }
            });
            for (final Map.Entry<String, List<String>> e : fixturesByCase.entrySet()) {
                final String caseId = e.getKey();
                final Map<String, String> rowByFixture = new LinkedHashMap<>();
                for (final String f : e.getValue()) {
                    final StringBuilder row = new StringBuilder();
                    row.append(refCell(referenceCheck, f, caseId)).append(" |");
                    for (final TargetRun r : others) {
                        row.append(' ').append(cell(verdicts, r.id, f, caseId)).append(" |");
                    }
                    rowByFixture.put(f, row.toString());
                }
                final Set<String> distinct = new LinkedHashSet<>(rowByFixture.values());
                if (distinct.size() == 1 && rowByFixture.size() > 1) {
                    md.append("| `").append(caseId).append("` | *all* | ").append(distinct.iterator().next()).append('\n');
                } else {
                    rowByFixture.forEach((f, row) -> md.append("| `").append(caseId).append("` | ").append(f).append(" | ").append(row).append('\n'));
                }
            }
        }

        md.append("\n## Registered and unexplained differences\n\n");
        final Map<String, List<Differential.Verdict>> notable = new TreeMap<>();
        for (final Differential.Verdict v : verdicts) {
            if (!v.classification().equals("PASS")) {
                notable.computeIfAbsent(v.target() + " · " + v.caseId(), k -> new ArrayList<>()).add(v);
            }
        }
        for (final Differential.Verdict v : referenceCheck) {
            if (v.classification().equals("REFERENCE_INVALID")) {
                notable.computeIfAbsent(v.target() + " · " + v.caseId(), k -> new ArrayList<>()).add(v);
            }
        }
        if (notable.isEmpty()) {
            md.append("None - every candidate matched the reference on every case.\n");
        }
        for (final Map.Entry<String, List<Differential.Verdict>> e : notable.entrySet()) {
            final Differential.Verdict first = e.getValue().get(0);
            md.append("### ").append(e.getKey()).append(" — ").append(first.classification()).append("\n\n");
            md.append(esc(first.description())).append("\n\n");
            md.append("Fixtures: ").append(e.getValue().stream().map(v -> "`" + v.fixture() + "`").collect(Collectors.joining(", "))).append("\n\n");
            if (first.expectation() != null) {
                md.append("- Registered as **").append(first.expectation().classification()).append("**")
                        .append(first.expectation().category() == null ? "" : " (" + first.expectation().category() + ")")
                        .append(": ").append(esc(first.expectation().reason())).append('\n');
                if (first.expectation().tracking() != null) {
                    md.append("- Tracking: ").append(first.expectation().tracking()).append('\n');
                }
            }
            for (final Differential.Verdict v : e.getValue()) {
                if (!v.diffs().isEmpty()) {
                    md.append("- `").append(v.fixture()).append("`: ").append(v.diffs().stream()
                            .map(d -> "`" + d.field() + "` " + show(d.reference()) + " → " + show(d.target()))
                            .collect(Collectors.joining(", "))).append('\n');
                }
            }
            final TargetRun r = runs.stream().filter(x -> x.id.equals(first.target())).findFirst().orElse(null);
            final JsonObject rec = r == null ? null : r.cases.get(TargetRun.key(first.fixture(), first.caseId()));
            if (rec != null && rec.has("diag") && rec.getAsJsonObject("diag").size() > 0) {
                md.append("- diag (`").append(first.fixture()).append("` on `").append(first.target()).append("`): `")
                        .append(esc(rec.getAsJsonObject("diag").toString())).append("`\n");
            }
            md.append('\n');
        }

        md.append("## Harness notes and server log\n\n");
        for (final TargetRun r : runs) {
            md.append("**`").append(r.id).append("`**\n\n");
            if (r.harnessNotes.isEmpty()) {
                md.append("- harness: no notes\n");
            }
            r.harnessNotes.forEach(n -> md.append("- harness: ").append(esc(n)).append('\n'));
            for (final Map.Entry<String, JsonElement> f : r.logFindings.entrySet()) {
                final JsonElement v = f.getValue();
                if (v.isJsonArray() && v.getAsJsonArray().isEmpty() || v.isJsonPrimitive() && v.getAsLong() == 0) {
                    continue;
                }
                md.append("- log `").append(f.getKey()).append("`: ");
                if (v.isJsonArray()) {
                    final Set<String> uniq = new LinkedHashSet<>();
                    v.getAsJsonArray().forEach(x -> uniq.add(x.getAsString()));
                    md.append(v.getAsJsonArray().size()).append(" line(s)");
                    uniq.stream().limit(12).forEach(u -> md.append("<br>`").append(esc(u)).append('`'));
                } else {
                    md.append(v);
                }
                md.append('\n');
            }
            md.append('\n');
        }
        return md.toString();
    }

    private static List<String> archetypesIn(final List<Differential.Verdict> a, final List<Differential.Verdict> b) {
        final Set<String> seen = new LinkedHashSet<>(ARCHETYPES);
        a.forEach(v -> seen.add(v.archetype()));
        b.forEach(v -> seen.add(v.archetype()));
        final List<String> out = new ArrayList<>();
        for (final String s : seen) {
            if (a.stream().anyMatch(v -> s.equals(v.archetype())) || b.stream().anyMatch(v -> s.equals(v.archetype()))) {
                out.add(s);
            }
        }
        return out;
    }

    private static String refCell(final List<Differential.Verdict> ref, final String fixture, final String caseId) {
        return ref.stream().filter(v -> v.fixture().equals(fixture) && v.caseId().equals(caseId)).findFirst()
                .map(v -> v.classification().equals("REFERENCE_OK") ? v.targetStatus()
                        : "**REFERENCE_INVALID** " + v.diffs().stream().map(d -> "`" + d.field() + "`").collect(Collectors.joining(" ")))
                .orElse("**MISSING**");
    }

    private static String cell(final List<Differential.Verdict> verdicts, final String target, final String fixture,
                               final String caseId) {
        return verdicts.stream().filter(v -> v.target().equals(target) && v.fixture().equals(fixture) && v.caseId().equals(caseId))
                .findFirst().map(v -> {
                    final String cls = v.classification();
                    final String head = cls.equals("PASS") ? "PASS" : "**" + cls + "**";
                    if (v.diffs().isEmpty()) {
                        return head;
                    }
                    return head + "<br>" + v.diffs().stream().limit(4)
                            .map(d -> "`" + d.field() + ": " + showPlain(d.reference()) + " → " + showPlain(d.target()) + "`")
                            .collect(Collectors.joining("<br>")) + (v.diffs().size() > 4 ? "<br>+" + (v.diffs().size() - 4) + " more" : "");
                }).orElse("-");
    }

    private static String show(final JsonElement e) {
        return "`" + showPlain(e) + "`";
    }

    private static String showPlain(final JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return "∅";
        }
        return e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : e.toString();
    }

    private static String esc(final String s) {
        return s == null ? "-" : s.replace("|", "\\|").replace("\n", " ");
    }
}
