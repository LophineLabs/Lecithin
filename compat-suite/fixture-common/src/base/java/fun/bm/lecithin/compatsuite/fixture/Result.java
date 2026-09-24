package fun.bm.lecithin.compatsuite.fixture;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What one scenario saw on one server.
 *
 * <p>Three maps, with different jobs:
 * <ul>
 *   <li>{@code expected} - the Paper contract, written by the scenario author. When present the fixture
 *       can judge PASS/FAIL on its own; when absent the case is {@code OBSERVED} and only the runner's
 *       differential against the reference server can judge it.</li>
 *   <li>{@code observed} - the contract-relevant facts. The runner compares these field by field against
 *       the reference server, so they must be deterministic on a correct platform (counts, booleans,
 *       exception class names), never timings or thread names.</li>
 *   <li>{@code diag} - everything useful for a human that is not stable across platforms: thread names,
 *       elapsed milliseconds, exception messages. Never compared.</li>
 * </ul>
 * Written from several threads, so every access is synchronized.
 */
public final class Result {

    private final Map<String, Object> expected = new LinkedHashMap<String, Object>();
    private final Map<String, Object> observed = new LinkedHashMap<String, Object>();
    private final Map<String, Object> diag = new LinkedHashMap<String, Object>();
    private String error;

    public synchronized Result expect(final String key, final Object value) {
        this.expected.put(key, normalize(value));
        return this;
    }

    public synchronized Result observe(final String key, final Object value) {
        this.observed.put(key, normalize(value));
        return this;
    }

    public synchronized Result diag(final String key, final Object value) {
        this.diag.put(key, normalize(value));
        return this;
    }

    /**
     * A failure of the scenario code itself (not of the API under test - those are observations).
     */
    public synchronized void error(final Throwable t) {
        final String line = t.getClass().getName() + ": " + t.getMessage();
        this.error = this.error == null ? line : this.error + " | " + line;
    }

    public synchronized String status() {
        if (this.error != null) {
            return "ERROR";
        }
        if (this.expected.isEmpty()) {
            return "OBSERVED";
        }
        for (final Map.Entry<String, Object> e : this.expected.entrySet()) {
            final Object actual = this.observed.get(e.getKey());
            if (actual == null ? e.getValue() != null : !actual.equals(e.getValue())) {
                return "FAIL";
            }
        }
        return "PASS";
    }

    synchronized Map<String, Object> toRecord() {
        final Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("status", status());
        out.put("expected", new LinkedHashMap<String, Object>(this.expected));
        out.put("observed", new LinkedHashMap<String, Object>(this.observed));
        out.put("diag", new LinkedHashMap<String, Object>(this.diag));
        out.put("error", this.error);
        return out;
    }

    /**
     * One numeric type, so that an int written by one scenario compares equal to the long the runner
     * reads back for the other server.
     */
    private static Object normalize(final Object value) {
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        return value;
    }
}
