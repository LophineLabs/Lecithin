package fun.bm.lecithin.compatsuite.runner;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * One server process in its own instance directory.
 *
 * <p>Each run starts from a fresh world, fresh configs and only the suite's plugins. Paperclip's
 * downloaded Mojang jar and libraries ({@code cache/}, {@code libraries/}, {@code versions/}) are kept
 * between runs; nothing else is.
 *
 * <p>The console is the process's stdin, kept open for the whole run: a server that reads EOF on stdin
 * treats it as {@code stop}.
 */
final class ServerInstance implements AutoCloseable {

    private static final Set<String> KEEP = Set.of("cache", "libraries", "versions");
    private static final Pattern DONE = Pattern.compile("Done \\(\\d+[.,]\\d+s\\)!");

    private final SuiteConfig.Target target;
    private final Path dir;
    private final Path jar;
    private final Path consoleLog;
    private final String java;
    private final List<String> javaOpts;
    private final Consumer<String> log;
    private final CountDownLatch done = new CountDownLatch(1);
    private final List<String> lines = new ArrayList<>();
    private Process process;
    private Writer stdin;
    private long startedAt;
    private long bootMillis = -1;

    ServerInstance(final SuiteConfig.Target target, final Path dir, final Path jar, final Path consoleLog,
                   final String java, final List<String> javaOpts, final Consumer<String> log) {
        this.target = target;
        this.dir = dir;
        this.jar = jar;
        this.consoleLog = consoleLog;
        this.java = java;
        this.javaOpts = javaOpts;
        this.log = log;
    }

    Path dir() {
        return this.dir;
    }

    Path resultsDir() {
        return this.dir.resolve("compat-results");
    }

    long bootMillis() {
        return this.bootMillis;
    }

    synchronized List<String> consoleLines() {
        return new ArrayList<>(this.lines);
    }

    /** Wipe everything but paperclip's caches, then write eula, server.properties, plugins and overrides. */
    void prepare(final List<Path> pluginJars) throws IOException {
        Files.createDirectories(this.dir);
        try (Stream<Path> children = Files.list(this.dir)) {
            for (final Path child : children.toList()) {
                if (!KEEP.contains(child.getFileName().toString())) {
                    deleteTree(child);
                }
            }
        }
        Files.writeString(this.dir.resolve("eula.txt"), "eula=true\n");
        Files.writeString(this.dir.resolve("server.properties"), String.join("\n",
                "server-ip=127.0.0.1",
                "server-port=" + this.target.port(),
                "online-mode=false",
                "enforce-secure-profile=false",
                "motd=lecithin compat suite " + this.target.id(),
                "level-name=world",
                "level-type=minecraft\\:flat",
                "generate-structures=false",
                "gamemode=creative",
                "force-gamemode=true",
                "difficulty=peaceful",
                "spawn-protection=0",
                "allow-flight=true",
                "max-players=10",
                "view-distance=4",
                "simulation-distance=4",
                "pause-when-empty-seconds=-1",
                "player-idle-timeout=0",
                "enable-rcon=false",
                "enable-query=false",
                "sync-chunk-writes=false",
                "network-compression-threshold=256",
                "") + "\n");
        final Path plugins = Files.createDirectories(this.dir.resolve("plugins"));
        for (final Path p : pluginJars) {
            Files.copy(p, plugins.resolve(p.getFileName()));
        }
        for (final var e : this.target.files().entrySet()) {
            final Path f = this.dir.resolve(e.getKey()).normalize();
            if (!f.startsWith(this.dir)) {
                throw new IOException("target file override escapes the instance: " + e.getKey());
            }
            Files.createDirectories(f.getParent());
            Files.writeString(f, e.getValue());
        }
    }

    void start() throws IOException {
        try (ServerSocket probe = new ServerSocket(this.target.port(), 1, InetAddress.getByName("127.0.0.1"))) {
            probe.setReuseAddress(true);
        } catch (final IOException e) {
            throw new IOException("port " + this.target.port() + " for target " + this.target.id()
                    + " is already in use - is another server running?", e);
        }
        final List<String> cmd = new ArrayList<>();
        cmd.add(this.java);
        cmd.addAll(this.javaOpts);
        cmd.add("-Dfile.encoding=UTF-8");
        cmd.add("-jar");
        cmd.add(this.jar.toAbsolutePath().toString());
        cmd.add("--nogui");
        Files.createDirectories(this.consoleLog.getParent());
        this.startedAt = System.nanoTime();
        this.process = new ProcessBuilder(cmd).directory(this.dir.toFile()).redirectErrorStream(true).start();
        this.stdin = new BufferedWriter(new OutputStreamWriter(this.process.getOutputStream(), StandardCharsets.UTF_8));
        final Thread pump = new Thread(this::pump, "compat-console-" + this.target.id());
        pump.setDaemon(true);
        pump.start();
        this.log.accept("started " + this.target.id() + " (pid " + this.process.pid() + ") on port " + this.target.port());
    }

    private void pump() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(this.process.getInputStream(), StandardCharsets.UTF_8));
             Writer w = Files.newBufferedWriter(this.consoleLog, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                w.write(line);
                w.write('\n');
                w.flush();
                synchronized (this) {
                    this.lines.add(line);
                }
                if (this.done.getCount() > 0 && DONE.matcher(line).find()) {
                    this.bootMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - this.startedAt);
                    this.done.countDown();
                }
            }
        } catch (final IOException e) {
            this.log.accept("console pump for " + this.target.id() + " ended: " + e);
        } finally {
            this.done.countDown();
        }
    }

    void awaitDone(final Duration timeout) throws IOException, InterruptedException {
        if (!this.done.await(timeout.toMillis(), TimeUnit.MILLISECONDS) || this.bootMillis < 0) {
            throw new IOException(this.target.id() + " did not finish starting within " + timeout
                    + (this.process.isAlive() ? "" : " (process exited " + this.process.exitValue() + ")")
                    + "; see " + this.consoleLog);
        }
    }

    synchronized void command(final String line) throws IOException {
        this.log.accept(this.target.id() + " > " + line);
        this.stdin.write(line);
        this.stdin.write('\n');
        this.stdin.flush();
    }

    boolean alive() {
        return this.process != null && this.process.isAlive();
    }

    /** Graceful {@code stop}, then force after the timeout. Returns whether it stopped on its own. */
    boolean stop(final Duration timeout) throws InterruptedException {
        if (this.process == null || !this.process.isAlive()) {
            return true;
        }
        try {
            command("stop");
        } catch (final IOException ignored) {
        }
        if (this.process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            return true;
        }
        this.log.accept(this.target.id() + " did not stop within " + timeout + "; killing it");
        this.process.descendants().forEach(ProcessHandle::destroyForcibly);
        this.process.destroyForcibly();
        this.process.waitFor(30, TimeUnit.SECONDS);
        return false;
    }

    @Override
    public void close() throws InterruptedException {
        stop(Duration.ofSeconds(60));
    }

    static void deleteTree(final Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (final Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
