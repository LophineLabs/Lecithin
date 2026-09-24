package fun.bm.lecithin.compatsuite.fixture;

import org.bukkit.Bukkit;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.ExecutionException;

/**
 * Small helpers shared by scenarios.
 */
public final class Probe {

    /** Observation value for "the callback never ran", so a missing run is visible, not null. */
    public static final String NOT_RUN = "not-run";
    public static final String NONE = "none";

    private Probe() {
    }

    public static boolean primary() {
        try {
            return Bukkit.isPrimaryThread();
        } catch (final Throwable t) {
            return false;
        }
    }

    public static String thread() {
        return Thread.currentThread().getName();
    }

    /**
     * The comparable name of a failure: the simple class name of the real cause. Messages go to diag.
     */
    public static String exName(Throwable t) {
        t = unwrap(t);
        return t == null ? NONE : t.getClass().getSimpleName();
    }

    public static String exMessage(Throwable t) {
        t = unwrap(t);
        return t == null ? null : t.getClass().getName() + ": " + t.getMessage();
    }

    public static Throwable unwrap(Throwable t) {
        while (t != null && (t instanceof ExecutionException || t instanceof InvocationTargetException)
                && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    public static Object orNotRun(final Object value) {
        return value == null ? NOT_RUN : value;
    }

    public static void sleep(final long ms) {
        try {
            Thread.sleep(ms);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
