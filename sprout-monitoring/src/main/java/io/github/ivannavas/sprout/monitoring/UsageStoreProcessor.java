package io.github.ivannavas.sprout.monitoring;

import io.github.ivannavas.sprout.abstrct.AbstractEventBus;
import io.github.ivannavas.sprout.annotation.Processor;
import io.github.ivannavas.sprout.container.SproutContainer;
import io.github.ivannavas.sprout.monitoring.abstrct.AbstractUsageStore;
import io.github.ivannavas.sprout.monitoring.annotation.UsageStore;
import io.github.ivannavas.sprout.monitoring.pricing.PricingTable;
import io.github.ivannavas.sprout.processor.ComponentProcessor;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Processor for {@link UsageStore @UsageStore} components, contributed by {@code sprout-monitoring} and
 * discovered like any other {@code @Processor}. It validates that the component implements
 * {@link AbstractUsageStore}, registers it under that type and the name {@code usageStore} (so it is
 * injectable as <em>the</em> usage store), and subscribes a {@link UsageCollector} to the event bus so
 * every agent and model execution is folded into it — pricing model calls through a {@link PricingTable}
 * resolved from {@code sprout.monitoring.pricing.*} configuration.
 *
 * <p>The shipped {@link io.github.ivannavas.sprout.monitoring.impl.InMemoryUsageStore} is the in-memory
 * default: include its package on the component scan to use it (as the RAG example does for core's
 * in-memory vector store), or declare your own {@code @UsageStore} to persist usage elsewhere. Exactly one
 * usage store is expected; a second is rejected, since the collector records into a single store.
 */
@Processor(UsageStore.class)
public class UsageStoreProcessor extends ComponentProcessor {

    /** The canonical name the resolved usage store is registered under. */
    public static final String STORE_BEAN_NAME = "usageStore";

    public UsageStoreProcessor(Class<?> component, SproutContainer sproutContainer) {
        super(component, sproutContainer);
    }

    @Override
    public void validate() {
        super.validate();
        if (!AbstractUsageStore.class.isAssignableFrom(component)) {
            throw new IllegalArgumentException("@UsageStore " + component + " must implement AbstractUsageStore");
        }
    }

    @Override
    public Set<String> beanNames() {
        Set<String> names = new LinkedHashSet<>(super.beanNames());
        names.add(STORE_BEAN_NAME);
        return names;
    }

    @Override
    public Object instantiate() {
        Object existing = sproutContainer.getSingleton(component);
        if (existing != null) {
            return existing;
        }
        if (sproutContainer.getSingleton(AbstractUsageStore.class) != null) {
            throw new IllegalStateException("Sprout: multiple @UsageStore components found; declare only one");
        }

        AbstractUsageStore store = (AbstractUsageStore) super.instantiate();
        // Register under the store type as well, so it is resolvable by type without an assignability scan
        // (and so the guard above sees it). The name "usageStore" comes from beanNames().
        sproutContainer.registerSingleton(AbstractUsageStore.class, store);

        // The event bus is registered before components are instantiated, so it is available here. Subscribe
        // a collector once, when the single usage store is built, so every event lands in this store.
        AbstractEventBus bus = (AbstractEventBus) sproutContainer.getOrCreateByType(AbstractEventBus.class);
        new UsageCollector(store, new PricingTable(sproutContainer::getProperty)).subscribe(bus);
        return store;
    }
}
