package fun.bm.lecithin.compatsuite.runner;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DifferentialTest {

    private static JsonObject rec(final String fixture, final String caseId, final String status, final String observed) {
        return JsonParser.parseString("{\"type\":\"case\",\"fixture\":\"" + fixture + "\",\"case\":\"" + caseId
                + "\",\"archetype\":\"scheduler\",\"description\":\"d\",\"status\":\"" + status
                + "\",\"expected\":{},\"observed\":" + observed + ",\"diag\":{}}").getAsJsonObject();
    }

    private static TargetRun run(final String id, final JsonObject... cases) {
        final TargetRun r = new TargetRun(id, id);
        for (final JsonObject c : cases) {
            r.cases.put(TargetRun.key(c.get("fixture").getAsString(), c.get("case").getAsString()), c);
        }
        return r;
    }

    private static Differential.Expectation known(final String caseId, final List<String> fields) {
        return new Differential.Expectation("lecithin*", "*", caseId, "KNOWN_FAIL", "REGRESSION", fields, false, "why", null);
    }

    @Test
    void identicalObservationsPassEvenWhenIntegersWereWrittenDifferently() {
        final TargetRun ref = run("paper", rec("f", "c", "PASS", "{\"runs\":1,\"primaryThread\":true}"));
        final TargetRun tgt = run("lecithin", rec("f", "c", "PASS", "{\"primaryThread\":true,\"runs\":1.0}"));
        final Differential.Verdict v = Differential.compare(ref, tgt, List.of()).get(0);
        assertEquals("PASS", v.classification());
        assertTrue(v.diffs().isEmpty());
    }

    @Test
    void unexplainedDifferenceIsRegressionAndNamesTheField() {
        final TargetRun ref = run("paper", rec("f", "c", "PASS", "{\"runs\":1}"));
        final TargetRun tgt = run("lecithin", rec("f", "c", "FAIL", "{\"runs\":0}"));
        final Differential.Verdict v = Differential.compare(ref, tgt, List.of()).get(0);
        assertEquals("REGRESSION", v.classification());
        assertEquals("runs", v.diffs().get(0).field());
    }

    @Test
    void registeredDifferenceOnlyCoversItsFields() {
        final TargetRun ref = run("paper", rec("f", "c", "PASS", "{\"runs\":1,\"exception\":\"none\"}"));
        final TargetRun onlyRuns = run("lecithin", rec("f", "c", "FAIL", "{\"runs\":0,\"exception\":\"none\"}"));
        final TargetRun alsoException = run("lecithin", rec("f", "c", "FAIL", "{\"runs\":0,\"exception\":\"X\"}"));
        final List<Differential.Expectation> exp = List.of(known("c", List.of("runs")));
        assertEquals("KNOWN_FAIL", Differential.compare(ref, onlyRuns, exp).get(0).classification());
        assertEquals("REGRESSION", Differential.compare(ref, alsoException, exp).get(0).classification());
    }

    @Test
    void registeredDifferenceThatNoLongerDiffersIsFlagged() {
        final TargetRun ref = run("paper", rec("f", "c", "PASS", "{\"runs\":1}"));
        final TargetRun tgt = run("lecithin", rec("f", "c", "PASS", "{\"runs\":1}"));
        assertEquals("UNEXPECTED_PASS", Differential.compare(ref, tgt, List.of(known("c", List.of()))).get(0).classification());
    }

    @Test
    void optionalEntryAllowsEitherOutcomeAndLaterEntriesCanCoverWhatEarlierOnesDoNot() {
        final TargetRun ref = run("paper", rec("f", "c", "PASS", "{\"runs\":1,\"exception\":\"none\"}"));
        final TargetRun same = run("v", rec("f", "c", "PASS", "{\"runs\":1,\"exception\":\"none\"}"));
        final TargetRun wider = run("v", rec("f", "c", "FAIL", "{\"runs\":0,\"exception\":\"X\"}"));
        final List<Differential.Expectation> exp = List.of(
                new Differential.Expectation("v", "*", "c", "KNOWN_FAIL", null, List.of("runs"), false, "narrow", null),
                new Differential.Expectation("v", "*", "*", "EXPECTED_FOLIA_DIFFERENCE", null, List.of(), true, "variant", null));
        assertEquals("PASS", Differential.compare(ref, same, List.of(exp.get(1))).get(0).classification());
        final Differential.Verdict v = Differential.compare(ref, wider, exp).get(0);
        assertEquals("EXPECTED_FOLIA_DIFFERENCE", v.classification());
        assertEquals("variant", v.expectation().reason());
    }

    @Test
    void expectationGlobsMatchTargetFixtureAndCase() {
        final Differential.Expectation e = new Differential.Expectation("lecithin*", "legacy-*", "tebex.*",
                "KNOWN_FAIL", null, List.of(), false, "why", null);
        assertTrue(e.matches("lecithin-dispatch-off", "legacy-1_8_8", "tebex.foreign_thread_callback_to_legacy_sync"));
        assertTrue(!e.matches("paper", "legacy-1_8_8", "tebex.x"));
        assertTrue(!e.matches("lecithin", "paper-26_2", "tebex.x"));
    }

    @Test
    void missingOnTargetAndErrorOnTarget() {
        final TargetRun ref = run("paper", rec("f", "a", "PASS", "{}"), rec("f", "b", "PASS", "{}"));
        final TargetRun tgt = run("lecithin", rec("f", "b", "ERROR", "{}"));
        final List<Differential.Verdict> v = Differential.compare(ref, tgt, List.of());
        assertEquals("MISSING", v.get(0).classification());
        assertEquals("HARNESS_ERROR", v.get(1).classification());
    }

    @Test
    void referenceThatFailsItsOwnContractIsInvalid() {
        final JsonObject bad = JsonParser.parseString("{\"fixture\":\"f\",\"case\":\"c\",\"archetype\":\"x\",\"description\":\"d\","
                + "\"status\":\"FAIL\",\"expected\":{\"runs\":1},\"observed\":{\"runs\":2}}").getAsJsonObject();
        final TargetRun ref = run("paper", bad);
        final Differential.Verdict v = Differential.referenceCheck(ref).get(0);
        assertEquals("REFERENCE_INVALID", v.classification());
        assertEquals("runs", v.diffs().get(0).field());
    }
}
