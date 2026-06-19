package io.github.ivannavas.sprout.annotation;

import java.lang.annotation.*;

/** Stereotype {@link Component} marking a service-layer bean. Behaves like {@code @Component}. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Component
public @interface Service {
}
