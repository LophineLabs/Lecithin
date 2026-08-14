package fun.bm.lophinya.compat;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.SimpleServicesManager;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lophinya: the one hook point for {@link LophinyaEconomySerialization}'s service boundary.
 *
 * <p>Every provider a plugin registers passes through {@link #register}, so wrapping here reaches
 * all of them without a per-plugin hook and without touching plugin code. The only behaviour change
 * is for services named in the version-locked rule table; everything else is registered byte-for-
 * byte as before, including when the kill switch is off.
 *
 * <p>{@code unregister(Object)} and {@code unregister(Class, Object)} take the <i>provider
 * instance</i>, so a plugin that unregisters its own provider would otherwise pass the original
 * object while the manager holds the wrapper. The identity map below translates it back. Plugin
 * disable goes through {@code unregisterAll(Plugin)}, which is keyed by plugin and needs no
 * translation.
 */
public class LophinyaServicesManager extends SimpleServicesManager {

    /**
     * original provider -> wrapper actually registered. Identity, because providers may not implement equals.
     */
    private final Map<Object, Object> wrappers = new ConcurrentHashMap<>();

    @Override
    @SuppressWarnings("unchecked")
    public <T> void register(@NotNull final Class<T> service, @NotNull final T provider,
                             @NotNull final Plugin plugin, @NotNull final ServicePriority priority) {
        final Object wrapped = LophinyaEconomySerialization.wrapServiceProvider(service, provider, plugin);
        if (wrapped != provider) {
            this.wrappers.put(provider, wrapped);
        }
        super.register(service, (T) wrapped, plugin, priority);
    }

    @Override
    public void unregister(@NotNull final Class<?> service, @NotNull final Object provider) {
        super.unregister(service, translate(provider));
    }

    @Override
    public void unregister(@NotNull final Object provider) {
        super.unregister(translate(provider));
    }

    private Object translate(final Object provider) {
        final Object wrapped = this.wrappers.remove(provider);
        return wrapped != null ? wrapped : provider;
    }
}
