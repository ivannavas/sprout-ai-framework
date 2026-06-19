package io.github.ivannavas.sprout.annotation;

import java.lang.annotation.*;

/**
 * Marks a class that supplies configuration through {@link ConfigurationProperty} fields and
 * methods.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Configuration {

    /** Profile this configuration is restricted to. Empty means it always applies. */
    String profile() default "";
}
