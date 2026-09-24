package fun.bm.lecithin.compatsuite.fixture;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Drives the scenarios of one fixture plugin and writes their results.
 *
 * <h2>Protocol with the runner</h2>
 * <ul>
 *   <li>The runner sends console commands {@code <command> phase <name> [args]}; each phase ends after a
 *       fixed deadline measured by {@link #watchdog()}, not by the Bukkit scheduler under test.</li>
 *   <li>Every record is one JSON line appended to {@code compat-results/<fixture-id>.jsonl} in the
 *       server working directory: a {@code fixture} record at enable, a {@code case} record per scenario
 *       when its phase ends, and a {@code phase} record when a phase is done.</li>
 *   <li>Test players chat {@code cfx ess} (async chat event) and send {@code /cfxtrigger <name>}
 *       (PlayerCommandPreprocessEvent); their names arrive with the {@code players} phase and map to
 *       roles {@code A} and {@code B}.</li>
 * </ul>
 */
public final class Harness {

    public static final String TRIGGER_PREFIX = "/cfxtrigger ";
    public static final String CHAT_PREFIX = "cfx ";

    private static final Map<String, Long> PHASE_DEADLINE_MS = new LinkedHashMap<String, Long>();

    static {
        PHASE_DEADLINE_MS.put(Scenario.SERVER, 6000L);
        PHASE_DEADLINE_MS.put(Scenario.PLAYERS, 20000L);
        PHASE_DEADLINE_MS.put(Scenario.LIFECYCLE_LIVENESS, 3000L);
        PHASE_DEADLINE_MS.put(Scenario.LIFECYCLE_DISABLE, 3000L);
    }

    public final JavaPlugin plugin;
    public final String fixtureId;
    public final String command;
    private final Properties descriptor;
    private final List<Scenario> scenarios = new ArrayList<Scenario>();
    private final ScheduledExecutorService watchdog;
    private final Map<String, String> roles = new ConcurrentHashMap<String, String>();
    private final Map<String, String> sinkTags = new ConcurrentHashMap<String, String>();
    private final Set<String> startedPhases = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final Writer out;

    Harness(final JavaPlugin plugin) throws IOException {
        this.plugin = plugin;
        this.descriptor = new Properties();
        final InputStream in = plugin.getResource("compat-fixture.properties");
        if (in == null) {
            throw new IOException("compat-fixture.properties missing from the fixture jar");
        }
        try {
            this.descriptor.load(in);
        } finally {
            in.close();
        }
        this.fixtureId = this.descriptor.getProperty("fixture.id");
        this.command = "cfx-" + this.fixtureId;
        final File dir = new File("compat-results");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir.getAbsolutePath());
        }
        this.out = new OutputStreamWriter(new FileOutputStream(new File(dir, this.fixtureId + ".jsonl"), true),
                Charset.forName("UTF-8"));
        this.watchdog = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(final Runnable r) {
                final Thread t = new Thread(r, "cfx-watchdog-" + Harness.this.fixtureId);
                t.setDaemon(true);
                return t;
            }
        });
    }

    // ------------------------------------------------------------------ setup

    void installLayers() throws Exception {
        for (final String layer : layers()) {
            final Class<?> cls = Class.forName(layerClassName(layer), true, getClass().getClassLoader());
            ((Layer) cls.getDeclaredConstructor().newInstance()).install(this);
        }
    }

    public void add(final Scenario scenario) {
        this.scenarios.add(scenario);
    }

    public List<String> layers() {
        return Arrays.asList(this.descriptor.getProperty("fixture.layers", "base").split(","));
    }

    /** {@code since_1_13} -> {@code ...fixture.layer.Since113Layer}. */
    static String layerClassName(final String layer) {
        final StringBuilder sb = new StringBuilder("fun.bm.lecithin.compatsuite.fixture.layer.");
        for (final String part : layer.trim().split("_")) {
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return sb.append("Layer").toString();
    }

    // ------------------------------------------------------------------ lifecycle hooks

    void enable() {
        final Map<String, Object> rec = new LinkedHashMap<String, Object>();
        rec.put("type", "fixture");
        rec.put("fixture", this.fixtureId);
        rec.put("plugin", this.plugin.getDescription().getName());
        rec.put("label", this.descriptor.getProperty("fixture.label"));
        rec.put("apiArtifact", this.descriptor.getProperty("fixture.api"));
        rec.put("apiVersionDeclared", this.descriptor.getProperty("fixture.apiVersion", ""));
        rec.put("javaRelease", this.descriptor.getProperty("fixture.release"));
        rec.put("classFileMajor", classFileMajor());
        rec.put("layers", layers());
        rec.put("serverVersion", Bukkit.getVersion());
        rec.put("bukkitVersion", Bukkit.getBukkitVersion());
        rec.put("enableThread", Probe.thread());
        write(rec);
        for (final Scenario s : this.scenarios) {
            try {
                s.onEnable(this);
            } catch (final Throwable t) {
                s.result.error(t);
            }
        }
    }

    void disable() {
        this.watchdog.shutdownNow();
        synchronized (this) {
            try {
                this.out.close();
            } catch (final IOException ignored) {
            }
        }
    }

    boolean startPhase(final String phase, final String[] args) {
        if (!PHASE_DEADLINE_MS.containsKey(phase) || !this.startedPhases.add(phase)) {
            return false;
        }
        if (Scenario.PLAYERS.equals(phase)) {
            if (args.length >= 1) {
                this.roles.put(args[0], "A");
            }
            if (args.length >= 2) {
                this.roles.put(args[1], "B");
            }
        }
        writePhase(phase, "started", -1);
        for (final Scenario s : this.scenarios) {
            try {
                s.onPhase(this, phase);
            } catch (final Throwable t) {
                s.result.error(t);
            }
        }
        this.watchdog.schedule(new Runnable() {
            @Override
            public void run() {
                finishPhase(phase);
            }
        }, PHASE_DEADLINE_MS.get(phase), TimeUnit.MILLISECONDS);
        return true;
    }

    private void finishPhase(final String phase) {
        int cases = 0;
        for (final Scenario s : this.scenarios) {
            if (!phase.equals(s.phase)) {
                continue;
            }
            try {
                s.finish(this);
            } catch (final Throwable t) {
                s.result.error(t);
            }
            final Map<String, Object> rec = new LinkedHashMap<String, Object>();
            rec.put("type", "case");
            rec.put("fixture", this.fixtureId);
            rec.put("case", s.id);
            rec.put("archetype", s.archetype);
            rec.put("phase", s.phase);
            rec.put("description", s.description);
            rec.putAll(s.result.toRecord());
            write(rec);
            cases++;
        }
        writePhase(phase, "done", cases);
    }

    public void onAsyncChat(final Player player, final String message, final boolean async, final String eventType) {
        final String role = roleOf(player);
        if (role == null || !message.startsWith(CHAT_PREFIX)) {
            return;
        }
        for (final Scenario s : this.scenarios) {
            try {
                s.onAsyncChat(this, player, role, message.substring(CHAT_PREFIX.length()).trim(), async, eventType);
            } catch (final Throwable t) {
                s.result.error(t);
            }
        }
    }

    void onTrigger(final Player player, final String trigger) {
        final String role = roleOf(player);
        if (role == null) {
            return;
        }
        for (final Scenario s : this.scenarios) {
            try {
                s.onTrigger(this, player, role, trigger);
            } catch (final Throwable t) {
                s.result.error(t);
            }
        }
    }

    // ------------------------------------------------------------------ services for scenarios

    /** A private timer thread that is not the Bukkit scheduler. Use it for all waiting and sampling. */
    public ScheduledExecutorService watchdog() {
        return this.watchdog;
    }

    public String roleOf(final Player player) {
        return player == null ? null : this.roles.get(player.getName());
    }

    /** Server-control sink: the plugin command {@code <command> sink <tag>} records its tag here. */
    void recordSink(final String tag) {
        this.sinkTags.put(tag, Probe.thread() + (Probe.primary() ? " (primary)" : ""));
    }

    public boolean sinkSeen(final String tag) {
        return this.sinkTags.containsKey(tag);
    }

    public String sinkThread(final String tag) {
        return this.sinkTags.get(tag);
    }

    /** The lifecycle victim plugin shipped next to this fixture, or {@code null}. */
    public Plugin victim() {
        return Bukkit.getPluginManager().getPlugin("CfxVictim-" + this.fixtureId);
    }

    /**
     * Call a public method on the victim plugin. The victim lives in another plugin classloader, so the
     * fixture cannot link against it; everything crosses as JDK types.
     */
    public Object callVictim(final String method, final Class<?>[] types, final Object... args) throws Throwable {
        final Plugin victim = victim();
        if (victim == null) {
            throw new IllegalStateException("victim plugin CfxVictim-" + this.fixtureId + " is not loaded");
        }
        try {
            return victim.getClass().getMethod(method, types).invoke(victim, args);
        } catch (final InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Long> victimCounters() throws Throwable {
        return (Map<String, Long>) callVictim("snapshot", new Class<?>[0]);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Integer> victimTaskIds() throws Throwable {
        return (Map<String, Integer>) callVictim("taskIds", new Class<?>[0]);
    }

    /** Whether this fixture's own plugin.yml contains {@code key:} at top level. */
    public boolean descriptorDeclares(final String key) {
        final String yml = readResource("plugin.yml");
        if (yml == null) {
            return false;
        }
        for (final String line : yml.split("\n")) {
            if (line.startsWith(key + ":")) {
                return true;
            }
        }
        return false;
    }

    /** Class-file major version of this fixture's own bytecode, as proof of the compile baseline. */
    public int classFileMajor() {
        final InputStream in = getClass().getResourceAsStream("/" + getClass().getName().replace('.', '/') + ".class");
        if (in == null) {
            return -1;
        }
        try {
            final byte[] head = new byte[8];
            int read = 0;
            while (read < 8) {
                final int n = in.read(head, read, 8 - read);
                if (n < 0) {
                    return -1;
                }
                read += n;
            }
            return ((head[6] & 0xff) << 8) | (head[7] & 0xff);
        } catch (final IOException e) {
            return -1;
        } finally {
            try {
                in.close();
            } catch (final IOException ignored) {
            }
        }
    }

    private String readResource(final String name) {
        final InputStream in = this.plugin.getResource(name);
        if (in == null) {
            return null;
        }
        try {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            final byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) >= 0) {
                bytes.write(buf, 0, n);
            }
            return new String(bytes.toByteArray(), "UTF-8");
        } catch (final IOException e) {
            return null;
        } finally {
            try {
                in.close();
            } catch (final IOException ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ output

    private void writePhase(final String phase, final String status, final int cases) {
        final Map<String, Object> rec = new LinkedHashMap<String, Object>();
        rec.put("type", "phase");
        rec.put("fixture", this.fixtureId);
        rec.put("phase", phase);
        rec.put("status", status);
        if (cases >= 0) {
            rec.put("cases", cases);
        }
        rec.put("thread", Probe.thread());
        write(rec);
    }

    private synchronized void write(final Map<String, Object> record) {
        record.put("t", System.currentTimeMillis());
        try {
            this.out.write(Json.write(record));
            this.out.write('\n');
            this.out.flush();
        } catch (final IOException e) {
            this.plugin.getLogger().severe("[compat-suite] cannot write result: " + e);
        }
    }
}
