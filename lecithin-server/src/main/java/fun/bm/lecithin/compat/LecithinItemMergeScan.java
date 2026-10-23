package fun.bm.lecithin.compat;

import net.minecraft.world.item.ItemStack;

/**
 * Lecithin: bound the cost of the {@code ItemEntity} merge scan on dense piles of items that cannot merge.
 *
 * <h2>The gap</h2>
 * {@code ItemEntity#mergeWithNeighbours} collects every mergable item around the entity and then calls
 * {@code tryToMerge} on each one. "Mergable" only means "below the stack limit", so a region full of 33-stacks
 * (33 + 33 never fits in 64), items with different components, or items reserved for another player all pass the
 * candidate filter and every one of them fails the real merge test. Each item scans every other item, which is
 * O(N^2) per scan interval, and every failed {@code tryToMerge} also allocates in the shadow-group wrapper.
 * A production end region with about 96k entities stayed in this loop for minutes.
 *
 * <h2>The fix, in three parts</h2>
 * <ul>
 *   <li>{@link #mayMerge}: a candidate that {@code areMergable} plus the target check would reject is never a
 *       candidate. This changes no result, it only avoids the allocation and the {@code ItemMergeEvent}-free
 *       failed attempt.</li>
 *   <li>{@link #candidateCap}: after the filter every candidate has at least one item, so more than
 *       {@code maxStack - count} successful merges are impossible; the scan stops collecting there (plus
 *       {@link #SLACK} for candidates that a wall check or a cancelled event still rejects).</li>
 *   <li>{@link #scanInterval} / {@link #nextBackoffExp}: a stationary item whose stack did not change and whose
 *       last scan merged nothing scans exponentially less often. Anything that can create a new opportunity
 *       (the item moving, its stack count changing, a merge happening) resets the interval.</li>
 * </ul>
 * The pre-filter is skipped by the caller when item over-stacking is enabled or a stack is managed by
 * the shadow-group wrapper, because those paths merge by rules {@code areMergable} does not describe.
 */
public final class LecithinItemMergeScan {

    /** Highest backoff exponent: a stationary item scans every {@code 40 << 4} = 640 ticks at most. */
    public static final int MAX_EXP = 4;

    /** Extra candidates collected beyond what could possibly merge, for walls and cancelled events. */
    public static final int SLACK = 16;

    /** Most intersecting candidates one scan evaluates before it stops; a pile denser than this is scanned best-effort. */
    public static final int SCAN_BUDGET = 256;

    /** Vanilla scan interval for a stationary item. */
    public static final int STATIONARY_INTERVAL = 40;

    /** Vanilla scan interval for an item that changed block position this tick. */
    public static final int MOVING_INTERVAL = 2;

    private LecithinItemMergeScan() {
    }

    /** Ticks between scans: always 2 for a moving item, {@code 40 << exp} (exp clamped to 0..MAX_EXP) otherwise. */
    public static int scanInterval(final boolean moved, final int exp) {
        if (moved) {
            return MOVING_INTERVAL;
        }
        return STATIONARY_INTERVAL << Math.max(0, Math.min(exp, MAX_EXP));
    }

    /**
     * Backoff exponent after a scan. Reset to 0 when the item moved, a merge happened, or its stack count changed
     * since the previous scan; otherwise one step up to {@link #MAX_EXP}.
     */
    public static int nextBackoffExp(final int exp, final boolean mergedAny, final boolean moved, final boolean countChanged) {
        if (moved || mergedAny || countChanged) {
            return 0;
        }
        return Math.min(Math.max(exp, 0) + 1, MAX_EXP);
    }

    /** Most candidates worth collecting for an item of {@code count} in a stack limited to {@code maxStack}. */
    public static int candidateCap(final int maxStack, final int count) {
        return Math.max(1, maxStack - count) + SLACK;
    }

    /**
     * Necessary condition for {@code ItemEntity#carpetOrgTryToMerge} to reach the merge: same target and
     * {@code ItemEntity.areMergable(a, b)} (count sum within the limit of {@code b}, same item, same components).
     * The checks are ordered cheapest first; the result is exactly {@code sameTarget && areMergable(a, b)}, so a
     * {@code false} here means the merge would have been rejected anyway.
     */
    public static boolean mayMerge(final ItemStack a, final ItemStack b, final boolean sameTarget) {
        if (!sameTarget) {
            return false;
        }
        if (a.getCount() + b.getCount() > b.getMaxStackSize()) {
            return false;
        }
        return ItemStack.isSameItemSameComponents(a, b);
    }

    /**
     * Whether an item scans on this tick. The entity id offsets the phase so items that were spawned or loaded together
     * do not all scan on the same tick; each item still scans exactly once per {@code rate} ticks.
     */
    public static boolean scanDue(final int tickCount, final int entityId, final int rate) {
        return Math.floorMod(tickCount + entityId, rate) == 0;
    }

    /** Whether a scan at this backoff step is limited to {@link #SCAN_BUDGET} candidates; the last step scans everything. */
    public static boolean budgeted(final int exp) {
        return exp < MAX_EXP;
    }
}
