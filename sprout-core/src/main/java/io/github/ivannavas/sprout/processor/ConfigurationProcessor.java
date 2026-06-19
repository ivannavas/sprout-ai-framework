package io.github.ivannavas.sprout.processor;

import io.github.ivannavas.sprout.annotation.Configuration;
import io.github.ivannavas.sprout.annotation.ConfigurationProperty;
import io.github.ivannavas.sprout.annotation.Processor;
import io.github.ivannavas.sprout.config.PropertyConverter;
import io.github.ivannavas.sprout.container.SproutContainer;
import io.github.ivannavas.sprout.model.ComponentField;
import io.github.ivannavas.sprout.model.ComponentMethod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Processor for {@link Configuration @Configuration} components. Binds their
 * {@link ConfigurationProperty @ConfigurationProperty} fields (injecting the resolved value) and
 * methods (using the return value as a default for the property).
 */
@Processor(Configuration.class)
public class ConfigurationProcessor extends ComponentProcessor {

    public ConfigurationProcessor(Class<?> component, SproutContainer sproutContainer) {
        super(component, sproutContainer);

        putComponentMethods(Map.of(
                ConfigurationProperty.class, new ComponentMethod(this::processDefaultMethod)
        ));

        putComponentFields(Map.of(
                ConfigurationProperty.class, new ComponentField(this::processConfigurationPropertyField)
        ));
    }

    @Override
    public void validate() {
        super.validate();

        for (Method method : component.getDeclaredMethods()) {
            if (method.isAnnotationPresent(ConfigurationProperty.class) && !method.getReturnType().isPrimitive()) {
                throw new IllegalArgumentException("Configuration property method " + method + " must return a primitive type");
            }
        }
    }

    private void processDefaultMethod(Method method) {
        ConfigurationProperty annotation = method.getAnnotation(ConfigurationProperty.class);
        String key = annotation.value().isEmpty() ? method.getName() : annotation.value();

        if (sproutContainer.getProperty(key) != null) {
            return;
        }

        method.setAccessible(true);
        try {
            Object result = method.invoke(currentInstance);
            if (result != null) {
                sproutContainer.setProperty(key, String.valueOf(result));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke @ConfigurationProperty method: " + method, e);
        }
    }

    private void processConfigurationPropertyField(Field field) {
        ConfigurationProperty annotation = field.getAnnotation(ConfigurationProperty.class);
        String key = annotation.value().isEmpty() ? field.getName() : annotation.value();
        String value = sproutContainer.getProperty(key);

        if (value == null) {
            return;
        }

        field.setAccessible(true);
        try {
            field.set(currentInstance, PropertyConverter.convert(value, field.getType()));
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject @ConfigurationProperty field: " + field, e);
        }
    }
}
