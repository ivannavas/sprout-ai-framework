package io.github.ivannavas.sprout.processor;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.ServiceLoader;

// Recognises the dependency-injection annotations Sprout honours. Core knows only its own
// (@Autowired, @Value, @Qualifier, @PostConstruct); another framework's equivalents are contributed
// by whichever module bridges it, via the DiAnnotationContributor SPI. This lets a Sprout-managed
// component mix them freely — holding a foreign @Autowired field alongside a Sprout @Value and a
// foreign @Value — without either annotation being silently ignored, while keeping core free of any
// dependency on the bridged framework.
//
// Matching is by fully-qualified name against the resulting allowlist, never by simple name, so an
// unrelated user annotation that happens to be called @Value is not mistaken for one of these.
// Attribute values are read reflectively for the same reason.
final class DiAnnotations {

    private DiAnnotations() {
    }

    private static final Set<String> AUTOWIRED;
    private static final Set<String> VALUE;
    private static final Set<String> QUALIFIER;
    private static final Set<String> POST_CONSTRUCT;

    static {
        Set<String> autowired = new HashSet<>(Set.of("io.github.ivannavas.sprout.annotation.Autowired"));
        Set<String> value = new HashSet<>(Set.of("io.github.ivannavas.sprout.annotation.Value"));
        Set<String> qualifier = new HashSet<>(Set.of("io.github.ivannavas.sprout.annotation.Qualifier"));
        Set<String> postConstruct = new HashSet<>(Set.of("io.github.ivannavas.sprout.annotation.PostConstruct"));

        // Modules on the classpath extend the allowlist by declaring a DiAnnotationContributor.
        for (DiAnnotationContributor contributor : ServiceLoader.load(
                DiAnnotationContributor.class, DiAnnotations.class.getClassLoader())) {
            autowired.addAll(contributor.autowired());
            value.addAll(contributor.value());
            qualifier.addAll(contributor.qualifier());
            postConstruct.addAll(contributor.postConstruct());
        }

        AUTOWIRED = Set.copyOf(autowired);
        VALUE = Set.copyOf(value);
        QUALIFIER = Set.copyOf(qualifier);
        POST_CONSTRUCT = Set.copyOf(postConstruct);
    }

    static boolean isAutowired(AnnotatedElement element) {
        return find(element, AUTOWIRED) != null;
    }

    static boolean isValueAnnotated(Field field) {
        return find(field, VALUE) != null;
    }

    static boolean isPostConstruct(Method method) {
        return find(method, POST_CONSTRUCT) != null;
    }

    // The ${...} expression from a @Value-style annotation, or null when the field carries none.
    static String valueExpression(Field field) {
        Annotation ann = find(field, VALUE);
        return ann == null ? null : readString(ann, "value");
    }

    // The target bean name from a @Qualifier / @Named on the element, or null when absent or blank.
    static String qualifier(AnnotatedElement element) {
        Annotation ann = find(element, QUALIFIER);
        if (ann == null) {
            return null;
        }
        String value = readString(ann, "value");
        return value == null || value.isBlank() ? null : value;
    }

    // Honours an @Autowired(required = false) attribute where a bridged annotation offers one;
    // anything without a required() attribute is required.
    static boolean isRequired(AnnotatedElement element) {
        Annotation ann = find(element, AUTOWIRED);
        if (ann == null) {
            return true;
        }
        try {
            Object result = ann.annotationType().getMethod("required").invoke(ann);
            return !(result instanceof Boolean bool) || bool;
        } catch (ReflectiveOperationException e) {
            return true;
        }
    }

    private static Annotation find(AnnotatedElement element, Set<String> names) {
        for (Annotation ann : element.getAnnotations()) {
            if (names.contains(ann.annotationType().getName())) {
                return ann;
            }
        }
        return null;
    }

    private static String readString(Annotation ann, String attribute) {
        try {
            Object result = ann.annotationType().getMethod(attribute).invoke(ann);
            return result == null ? null : result.toString();
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
