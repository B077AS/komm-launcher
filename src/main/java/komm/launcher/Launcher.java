package komm.launcher;

import com.sun.jna.Platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Entry point for the Komm launcher.
 *
 * <p>Mirrors the komm client's {@code Launcher}: it resolves the same per-OS app
 * data directory ({@code %APPDATA%/Komm} on Windows), sets the logging system
 * properties logback.xml reads, then hands control to the JavaFX application.
 *
 * <p>The launcher owns the installed client jar under {@link #getBinDirectory()}.
 * On startup it checks the hub for a newer version, downloads/replaces the jar if
 * needed, and finally launches it.
 */
public class Launcher {

    private static final String APP_NAME = "Komm";

    /** Same root directory the komm client uses, so config/logs/data are shared. */
    public static Path getAppDataDirectory() {
        String userHome = System.getProperty("user.home");

        if (Platform.isWindows()) {
            String appData = System.getenv("APPDATA");
            if (appData != null) return Paths.get(appData, APP_NAME);
            return Paths.get(userHome, "AppData", "Roaming", APP_NAME);
        } else {
            return Paths.get(userHome, ".config", APP_NAME);
        }
    }

    /** Where the launcher stores the installed client jar. */
    public static Path getBinDirectory() {
        return getAppDataDirectory().resolve("bin");
    }

    public static Path getClientJar() {
        return getBinDirectory().resolve("komm.jar");
    }

    public static Path getLogsDirectory() {
        return getAppDataDirectory().resolve("logs");
    }

    public static void main(String[] args) {
        // macOS is not a supported target (no packaging, no protocol registration) — bail out immediately.
        if (Platform.isMac()) {
            System.exit(1);
        }
        initializeSystemProperties();
        initializeDirectories();
        // Off the critical path: the UI shouldn't wait on reg.exe.
        Thread.ofVirtual().start(ProtocolRegistrar::registerIfPossible);
        LauncherApp.appStart(args);
    }

    private static void initializeSystemProperties() {
        System.setProperty("slf4j.provider", "ch.qos.logback.classic.spi.LogbackServiceProvider");
        System.setProperty("komm.logDir", getLogsDirectory().toAbsolutePath().toString());
    }

    private static void initializeDirectories() {
        createDirectory(getAppDataDirectory());
        createDirectory(getBinDirectory());
        createDirectory(getLogsDirectory());
    }

    private static void createDirectory(Path path) {
        try {
            if (!Files.exists(path)) {
                Files.createDirectories(path);
            }
        } catch (IOException ignored) {
        }
    }
}
