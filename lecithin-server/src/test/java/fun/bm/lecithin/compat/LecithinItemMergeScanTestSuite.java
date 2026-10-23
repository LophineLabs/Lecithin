package fun.bm.lecithin.compat;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.flag.FeatureFlags;
import org.bukkit.support.RegistryHelper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link LecithinItemMergeScan}: the interval/backoff arithmetic, the candidate pre-filter against the real
 * {@code ItemEntity.areMergable}, and a model of one merge round that counts how much work the old and the new scan do.
 */
// RegistryHelper.setup binds item components and registries; Bootstrap alone leaves Item components unbound
class LecithinItemMergeScanTestSuite {

    @BeforeAll
    static void bindRegistries() {
        RegistryHelper.setup(FeatureFlags.VANILLA_SET);
    }

    // ---- interval / backoff / cap -------------------------------------------------------------

    @Test
    void scanIntervalKeepsVanillaValuesAndDoublesWhenStationary() {
        assertEquals(2, LecithinItemMergeScan.scanInterval(true, 0));
        assertEquals(2, LecithinItemMergeScan.scanInterval(true, 4));
        assertEquals(40, LecithinItemMergeScan.scanInterval(false, 0));
        assertEquals(80, LecithinItemMergeScan.scanInterval(false, 1));
        assertEquals(160, LecithinItemMergeScan.scanInterval(false, 2));
        assertEquals(320, LecithinItemMergeScan.scanInterval(false, 3));
        assertEquals(640, LecithinItemMergeScan.scanInterval(false, 4));
        // out-of-range exponents are clamped, never an overflow or a zero interval
        assertEquals(640, LecithinItemMergeScan.scanInterval(false, 99));
        assertEquals(40, LecithinItemMergeScan.scanInterval(false, -3));
        for (int exp = 0; exp <= LecithinItemMergeScan.MAX_EXP; exp++) {
            assertEquals(0, LecithinItemMergeScan.scanInterval(false, exp) % 40, "phase must stay a multiple of 40");
        }
    }

    @Test
    void backoffGrowsOnlyOnQuietStationaryScansAndCaps() {
        int exp = 0;
        for (int expected = 1; expected <= LecithinItemMergeScan.MAX_EXP; expected++) {
            exp = LecithinItemMergeScan.nextBackoffExp(exp, false, false, false);
            assertEquals(expected, exp);
        }
        assertEquals(LecithinItemMergeScan.MAX_EXP, LecithinItemMergeScan.nextBackoffExp(exp, false, false, false));
        assertEquals(0, LecithinItemMergeScan.nextBackoffExp(3, true, false, false), "a merge resets");
        assertEquals(0, LecithinItemMergeScan.nextBackoffExp(3, false, true, false), "moving resets");
        assertEquals(0, LecithinItemMergeScan.nextBackoffExp(3, false, false, true), "a changed count resets");
        assertEquals(1, LecithinItemMergeScan.nextBackoffExp(-5, false, false, false));
    }

    @Test
    void candidateCapIsRoomPlusSlackWithAFloor() {
        assertEquals(63 + 16, LecithinItemMergeScan.candidateCap(64, 1));
        assertEquals(31 + 16, LecithinItemMergeScan.candidateCap(64, 33));
        assertEquals(1 + 16, LecithinItemMergeScan.candidateCap(64, 63));
        assertEquals(1 + 16, LecithinItemMergeScan.candidateCap(64, 64), "full stack still gets the floor");
        assertEquals(1 + 16, LecithinItemMergeScan.candidateCap(64, 100));
        assertEquals(15 + 16, LecithinItemMergeScan.candidateCap(16, 1));
        assertEquals(1 + 16, LecithinItemMergeScan.candidateCap(1, 1));
    }

    // ---- pre-filter versus the real areMergable -----------------------------------------------

    private static List<ItemStack> samples() {
        List<ItemStack> out = new ArrayList<>();
        int[] counts = {1, 2, 16, 31, 32, 33, 63, 64};
        for (int c : counts) {
            out.add(new ItemStack(Items.COBBLESTONE, c));
            out.add(new ItemStack(Items.STONE, c));
            ItemStack named = new ItemStack(Items.COBBLESTONE, c);
            named.set(DataComponents.CUSTOM_NAME, Component.literal("a"));
            out.add(named);
            ItemStack namedB = new ItemStack(Items.COBBLESTONE, c);
            namedB.set(DataComponents.CUSTOM_NAME, Component.literal("b"));
            out.add(namedB);
            ItemStack small = new ItemStack(Items.COBBLESTONE, c);
            small.set(DataComponents.MAX_STACK_SIZE, 40);
            out.add(small);
        }
        out.add(new ItemStack(Items.DIAMOND_SWORD, 1));
        out.add(new ItemStack(Items.ENDER_PEARL, 7));
        out.add(new ItemStack(Items.ENDER_PEARL, 9));
        return out;
    }

    @Test
    void preFilterEqualsTargetAndAreMergableOnEveryPair() {
        List<ItemStack> all = samples();
        int trues = 0;
        int falses = 0;
        for (ItemStack a : all) {
            for (ItemStack b : all) {
                boolean real = ItemEntity.areMergable(a, b);
                assertEquals(real, LecithinItemMergeScan.mayMerge(a, b, true), a + " vs " + b);
                assertFalse(LecithinItemMergeScan.mayMerge(a, b, false), "different targets never merge");
                if (real) {
                    trues++;
                } else {
                    falses++;
                }
            }
        }
        assertTrue(trues > 0 && falses > 0, "sample set must contain both outcomes");
    }

    @Test
    void preFilterNamedCases() {
        ItemStack a33 = new ItemStack(Items.COBBLESTONE, 33);
        ItemStack b33 = new ItemStack(Items.COBBLESTONE, 33);
        ItemStack b31 = new ItemStack(Items.COBBLESTONE, 31);
        ItemStack b64 = new ItemStack(Items.COBBLESTONE, 64);
        ItemStack stone = new ItemStack(Items.STONE, 1);
        ItemStack named = new ItemStack(Items.COBBLESTONE, 1);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("x"));
        ItemStack plain1 = new ItemStack(Items.COBBLESTONE, 1);

        assertFalse(LecithinItemMergeScan.mayMerge(a33, b33, true), "33 + 33 > 64");
        assertTrue(LecithinItemMergeScan.mayMerge(a33, b31, true), "33 + 31 == 64");
        assertFalse(LecithinItemMergeScan.mayMerge(a33, b64, true));
        assertFalse(LecithinItemMergeScan.mayMerge(a33, stone, true), "different item");
        assertFalse(LecithinItemMergeScan.mayMerge(plain1, named, true), "different components");
        assertTrue(LecithinItemMergeScan.mayMerge(plain1, new ItemStack(Items.COBBLESTONE, 5), true));
        assertFalse(LecithinItemMergeScan.mayMerge(plain1, new ItemStack(Items.COBBLESTONE, 5), false));
    }

    // ---- model of one merge round -------------------------------------------------------------

    /** A drop in the model: item, count, target, alive flag. Mirrors the fields the scan looks at. */
    private static final class Drop {
        final ItemStack stack;
        final UUID target;
        boolean removed;

        Drop(ItemStack stack, UUID target) {
            this.stack = stack;
            this.target = target;
        }

        boolean mergable() {
            return !this.removed && this.stack.getCount() < this.stack.getMaxStackSize();
        }
    }

    private static final class Work {
        long predicateTests;
        long tryToMerge;
        long merges;
    }

    /** What carpetOrgTryToMerge does once it is reached (target + areMergable), without the Bukkit event. */
    private static boolean tryToMerge(Drop self, Drop other, Work work) {
        work.tryToMerge++;
        if (!java.util.Objects.equals(self.target, other.target) || !ItemEntity.areMergable(self.stack, other.stack)) {
            return false;
        }
        if (other.stack.getCount() < self.stack.getCount()) {
            moveInto(self, other);
        } else {
            moveInto(other, self);
        }
        work.merges++;
        return true;
    }

    private static void moveInto(Drop to, Drop from) {
        to.stack.grow(from.stack.getCount());
        from.stack.setCount(0);
        from.removed = true;
    }

    /** The old scan: every mergable neighbour is attempted; nothing stops it once the stack is full. */
    private static void oldScan(Drop self, List<Drop> world, Work work) {
        if (!self.mergable()) {
            return;
        }
        List<Drop> candidates = new ArrayList<>();
        for (Drop other : world) {
            work.predicateTests++;
            if (other != self && other.mergable()) {
                candidates.add(other);
            }
        }
        for (Drop other : candidates) {
            if (other.mergable()) {
                tryToMerge(self, other, work);
                if (self.removed) {
                    break;
                }
            }
        }
    }

    /** The new scan: pre-filtered, capped candidates and an early exit once this stack cannot take more. */
    private static void newScan(Drop self, List<Drop> world, Work work) {
        if (!self.mergable()) {
            return;
        }
        int cap = LecithinItemMergeScan.candidateCap(self.stack.getMaxStackSize(), self.stack.getCount());
        List<Drop> candidates = new ArrayList<>();
        for (Drop other : world) {
            work.predicateTests++;
            if (other != self && other.mergable()
                && LecithinItemMergeScan.mayMerge(self.stack, other.stack, java.util.Objects.equals(self.target, other.target))) {
                candidates.add(other);
                if (candidates.size() >= cap) {
                    break;
                }
            }
        }
        for (Drop other : candidates) {
            if (other.mergable()) {
                tryToMerge(self, other, work);
                if (self.removed || !self.mergable()) {
                    break;
                }
            }
        }
    }

    private static List<Drop> piles(int stacks33, int cobble, int cobbleCount) {
        List<Drop> world = new ArrayList<>();
        for (int i = 0; i < stacks33; i++) {
            world.add(new Drop(new ItemStack(Items.STONE, 33), null));
        }
        for (int i = 0; i < cobble; i++) {
            world.add(new Drop(new ItemStack(Items.COBBLESTONE, cobbleCount), null));
            // interleave a few 33s so the cobble is not contiguous
            if (i % 5 == 0) {
                world.add(new Drop(new ItemStack(Items.STONE, 33), null));
            }
        }
        return world;
    }

    private static List<Drop> copy(List<Drop> world) {
        List<Drop> out = new ArrayList<>();
        for (Drop d : world) {
            out.add(new Drop(d.stack.copy(), d.target));
        }
        return out;
    }

    private static long total(List<Drop> world, net.minecraft.world.item.Item item) {
        long sum = 0;
        for (Drop d : world) {
            if (!d.removed && d.stack.is(item)) {
                sum += d.stack.getCount();
            }
        }
        return sum;
    }

    private static List<Integer> counts(List<Drop> world, net.minecraft.world.item.Item item) {
        List<Integer> out = new ArrayList<>();
        for (Drop d : world) {
            if (!d.removed && d.stack.is(item)) {
                out.add(d.stack.getCount());
            }
        }
        return out;
    }

    private static void assertSane(List<Drop> world, long stone, long cobble) {
        for (Drop d : world) {
            if (!d.removed) {
                assertTrue(d.stack.getCount() <= d.stack.getMaxStackSize(), "stack over its limit: " + d.stack);
                assertTrue(d.stack.getCount() > 0, "live drop with empty stack");
            }
        }
        assertEquals(stone, total(world, Items.STONE), "stone total conserved");
        assertEquals(cobble, total(world, Items.COBBLESTONE), "cobble total conserved");
    }

    private static void runBoth(String label, List<Drop> start) {
        long stone = total(start, Items.STONE);
        long cobble = total(start, Items.COBBLESTONE);

        List<Drop> oldWorld = copy(start);
        Work oldWork = new Work();
        for (Drop d : new ArrayList<>(oldWorld)) {
            oldScan(d, oldWorld, oldWork);
        }
        assertSane(oldWorld, stone, cobble);

        List<Drop> newWorld = copy(start);
        Work newWork = new Work();
        for (Drop d : new ArrayList<>(newWorld)) {
            newScan(d, newWorld, newWork);
        }
        assertSane(newWorld, stone, cobble);

        // same outcome: the capped scan reaches every merge the old one did in this round
        assertEquals(counts(oldWorld, Items.STONE), counts(newWorld, Items.STONE), label + " stone stacks");
        assertEquals(counts(oldWorld, Items.COBBLESTONE), counts(newWorld, Items.COBBLESTONE), label + " cobble stacks");
        assertEquals(oldWork.merges, newWork.merges, label + " merges");

        System.out.println("[merge-scan] " + label + " entities=" + start.size()
            + " | old: tryToMerge=" + oldWork.tryToMerge + " predicateTests=" + oldWork.predicateTests + " merges=" + oldWork.merges
            + " | new: tryToMerge=" + newWork.tryToMerge + " predicateTests=" + newWork.predicateTests + " merges=" + newWork.merges);
        assertTrue(newWork.tryToMerge <= oldWork.tryToMerge);
        if (oldWork.tryToMerge > 100) {
            assertTrue(newWork.tryToMerge * 20 < oldWork.tryToMerge,
                label + ": new tryToMerge " + newWork.tryToMerge + " not far below old " + oldWork.tryToMerge);
        }
    }

    @Test
    void densePileOf33StacksNeverAttemptsAMerge() {
        List<Drop> world = piles(400, 0, 0);
        runBoth("400x33", world);
        // the new scan must not have attempted a single merge on a pile that cannot merge
        List<Drop> check = copy(world);
        Work work = new Work();
        for (Drop d : new ArrayList<>(check)) {
            newScan(d, check, work);
        }
        assertEquals(0, work.tryToMerge);
        assertEquals(0, work.merges);
    }

    @Test
    void mixedPileStillMergesTheCobble() {
        runBoth("200x1-cobble mixed with 33-stacks", piles(0, 200, 1));
        runBoth("120x20-cobble mixed with 33-stacks", piles(100, 120, 20));
    }

    @Test
    void differentTargetsAreNotAttempted() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        List<Drop> world = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            world.add(new Drop(new ItemStack(Items.COBBLESTONE, 1), i % 2 == 0 ? a : b));
        }
        Work oldWork = new Work();
        List<Drop> oldWorld = copy(world);
        for (Drop d : new ArrayList<>(oldWorld)) {
            oldScan(d, oldWorld, oldWork);
        }
        Work newWork = new Work();
        List<Drop> newWorld = copy(world);
        for (Drop d : new ArrayList<>(newWorld)) {
            newScan(d, newWorld, newWork);
        }
        assertEquals(counts(oldWorld, Items.COBBLESTONE), counts(newWorld, Items.COBBLESTONE));
        assertEquals(100, total(newWorld, Items.COBBLESTONE));
        System.out.println("[merge-scan] two targets entities=100 | old: tryToMerge=" + oldWork.tryToMerge
            + " | new: tryToMerge=" + newWork.tryToMerge);
        assertTrue(newWork.tryToMerge < oldWork.tryToMerge);
    }

    @Test
    void stationaryUnmergeableItemScansFarLessOftenOverTime() {
        final int ticks = 40 * 64;
        int oldScans = 0;
        for (int tick = 1; tick <= ticks; tick++) {
            if (tick % LecithinItemMergeScan.scanInterval(false, 0) == 0) {
                oldScans++;
            }
        }
        int newScans = 0;
        int exp = 0;
        int lastCount = 0;
        final int count = 33;
        for (int tick = 1; tick <= ticks; tick++) {
            boolean countChanged = count != lastCount;
            if (countChanged) {
                exp = 0;
            }
            if (tick % LecithinItemMergeScan.scanInterval(false, exp) == 0) {
                newScans++;
                exp = LecithinItemMergeScan.nextBackoffExp(exp, false, false, countChanged);
                lastCount = count;
            }
        }
        System.out.println("[merge-scan] stationary 33-stack over " + ticks + " ticks | old scans=" + oldScans + " new scans=" + newScans);
        assertEquals(64, oldScans);
        assertTrue(newScans * 3 < oldScans, "backoff should cut scans of a quiet item to well under a third, got " + newScans);
        // a count change brings the vanilla interval straight back
        assertEquals(0, LecithinItemMergeScan.nextBackoffExp(LecithinItemMergeScan.MAX_EXP, false, false, true));
    }

    // ---- stagger and budget ---------------------------------------------------------------------

    @Test
    void scanDueSpreadsAGroupOverTheWholeInterval() {
        final int rate = LecithinItemMergeScan.STATIONARY_INTERVAL;
        final int[] perTick = new int[rate];
        for (int id = 0; id < 4000; id++) {
            int scans = 0;
            for (int tick = 0; tick < rate; tick++) {
                if (LecithinItemMergeScan.scanDue(tick, id, rate)) {
                    scans++;
                    perTick[tick]++;
                }
            }
            assertEquals(1, scans, "every item scans exactly once per interval");
        }
        for (int count : perTick) {
            assertEquals(4000 / rate, count, "items spawned together must not all scan on one tick");
        }
    }

    @Test
    void scanBudgetIsFiniteButLargerThanAnyNormalPile() {
        assertTrue(LecithinItemMergeScan.SCAN_BUDGET >= 128 && LecithinItemMergeScan.SCAN_BUDGET <= 1024);
    }

    @Test
    void onlyTheLastBackoffStepScansWithoutBudget() {
        for (int exp = 0; exp < LecithinItemMergeScan.MAX_EXP; exp++) {
            assertTrue(LecithinItemMergeScan.budgeted(exp));
        }
        assertFalse(LecithinItemMergeScan.budgeted(LecithinItemMergeScan.MAX_EXP));
        // an item that keeps finding nothing reaches the unbudgeted step in a bounded number of scans
        int exp = 0;
        int scans = 0;
        while (LecithinItemMergeScan.budgeted(exp)) {
            exp = LecithinItemMergeScan.nextBackoffExp(exp, false, false, false);
            scans++;
        }
        assertEquals(LecithinItemMergeScan.MAX_EXP, scans);
    }
}
