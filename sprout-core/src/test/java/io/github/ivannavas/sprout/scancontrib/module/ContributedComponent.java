package io.github.ivannavas.sprout.scancontrib.module;

import io.github.ivannavas.sprout.annotation.Component;

// Lives in a package that is neither the entry point's nor configured via sprout.scan.base-packages;
// it is reachable only because TestScanPackageContributor adds this package to the scan.
@Component
public class ContributedComponent {

    public String describe() {
        return "contributed";
    }
}
