package io.github.ivannavas.sprout.ollama;

import io.github.ivannavas.sprout.scanner.ScanPackageContributor;

import java.util.Set;

/**
 * Adds the Ollama module's package to the component scan, so its {@code @Model} executor and
 * embedding model are picked up automatically once the jar is on the classpath — without the
 * application listing the package in {@code sprout.scan.base-packages}.
 */
public final class OllamaScanPackageContributor implements ScanPackageContributor {

    @Override
    public Set<String> basePackages() {
        return Set.of("io.github.ivannavas.sprout.ollama");
    }
}
