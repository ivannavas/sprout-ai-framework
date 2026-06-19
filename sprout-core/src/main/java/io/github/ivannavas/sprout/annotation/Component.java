package io.github.ivannavas.sprout.annotation;

import java.lang.annotation.*;

/**
 * Marks a class as a Sprout-managed component: it is discovered by component scanning, instantiated
 * as a singleton and made available for dependency injection. Also usable as a meta-annotation —
 * stereotypes such as {@link Service} and {@link Agent} are themselves {@code @Component}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Component {
}
