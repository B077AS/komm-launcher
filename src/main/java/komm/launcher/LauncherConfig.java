package komm.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import lombok.extern.slf4j.Slf4j;

/**
 * Reads the bundled {@code app.properties}, mirroring the komm client's
 * {@code AppConfig}. The only value the launcher needs is {@code api.url} — the
 * hub base it asks for client updates.
 */
@Slf4j
public class LauncherConfig {

    private static LauncherConfig instance;
    private final Properties properties = new Properties();

    private LauncherConfig() {
        loadProperties();
    }

    public static LauncherConfig getInstance() {
        if (instance == null) {
            synchronized (LauncherConfig.class) {
                if (instance == null) {
                    instance = new LauncherConfig();
                }
            }
        }
        return instance;
    }

    private void loadProperties() {
        try (InputStream input = LauncherConfig.class.getClassLoader().getResourceAsStream("app.properties")) {
            if (input != null) {
                properties.load(input);
                log.debug("Loaded launcher properties from classpath: app.properties");
            } else {
                log.error("app.properties not found in classpath");
            }
        } catch (IOException e) {
            log.error("Error loading properties: {}", e.getMessage());
        }
    }

    public String getApiUrl() {
        // A -Dapi.url=... override wins, so testers can point at any hub without rebuilding.
        String override = System.getProperty("api.url");
        if (override != null && !override.isBlank()) return override.trim();
        return properties.getProperty("api.url");
    }
}
