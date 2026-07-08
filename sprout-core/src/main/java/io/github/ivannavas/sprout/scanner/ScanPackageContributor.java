package io.github.ivannavas.sprout.scanner;

import java.util.Set;

/**
 * SPI by which a framework module declares the base packages that hold its auto-scanned components,
 * so an application need not list them in {@code sprout.scan.base-packages}. A module places an
 * implementation on the classpath and declares it in
 * {@code META-INF/services/io.github.ivannavas.sprout.scanner.ScanPackageContributor}; Sprout
 * discovers implementations via {@link java.util.ServiceLoader} and adds their packages to the scan.
 *
 * <p>For example the OpenAI and Anthropic modules each contribute their own package so their
 * {@code @Model} executors are picked up automatically once the jar is on the classpath. Only
 * packages that hold components meant to be registered by every consuming application should be
 * contributed — a module whose components are instantiated on demand, or are base classes for user
 * code, must not contribute, to avoid registering beans the application did not ask for.
 */
public interface ScanPackageContributor {

    /** Base packages to add to the component scan. */
    Set<String> basePackages();
}
