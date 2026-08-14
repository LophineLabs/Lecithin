package fun.bm.lophinya.compat;

import com.mojang.logging.LogUtils;
import fun.bm.lecithin.config.modules.CompatConfig;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lophinya: purely diagnostic reporting for Bukkit scheduler calls that Folia rejects.
 *
 * <p>Folia's {@code CraftScheduler.handle} throws a bare {@link UnsupportedOperationException}
 * with no message. The stack trace names the plugin class, but not the plugin version, the jar
 * it came from, the scheduler flavour, the delay/period, or the thread context. That makes
 * "which plugin, doing what, from where" a manual archaeology job every single time.
 *
 * <p>This class changes <b>nothing</b> about scheduling behaviour. It only turns one opaque
 * exception into one attributable log record. The caller still throws exactly as before.
 *
 * <p>Kill switch: {@code -Dlophinya.compat.diagnostics=false}.
 */
public final class LophinyaSchedulerDiagnostics {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Report each distinct (plugin, callsite, method) once. A rejected ticker would otherwise spam.
     */
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    /**
     * SHA-256 of a plugin jar is stable for the run; hashing a 10 MB jar per call would not be free.
     */
    private static final Map<String, String> JAR_SHA = new ConcurrentHashMap<>();

    /**
     * Frames belonging to the scheduler plumbing itself — never the answer to "who called this".
     */
    private static final String[] PLUMBING = {
            "org.bukkit.craftbukkit.scheduler.",
            "org.bukkit.scheduler.",
            "io.papermc.paper.threadedregions.",
            "fun.bm.lophinya.compat.",
    };

    /**
     * Log one attributable record for a scheduler call Folia is about to reject.
     *
     * @param plugin the calling plugin
     * @param delay  delay in ticks, or -1 when not applicable
     * @param period period in ticks, {@code -1} meaning no repeat
     * @param async  whether the task was submitted as an async task
     * @return a short human-readable reason, suitable for the thrown exception's message
     */
    public static String reportRejected(final Plugin plugin,
                                        final long delay, final long period, final boolean async) {
        // ponytail: diagnostics must never be the reason a server dies. Everything below is best-effort.
        try {
            final String[] frames = walk();
            final String method = frames[0];
            final String callsite = frames[1];
            final String name = plugin == null ? "<null>" : plugin.getName();
            final String key = name + '|' + callsite + '|' + method;

            final String reason = (async ? "async" : "sync")
                    + " Bukkit scheduler task from " + name
                    + " (" + method + ", delay=" + delay + ", period=" + period + ") "
                    + "is not supported under regionised threading";

            if (!CompatConfig.diagnostics || !REPORTED.add(key)) {
                return reason;
            }

            LOGGER.warn("""
                            [Lophinya] Rejected Bukkit scheduler call
                              plugin    : {} v{}
                              jar       : {}
                              jar sha256: {}
                              scheduler : {} (delay={} ticks, period={} ticks, async={})
                              callsite  : {}
                              context   : {}
                              why       : Folia has no single main thread. Use RegionScheduler (block/location),
                                          EntityScheduler (entity), GlobalRegionScheduler (server-global) or
                                          AsyncScheduler. This message is diagnostic only; the call still fails.""",
                    name, version(plugin), jarName(plugin), jarSha(plugin),
                    method, delay, period, async, callsite, context());

            return reason;
        } catch (final Throwable t) {
            LOGGER.warn("[Lophinya] scheduler diagnostics failed (harmless)", t);
            return "unsupported Bukkit scheduler call";
        }
    }

    /**
     * One stack walk, two answers:
     * <ul>
     *   <li>[0] the Bukkit scheduler API method the plugin actually called — the <i>outermost</i>
     *       plumbing frame, e.g. {@code BukkitRunnable.runTaskTimer}</li>
     *   <li>[1] the plugin callsite — the <i>innermost</i> frame that is not plumbing</li>
     * </ul>
     */
    private static String[] walk() {
        final String[] out = {"<unknown>", "<unknown>"};
        StackWalker.getInstance().forEach(f -> {
            final String cn = f.getClassName();
            boolean plumbing = false;
            for (final String p : PLUMBING) {
                if (cn.startsWith(p)) {
                    plumbing = true;
                    break;
                }
            }
            if (plumbing) {
                // keep overwriting: the last plumbing frame seen is the API the plugin called
                if ("<unknown>".equals(out[1])) {
                    out[0] = cn.substring(cn.lastIndexOf('.') + 1) + '.' + f.getMethodName();
                }
            } else if ("<unknown>".equals(out[1])) {
                out[1] = cn + '.' + f.getMethodName() + '(' + f.getFileName() + ':' + f.getLineNumber() + ')';
            }
        });
        return out;
    }

    /**
     * Which thread and which region we were on when the call came in.
     */
    private static String context() {
        final StringBuilder sb = new StringBuilder(96);
        sb.append("thread=").append(Thread.currentThread().getName());
        try {
            final Object region = io.papermc.paper.threadedregions.TickRegionScheduler.getCurrentRegion();
            sb.append(region == null ? ", region=none (no owning region on this thread)" : ", region=" + region);
        } catch (final Throwable ignored) {
            sb.append(", region=<unavailable>");
        }
        return sb.toString();
    }

    private static String version(final Plugin plugin) {
        try {
            return plugin.getPluginMeta().getVersion();
        } catch (final Throwable ignored) {
            return "<unknown>";
        }
    }

    private static Path jarPath(final Plugin plugin) {
        try {
            final URI uri = plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI();
            return Path.of(uri);
        } catch (final Throwable ignored) {
            return null;
        }
    }

    private static String jarName(final Plugin plugin) {
        final Path p = jarPath(plugin);
        return p == null ? "<unknown>" : p.getFileName().toString();
    }

    private static String jarSha(final Plugin plugin) {
        final Path p = jarPath(plugin);
        if (p == null) {
            return "<unknown>";
        }
        return JAR_SHA.computeIfAbsent(p.toString(), path -> {
            try {
                final MessageDigest md = MessageDigest.getInstance("SHA-256");
                return HexFormat.of().formatHex(md.digest(Files.readAllBytes(Path.of(path))));
            } catch (final Throwable ignored) {
                return "<unreadable>";
            }
        });
    }
}
