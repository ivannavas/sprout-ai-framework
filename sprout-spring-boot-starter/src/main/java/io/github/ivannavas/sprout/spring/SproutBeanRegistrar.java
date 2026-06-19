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

        for (Map.Entry<String, Object> entry : container.getSingletonsByName().entrySet()) {
            String name = entry.getKey();
            // Avoid clobbering an existing Spring bean that happens to share the name.
            if (beanFactory.containsBean(name)) {
                name = "sprout_" + name;
            }
            if (!beanFactory.containsSingleton(name)) {
                beanFactory.registerSingleton(name, entry.getValue());
            }
        }
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
