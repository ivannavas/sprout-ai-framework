package io.github.ivannavas.sprout.processor;

import java.util.Set;

/**
 * SPI for teaching Sprout's dependency-injection wiring to treat another framework's annotations as
 * equivalents of its own. Core recognises only Sprout's {@code @Autowired}, {@code @Value},
 * {@code @Qualifier} and {@code @PostConstruct}; a module contributes further equivalents by
 * implementing this interface and declaring it in
 * {@code META-INF/services/io.github.ivannavas.sprout.processor.DiAnnotationContributor}. Sprout
 * discovers implementations via {@link java.util.ServiceLoader} at startup and then treats the
 * listed annotations as interchangeable with its own.
 *
 * <p>A bridging module (for instance one that integrates Sprout with another DI framework) uses
 * this to register that framework's equivalents, so a Sprout-managed component can mix, say, a
 * foreign {@code @Autowired} with a Sprout {@code @Value} without either being silently ignored —
 * keeping core free of any dependency on the bridged framework.
 *
 * <p>Each method returns fully-qualified annotation type names and defaults to none, so an
 * implementation overrides only the categories it contributes to. Names are matched exactly, never
 * by simple name, so an unrelated annotation that happens to share a name is never mistaken for one
 * of these.
 */
public interface DiAnnotationContributor {

    /** Fully-qualified names to recognise as equivalent to Sprout's {@code @Autowired}. */
    default Set<String> autowired() {
        return Set.of();
    }

    /** Fully-qualified names to recognise as equivalent to Sprout's {@code @Value}. */
    default Set<String> value() {
        return Set.of();
    }

    /** Fully-qualified names to recognise as equivalent to Sprout's {@code @Qualifier}. */
    default Set<String> qualifier() {
        return Set.of();
    }

    /** Fully-qualified names to recognise as equivalent to Sprout's {@code @PostConstruct}. */
    default Set<String> postConstruct() {
        return Set.of();
    }
}
