package io.github.ivannavas.sprout.scancontrib;

import io.github.ivannavas.sprout.scanner.ScanPackageContributor;

import java.util.Set;

// Stands in for a framework module: contributes a base package so the container scans it without the
// application configuring sprout.scan.base-packages. Registered via META-INF/services.
public class TestScanPackageContributor implements ScanPackageContributor {

    @Override
    public Set<String> basePackages() {
        return Set.of("io.github.ivannavas.sprout.scancontrib.module");
    }
}
