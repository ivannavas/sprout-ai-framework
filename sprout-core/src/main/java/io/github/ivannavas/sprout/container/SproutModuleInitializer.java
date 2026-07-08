package io.github.ivannavas.sprout.container;

/**
 * SPI by which a framework module runs setup once the container is fully wired, without the
 * application scanning or configuring anything. A module places an implementation on the classpath
 * and declares it in
 * {@code META-INF/services/io.github.ivannavas.sprout.container.SproutModuleInitializer}; Sprout
 * discovers implementations via {@link java.util.ServiceLoader} and invokes each after every
 * component has been instantiated and wired (the same point as {@link SproutContainer#onReady}).
 *
 * <p>Use it to register a module's opt-out default that should yield to anything the application
 * supplied — for example {@code sprout-monitoring} installs its in-memory usage store only when the
 * application declared no {@code @UsageStore} of its own. Because it runs after wiring, an
 * initializer can inspect what the application registered (e.g. via
 * {@link SproutContainer#getSingleton(Class)}) and step aside when a user bean is already present.
 */
public interface SproutModuleInitializer {

    /** Invoked once, after the container has finished wiring every component. */
    void onContainerReady(SproutContainer container);
}
