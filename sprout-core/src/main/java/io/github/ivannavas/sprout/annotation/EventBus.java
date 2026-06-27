package io.github.ivannavas.sprout.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an {@link io.github.ivannavas.sprout.abstrct.AbstractEventBus} implementation as a managed
 * component. A scanned {@code @EventBus} bean replaces the default
 * {@link io.github.ivannavas.sprout.impl.InMemoryEventBus} as the application's event bus, so a module
 * can supply a Redis-, Kafka- or broker-backed bus without any other code changing.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Component
public @interface EventBus {
}
