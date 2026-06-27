package io.github.ivannavas.sprout.monitoring.annotation;

import io.github.ivannavas.sprout.annotation.Component;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an {@link io.github.ivannavas.sprout.monitoring.abstrct.AbstractUsageStore} implementation as a
 * managed component. A scanned {@code @UsageStore} bean replaces the default
 * {@link io.github.ivannavas.sprout.monitoring.impl.InMemoryUsageStore} as the application's usage store,
 * so a module can persist usage to a database, push it to Prometheus/StatsD or anywhere else without any
 * other code changing.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Component
public @interface UsageStore {
}
