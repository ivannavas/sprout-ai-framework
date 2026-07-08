package io.github.ivannavas.sprout.container;

import io.github.ivannavas.sprout.abstrct.AbstractEventBus;
import io.github.ivannavas.sprout.config.PropertiesLoader;
import io.github.ivannavas.sprout.config.PropertyResolver;
import io.github.ivannavas.sprout.impl.InMemoryEventBus;
import io.github.ivannavas.sprout.processor.ComponentProcessor;
import io.github.ivannavas.sprout.scanner.ComponentScanner;
import io.github.ivannavas.sprout.scanner.ProcessorScanner;
import io.github.ivannavas.sprout.scanner.ScanPackageContributor;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.util.*;
import java.util.logging.Logger;

/**
 * Sprout's IoC container. Scans for components, instantiates them as singletons, performs dependency
 * injection and property resolution, and runs the per-annotation processors that build agents,
 * models and other specialised beans. Obtain one via {@link io.github.ivannavas.sprout.SproutApplication}
 * (or, when embedded, the host framework's starter) and look beans up with {@link #getSingleton(Class)} /
 * {@link #getSingleton(String)}.
 */
public final class SproutContainer {

    private final Class<?> mainClass;
    private final Logger logger;
    private final Map<Class<?>, Object> singletons = new HashMap<>();
    private final Map<String, Object> singletonsByName = new HashMap<>();
    private final Map<String, String> configurationProperties = new HashMap<>();

    private final Map<Class<?>, List<ComponentProcessor>> processorsByClass = new HashMap<>();
    private final Map<String, Class<?>> componentsByBeanName = new HashMap<>();
    private final Set<Class<?>> underConstruction = new LinkedHashSet<>();
    private final List<Runnable> readyCallbacks = new ArrayList<>();

    private ExternalBeanResolver externalBeanResolver;

    public SproutContainer(Class<?> mainClass, Logger logger) {
        this.mainClass = mainClass;
        this.logger = logger;
    }

    public void bootstrap() {
        Map<String, String> loaded = PropertiesLoader.load(mainClass.getClassLoader(), logger);
        // Properties already present (e.g. seeded from an embedding container's configuration) take
        // precedence over values loaded from sprout.properties.
        loaded.forEach(configurationProperties::putIfAbsent);

        ProcessorScanner processorScanner = new ProcessorScanner(mainClass, logger);
        Map<Class<? extends Annotation>, Class<? extends ComponentProcessor>> processorMap = processorScanner.scan();

        ComponentScanner componentScanner = new ComponentScanner(mainClass, resolveScanPackages(), logger);
        List<Class<?>> components = componentScanner.scan();

        for (Class<?> clazz : components) {
            List<ComponentProcessor> processors = resolveProcessors(clazz, processorMap);
            for (ComponentProcessor processor : processors) {
                processor.validate();
                for (String name : processor.beanNames()) {
                    componentsByBeanName.put(name, clazz);
                }
            }
            processorsByClass.put(clazz, processors);
        }

        registerEventBus(components);

        for (Class<?> clazz : components) {
            getOrCreate(clazz);
        }

        for (Class<?> clazz : components) {
            // The field/method lifecycle (@Autowired/@Value/@PostConstruct) is identical across a
            // component's processors, so it runs once. Class-level annotation handlers, however, may
            // differ per processor (e.g. an overriding agent processor that also handles @UseMcp), so
            // every processor gets to fire those.
            List<ComponentProcessor> processors = processorsByClass.get(clazz);
            Object instance = singletons.get(clazz);
            processors.get(0).process(instance);
            for (int i = 1; i < processors.size(); i++) {
                processors.get(i).processAnnotations(instance);
            }
        }

        for (Runnable callback : readyCallbacks) {
            callback.run();
        }

        // Modules on the classpath run their own setup now that every component is wired — e.g. to
        // install an opt-out default that yields to anything the application already registered.
        for (SproutModuleInitializer initializer : ServiceLoader.load(
                SproutModuleInitializer.class, getClass().getClassLoader())) {
            initializer.onContainerReady(this);
        }
    }

    /**
     * Registers a callback to run once {@link #bootstrap()} has fully wired every component. Useful
     * for processors that need to act after all beans exist (rather than mid-construction).
     */
    public void onReady(Runnable callback) {
        readyCallbacks.add(callback);
    }

    /**
     * The application's event bus. A scanned {@link io.github.ivannavas.sprout.annotation.EventBus @EventBus}
     * component (e.g. a Redis- or Kafka-backed one) is used when present; otherwise the default
     * {@link InMemoryEventBus}. Subscribe to it to observe the prefab agent/model lifecycle events, or
     * publish your own {@link io.github.ivannavas.sprout.event.Event}s.
     */
    public AbstractEventBus eventBus() {
        return getSingleton(AbstractEventBus.class);
    }

    // Resolves the single event bus and registers it under AbstractEventBus, so it is available for
    // injection by type and shared by every agent. A user-supplied @EventBus component (already scanned
    // into the component set) wins over the in-memory default; the default is otherwise instantiated
    // directly because core's impl package is not part of the application's scanned packages. Mapped by
    // type plus the single canonical name "eventBus" (not the derived "abstractEventBus"), so an
    // embedding container exposes one unambiguous bean.
    private void registerEventBus(List<Class<?>> components) {
        Class<?> custom = null;
        for (Class<?> clazz : components) {
            if (AbstractEventBus.class.isAssignableFrom(clazz)) {
                if (custom != null) {
                    throw new IllegalStateException("Sprout: multiple @EventBus components found ("
                            + custom + ", " + clazz + "); declare only one");
                }
                custom = clazz;
            }
        }
        AbstractEventBus bus = custom != null
                ? (AbstractEventBus) getOrCreate(custom)
                : new InMemoryEventBus();
        singletons.put(AbstractEventBus.class, bus);
        singletonsByName.put("eventBus", bus);
    }

    private List<String> resolveScanPackages() {
        Set<String> packages = new LinkedHashSet<>();

        // The application's own packages: whatever sprout.scan.base-packages lists, or the main
        // class's package when it is unset.
        String configured = getProperty("sprout.scan.base-packages");
        if (configured != null && !configured.isBlank()) {
            Arrays.stream(configured.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(packages::add);
        } else {
            packages.add(mainClass.getPackageName());
        }

        // Framework modules on the classpath declare the packages holding their auto-scanned
        // components (e.g. the OpenAI/Anthropic @Model executors), so an application need not list
        // them itself. Always added, since the module was deliberately put on the classpath.
        for (ScanPackageContributor contributor : ServiceLoader.load(
                ScanPackageContributor.class, getClass().getClassLoader())) {
            packages.addAll(contributor.basePackages());
        }

        return List.copyOf(packages);
    }

    /**
     * Resolves every processor whose annotation is present on the component. A class may carry more
     * than one specialized annotation (e.g. {@code @Agent} together with {@code @Mcp}); each
     * matching processor contributes its specialization. When none match, the default
     * {@link ComponentProcessor} handles plain wiring.
     */
    private List<ComponentProcessor> resolveProcessors(Class<?> clazz,
                                                Map<Class<? extends Annotation>, Class<? extends ComponentProcessor>> processorMap) {
        List<ComponentProcessor> processors = new ArrayList<>();
        for (Map.Entry<Class<? extends Annotation>, Class<? extends ComponentProcessor>> entry : processorMap.entrySet()) {
            if (clazz.isAnnotationPresent(entry.getKey())) {
                processors.add(instantiateProcessor(entry.getValue(), clazz));
            }
        }
        if (processors.isEmpty()) {
            processors.add(new ComponentProcessor(clazz, this));
        }
        return processors;
    }

    private ComponentProcessor instantiateProcessor(Class<? extends ComponentProcessor> processorClass, Class<?> clazz) {
        try {
            Constructor<? extends ComponentProcessor> ctor = processorClass.getConstructor(Class.class, SproutContainer.class);
            return ctor.newInstance(clazz, this);
        } catch (Exception e) {
            throw new IllegalStateException("Sprout: failed instantiating processor " + processorClass + " for " + clazz, e);
        }
    }

    private Object getOrCreate(Class<?> clazz) {
        Object existing = singletons.get(clazz);
        if (existing != null) {
            return existing;
        }
        List<ComponentProcessor> processors = processorsByClass.get(clazz);
        if (processors == null) {
            throw new IllegalStateException("Sprout: no managed component for type " + clazz);
        }
        if (!underConstruction.add(clazz)) {
            throw new IllegalStateException("Sprout: circular dependency detected: " + underConstruction + " -> " + clazz);
        }
        try {
            // Each processor's instantiate() is run; the base instantiation is idempotent (it returns
            // the already-registered singleton), so specialized processors share the one instance.
            Object instance = null;
            for (ComponentProcessor processor : processors) {
                Object created = processor.instantiate();
                if (instance == null) {
                    instance = created;
                }
                for (String name : processor.beanNames()) {
                    singletonsByName.put(name, instance);
                }
            }
            return instance;
        } finally {
            underConstruction.remove(clazz);
        }
    }

    public Object getOrCreateByType(Class<?> type) {
        Object existing = singletons.get(type);
        if (existing != null) {
            return existing;
        }
        if (processorsByClass.containsKey(type)) {
            return getOrCreate(type);
        }
        Class<?> match = null;
        for (Class<?> candidate : processorsByClass.keySet()) {
            if (type.isAssignableFrom(candidate)) {
                if (match != null) {
                    throw new IllegalStateException(
                            "Sprout: ambiguous dependency for type " + type + " (" + match + ", " + candidate + ")");
                }
                match = candidate;
            }
        }
        if (match != null) {
            return getOrCreate(match);
        }
        return externalBeanResolver == null ? null : externalBeanResolver.resolveByType(type);
    }

    public Object getOrCreateByName(String name) {
        Object existing = singletonsByName.get(name);
        if (existing != null) {
            return existing;
        }
        Class<?> clazz = componentsByBeanName.get(name);
        if (clazz == null) {
            return externalBeanResolver == null ? null : externalBeanResolver.resolveByName(name);
        }
        getOrCreate(clazz);
        return singletonsByName.get(name);
    }

    /**
     * Registers a fallback resolver consulted when a dependency cannot be satisfied by a
     * Sprout-managed component. Must be set before {@link #bootstrap()} for it to take effect
     * during wiring.
     */
    public void setExternalBeanResolver(ExternalBeanResolver externalBeanResolver) {
        this.externalBeanResolver = externalBeanResolver;
    }

    public void shutdown() {
        singletons.clear();
        singletonsByName.clear();
    }

    public void registerSingleton(Class<?> type, Object instance) {
        singletons.put(type, instance);
        singletonsByName.put(toBeanName(type), instance);
    }

    public void registerSingleton(String name, Object instance) {
        singletonsByName.put(name, instance);
    }

    @SuppressWarnings("unchecked")
    public <T> T getSingleton(Class<T> type) {
        return (T) singletons.get(type);
    }

    @SuppressWarnings("unchecked")
    public <T> T getSingleton(String name) {
        return (T) singletonsByName.get(name);
    }

    /**
     * Snapshot of every managed singleton keyed by bean name. Intended for embedding containers
     * (e.g. a host framework's starter) that want to expose Sprout beans in their own registry.
     */
    public Map<String, Object> getSingletonsByName() {
        return Map.copyOf(singletonsByName);
    }

    private static String toBeanName(Class<?> type) {
        String name = type.getSimpleName();
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    public Class<?> mainClass() {
        return mainClass;
    }

    public Logger logger() {
        return logger;
    }

    public String getProperty(String key) {
        String raw = lookupRaw(key);
        return raw == null ? null : PropertyResolver.resolve(raw, this::lookupRaw);
    }

    public String getProperty(String key, String defaultValue) {
        String value = getProperty(key);
        return value == null ? defaultValue : value;
    }

    public String resolveExpression(String expression) {
        return PropertyResolver.resolve(expression, this::lookupRaw);
    }

    public void setProperty(String key, String value) {
        configurationProperties.put(key, value);
    }

    public Map<String, String> getAllProperties() {
        Map<String, String> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : configurationProperties.entrySet()) {
            resolved.put(entry.getKey(), PropertyResolver.resolve(entry.getValue(), this::lookupRaw));
        }
        return Map.copyOf(resolved);
    }

    private String lookupRaw(String key) {
        String value = configurationProperties.get(key);
        if (value == null) {
            value = System.getProperty(key);
        }
        if (value == null) {
            value = System.getenv(key);
        }
        if (value == null) {
            value = System.getenv(toEnvVarName(key));
        }
        return value;
    }

    private static String toEnvVarName(String key) {
        return key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }
}
