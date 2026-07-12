package io.github.ivannavas.sprout.pgvector;

import io.github.ivannavas.sprout.scanner.ScanPackageContributor;

import java.util.Set;

/**
 * Adds the pgvector module's package to the component scan, so {@link PgVectorStore} is picked up
 * automatically once the jar is on the classpath — without the application listing the package in
 * {@code sprout.scan.base-packages}.
 */
public final class PgVectorScanPackageContributor implements ScanPackageContributor {

    @Override
    public Set<String> basePackages() {
        return Set.of("io.github.ivannavas.sprout.pgvector");
    }
}
