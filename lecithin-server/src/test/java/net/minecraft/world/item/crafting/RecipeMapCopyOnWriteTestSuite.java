package net.minecraft.world.item.crafting;

import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Region threads iterate recipes while a plugin adds them from the global region: 07:48:53 on
 * 2026-11-01 a Crafter's getRecipeFor hit a ConcurrentModificationException and failed its region.
 */
@Timeout(60)
class RecipeMapCopyOnWriteTestSuite {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static RecipeHolder<?> holder(String name) {
        ResourceKey<Recipe<?>> key = ResourceKey.create(Registries.RECIPE, Identifier.fromNamespaceAndPath("test", name));
        return new RecipeHolder<>(key, new ShapelessRecipe(new Recipe.CommonInfo(true),
                new CraftingRecipe.CraftingBookInfo(CraftingBookCategory.MISC, ""),
                new ItemStackTemplate(Items.STICK), List.of(Ingredient.of(Items.DEAD_BUSH))));
    }

    @Test
    void aReaderKeepsIteratingAcrossAnAdd() {
        RecipeMap map = RecipeMap.createEmpty();
        map.addRecipe(holder("a"));
        map.addRecipe(holder("b"));

        var iterator = map.byType(RecipeType.CRAFTING).iterator();
        iterator.next();
        map.addRecipe(holder("c"));

        assertDoesNotThrow(iterator::next, "this next() is the crash: LinkedHashMultimap's iterator saw the change");
        assertEquals(3, map.byType(RecipeType.CRAFTING).size(), "a reader that starts later sees the new recipe");
    }

    @Test
    void aReaderKeepsIteratingAcrossARemove() {
        RecipeMap map = RecipeMap.createEmpty();
        RecipeHolder<?> first = holder("a");
        map.addRecipe(first);
        map.addRecipe(holder("b"));

        var iterator = map.byType(RecipeType.CRAFTING).iterator();
        iterator.next();
        assertTrue(map.removeRecipe(first.id()));

        assertDoesNotThrow(iterator::next);
        assertEquals(1, map.byType(RecipeType.CRAFTING).size());
        assertNull(map.byKey(first.id()));
    }

    @Test
    void readersSurviveARealWriter() throws Exception {
        RecipeMap map = RecipeMap.createEmpty();
        for (int index = 0; index < 256; index++) map.addRecipe(holder("base_" + index));

        var stop = new AtomicBoolean();
        var failure = new AtomicReference<Throwable>();
        List<Thread> readers = new ArrayList<>();
        for (int reader = 0; reader < 4; reader++) {
            Thread thread = new Thread(() -> {
                try {
                    while (!stop.get()) map.byType(RecipeType.CRAFTING).stream().reduce((first, second) -> second);
                } catch (Throwable problem) {
                    failure.set(problem);
                }
            });
            thread.start();
            readers.add(thread);
        }
        for (int index = 0; index < 2_000 && failure.get() == null; index++) {
            RecipeHolder<?> added = holder("storm_" + index);
            map.addRecipe(added);
            map.removeRecipe(added.id());
        }
        stop.set(true);
        for (Thread thread : readers) thread.join();

        assertNull(failure.get(), () -> "reader failed: " + failure.get());
        assertEquals(256, map.byType(RecipeType.CRAFTING).size());
    }

    @Test
    void runtimeRecipesKeepTheirInsertionOrder() {
        RecipeMap map = RecipeMap.createEmpty();
        map.addRecipe(holder("a"));
        map.addRecipe(holder("b"));
        map.addRecipe(holder("c"));

        var names = map.byType(RecipeType.CRAFTING).stream().map(recipe -> recipe.id().identifier().getPath()).toList();

        assertEquals(List.of("a", "b", "c"), names, "getRecipeFor prioritises runtime recipes by insertion order");
    }

    @Test
    void aDuplicateStillFails() {
        RecipeMap map = RecipeMap.createEmpty();
        map.addRecipe(holder("a"));

        assertThrows(IllegalStateException.class, () -> map.addRecipe(holder("a")));
        assertEquals(1, map.byType(RecipeType.CRAFTING).size());
    }

    @Test
    void removingAnUnknownRecipeChangesNothing() {
        RecipeMap map = RecipeMap.createEmpty();
        var before = map.byType;

        assertFalse(map.removeRecipe(holder("missing").id()));
        assertSame(before, map.byType);
    }

    @Test
    void theSharedEmptyMapStaysEmpty() {
        assertThrows(UnsupportedOperationException.class, () -> RecipeMap.EMPTY.addRecipe(holder("a")));
        assertTrue(RecipeMap.EMPTY.values().isEmpty());
    }
}
