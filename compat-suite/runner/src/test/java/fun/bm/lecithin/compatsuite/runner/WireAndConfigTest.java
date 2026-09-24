package fun.bm.lecithin.compatsuite.runner;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WireAndConfigTest {

    @Test
    void varIntRoundTripsIncludingNegativeValues() throws Exception {
        for (final int v : new int[]{0, 1, 127, 128, 255, 25565, 2097151, Integer.MAX_VALUE, -1}) {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            MiniClient.writeVarInt(new DataOutputStream(bytes), v);
            assertEquals(v, MiniClient.readVarInt(new ByteArrayInputStream(bytes.toByteArray())));
        }
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        MiniClient.writeVarInt(new DataOutputStream(bytes), 300);
        assertEquals(List.of((byte) 0xAC, (byte) 0x02), List.of(bytes.toByteArray()[0], bytes.toByteArray()[1]));
    }

    @Test
    void stringsAreVarIntLengthPrefixedUtf8() throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        MiniClient.writeString(new DataOutputStream(bytes), "cfx 測試");
        assertEquals("cfx 測試", MiniClient.readString(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))));
    }

    @Test
    void offlineUuidMatchesTheServersDerivation() {
        // Bukkit/Paper offline players: UUID.nameUUIDFromBytes("OfflinePlayer:" + name).
        assertEquals(UUID.nameUUIDFromBytes("OfflinePlayer:CompatA".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                MiniClient.offlineUuid("CompatA"));
    }

    @Test
    void protocolProfileFor262HasEveryIdTheClientUses() throws Exception {
        final ProtocolProfile p = ProtocolProfile.load("26.2");
        assertEquals(776, p.protocolVersion());
        for (final String key : List.of("login.cb.success", "login.sb.login_ack", "config.cb.known_packs",
                "config.sb.finish", "play.cb.position", "play.sb.chat_message", "play.sb.chat_command",
                "play.sb.player_loaded", "play.sb.keep_alive")) {
            p.id(key);
        }
        assertThrows(java.io.IOException.class, () -> ProtocolProfile.load("0.0"));
    }

    @Test
    void suitePropertiesDeclareReferenceFirstAndResolvableAliases() throws Exception {
        final SuiteConfig c = SuiteConfig.load(Path.of("../suite.properties"));
        final List<SuiteConfig.Target> t = c.select(List.of("lecithin-dispatch-off"));
        assertEquals("paper", t.get(0).id());
        assertEquals("lecithin-dispatch-off", t.get(1).id());
        assertEquals("@lecithin", t.get(1).jar());
        assertEquals("[compat-config]\ncaller-context-dispatch = false\n",
                t.get(1).files().get("lecithin_config/lecithin_global_config.toml"));
        assertThrows(IllegalArgumentException.class, () -> c.select(List.of("nope")));
    }
}
