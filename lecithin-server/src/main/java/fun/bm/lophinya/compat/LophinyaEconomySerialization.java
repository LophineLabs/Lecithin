package fun.bm.lophinya.compat;

import com.mojang.logging.LogUtils;
import fun.bm.lecithin.config.modules.CompatConfig;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Lophinya: per-account serialization for plugin economy ledgers that were written for a single
 * main thread and have no internal locking of their own.
 *
 * <h2>The problem this exists for</h2>
 * A ledger like EssentialsX's stores the balance in a plain, non-volatile field and mutates it with
 * a non-atomic read-modify-write spread across two method calls
 * ({@code setMoney(getMoney().subtract(x))}). On Paper that is safe by accident: every caller runs
 * on the one main thread, so the operations serialize. On Folia two players in two regions run on
 * two threads, the two read-modify-writes interleave, and one update is lost - then written cleanly
 * to disk by the plugin's own save executor. There is no exception, no log line, and
 * <b>no thread-ownership violation</b>, because a money field is not world state: the whole flow
 * never reaches {@code TickThread.ensureTickThread}. The project's usual "ownership violations = 0"
 * hard metric is structurally blind to it (project-docs evidence 20260729d-t01 section 6.4).
 *
 * <h2>What this class does, and what it deliberately does not</h2>
 * It restores the <i>serialization</i> Paper provided implicitly, at the two boundaries the core
 * actually owns - without touching, patching, or instrumenting a single byte of the plugin:
 * <ol>
 *   <li><b>Service boundary.</b> When a version-locked plugin registers an economy service into
 *       Bukkit's {@code ServicesManager}, the provider is wrapped in a {@link Proxy} that holds a
 *       per-account lock for the duration of each call. Every Vault consumer (shop plugins, reward
 *       plugins, server tweaks) goes through this one object, so one wrapper covers all of them.
 *       Different accounts still run fully in parallel - the lock is per account, not global.</li>
 *   <li><b>Legacy command boundary.</b> The plugin's own commands ({@code /pay}, {@code /eco},
 *       {@code /sell}, {@code /balance}) do not go through Vault; they call the plugin's internal
 *       API directly, which the core cannot see. Those are executed under a coarse exclusive lock
 *       that also excludes every service-boundary call above, so the two layers genuinely interlock
 *       instead of each guarding half the ledger.</li>
 * </ol>
 * It does <b>not</b> add a lock inside the plugin, rewrite its data model, or make its balance field
 * volatile - those are {@code DEC-19} B5 and stay out of scope. It also does not, on its own, make
 * any rejected plugin loadable: the {@code DEC-47} refusal list is a separate mechanism and is not
 * touched here.
 *
 * <h2>Lock order and why it cannot deadlock</h2>
 * The only order ever taken is {@code coarse -> account}, and account locks are acquired in sorted
 * key order when a call names more than one account. Nothing is ever held across a scheduler
 * hand-off, a chunk load, or a future - every critical section is a straight-line call into the
 * plugin and back, so a region thread blocked on one of these locks is always waiting on a section
 * that is itself making progress. {@link ReentrantReadWriteLock} permits taking the read lock while
 * already holding the write lock, so a command that internally reaches the service boundary
 * (an economy layer bridging back through Vault) downgrades rather than self-deadlocks.
 *
 * <h2>Fail-open by construction</h2>
 * Unlike {@link LophinyaPluginSchedulerDispatch}, which fails <i>closed</i> (an unreadable field
 * means "do not dispatch"), this class fails <i>open</i>: if the rule lookup, the jar hash, or the
 * proxy construction fails, the original provider is returned unwrapped and the server behaves
 * exactly as it does today. The reason for the difference is that this class never grants a plugin
 * new reach - it only narrows concurrency for a plugin that is already running. A failure here can
 * only lose the added protection, never create a new code path.
 *
 * <p>Kill switch: {@code -Dlophinya.compat.economySerialization=false} - defaults to <b>on</b>, see
 * {@link #ENABLED} for why this one is not default-off.
 */
public final class LophinyaEconomySerialization {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * One entry per (plugin, exact jar SHA-256), same versioning discipline as
     * {@link LophinyaPluginSchedulerDispatch#RULES}: a plugin update needs a fresh entry, it never
     * inherits the previous version's guard.
     *
     * @param services fully-qualified interface names whose registered providers get wrapped
     * @param commands Bukkit command <i>names</i> (not aliases - {@link Command#getName()} already
     *                 resolves aliases to the primary name) executed under the coarse exclusive lock
     */
    public record Guard(Set<String> services, Set<String> commands) {
    }

    private static final Map<String, Guard> RULES = Map.of(
        // EssentialsX 2.22.1-dev+12-776f709 (the exact artifact installed on s01). Research:
        // evidence/20260729d-t01-essentialsx-callsite-analysis.md (the read-modify-write and the
        // zero-lock scan), evidence/20260730b-essentialsx-economy-serialization.md (this design,
        // the full mutation call graph, and the concurrency harness results).
        //
        // Every balance mutation in the plugin funnels through User.setMoney(BigDecimal, Cause) and
        // every read through User.getMoney(); the mutation origins are exactly five, and each is
        // reachable only via one of the two boundaries guarded here:
        //   api/Economy.java:253,331 (statics) .......... service boundary (VaultEconomyProvider)
        //   User.payUser:312-313 ........................ command boundary (/pay)
        //   Trade.java:239,307,326 ...................... command boundary (/sell, command costs)
        //   commands/Commandeco.java:58,63,75 ........... command boundary (/eco)
        //   commands/Commandsell.java:122 ............... command boundary (/sell)
        // Sign shops are the one other Trade entry point; they are disabled on s01 (config.yml
        // "enabledSigns:" is empty), which is recorded as a scope limit, not as a fix.
        //
        // balancetop is deliberately NOT guarded: it is read-only and already runs off-thread over
        // the whole userdata set, so putting it under the exclusive lock would stall every region
        // thread for the length of a full scan to protect a value that is advisory by design.
        "ED0C4432BB286CE06820BA5A162FFAC91E34E02EBA644CFCF66AEC4FDA86AF42", new Guard(
            Set.of("net.milkbowl.vault.economy.Economy"),
            Set.of("pay", "eco", "sell", "balance")
        )
    );

    /** coarse -> account is the only lock order taken anywhere in this class. */
    private static final ReentrantReadWriteLock COARSE = new ReentrantReadWriteLock(true);
    private static final Map<String, ReentrantLock> ACCOUNT_LOCKS = new ConcurrentHashMap<>();
    private static final Map<Path, String> SHA_CACHE = new ConcurrentHashMap<>();
    /** Log the first time each guarded command actually goes through layer B, not every time. */
    private static final Set<String> COMMANDS_LOGGED = ConcurrentHashMap.newKeySet();

    // ---------------------------------------------------------------- service boundary

    /**
     * Called from the services manager on every {@code register(...)}.
     *
     * @return the wrapped provider, or {@code provider} unchanged when no rule applies or anything
     *         at all goes wrong (fail-open, see class docs)
     */
    public static Object wrapServiceProvider(final Class<?> service, final Object provider, final Plugin plugin) {
        if (!CompatConfig.economySerialization || service == null || provider == null || plugin == null || !service.isInterface()) {
            return provider;
        }
        try {
            final Guard guard = RULES.get(sha256(plugin));
            if (guard == null || !guard.services().contains(service.getName())) {
                return provider;
            }
            final Object wrapped = Proxy.newProxyInstance(
                provider.getClass().getClassLoader(),
                new Class<?>[]{service},
                new SerializingHandler(provider)
            );
            LOGGER.warn("[Lophinya] {}: serializing economy service {} per account - version-locked "
                + "rule table entry; different accounts still run in parallel",
                plugin.getName(), service.getName());
            return wrapped;
        } catch (final Throwable t) {
            LOGGER.warn("[Lophinya] economy service wrap failed (leaving provider unwrapped)", t);
            return provider;
        }
    }

    private record SerializingHandler(Object delegate) implements InvocationHandler {
        @Override
        public Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable {
            final List<String> keys = accountKeys(args);
            if (keys.isEmpty()) {
                // No account named (format(), currencyNamePlural(), getName(), ...): nothing to
                // serialize, and taking the coarse lock for these would be pure contention.
                return call(method, args);
            }
            COARSE.readLock().lock();
            final List<ReentrantLock> held = new ArrayList<>(keys.size());
            try {
                for (final String key : keys) {
                    final ReentrantLock lock = ACCOUNT_LOCKS.computeIfAbsent(key, k -> new ReentrantLock());
                    lock.lock();
                    held.add(lock);
                }
                return call(method, args);
            } finally {
                for (int i = held.size() - 1; i >= 0; i--) {
                    held.get(i).unlock();
                }
                COARSE.readLock().unlock();
            }
        }

        private Object call(final Method method, final Object[] args) throws Throwable {
            try {
                return method.invoke(this.delegate, args);
            } catch (final InvocationTargetException e) {
                // Unwrap so the caller sees exactly the exception the real provider threw.
                throw e.getCause() == null ? e : e.getCause();
            }
        }
    }

    /**
     * Derives the canonical lock key(s) named by a service call's arguments, sorted so that a call
     * naming two accounts always locks them in the same order.
     *
     * <p>An {@link OfflinePlayer} yields its UUID directly. A bare name is resolved through the
     * online player list first so that {@code withdrawPlayer("Steve", 5)} and
     * {@code withdrawPlayer(steveOfflinePlayer, 5)} land on the <i>same</i> lock; if that lookup is
     * unavailable the name itself is used, which is no worse than the unguarded behaviour.
     */
    private static List<String> accountKeys(final Object[] args) {
        if (args == null) {
            return Collections.emptyList();
        }
        final List<String> keys = new ArrayList<>(2);
        for (final Object arg : args) {
            if (arg instanceof OfflinePlayer op) {
                final UUID id = op.getUniqueId();
                if (id != null) {
                    addKey(keys, "u:" + id);
                }
            } else if (arg instanceof String s && !s.isEmpty()) {
                addKey(keys, nameKey(s));
            }
        }
        Collections.sort(keys);
        return keys;
    }

    private static void addKey(final List<String> keys, final String key) {
        if (!keys.contains(key)) {
            keys.add(key);
        }
    }

    private static String nameKey(final String name) {
        try {
            final Player online = Bukkit.getPlayerExact(name);
            if (online != null) {
                return "u:" + online.getUniqueId();
            }
        } catch (final Throwable ignored) {
            // Fall through to the name key: a separate lock is exactly today's behaviour.
        }
        return "n:" + name.toLowerCase(Locale.ROOT);
    }

    // ---------------------------------------------------------------- legacy command boundary

    /**
     * @return {@code true} when this command belongs to a guarded plugin and is one of its guarded
     *         economy commands, meaning the caller must run it inside
     *         {@link #beginExclusive()}/{@link #endExclusive()}
     */
    public static boolean isGuardedCommand(final Command command) {
        if (!CompatConfig.economySerialization || !(command instanceof PluginCommand pc)) {
            return false;
        }
        try {
            final Guard guard = RULES.get(sha256(pc.getPlugin()));
            final String name = command.getName().toLowerCase(Locale.ROOT);
            if (guard == null || !guard.commands().contains(name)) {
                return false;
            }
            // Layer B otherwise has no positive signal at all: the only evidence it ran was that
            // the guarded commands did not misbehave, which is indistinguishable from the guard
            // never having matched. One line per command name, once per server lifetime.
            if (COMMANDS_LOGGED.add(name)) {
                LOGGER.info("[Lophinya] {}: running /{} under the exclusive economy lock - first hit "
                    + "this run; version-locked rule table entry", pc.getPlugin().getName(), name);
            }
            return true;
        } catch (final Throwable t) {
            return false;
        }
    }

    /**
     * Exclusive against every service-boundary call. Coarse on purpose: at this boundary the core
     * can see which plugin owns the command but not which accounts the command body will touch
     * (a {@code /pay} names its target only inside the plugin's own argument parsing). These
     * commands are human-typed and rare, so buying correctness with exclusivity is the right trade;
     * the high-frequency path is the service boundary, which stays per-account.
     */
    public static void beginExclusive() {
        COARSE.writeLock().lock();
    }

    public static void endExclusive() {
        COARSE.writeLock().unlock();
    }

    // ---------------------------------------------------------------- shared

    private static String sha256(final Plugin plugin) {
        if (plugin == null) {
            return "<unreadable>";
        }
        try {
            final Path jarPath = Path.of(plugin.getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
            return SHA_CACHE.computeIfAbsent(jarPath, LophinyaEconomySerialization::hash);
        } catch (final Throwable t) {
            return "<unreadable>";
        }
    }

    private static String hash(final Path jarPath) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().withUpperCase().formatHex(md.digest(Files.readAllBytes(jarPath)));
        } catch (final Throwable t) {
            return "<unreadable>";
        }
    }
}
