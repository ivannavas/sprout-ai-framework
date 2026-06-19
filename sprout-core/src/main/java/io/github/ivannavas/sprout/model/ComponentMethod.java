package io.github.ivannavas.sprout.model;

import java.lang.reflect.Method;
import java.util.function.Consumer;

/** A handler a processor registers to act on each method carrying a given annotation. */
public record ComponentMethod(
        Consumer<Method> consumer
) {}
