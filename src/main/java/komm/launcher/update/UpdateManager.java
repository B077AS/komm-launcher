package komm.launcher.update;

import com.google.gson.Gson;
import com.sun.jna.Platform;
import komm.launcher.Launcher;
import komm.launcher.LauncherConfig;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Drives the whole launcher sequence on a background thread:
 *
 * <ol>
 *   <li>ask the hub for the latest client version ({@code GET /api/client/latest}),</li>
 *   <li>compare it to the {@code client.version} embedded in the installed jar's
 *       {@code app.properties} — the same self-describing entry the hub reads,</li>
 *   <li>download + replace the jar if it's missing or stale
 *       ({@code GET /api/client/download}),</li>
 *   <li>launch the client jar and exit.</li>
 * </ol>
 *
 * <p>Every update is mandatory: once the hub has announced a newer version, a
 * failed download shows an error instead of falling back to the stale jar. Only
 * when the hub itself is unreachable does an already-installed jar launch as-is.
 *
 * <p>All progress is reported through {@link Listener}; callbacks fire on the
 * background thread, so the UI marshals them onto the FX thread itself.
 *
 * <p>A scripted {@link #runDemo()} reproduces the same visuals without a hub or a
 * real jar, so the look &amp; feel can be reviewed in isolation.
 */
@Slf4j
public class UpdateManager {

    /** Callbacks for the UI. All fire on the background worker thread. */
    public interface Listener {
        void onPhase(Phase phase);

        /** Determinate download progress, 0..1. */
        void onProgress(double fraction);

        /** Terminal failure; no client was launched. */
        void onError(String message);

        /** The client jar was launched; the launcher should now close. */
        void onLaunched();
    }

    private final LauncherConfig config = LauncherConfig.getInstance();
    private final Gson gson = new Gson();
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final Listener listener;
    private final String[] clientArgs;

    public UpdateManager(Listener listener, String[] clientArgs) {
        this.listener = listener;
        this.clientArgs = clientArgs == null ? new String[0] : clientArgs;
    }

    // ── Real flow ─────────────────────────────────────────────────────────────

    public void run() {
        BundledClientSeeder.seedIfMissing();
        deleteLegacyVersionFile();

        Path jar = Launcher.getClientJar();
        // Flipped once the hub has announced a version we don't have; from that
        // point on the stale jar is never launched as a fallback.
        boolean updateRequired = false;

        try {
            listener.onPhase(Phase.CHECKING);
            pause(450); // let the phrase land, Discord-style

            ClientVersionResponse latest = fetchLatest();
            String localVersion = readVersionFromJar(jar);
            log.info("Local version={}, latest version={}", localVersion, latest.getVersion());

            boolean upToDate = localVersion != null
                    && latest.getVersion() != null
                    && latest.getVersion().equals(localVersion);

            if (upToDate) {
                listener.onPhase(Phase.UP_TO_DATE);
                pause(550);
            } else {
                updateRequired = true;
                downloadAndInstall(latest);
            }

            listener.onPhase(Phase.STARTING);
            pause(350);
            launchClient(jar);
            listener.onLaunched();

        } catch (Exception e) {
            log.warn("Update flow failed: {}", e.toString());
            // Hub unreachable (offline, hub down): run what we already have. A
            // known-stale jar is never launched — the error stays on screen.
            if (!updateRequired && Files.exists(jar)) {
                try {
                    listener.onPhase(Phase.STARTING);
                    pause(300);
                    launchClient(jar);
                    listener.onLaunched();
                    return;
                } catch (Exception launchEx) {
                    log.error("Failed to launch existing jar: {}", launchEx.toString());
                }
            }
            listener.onError(friendlyError(e));
        }
    }

    private ClientVersionResponse fetchLatest() throws Exception {
        String url = config.getApiUrl().replaceAll("/+$", "") + "/api/client/latest";
        log.debug("GET {}", url);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new IllegalStateException("Hub returned HTTP " + res.statusCode());
        }
        ClientVersionResponse parsed = gson.fromJson(res.body(), ClientVersionResponse.class);
        if (parsed == null || parsed.getDownloadUrl() == null) {
            throw new IllegalStateException("Malformed version response");
        }
        return parsed;
    }

    private void downloadAndInstall(ClientVersionResponse latest) throws Exception {
        listener.onPhase(Phase.DOWNLOADING);
        listener.onProgress(0);

        Path bin = Launcher.getBinDirectory();
        Files.createDirectories(bin);
        Path tmp = bin.resolve("komm.jar.download");

        HttpRequest req = HttpRequest.newBuilder(URI.create(latest.getDownloadUrl())).GET().build();
        HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() != 200) {
            throw new IllegalStateException("Download failed: HTTP " + res.statusCode());
        }

        long contentLength = res.headers().firstValueAsLong("Content-Length").orElse(-1);
        MessageDigest digest = newSha256();
        try (InputStream in = res.body();
             var out = Files.newOutputStream(tmp)) {
            byte[] buffer = new byte[1 << 16];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (digest != null) digest.update(buffer, 0, read);
                total += read;
                if (contentLength > 0) {
                    listener.onProgress((double) total / contentLength);
                }
            }
        }
        listener.onProgress(1.0);

        listener.onPhase(Phase.INSTALLING);
        pause(400);
        // The jar must be able to state its own version, or the next startup's
        // comparison would break — a truncated/corrupt download fails here
        // instead of replacing a working install.
        String downloadedVersion = readVersionFromJar(tmp);
        if (downloadedVersion == null) {
            Files.deleteIfExists(tmp);
            throw new IllegalStateException("Downloaded jar is corrupt (no readable version)");
        }
        // Cryptographic check against the hash the hub computed for this same
        // download — skipped if the hub didn't send one (e.g. not yet upgraded).
        String expectedSha256 = latest.getSha256();
        if (expectedSha256 != null && !expectedSha256.isBlank() && digest != null) {
            String actualSha256 = HexFormat.of().formatHex(digest.digest());
            if (!expectedSha256.trim().equalsIgnoreCase(actualSha256)) {
                Files.deleteIfExists(tmp);
                throw new IllegalStateException("Downloaded jar failed integrity check");
            }
        }
        Path jar = Launcher.getClientJar();
        Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
        deleteExtractedNatives(bin);
        log.info("Installed client jar version {} -> {}", downloadedVersion, jar);
    }

    /**
     * The client's JNativeHook extracts its native library next to the jar,
     * named after the jar's Implementation-Version — so every update strands
     * the previous version's copy. Best-effort sweep; the client re-extracts
     * on next start, and a file still loaded by a running client just stays.
     */
    private static void deleteExtractedNatives(Path bin) {
        try (var files = Files.list(bin)) {
            files.filter(f -> f.getFileName().toString().startsWith("JNativeHook-"))
                    .forEach(f -> {
                        try {
                            Files.deleteIfExists(f);
                        } catch (Exception ignored) {
                        }
                    });
        } catch (Exception e) {
            log.debug("Could not clean extracted natives: {}", e.toString());
        }
    }

    private void launchClient(Path jar) throws Exception {
        if (!Files.exists(jar)) {
            throw new IllegalStateException("Client jar not found at " + jar);
        }
        String javaBin = resolveJavaBinary();
        List<String> command = new ArrayList<>();
        command.add(javaBin);
        // Lets the client tell whether the launcher that started it needs updating —
        // absent entirely on an old/unpatched launcher, which the client treats as stale.
        String launcherVersion = config.getLauncherVersion();
        if (launcherVersion != null && !launcherVersion.isBlank()) {
            command.add("-Dlauncher.version=" + launcherVersion);
        }
        command.add("-jar");
        command.add(jar.toAbsolutePath().toString());
        // Forward any deep-link args (e.g. komm://invite/...) the launcher was opened with.
        for (String a : clientArgs) command.add(a);

        log.info("Launching client: {}", String.join(" ", command));
        Process process = new ProcessBuilder(command)
                .directory(jar.getParent().toFile())
                .inheritIO()
                .start();
        guardAppImageMount(process);
    }

    /** True once the client was started from inside an AppImage; the launcher JVM
     *  must then outlive the window (see {@link #guardAppImageMount}). */
    private static volatile boolean appImageMountGuarded = false;

    public static boolean isAppImageMountGuarded() {
        return appImageMountGuarded;
    }

    /**
     * Running from an AppImage, this process's exit unmounts the FUSE image — and
     * with it the private runtime the client was just launched with. So linger
     * invisibly (the window still closes via {@code Platform.exit()}) on a
     * non-daemon thread until the client exits, then let the JVM die.
     */
    private static void guardAppImageMount(Process client) {
        if (System.getenv("APPIMAGE") == null) return;
        appImageMountGuarded = true;
        Thread keeper = new Thread(() -> {
            try {
                client.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            System.exit(0);
        }, "appimage-mount-keeper");
        keeper.setDaemon(false);
        keeper.start();
        log.info("AppImage detected; keeping the mount alive until the client exits");
    }

    /**
     * Prefer javaw.exe on Windows so the client has no stray console window. Packaged
     * builds carry a rebranded copy of javaw.exe (runtime/bin/Komm.exe, see the
     * installer profile's rebrand-client-exe step) whose version resource reads "Komm"
     * instead of stock javaw.exe's "Java Platform SE binary" — used when present so the
     * client shows up as "Komm" in Task Manager; dev runs fall back to plain javaw.exe.
     */
    private String resolveJavaBinary() {
        Path javaHome = Path.of(System.getProperty("java.home"));
        boolean win = Platform.isWindows();
        if (win) {
            Path branded = javaHome.resolve("bin").resolve("Komm.exe");
            if (Files.exists(branded)) return branded.toAbsolutePath().toString();
        }
        Path candidate = javaHome.resolve("bin").resolve(win ? "javaw.exe" : "java");
        if (Files.exists(candidate)) return candidate.toAbsolutePath().toString();
        return win ? "javaw" : "java";
    }

    /** SHA-256 instance for hashing a download in-flight, or null if unavailable
     *  (every JDK provides it, but the integrity check is best-effort either way). */
    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            log.warn("SHA-256 unavailable, skipping download integrity check: {}", e.toString());
            return null;
        }
    }

    /** Jar entry + property carrying the client's version — the same
     *  self-describing {@code app.properties} the hub reads on its side. */
    private static final String PROPERTIES_ENTRY = "app.properties";
    private static final String VERSION_PROPERTY = "client.version";

    /** Version embedded in the given jar, or null if the jar is missing or unreadable. */
    private static String readVersionFromJar(Path jar) {
        if (!Files.exists(jar)) return null;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(PROPERTIES_ENTRY);
            if (entry == null) return null;
            Properties props = new Properties();
            try (InputStream in = zip.getInputStream(entry)) {
                props.load(in);
            }
            String version = props.getProperty(VERSION_PROPERTY);
            return version == null || version.isBlank() ? null : version.trim();
        } catch (Exception e) {
            log.debug("Could not read version from {}: {}", jar, e.toString());
            return null;
        }
    }

    /** Older launcher versions tracked the installed version in a version.txt
     *  next to the jar; the jar is self-describing now, so clean the file up. */
    private static void deleteLegacyVersionFile() {
        try {
            Files.deleteIfExists(Launcher.getBinDirectory().resolve("version.txt"));
        } catch (Exception ignored) {
        }
    }

    private static String friendlyError(Exception e) {
        if (e instanceof java.net.ConnectException
                || e instanceof java.net.http.HttpConnectTimeoutException
                || e instanceof java.net.UnknownHostException) {
            return "Can't reach the update server";
        }
        return "Update failed — please try again";
    }

    // ── Demo flow ─────────────────────────────────────────────────────────────

    /** Scripted sequence so the visuals can be reviewed without a hub or a real jar. */
    public void runDemo() {
        try {
            listener.onPhase(Phase.CHECKING);
            pause(1100);

            listener.onPhase(Phase.DOWNLOADING);
            for (int i = 0; i <= 100; i += 2) {
                listener.onProgress(i / 100.0);
                pause(45);
            }

            listener.onPhase(Phase.INSTALLING);
            pause(1200);

            listener.onPhase(Phase.STARTING);
            pause(1000);

            listener.onPhase(Phase.DONE);
        } catch (Exception e) {
            listener.onError("Demo interrupted");
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
