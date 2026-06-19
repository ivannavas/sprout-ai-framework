package io.github.ivannavas.sprout.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for the Sprout Spring Boot integration, bound from the {@code sprout.*} namespace.
 *
 * <p>Note that core scanning options such as {@code sprout.scan.base-packages} are consumed directly
 * by the Sprout container once the Spring {@code Environment} has been bridged into it; they are
 * mirrored here purely so IDEs can offer completion and documentation.
 */
@ConfigurationProperties(prefix = "sprout")
public class SproutProperties {

    /** Whether the Sprout container should be bootstrapped and its beans exposed to Spring. */
    private boolean enabled = true;

    private final Scan scan = new Scan();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Scan getScan() {
        return scan;
    }

    public static class Scan {

        /**
         * Packages Sprout scans for components. When empty, Sprout falls back to the package of the
         * Spring Boot main application class.
         */
        private List<String> basePackages = new ArrayList<>();

        public List<String> getBasePackages() {
            return basePackages;
        }

        public void setBasePackages(List<String> basePackages) {
            this.basePackages = basePackages;
        }
    }
}
