package fun.bm.lecithin.compatsuite.runner;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Packet ids for one Minecraft version, loaded from {@code protocol/<version>.properties}.
 */
final class ProtocolProfile {

    final String version;
    private final Properties ids = new Properties();

    private ProtocolProfile(final String version) {
        this.version = version;
    }

    static ProtocolProfile load(final String version) throws IOException {
        final ProtocolProfile p = new ProtocolProfile(version);
        try (InputStream in = ProtocolProfile.class.getResourceAsStream("/protocol/" + version + ".properties")) {
            if (in == null) {
                throw new IOException("no protocol profile for Minecraft " + version
                        + " (add runner/src/main/resources/protocol/" + version + ".properties)");
            }
            p.ids.load(in);
        }
        return p;
    }

    int protocolVersion() {
        return Integer.parseInt(this.ids.getProperty("protocol.version").trim());
    }

    int id(final String key) {
        final String v = this.ids.getProperty(key);
        if (v == null) {
            throw new IllegalStateException("protocol profile " + this.version + " has no packet id '" + key + "'");
        }
        return Integer.decode(v.trim());
    }
}
