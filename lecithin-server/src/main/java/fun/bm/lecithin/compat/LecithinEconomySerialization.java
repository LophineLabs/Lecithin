package fun.bm.lecithin.compat;

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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Lecithin: per-account serialization for plugin economy ledgers that were written for a single
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
 *   <li><b>Service boundary.</b> When any plugin registers a known economy service interface into
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
 * Unlike {@link LecithinCallerContextDispatch}, which fails <i>closed</i> (an unknown owner means
 * "do not dispatch"), this class fails <i>open</i>: if the service lookup or the
 * proxy construction fails, the original provider is returned unwrapped and the server behaves
 * exactly as it does today. The reason for the difference is that this class never grants a plugin
 * new reach - it only narrows concurrency for a plugin that is already running. A failure here can
 * only lose the added protection, never create a new code path.
 *
 * <p>Kill switch: {@code compat-config.economy-serialization=false} - defaults to <b>on</b>, see
 * {@link #ENABLED} for why this one is not default-off.
 */
public final class LecithinEconomySerialization {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * The economy service interfaces this guard applies to.
     *
     * <p>Keyed on the <b>API contract</b>, not on whoever implements it. That is the whole point:
     * {@code net.milkbowl.vault.economy.Economy} is a published interface whose per-account
     * read-modify-write shape is the same whichever plugin registers it, and it does not change when
     * that plugin is updated. An earlier version of this class keyed on the exact SHA-256 of one
     * plugin jar, which meant an unmodified plugin silently lost the protection the moment it was
     * updated - the same defect that made the scheduler rule table unusable in production.
     */
    private static final Set<String> GUARDED_SERVICES = Set.of(
            "net.milkbowl.vault.economy.Economy",
            "net.milkbowl.vault2.economy.Economy"
    );

    /**
     * Command names run under the coarse exclusive lock, but only for a plugin that actually
     * registered one of {@link #GUARDED_SERVICES} - see {@link #isGuardedCommand}.
     *
     * <h2>Why a name list at all, and why it is not a plugin table</h2>
     * The service boundary above covers every Vault consumer, but an economy plugin's own commands
     * do not go through Vault - they call the plugin's internal API directly, which the core cannot
     * see. There is no API that says "this command moves money", so the only available signal is the
     * command name, and these are the conventional economy command names, shared across economy
     * plugins rather than taken from any one of them.
     *
     * <p>What keeps this honest is the second condition: a name here only matters for a plugin that
     * has registered a guarded economy service. A chat plugin owning a command called {@code pay}
     * is not affected, and no plugin is named, versioned or hashed anywhere.
     */
    private static final Set<String> GUARDED_COMMAND_NAMES = Set.of(
            "pay", "eco", "economy", "balance", "bal", "money",
            "sell", "worth", "buy", "deposit", "withdraw"
    );

    /**
     * Plugins observed registering one of {@link #GUARDED_SERVICES} during this run. Populated by
     * {@link #wrapServiceProvider}, read by {@link #isGuardedCommand} - which is what ties the
     * command boundary to a real, observed fact rather than to a name in a table.
     */
    private static final Set<String> ECONOMY_PROVIDER_PLUGINS = ConcurrentHashMap.newKeySet();

    /**
     * coarse -> account is the only lock order taken anywhere in this class.
     */
    private static final ReentrantReadWriteLock COARSE = new ReentrantReadWriteLock(true);
    private static final Map<String, ReentrantLock> ACCOUNT_LOCKS = new ConcurrentHashMap<>();
    /**
     * Log the first time each guarded command actually goes through layer B, not every time.
     */
    private static final Set<String> COMMANDS_LOGGED = ConcurrentHashMap.newKeySet();

    // ---------------------------------------------------------------- service boundary

    /**
     * Called from the services manager on every {@code register(...)}.
     *
     * @return the wrapped provider, or {@code provider} unchanged when no rule applies or anything
     * at all goes wrong (fail-open, see class docs)
     */
    public static Object wrapServiceProvider(final Class<?> service, final Object provider, final Plugin plugin) {
        if (!CompatConfig.economySerialization || service == null || provider == null || plugin == null || !service.isInterface()) {
            return provider;
        }
        try {
            if (!GUARDED_SERVICES.contains(service.getName())) {
                return provider;
            }
            ECONOMY_PROVIDER_PLUGINS.add(plugin.getName());
            final Object wrapped = Proxy.newProxyInstance(
                    provider.getClass().getClassLoader(),
                    new Class<?>[]{service},
                    new SerializingHandler(provider)
            );
            LOGGER.info("[Lecithin] {}: serializing economy service {} per account - keyed on the "
                            + "service interface, not on the plugin; different accounts still run in parallel",
                    plugin.getName(), service.getName());
            return wrapped;
        } catch (final Throwable t) {
            LOGGER.warn("[Lecithin] economy service wrap failed (leaving provider unwrapped)", t);
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
     * economy commands, meaning the caller must run it inside
     * {@link #beginExclusive()}/{@link #endExclusive()}
     */
    public static boolean isGuardedCommand(final Command command) {
        if (!CompatConfig.economySerialization || !(command instanceof PluginCommand pc)) {
            return false;
        }
        try {
            final String name = command.getName().toLowerCase(Locale.ROOT);
            if (!GUARDED_COMMAND_NAMES.contains(name)
                    || !ECONOMY_PROVIDER_PLUGINS.contains(pc.getPlugin().getName())) {
                return false;
            }
            // Layer B otherwise has no positive signal at all: the only evidence it ran was that
            // the guarded commands did not misbehave, which is indistinguishable from the guard
            // never having matched. One line per command name, once per server lifetime.
            if (COMMANDS_LOGGED.add(name)) {
                LOGGER.info("[Lecithin] {}: running /{} under the exclusive economy lock - first hit "
                        + "this run; this plugin registered a guarded economy service", pc.getPlugin().getName(), name);
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
}
