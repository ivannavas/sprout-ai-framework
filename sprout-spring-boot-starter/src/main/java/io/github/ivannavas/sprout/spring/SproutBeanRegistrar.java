package io.github.ivannavas.sprout.spring;

import io.github.ivannavas.sprout.container.SproutContainer;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.util.ClassUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

// Bootstraps the SproutContainer during context refresh and registers every Sprout-managed
// singleton as a Spring singleton, so they can be @Autowired from regular Spring beans. Runs as a
// BeanFactoryPostProcessor so the singletons exist before any application bean is instantiated,
// making them autowirable both by type and by name.
public class SproutBeanRegistrar implements BeanFactoryPostProcessor {

    static final String CONTAINER_BEAN_NAME = "sproutContainer";

    private final ConfigurableEnvironment environment;

    public SproutBeanRegistrar(ConfigurableEnvironment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        Class<?> mainClass = resolveMainClass(beanFactory);
        Logger logger = Logger.getLogger("io.github.ivannavas.sprout");

        SproutContainer container = new SproutContainer(mainClass, logger);
        seedEnvironment(container);
        // Lets Sprout-managed components fall back to Spring beans for dependencies Sprout
        // cannot satisfy itself.
        container.setExternalBeanResolver(new SpringBeanResolver(beanFactory));
        container.bootstrap();

        if (!beanFactory.containsBean(CONTAINER_BEAN_NAME)) {
            beanFactory.registerSingleton(CONTAINER_BEAN_NAME, container);
        }

        registerSproutSingletons(beanFactory, container);
    }

    // Exposes every Sprout-managed singleton as a Spring bean. A Sprout bean can be registered under
    // several names (e.g. a @Model("openai") is both "openai" and "openaiModelExecutor", and a
    // module's @UsageStore is both "usageStore" and "abstractUsageStore"). Each such bean is registered
    // as ONE Spring singleton under a primary name, with its remaining names attached as aliases —
    // otherwise the same instance would be registered as several distinct singletons, making by-type
    // autowiring (@Autowired ModelExecutor) ambiguous and breaking it for anything but a @Qualifier.
    private void registerSproutSingletons(ConfigurableListableBeanFactory beanFactory, SproutContainer container) {
        // Group the names by the identical instance they point to (identity, not equals: distinct beans
        // may be equal()).
        Map<Object, List<String>> namesByInstance = new IdentityHashMap<>();
        for (Map.Entry<String, Object> entry : container.getSingletonsByName().entrySet()) {
            namesByInstance.computeIfAbsent(entry.getValue(), k -> new ArrayList<>()).add(entry.getKey());
        }

        for (Map.Entry<Object, List<String>> entry : namesByInstance.entrySet()) {
            Object instance = entry.getKey();
            List<String> names = new ArrayList<>(entry.getValue());
            // Sort so the primary name (and thus alias assignment) is deterministic across runs.
            Collections.sort(names);

            String primary = availableName(beanFactory, names.get(0));
            if (!beanFactory.containsSingleton(primary)) {
                beanFactory.registerSingleton(primary, instance);
            }

            for (int i = 1; i < names.size(); i++) {
                String alias = availableName(beanFactory, names.get(i));
                if (!alias.equals(primary) && !beanFactory.containsBean(alias)) {
                    beanFactory.registerAlias(primary, alias);
                }
            }
        }
    }

    // A name usable in the Spring registry: the Sprout name, or a "sprout_"-prefixed fallback when an
    // existing Spring bean already claims it, so we never clobber the application's own beans.
    private static String availableName(ConfigurableListableBeanFactory beanFactory, String name) {
        return beanFactory.containsBean(name) ? "sprout_" + name : name;
    }

    // Copies every property visible to Spring into the container, so @Value, @ConfigurationProperty
    // and sprout.scan.base-packages resolve from application.yml/properties and any other Spring
    // property source. Spring values take precedence over sprout.properties.
    private void seedEnvironment(SproutContainer container) {
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) {
                continue;
            }
            for (String name : enumerable.getPropertyNames()) {
                // Use the Environment so placeholders are resolved and the effective (highest
                // precedence) value is always the one stored.
                String value = environment.getProperty(name);
                if (value != null) {
                    container.setProperty(name, value);
                }
            }
        }
    }

    private Class<?> resolveMainClass(ConfigurableListableBeanFactory beanFactory) {
        for (String name : beanFactory.getBeanNamesForAnnotation(SpringBootConfiguration.class)) {
            Class<?> type = beanFactory.getType(name);
            if (type != null) {
                return ClassUtils.getUserClass(type);
            }
        }
        return SproutBeanRegistrar.class;
    }
}
