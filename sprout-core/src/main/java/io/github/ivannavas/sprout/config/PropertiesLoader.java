package io.github.ivannavas.sprout.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

public final class PropertiesLoader {

    private static final String BASE_FILE = "sprout.properties";
    private static final String PROFILE_ACTIVE_KEY = "sprout.profiles.active";

    private PropertiesLoader() {
    }

    public static Map<String, String> load(ClassLoader classLoader, Logger logger) {
        Map<String, String> properties = new HashMap<>();

        loadFile(classLoader, BASE_FILE, properties, logger);

        String activeProfiles = properties.get(PROFILE_ACTIVE_KEY);
        if (activeProfiles != null && !activeProfiles.isBlank()) {
            for (String profile : activeProfiles.split(",")) {
                profile = profile.trim();
                if (!profile.isEmpty()) {
                    String profileFile = "sprout-" + profile + ".properties";
                    loadFile(classLoader, profileFile, properties, logger);
                }
            }
        }

        return properties;
    }

    private static void loadFile(ClassLoader classLoader, String filename,
                                  Map<String, String> target, Logger logger) {
        try (InputStream is = classLoader.getResourceAsStream(filename)) {
            if (is == null) {
                return;
            }
            Properties props = new Properties();
            props.load(is);
            for (String key : props.stringPropertyNames()) {
                target.put(key, props.getProperty(key));
            }
            logger.info("Sprout: loaded configuration from " + filename);
        } catch (IOException e) {
            logger.warning("Sprout: failed to load " + filename + " - " + e.getMessage());
        }
    }
}
