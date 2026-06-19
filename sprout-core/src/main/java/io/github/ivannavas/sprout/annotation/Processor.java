package io.github.ivannavas.sprout.annotation;

import io.github.ivannavas.sprout.processor.ComponentProcessor;

import java.lang.annotation.*;

/**
 * Registers a {@link ComponentProcessor} extension. The container applies the annotated processor to
 * every component carrying the {@link #value() target annotation}, letting a module add custom wiring
 * (this is how {@code @Agent}, {@code @Model} and {@code @Mcp} support is plugged in).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Processor {

    /** The annotation whose components this processor handles. */
    Class<? extends Annotation> value();

    /** A processor this one replaces, so a module can override another's behaviour for the same annotation. */
    Class<? extends ComponentProcessor> overrides() default ComponentProcessor.class;
}
