package io.github.ivannavas.sprout.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Requests dependency injection. On a field, the dependency is injected after construction; on a
 * constructor, its arguments are resolved and the constructor is used to instantiate the bean.
 * Dependencies are matched by type, or by bean name when combined with {@link Qualifier}.
 *
 * <p>On a constructor this is only needed to choose between several: a component that declares a single
 * constructor has it injected whether or not it is annotated.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.CONSTRUCTOR})
public @interface Autowired {
}
