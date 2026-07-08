package io.github.ivannavas.sprout.processor;

import io.github.ivannavas.sprout.config.PropertyConverter;
import io.github.ivannavas.sprout.container.SproutContainer;
import io.github.ivannavas.sprout.model.ComponentAnnotation;
import io.github.ivannavas.sprout.model.ComponentField;
import io.github.ivannavas.sprout.model.ComponentMethod;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * The unit of bean construction, and Sprout's primary extension point. The container creates one
 * processor per component and drives it through three phases: {@link #instantiate()},
 * {@link #process(Object)} (field/method lifecycle) and {@link #processAnnotations(Object)}
 * (class-level annotation handlers). This base class implements the standard wiring —
 * {@code @Autowired}, {@code @Value}, {@code @PostConstruct} and the default bean naming.
 *
 * <p>To teach the container about a new annotation, write a subclass and register it with
 * {@link io.github.ivannavas.sprout.annotation.Processor @Processor(MyAnnotation.class)}; it is then
 * discovered automatically and applied to every component carrying that annotation. A subclass can:
 * <ul>
 *   <li>register field/method/class-annotation handlers from its constructor via
 *       {@link #putComponentFields}, {@link #putComponentMethods} and {@link #putComponentAnnotations};</li>
 *   <li>override {@link #instantiate()} to build a specialised bean (as {@code AgentProcessor} does);</li>
 *   <li>override {@link #validate()} to enforce constraints, or {@link #beanNames()} to add aliases;</li>
 *   <li>override another processor via {@code @Processor(value = ..., overrides = ...)}.</li>
 * </ul>
 * This is how the {@code @Agent}, {@code @Model}, {@code @Configuration} and {@code @Mcp} modules
 * plug in without the core knowing about them — the same mechanism is open to application code and
 * third-party modules.
 */
public class ComponentProcessor {

    public ComponentProcessor(Class<?> component, SproutContainer sproutContainer) {
        this.component = component;
        this.sproutContainer = sproutContainer;

        this.componentMethodsByAnnotation = new HashMap<>();
        this.componentFieldsByAnnotation = new HashMap<>();
        this.componentAnnotationsByAnnotation = new HashMap<>();
    }

    protected final Class<?> component;
    protected final SproutContainer sproutContainer;
    protected Object currentInstance;

    private final HashMap<Class<?>, ComponentMethod> componentMethodsByAnnotation;
    private final HashMap<Class<?>, ComponentField> componentFieldsByAnnotation;
    private final HashMap<Class<?>, ComponentAnnotation> componentAnnotationsByAnnotation;

    /** Registers handlers invoked for each method annotated with the given annotation type. */
    protected void putComponentMethods(Map<Class<?>, ComponentMethod> componentMethodsByAnnotation) {
        this.componentMethodsByAnnotation.putAll(componentMethodsByAnnotation);
    }

    /** Registers handlers invoked for each field annotated with the given annotation type. */
    protected void putComponentFields(Map<Class<?>, ComponentField> componentFieldsByAnnotation) {
        this.componentFieldsByAnnotation.putAll(componentFieldsByAnnotation);
    }

    /** Registers handlers invoked for each class-level annotation of the given type. */
    protected void putComponentAnnotations(Map<Class<?>, ComponentAnnotation> componentAnnotationsByAnnotation) {
        this.componentAnnotationsByAnnotation.putAll(componentAnnotationsByAnnotation);
    }

    /**
     * Checks the component before any instance is created. Throws if it cannot be managed. Override
     * to add annotation-specific constraints (call {@code super.validate()} first).
     */
    public void validate() {
        if (component.isInterface() || Modifier.isAbstract(component.getModifiers())) {
            throw new IllegalArgumentException("Component " + component + " must be a concrete class");
        }
    }

    /**
     * The names this component is registered under. Defaults to the class name in camelCase;
     * override to add aliases (as {@code ModelProcessor} does for {@code @Model("name")}).
     */
    public Set<String> beanNames() {
        String simple = component.getSimpleName();
        return Set.of(Character.toLowerCase(simple.charAt(0)) + simple.substring(1));
    }

    /**
     * Creates the singleton instance (constructor injection if a constructor is {@code @Autowired},
     * otherwise the no-arg constructor) and registers it. Idempotent: returns the existing singleton
     * if already built. Override to construct or register additional specialised beans.
     */
    public Object instantiate() {
        Object existing = sproutContainer.getSingleton(component);
        if (existing != null) {
            return existing;
        }

        for (Constructor<?> ctor : component.getDeclaredConstructors()) {
            if (DiAnnotations.isAutowired(ctor)) {
                try {
                    Object[] args = resolveConstructorArgs(ctor);
                    ctor.setAccessible(true);
                    Object instance = ctor.newInstance(args);
                    sproutContainer.registerSingleton(component, instance);
                    return instance;
                } catch (Exception e) {
                    throw new RuntimeException("Sprout: failed constructor injection for " + component, e);
                }
            }
        }

        try {
            Object instance = component.getConstructor().newInstance();
            sproutContainer.registerSingleton(component, instance);
            return instance;
        } catch (Exception e) {
            throw new RuntimeException("Sprout: failed instantiating component " + component, e);
        }
    }

    private Object[] resolveConstructorArgs(Constructor<?> ctor) {
        Parameter[] parameters = ctor.getParameters();
        Object[] args = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            Parameter param = parameters[i];
            args[i] = resolveDependency(
                    DiAnnotations.qualifier(param),
                    param.getType(),
                    "parameter " + param.getName() + " in constructor of " + ctor.getDeclaringClass());
        }
        return args;
    }

    /**
     * Runs the component's lifecycle on the wired instance: class-level annotation handlers, then
     * method handlers (e.g. {@code @PostConstruct}), then field handlers (e.g. {@code @Autowired},
     * {@code @Value}). Invoked by the container after every component has been instantiated.
     */
    public void process(Object instance) {
        processAnnotations(instance);

        for (Method method : component.getDeclaredMethods()) {
            for (Annotation ann : method.getAnnotations()) {
                ComponentMethod componentMethod = componentMethodsByAnnotation.get(ann.annotationType());
                if (componentMethod != null && componentMethod.consumer() != null) {
                    componentMethod.consumer().accept(method);
                }
            }
            if (DiAnnotations.isPostConstruct(method)) {
                processPostConstruct(method, currentInstance);
            }
        }

        processFields();
    }

    /**
     * Runs only the class-level annotation handlers. Split out from {@link #process(Object)} so the
     * container can fire these across several cooperating processors of one component (e.g. an
     * {@code @Agent @Mcp} class) while running the field/method lifecycle just once.
     */
    public void processAnnotations(Object instance) {
        this.currentInstance = instance;

        for (Annotation ann : component.getAnnotations()) {
            ComponentAnnotation componentAnnotation = componentAnnotationsByAnnotation.get(ann.annotationType());
            if (componentAnnotation != null && componentAnnotation.consumer() != null) {
                componentAnnotation.consumer().accept(ann);
            }
        }
    }

    private void processFields() {

        for (Field field : component.getDeclaredFields()) {
            for (Annotation ann : field.getAnnotations()) {
                ComponentField componentField = componentFieldsByAnnotation.get(ann.annotationType());
                if (componentField != null && componentField.consumer() != null) {
                    componentField.consumer().accept(field);
                }
            }
            // Built-in wiring, tolerant of every registered flavour of each annotation (Sprout's own
            // plus any a bridging module has contributed).
            if (DiAnnotations.isValueAnnotated(field)) {
                processValue(field);
            } else if (DiAnnotations.isAutowired(field)) {
                processAutowired(field);
            }
        }
    }

    private void processPostConstruct(Method method, Object instance) {
        method.setAccessible(true);
        try {
            method.invoke(instance);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke @PostConstruct method: " + method, e);
        }
    }

    private void processAutowired(Field field) {
        field.setAccessible(true);
        String qualifier = DiAnnotations.qualifier(field);
        Object dependency = qualifier != null
                ? sproutContainer.getOrCreateByName(qualifier)
                : sproutContainer.getOrCreateByType(field.getType());
        if (dependency == null) {
            // An optional dependency (e.g. an @Autowired(required = false) from a bridged framework)
            // is left unset.
            if (!DiAnnotations.isRequired(field)) {
                return;
            }
            throw new RuntimeException("Failed to inject @Autowired field: " + field + " - "
                    + (qualifier != null ? "no bean with qualifier '" + qualifier + "'"
                                         : "no bean of type " + field.getType().getName()));
        }
        try {
            field.set(currentInstance, dependency);
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject @Autowired field: " + field, e);
        }
    }

    private Object resolveDependency(String qualifier, Class<?> type, String target) {
        if (qualifier != null) {
            Object dependency = sproutContainer.getOrCreateByName(qualifier);
            if (dependency == null) {
                throw new RuntimeException("No bean found with qualifier '" + qualifier + "' for " + target);
            }
            return dependency;
        }
        Object dependency = sproutContainer.getOrCreateByType(type);
        if (dependency == null) {
            throw new RuntimeException("No bean found for type: " + type + " for " + target);
        }
        return dependency;
    }

    private void processValue(Field field) {
        String resolved = sproutContainer.resolveExpression(DiAnnotations.valueExpression(field));
        field.setAccessible(true);
        try {
            field.set(currentInstance, PropertyConverter.convert(resolved, field.getType()));
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject @Value field: " + field, e);
        }
    }
}
