package fun.bm.lecithin.compatsuite.fixture;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

/**
 * Minimal JSON writer. The fixture must run on a 2015 Bukkit API with nothing but the JDK, so it cannot
 * rely on whatever Gson the server happens to ship.
 */
final class Json {

    private Json() {
    }

    static String write(final Object value) {
        final StringBuilder sb = new StringBuilder();
        append(sb, value);
        return sb.toString();
    }

    private static void append(final StringBuilder sb, final Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Boolean || value instanceof Long || value instanceof Integer) {
            sb.append(value);
        } else if (value instanceof Number) {
            final double d = ((Number) value).doubleValue();
            sb.append(Double.isNaN(d) || Double.isInfinite(d) ? "null" : String.valueOf(d));
        } else if (value instanceof Map) {
            sb.append('{');
            final Iterator<? extends Map.Entry<?, ?>> it = ((Map<?, ?>) value).entrySet().iterator();
            while (it.hasNext()) {
                final Map.Entry<?, ?> e = it.next();
                string(sb, String.valueOf(e.getKey()));
                sb.append(':');
                append(sb, e.getValue());
                if (it.hasNext()) {
                    sb.append(',');
                }
            }
            sb.append('}');
        } else if (value instanceof Collection) {
            sb.append('[');
            final Iterator<?> it = ((Collection<?>) value).iterator();
            while (it.hasNext()) {
                append(sb, it.next());
                if (it.hasNext()) {
                    sb.append(',');
                }
            }
            sb.append(']');
        } else {
            string(sb, value.toString());
        }
    }

    private static void string(final StringBuilder sb, final String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }
}
