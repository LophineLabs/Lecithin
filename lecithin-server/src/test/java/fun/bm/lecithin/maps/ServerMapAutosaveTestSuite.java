package fun.bm.lecithin.maps;

import fun.bm.lophine.utils.SaveAllUtil;
import io.papermc.paper.threadedregions.RegionizedServer;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapIndex;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Map data (MapItemSavedData) and the map id counter (MapIndex, data/minecraft/maps/last_id.dat) live in the server-level
 * SavedDataStorage (MinecraftServer#getDataStorage). The Folia map autosave saved each world's own storage instead, which never
 * holds maps, so after an unclean shutdown map files were missing and map ids were handed out again.
 * <p>
 * The suite drives RegionizedServer's map autosave entry reflectively so it runs against both the old per-world signature and the
 * fixed global one; the old code must fail these assertions.
 */
class ServerMapAutosaveTestSuite {

    @TempDir
    Path serverDir;
    @TempDir
    Path worldDir;

    private MinecraftServer server;
    private SavedDataStorage serverStorage;
    private SavedDataStorage worldStorage;
    private ServerLevel world;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void setUp() throws Exception {
        this.serverStorage = spy(new SavedDataStorage(this.serverDir, DataFixers.getDataFixer(), RegistryAccess.EMPTY));
        this.worldStorage = spy(new SavedDataStorage(this.worldDir, DataFixers.getDataFixer(), RegistryAccess.EMPTY));

        this.server = mock(MinecraftServer.class);
        doReturn(this.serverStorage).when(this.server).getDataStorage();
        doReturn(List.of()).when(this.server).getAllLevels();
        setStaticServer(this.server);

        this.world = mock(ServerLevel.class);
        doReturn(this.server).when(this.world).getServer();
        doReturn(this.worldStorage).when(this.world).getDataStorage();
        doCallRealMethod().when(this.world).getFreeMapId();

        // drain any save request left over from a previous test in this JVM; nothing is dirty yet, so nothing is written
        this.server.autosavePeriod = 0;
        runMapAutosave();
        clearInvocations(this.serverStorage, this.worldStorage);
    }

    @AfterEach
    void tearDown() throws Exception {
        Field current = SaveAllUtil.class.getDeclaredField("currentSaveAll");
        current.setAccessible(true);
        current.set(null, null);
        setStaticServer(null);
    }

    @Test
    void periodicAutosaveWritesServerStorageMapsOncePerPeriod() throws Exception {
        MapId id = this.serverStorage.computeIfAbsent(MapIndex.TYPE).getNextMapId();
        this.serverStorage.set(MapItemSavedData.type(id), freshMap());

        this.server.autosavePeriod = 1; // one tick: the period (50 ms) has elapsed by now
        Thread.sleep(120L);
        runMapAutosave();
        runMapAutosave(); // same period: must not save again
        awaitWrites();

        assertTrue(Files.isRegularFile(mapFile(this.serverDir, id)), "periodic autosave did not write " + mapFile(this.serverDir, id));
        assertEquals(id.id(), readLastId(this.serverDir), "periodic autosave did not write last_id");
        verify(this.serverStorage, times(1)).saveMaps();
        verify(this.worldStorage, never()).saveMaps();
    }

    @Test
    void allocatingAMapIdPersistsLastIdWithoutWaitingForThePeriod() throws Exception {
        this.server.autosavePeriod = 0; // the periodic gate is closed: only an allocation-driven save can write last_id
        MapId id = this.world.getFreeMapId();

        runMapAutosave(); // the next global tick
        awaitWrites();

        assertEquals(id.id(), readLastId(this.serverDir), "allocating map id " + id.id() + " did not persist last_id");
    }

    @Test
    void saveAllWritesServerStorageMaps() throws Exception {
        MapId id = this.serverStorage.computeIfAbsent(MapIndex.TYPE).getNextMapId();
        this.serverStorage.set(MapItemSavedData.type(id), freshMap());
        this.server.autosavePeriod = 0;

        CommandSourceStack source = mock(CommandSourceStack.class);
        doReturn(this.server).when(source).getServer();
        SaveAllUtil.preSaveAll(source, false);

        runMapAutosave(); // the next global tick
        awaitWrites();

        assertTrue(Files.isRegularFile(mapFile(this.serverDir, id)), "save-all did not write " + mapFile(this.serverDir, id));
        assertEquals(id.id(), readLastId(this.serverDir), "save-all did not write last_id");
    }

    private void runMapAutosave() throws Exception {
        Method method = Arrays.stream(RegionizedServer.class.getDeclaredMethods())
            .filter(m -> m.getName().equals("autoSaveMaps"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("RegionizedServer#autoSaveMaps not found"));
        method.setAccessible(true);
        if (method.getParameterCount() == 1) {
            method.invoke(RegionizedServer.getInstance(), this.world); // pre-fix: called once per world
        } else {
            method.invoke(RegionizedServer.getInstance());
        }
    }

    private void awaitWrites() throws Exception {
        this.serverStorage.carpetScheduledWrites().get(10L, TimeUnit.SECONDS);
        this.worldStorage.carpetScheduledWrites().get(10L, TimeUnit.SECONDS);
    }

    private static MapItemSavedData freshMap() {
        return MapItemSavedData.createFresh(0.0, 0.0, (byte) 0, false, false, Level.OVERWORLD);
    }

    private static Path mapFile(Path dataDir, MapId id) {
        return dataDir.resolve("minecraft").resolve(id.key() + ".dat");
    }

    private static int readLastId(Path dataDir) throws Exception {
        Path file = dataDir.resolve("minecraft").resolve("maps").resolve("last_id.dat");
        assertTrue(Files.isRegularFile(file), "last_id.dat not written to server storage: " + file);
        CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        return tag.getCompoundOrEmpty("data").getIntOr("map", Integer.MIN_VALUE);
    }

    private static void setStaticServer(MinecraftServer server) throws Exception {
        Field field = MinecraftServer.class.getDeclaredField("SERVER");
        field.setAccessible(true);
        field.set(null, server);
    }
}
