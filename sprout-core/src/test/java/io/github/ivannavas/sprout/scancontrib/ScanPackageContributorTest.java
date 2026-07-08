package io.github.ivannavas.sprout.scancontrib;

import io.github.ivannavas.sprout.SproutApplication;
import io.github.ivannavas.sprout.container.SproutContainer;
import io.github.ivannavas.sprout.scancontrib.entry.ScanContribEntry;
import io.github.ivannavas.sprout.scancontrib.module.ContributedComponent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

// A module's ScanPackageContributor (registered via META-INF/services) makes the container scan the
// module's package automatically, even though the entry point is in an unrelated package and
// sprout.scan.base-packages is not set.
class ScanPackageContributorTest {

    @Test
    void componentFromContributedPackageIsScannedWithoutConfiguration() {
        SproutContainer container = SproutApplication.run(ScanContribEntry.class);

        ContributedComponent component = container.getSingleton(ContributedComponent.class);
        assertNotNull(component, "a component in a contributed package should be scanned and managed");
    }
}
