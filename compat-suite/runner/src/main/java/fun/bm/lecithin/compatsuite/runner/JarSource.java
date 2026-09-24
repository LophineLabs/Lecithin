package fun.bm.lecithin.compatsuite.runner;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;

/**
 * Resolves a {@code target.<id>.jar} value to a local file.
 *
 * <ul>
 *   <li>{@code paper:<version>:<build>:<sha256>} - a PaperMC build from the fill API, verified.</li>
 *   <li>{@code github-release:<owner>/<repo>:<tag>:<asset>:<sha256>} - a public release asset, verified.</li>
 *   <li>{@code @<target>} - the same jar as another target (config-only variants).</li>
 *   <li>anything else - a local path, relative to the suite directory.</li>
 * </ul>
 * Downloads are cached in {@code compat-suite/.cache/jars} by sha256.
 */
final class JarSource {

    record Resolved(Path path, String sha256, String source) {
    }

    private final Path baseDir;
    private final Path cache;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build();

    JarSource(final Path baseDir) {
        this.baseDir = baseDir;
        this.cache = baseDir.resolve(".cache/jars");
    }

    Resolved resolve(final String spec, final Map<String, SuiteConfig.Target> targets) throws IOException, InterruptedException {
        if (spec.startsWith("@")) {
            final SuiteConfig.Target other = targets.get(spec.substring(1));
            if (other == null || other.jar().startsWith("@")) {
                throw new IOException("jar alias " + spec + " must name a target with a real jar source");
            }
            return resolve(other.jar(), targets);
        }
        if (spec.startsWith("paper:")) {
            final String[] p = spec.split(":");
            if (p.length != 4) {
                throw new IOException("expected paper:<version>:<build>:<sha256>, got " + spec);
            }
            final JsonObject build = JsonParser.parseString(get(
                    "https://fill.papermc.io/v3/projects/paper/versions/" + p[1] + "/builds/" + p[2])).getAsJsonObject();
            final JsonObject dl = build.getAsJsonObject("downloads").getAsJsonObject("server:default");
            final String published = dl.getAsJsonObject("checksums").get("sha256").getAsString();
            if (!published.equalsIgnoreCase(p[3])) {
                throw new IOException("Paper " + p[1] + " build " + p[2] + " publishes sha256 " + published
                        + " but suite.properties pins " + p[3]);
            }
            return download(dl.get("url").getAsString(), p[3], spec);
        }
        if (spec.startsWith("github-release:")) {
            final String[] p = spec.split(":");
            if (p.length != 5) {
                throw new IOException("expected github-release:<owner>/<repo>:<tag>:<asset>:<sha256>, got " + spec);
            }
            return download("https://github.com/" + p[1] + "/releases/download/" + p[2] + "/" + p[3], p[4], spec);
        }
        final Path local = this.baseDir.resolve(spec).normalize();
        if (!Files.isRegularFile(local)) {
            throw new IOException("jar not found: " + local);
        }
        return new Resolved(local, sha256(local), local.toString());
    }

    private Resolved download(final String url, final String sha, final String spec) throws IOException, InterruptedException {
        final Path target = this.cache.resolve(sha.toLowerCase() + ".jar");
        if (Files.isRegularFile(target) && sha256(target).equalsIgnoreCase(sha)) {
            return new Resolved(target, sha.toLowerCase(), spec);
        }
        Files.createDirectories(this.cache);
        final Path tmp = Files.createTempFile(this.cache, "download", ".part");
        final HttpResponse<InputStream> r = this.http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (r.statusCode() != 200) {
            throw new IOException("GET " + url + " -> HTTP " + r.statusCode());
        }
        try (InputStream in = r.body()) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        final String actual = sha256(tmp);
        if (!actual.equalsIgnoreCase(sha)) {
            Files.deleteIfExists(tmp);
            throw new IOException(url + " has sha256 " + actual + ", expected " + sha);
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        return new Resolved(target, actual, spec);
    }

    private String get(final String url) throws IOException, InterruptedException {
        final HttpResponse<String> r = this.http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) {
            throw new IOException("GET " + url + " -> HTTP " + r.statusCode());
        }
        return r.body();
    }

    static String sha256(final Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            final byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
