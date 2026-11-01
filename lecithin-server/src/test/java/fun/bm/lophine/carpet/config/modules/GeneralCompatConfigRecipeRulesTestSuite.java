package fun.bm.lophine.carpet.config.modules;

import io.papermc.paper.configuration.GlobalConfiguration;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.crafting.RecipeManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * A boot used to reload all resources two seconds after Done because the applied rule values start
 * null, which wiped the recipes plugins had just added and made them add theirs again while regions tick.
 */
@Timeout(30)
class GeneralCompatConfigRecipeRulesTestSuite {
    private MinecraftServer server;
    private RecipeManager recipes;

    @BeforeAll
    static void bootstrap() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        GlobalConfiguration config = new GlobalConfiguration();
        config.misc = config.new Misc();
        Field instance = GlobalConfiguration.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, config);
    }

    @BeforeEach
    void setUp() throws Exception {
        Field applied = GeneralCompatConfig.class.getDeclaredField("appliedRecipeRules");
        applied.setAccessible(true);
        applied.set(null, null);
        server = mock(MinecraftServer.class);
        recipes = mock(RecipeManager.class);
        when(server.isRunning()).thenReturn(true);
        when(server.getRecipeManager()).thenReturn(recipes);
    }

    @Test
    void theFirstLoadDoesNotReloadResources() {
        GeneralCompatConfig.applyRecipeRules(server);

        verify(server, never()).reloadResources(any());
        verify(server, never()).reloadResources(any(), any());
        verify(recipes).carpetReloadCompatRecipes();
    }

    @Test
    void unchangedRulesDoNothingOnLaterLoads() {
        GeneralCompatConfig.applyRecipeRules(server);
        clearInvocations(server, recipes);

        GeneralCompatConfig.applyRecipeRules(server);

        verifyNoInteractions(server, recipes);
    }

    @Test
    void aLoadBeforeTheServerExistsStillCountsAsTheFirst() {
        GeneralCompatConfig.applyRecipeRules(null);

        GeneralCompatConfig.applyRecipeRules(server);

        verify(server, never()).reloadResources(any());
    }
}
