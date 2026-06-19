package io.github.ivannavas.sprout.model;

import java.lang.annotation.Annotation;
import java.util.function.Consumer;

/** A handler a processor registers to act on a class-level annotation of a given type. */
public record ComponentAnnotation(
        Consumer<Annotation> consumer
) {}
