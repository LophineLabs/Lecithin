package fun.bm.lecithin.compatsuite.runner;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * A headless offline-mode player: enough of the Java Edition protocol to log in, finish configuration,
 * stay connected (keep-alive, teleport confirm, chunk batch acks) and send chat and commands.
 *
 * <p>It exists so a real client connection - real async chat thread, real command packet on the
 * player's region - drives the player-scoped scenarios, without depending on a node toolchain whose
 * 26.2 support lives in unmerged branches. It never parses world data; everything the suite judges is
 * observed on the server by the fixtures.
 */
final class MiniClient implements AutoCloseable {

    private enum State { LOGIN, CONFIGURATION, PLAY, CLOSED }

    private final String host;
    private final int port;
    final String name;
    private final ProtocolProfile p;
    private final Consumer<String> log;
    private final CountDownLatch spawned = new CountDownLatch(1);

    private Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private volatile State state = State.LOGIN;
    private volatile int compressionThreshold = -1;
    private volatile String disconnectReason;
    private volatile double x;
    private volatile double y;
    private volatile double z;

    MiniClient(final String host, final int port, final String name, final ProtocolProfile profile,
               final Consumer<String> log) {
        this.host = host;
        this.port = port;
        this.name = name;
        this.p = profile;
        this.log = log;
    }

    static UUID offlineUuid(final String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    /** Connect and block until the server has placed the player in the world. */
    void connect(final Duration timeout) throws IOException, InterruptedException {
        this.socket = new Socket();
        this.socket.connect(new InetSocketAddress(this.host, this.port), 5000);
        this.socket.setTcpNoDelay(true);
        this.in = new DataInputStream(new BufferedInputStream(this.socket.getInputStream(), 1 << 16));
        this.out = new BufferedOutputStream(this.socket.getOutputStream(), 1 << 14);

        send(0x00, b -> {
            writeVarInt(b, this.p.protocolVersion());
            writeString(b, this.host);
            b.writeShort(this.port);
            writeVarInt(b, 2);
        });
        final UUID uuid = offlineUuid(this.name);
        send(this.p.id("login.sb.login_start"), b -> {
            writeString(b, this.name);
            b.writeLong(uuid.getMostSignificantBits());
            b.writeLong(uuid.getLeastSignificantBits());
        });
        final Thread reader = new Thread(this::readLoop, "compat-bot-" + this.name);
        reader.setDaemon(true);
        reader.start();
        if (!this.spawned.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            close();
            throw new IOException("bot " + this.name + " was not placed in the world within " + timeout
                    + (this.disconnectReason != null ? ": " + this.disconnectReason : " (state " + this.state + ")"));
        }
        if (this.state != State.PLAY) {
            throw new IOException("bot " + this.name + " disconnected: " + this.disconnectReason);
        }
    }

    boolean connected() {
        return this.state == State.PLAY;
    }

    String disconnectReason() {
        return this.disconnectReason;
    }

    double x() {
        return this.x;
    }

    double z() {
        return this.z;
    }

    /** Wait until the server has moved this player near (x, z). */
    boolean awaitPositionNear(final double tx, final double tz, final Duration timeout) throws InterruptedException {
        final long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Math.abs(this.x - tx) < 2 && Math.abs(this.z - tz) < 2) {
                return true;
            }
            Thread.sleep(100);
        }
        return false;
    }

    /** An unsigned chat message; the server must have enforce-secure-profile=false. */
    void chat(final String message) throws IOException {
        send(this.p.id("play.sb.chat_message"), b -> {
            writeString(b, message);
            b.writeLong(System.currentTimeMillis());
            b.writeLong(ThreadLocalRandom.current().nextLong());
            b.writeBoolean(false);      // no signature
            writeVarInt(b, 0);          // last-seen offset
            b.write(new byte[3]);       // acknowledged bitset (20 bits)
            b.writeByte(0);             // checksum 0 = not checked
        });
    }

    /** An unsigned command, without the leading slash. */
    void command(final String command) throws IOException {
        send(this.p.id("play.sb.chat_command"), b -> writeString(b, command));
    }

    @Override
    public void close() {
        this.state = State.CLOSED;
        try {
            if (this.socket != null) {
                this.socket.close();
            }
        } catch (final IOException ignored) {
        }
        this.spawned.countDown();
    }

    // ------------------------------------------------------------------ reading

    private void readLoop() {
        try {
            while (this.state != State.CLOSED) {
                final int length = readVarInt(this.in);
                final byte[] frame = this.in.readNBytes(length);
                if (frame.length != length) {
                    throw new EOFException("short frame");
                }
                DataInputStream packet = new DataInputStream(new ByteArrayInputStream(frame));
                if (this.compressionThreshold >= 0) {
                    final int dataLength = readVarInt(packet);
                    if (dataLength != 0) {
                        packet = new DataInputStream(new ByteArrayInputStream(inflate(packet.readAllBytes(), dataLength)));
                    }
                }
                handle(readVarInt(packet), packet);
            }
        } catch (final Exception e) {
            if (this.state != State.CLOSED) {
                if (this.disconnectReason == null) {
                    this.disconnectReason = "connection lost: " + e;
                }
                this.log.accept("bot " + this.name + " connection ended: " + this.disconnectReason);
            }
        } finally {
            this.state = State.CLOSED;
            this.spawned.countDown();
        }
    }

    private void handle(final int id, final DataInputStream b) throws IOException {
        switch (this.state) {
            case LOGIN -> handleLogin(id, b);
            case CONFIGURATION -> handleConfiguration(id, b);
            case PLAY -> handlePlay(id, b);
            case CLOSED -> {
            }
        }
    }

    private void handleLogin(final int id, final DataInputStream b) throws IOException {
        if (id == this.p.id("login.cb.compress")) {
            this.compressionThreshold = readVarInt(b);
        } else if (id == this.p.id("login.cb.success")) {
            send(this.p.id("login.sb.login_ack"), x -> {
            });
            this.state = State.CONFIGURATION;
        } else if (id == this.p.id("login.cb.disconnect")) {
            this.disconnectReason = readString(b);
            close();
        } else if (id == this.p.id("login.cb.plugin_request")) {
            final int messageId = readVarInt(b);
            send(this.p.id("login.sb.plugin_response"), x -> {
                writeVarInt(x, messageId);
                x.writeBoolean(false);
            });
        } else if (id == this.p.id("login.cb.cookie_request")) {
            final String key = readString(b);
            send(this.p.id("login.sb.cookie_response"), x -> {
                writeString(x, key);
                x.writeBoolean(false);
            });
        } else if (id == this.p.id("login.cb.encryption")) {
            this.disconnectReason = "server requested encryption; the suite needs online-mode=false";
            close();
        }
    }

    private void handleConfiguration(final int id, final DataInputStream b) throws IOException {
        if (id == this.p.id("config.cb.known_packs")) {
            final int count = readVarInt(b);
            final List<String[]> packs = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                packs.add(new String[]{readString(b), readString(b), readString(b)});
            }
            send(this.p.id("config.sb.known_packs"), x -> {
                writeVarInt(x, packs.size());
                for (final String[] pack : packs) {
                    for (final String s : pack) {
                        writeString(x, s);
                    }
                }
            });
        } else if (id == this.p.id("config.cb.keep_alive")) {
            final long value = b.readLong();
            send(this.p.id("config.sb.keep_alive"), x -> x.writeLong(value));
        } else if (id == this.p.id("config.cb.ping")) {
            final int value = b.readInt();
            send(this.p.id("config.sb.pong"), x -> x.writeInt(value));
        } else if (id == this.p.id("config.cb.finish")) {
            send(this.p.id("config.sb.finish"), x -> {
            });
            this.state = State.PLAY;
        } else if (id == this.p.id("config.cb.cookie_request")) {
            final String key = readString(b);
            send(this.p.id("config.sb.cookie_response"), x -> {
                writeString(x, key);
                x.writeBoolean(false);
            });
        } else if (id == this.p.id("config.cb.code_of_conduct")) {
            send(this.p.id("config.sb.accept_code_of_conduct"), x -> {
            });
        } else if (id == this.p.id("config.cb.disconnect")) {
            this.disconnectReason = printable(b.readAllBytes());
            close();
        }
    }

    private void handlePlay(final int id, final DataInputStream b) throws IOException {
        if (id == this.p.id("play.cb.keep_alive")) {
            final long value = b.readLong();
            send(this.p.id("play.sb.keep_alive"), x -> x.writeLong(value));
        } else if (id == this.p.id("play.cb.ping")) {
            final int value = b.readInt();
            send(this.p.id("play.sb.pong"), x -> x.writeInt(value));
        } else if (id == this.p.id("play.cb.position")) {
            final int teleportId = readVarInt(b);
            this.x = b.readDouble();
            this.y = b.readDouble();
            this.z = b.readDouble();
            send(this.p.id("play.sb.teleport_confirm"), x -> writeVarInt(x, teleportId));
            if (this.spawned.getCount() > 0) {
                send(this.p.id("play.sb.player_loaded"), x -> {
                });
                this.spawned.countDown();
            }
        } else if (id == this.p.id("play.cb.chunk_batch_finished")) {
            send(this.p.id("play.sb.chunk_batch_received"), x -> x.writeFloat(25.0f));
        } else if (id == this.p.id("play.cb.start_configuration")) {
            send(this.p.id("play.sb.configuration_ack"), x -> {
            });
            this.state = State.CONFIGURATION;
        } else if (id == this.p.id("play.cb.disconnect")) {
            this.disconnectReason = printable(b.readAllBytes());
            close();
        }
    }

    // ------------------------------------------------------------------ writing

    @FunctionalInterface
    private interface Body {
        void write(DataOutputStream out) throws IOException;
    }

    private synchronized void send(final int id, final Body body) throws IOException {
        if (this.socket == null || this.socket.isClosed()) {
            throw new IOException("bot " + this.name + " is not connected");
        }
        final ByteArrayOutputStream raw = new ByteArrayOutputStream();
        final DataOutputStream d = new DataOutputStream(raw);
        writeVarInt(d, id);
        body.write(d);
        byte[] payload = raw.toByteArray();
        if (this.compressionThreshold >= 0) {
            final ByteArrayOutputStream framed = new ByteArrayOutputStream();
            final DataOutputStream f = new DataOutputStream(framed);
            if (payload.length >= this.compressionThreshold) {
                writeVarInt(f, payload.length);
                f.write(deflate(payload));
            } else {
                writeVarInt(f, 0);
                f.write(payload);
            }
            payload = framed.toByteArray();
        }
        final ByteArrayOutputStream packet = new ByteArrayOutputStream();
        final DataOutputStream pd = new DataOutputStream(packet);
        writeVarInt(pd, payload.length);
        pd.write(payload);
        this.out.write(packet.toByteArray());
        this.out.flush();
    }

    // ------------------------------------------------------------------ codec

    static void writeVarInt(final DataOutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    static int readVarInt(final InputStream in) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            final int b = in.read();
            if (b < 0) {
                throw new EOFException();
            }
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new IOException("VarInt too long");
    }

    static void writeString(final DataOutputStream out, final String s) throws IOException {
        final byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    static String readString(final DataInputStream in) throws IOException {
        final int len = readVarInt(in);
        return new String(in.readNBytes(len), StandardCharsets.UTF_8);
    }

    private static byte[] deflate(final byte[] data) {
        final Deflater deflater = new Deflater();
        deflater.setInput(data);
        deflater.finish();
        final ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        final byte[] buf = new byte[8192];
        while (!deflater.finished()) {
            out.write(buf, 0, deflater.deflate(buf));
        }
        deflater.end();
        return out.toByteArray();
    }

    private static byte[] inflate(final byte[] data, final int length) throws IOException {
        final Inflater inflater = new Inflater();
        inflater.setInput(data);
        final byte[] out = new byte[length];
        try {
            int n = 0;
            while (n < length && !inflater.finished()) {
                final int r = inflater.inflate(out, n, length - n);
                if (r == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                n += r;
            }
            if (n != length) {
                throw new IOException("inflated " + n + " of " + length + " bytes");
            }
            return out;
        } catch (final DataFormatException e) {
            throw new IOException(e);
        } finally {
            inflater.end();
        }
    }

    /** Disconnect reasons are NBT text components; keep the readable characters. */
    private static String printable(final byte[] bytes) {
        final StringBuilder sb = new StringBuilder();
        for (final byte v : bytes) {
            final int c = v & 0xff;
            sb.append(c >= 0x20 && c < 0x7f ? (char) c : ' ');
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }
}
