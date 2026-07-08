package io.github.ivannavas.sprout.monitoring;

import io.github.ivannavas.sprout.container.SproutContainer;
import io.github.ivannavas.sprout.container.SproutModuleInitializer;
import io.github.ivannavas.sprout.monitoring.abstrct.AbstractUsageStore;
import io.github.ivannavas.sprout.monitoring.impl.InMemoryUsageStore;
import io.github.ivannavas.sprout.monitoring.pricing.PricingTable;

/**
 * Activates monitoring with zero configuration once {@code sprout-monitoring} is on the classpath:
 * if the application declared no {@code @UsageStore} of its own, this installs the in-memory default
 * and subscribes the collector. A scanned {@code @UsageStore} is wired earlier by
 * {@link UsageStoreProcessor}, so by the time this runs the store already exists and the default
 * steps aside — mirroring how the in-memory event bus yields to a user {@code @EventBus}.
 */
public final class MonitoringInitializer implements SproutModuleInitializer {

    @Override
    public void onContainerReady(SproutContainer container) {
        // A user @UsageStore was scanned and wired by the processor — leave it be.
        if (container.getSingleton(AbstractUsageStore.class) != null) {
            return;
        }

        InMemoryUsageStore store = new InMemoryUsageStore();
        container.registerSingleton(AbstractUsageStore.class, store);
        container.registerSingleton(UsageStoreProcessor.STORE_BEAN_NAME, store);

        // Fold every agent and model execution into the store, pricing model calls via configuration.
        new UsageCollector(store, new PricingTable(container::getProperty)).subscribe(container.eventBus());
    }
}
