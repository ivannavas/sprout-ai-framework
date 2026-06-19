package io.github.ivannavas.sprout.model;

import java.lang.reflect.Field;
import java.util.function.Consumer;

/** A handler a processor registers to act on each field carrying a given annotation. */
public record ComponentField(
        Consumer<Field> consumer
) {}
