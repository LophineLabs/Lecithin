package fun.bm.lecithin.compat.legacy;

import com.mojang.logging.LogUtils;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Lecithin: calls into a service provider registered by a managed plugin run inside that plugin's
 * domain.
 *
 * <h2>Why</h2>
 * A {@code ServicesManager} provider is the plugin's own state reached from outside it: a Vault
 * economy, a permission or chat bridge. On Paper a main-thread caller and the provider plugin's own
 * listeners could never interleave; on Folia a region thread calling the provider and another region
 * thread inside the provider plugin's listener can, and the provider's unsynchronised read-modify-write
 * loses updates without a single exception or ownership violation. Entering the provider plugin's
 * domain for the duration of the call restores the interleaving Paper had.
 *
 * <h2>What the caller still sees</h2>
 * The call stays synchronous on the caller's thread: same return value, the provider's exception
 * rethrown unwrapped, same side effects in the same order. Only concurrency narrows, and only for
 * synchronous callers (tick threads and the legacy lane): an asynchronous caller ran concurrently with
 * the main thread on Paper and still does. The one exception is an economy interface the core already
 * guarded ({@code LecithinEconomySerialization}): its async callers were serialised per account before
 * this runtime existed, and a worker thread settling a payment must not start racing a player's
 * {@code /pay} again, so they enter the domain too. Calls on {@link Object}'s own methods never do. The wrapper is a {@link Proxy} of the service
 * interface, which is the type every consumer looks the service up by.
 *
 * <p>This supersedes the economy-specific per-account serialization for a managed provider (the
 * domain is strictly stronger), and deliberately does not stack with it: two independent lock
 * orders around the same provider would reintroduce the deadlock both are designed to avoid.
 * A provider registered by a native (Folia) plugin is not touched here.
 */
public final class LegacyServiceBoundary {

    private static final Logger LOGGER = LogUtils.getLogger();

    private LegacyServiceBoundary() {
    }

    /**
     * @return the domain-entering wrapper, or {@code provider} itself when the registering plugin is
     * not managed or the service is not an interface
     */
    public static Object wrap(final Class<?> service, final Object provider, final Plugin plugin) {
        if (service == null || provider == null || !service.isInterface() || Proxy.isProxyClass(provider.getClass())) {
            return provider;
        }
        final LegacyPluginState state = LegacyPluginRuntime.stateOf(plugin);
        if (state == null) {
            return provider;
        }
        try {
            final Object wrapped = Proxy.newProxyInstance(service.getClassLoader() != null ? service.getClassLoader()
                    : provider.getClass().getClassLoader(), new Class<?>[]{service}, new DomainHandler(state, provider,
                    fun.bm.lecithin.compat.LecithinEconomySerialization.isGuardedService(service)));
            LOGGER.info("[Lecithin] {}: service {} is served inside the plugin's legacy domain",
                    plugin.getName(), service.getName());
            return wrapped;
        } catch (final Throwable t) {
            // The proxy could not be built (for example a non-public interface); leave the provider
            // exactly as registered rather than failing the registration.
            LOGGER.warn("[Lecithin] {}: could not wrap service {} for legacy execution; registering it unwrapped",
                    plugin.getName(), service.getName(), t);
            return provider;
        }
    }

    public static boolean isManagedProvider(final Plugin plugin) {
        return LegacyPluginRuntime.isManaged(plugin);
    }

    private record DomainHandler(LegacyPluginState state, Object delegate, boolean serialiseAsyncCallers) implements InvocationHandler {
        @Override
        public Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> args[0] == proxy || this.delegate.equals(args[0]);
                    case "hashCode" -> this.delegate.hashCode();
                    default -> invokeDelegate(method, args);
                };
            }
            final LegacyPluginRuntime.Frame frame = LegacyPluginRuntime.enterService(this.state, this.serialiseAsyncCallers);
            try {
                return invokeDelegate(method, args);
            } finally {
                LegacyPluginRuntime.exit(frame);
            }
        }

        private Object invokeDelegate(final Method method, final Object[] args) throws Throwable {
            try {
                if (!method.canAccess(this.delegate)) {
                    method.setAccessible(true);
                }
                return method.invoke(this.delegate, args);
            } catch (final InvocationTargetException e) {
                throw e.getCause() == null ? e : e.getCause();
            }
        }
    }
}
