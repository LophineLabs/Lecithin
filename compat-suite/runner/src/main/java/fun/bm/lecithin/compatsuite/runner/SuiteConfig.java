package fun.bm.lecithin.compatsuite.runner;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * {@code suite.properties}: which servers to run and how to obtain them.
 *
 * <pre>
 * suite.minecraft   = 26.2            # informational
 * suite.protocol    = 26.2            # runner resource protocol/&lt;v&gt;.properties
 * suite.reference   = paper           # the oracle target
 * suite.targets     = paper,lecithin  # default target list, reference first
 * target.&lt;id&gt;.label  = human label
 * target.&lt;id&gt;.jar    = jar source, see {@link JarSource}
 * target.&lt;id&gt;.port   = TCP port the server listens on (127.0.0.1)
 * target.&lt;id&gt;.file.&lt;relative path&gt; = file content written into the instance before boot
 * </pre>
 */
final class SuiteConfig {

    record Target(String id, String label, String jar, int port, Map<String, String> files) {
    }

    final Path baseDir;
    final String minecraft;
    final String protocol;
    final String reference;
    final List<String> defaultTargets;
    final Map<String, Target> targets = new LinkedHashMap<>();
    final String javaOpts;

    private SuiteConfig(final Path baseDir, final Properties p) {
        this.baseDir = baseDir;
        this.minecraft = p.getProperty("suite.minecraft", "?").trim();
        this.protocol = require(p, "suite.protocol");
        this.reference = require(p, "suite.reference");
        this.defaultTargets = split(require(p, "suite.targets"));
        this.javaOpts = p.getProperty("suite.javaOpts", "-Xms1G -Xmx2G").trim();
        final List<String> ids = new ArrayList<>();
        for (final String key : p.stringPropertyNames()) {
            if (key.startsWith("target.") && key.endsWith(".jar")) {
                ids.add(key.substring("target.".length(), key.length() - ".jar".length()));
            }
        }
        ids.sort(null);
        for (final String id : ids) {
            final String prefix = "target." + id + ".";
            final Map<String, String> files = new LinkedHashMap<>();
            for (final String key : p.stringPropertyNames()) {
                if (key.startsWith(prefix + "file.")) {
                    files.put(key.substring((prefix + "file.").length()), p.getProperty(key).replace("\\n", "\n"));
                }
            }
            this.targets.put(id, new Target(id, p.getProperty(prefix + "label", id).trim(),
                    p.getProperty(prefix + "jar").trim(), Integer.parseInt(require(p, prefix + "port")), files));
        }
        if (!this.targets.containsKey(this.reference)) {
            throw new IllegalArgumentException("suite.reference '" + this.reference + "' has no target.<id>.jar");
        }
    }

    static SuiteConfig load(final Path file) throws IOException {
        final Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return new SuiteConfig(file.toAbsolutePath().getParent(), p);
    }

    /** Targets to run: reference first, then the others in the requested order. */
    List<Target> select(final List<String> requested) {
        final List<String> ids = new ArrayList<>();
        ids.add(this.reference);
        for (final String id : requested.isEmpty() ? this.defaultTargets : requested) {
            if (!ids.contains(id)) {
                ids.add(id);
            }
        }
        final List<Target> out = new ArrayList<>();
        for (final String id : ids) {
            final Target t = this.targets.get(id);
            if (t == null) {
                throw new IllegalArgumentException("unknown target '" + id + "'; known: " + this.targets.keySet());
            }
            out.add(t);
        }
        return out;
    }

    Target withJar(final Target t, final String jar) {
        return new Target(t.id(), t.label(), jar, t.port(), t.files());
    }

    static List<String> split(final String s) {
        final List<String> out = new ArrayList<>();
        if (s == null) {
            return out;
        }
        for (final String part : s.split(",")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    private static String require(final Properties p, final String key) {
        final String v = p.getProperty(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("suite.properties: missing " + key);
        }
        return v.trim();
    }
}
