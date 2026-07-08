package io.github.ivannavas.sprout.spring;

import io.github.ivannavas.sprout.annotation.Autowired;
import io.github.ivannavas.sprout.annotation.PostConstruct;
import io.github.ivannavas.sprout.annotation.Qualifier;
import io.github.ivannavas.sprout.annotation.Value;
import io.github.ivannavas.sprout.config.PropertyConverter;
import io.github.ivannavas.sprout.container.SproutContainer;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.Ordered;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// The mirror of the core support that lets Sprout-managed components use Spring annotations: this
// lets Spring-managed beans use Sprout's own DI annotations. Spring already handles its @Autowired
// and @Value, so this processor deliberately acts only on Sprout's annotations
// (io.github.ivannavas.sprout.annotation.*) — injecting @Value/@Autowired fields, honouring
// @Qualifier and invoking @PostConstruct after the bean is otherwise wired.
//
// Sprout-managed beans are registered as pre-built singletons and never pass through
// BeanPostProcessors, so only genuine Spring beans reach here.
class SproutAnnotationBeanPostProcessor implements BeanPostProcessor, BeanFactoryAware, Ordered {

    private ConfigurableListableBeanFactory beanFactory;
    private SproutContainer container;

    private final Map<Class<?>, Metadata> metadataCache = new ConcurrentHashMap<>();

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beanFactory = (ConfigurableListableBeanFactory) beanFactory;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        Metadata metadata = metadataCache.computeIfAbsent(bean.getClass(), SproutAnnotationBeanPostProcessor::scan);
        if (metadata.isEmpty()) {
            return bean;
        }

        for (Field field : metadata.values()) {
            injectValue(bean, field);
        }
        for (Field field : metadata.autowired()) {
            injectDependency(bean, field);
        }
        // Runs after every field is set, so a @PostConstruct method sees a fully wired bean — whether
        // its fields were injected by Sprout above or by Spring during the preceding populate phase.
        for (Method method : metadata.postConstructs()) {
            ReflectionUtils.makeAccessible(method);
            ReflectionUtils.invokeMethod(method, bean);
        }
        return bean;
    }

    private void injectValue(Object bean, Field field) {
        String resolved = container().resolveExpression(field.getAnnotation(Value.class).value());
        ReflectionUtils.makeAccessible(field);
        ReflectionUtils.setField(field, bean, PropertyConverter.convert(resolved, field.getType()));
    }

    private void injectDependency(Object bean, Field field) {
        Qualifier qualifier = field.getAnnotation(Qualifier.class);
        Object dependency;
        try {
            dependency = qualifier != null
                    ? beanFactory.getBean(qualifier.value())
                    : beanFactory.getBean(field.getType());
        } catch (NoSuchBeanDefinitionException e) {
            throw new IllegalStateException("Failed to inject Sprout @Autowired field '" + field.getName()
                    + "' on " + bean.getClass().getName() + ": " + e.getMessage(), e);
        }
        ReflectionUtils.makeAccessible(field);
        ReflectionUtils.setField(field, bean, dependency);
    }

    private SproutContainer container() {
        if (container == null) {
            container = beanFactory.getBean(SproutContainer.class);
        }
        return container;
    }

    // Runs late enough that Spring's own @Autowired/@Value injection (done in the earlier populate
    // phase) has already completed before any @PostConstruct here fires.
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    private static Metadata scan(Class<?> type) {
        List<Field> values = new ArrayList<>();
        List<Field> autowired = new ArrayList<>();
        List<Method> postConstructs = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (field.isAnnotationPresent(Value.class)) {
                    values.add(field);
                } else if (field.isAnnotationPresent(Autowired.class)) {
                    autowired.add(field);
                }
            }
            for (Method method : c.getDeclaredMethods()) {
                if (method.isAnnotationPresent(PostConstruct.class) && method.getParameterCount() == 0) {
                    postConstructs.add(method);
                }
            }
        }
        return new Metadata(values, autowired, postConstructs);
    }

    private record Metadata(List<Field> values, List<Field> autowired, List<Method> postConstructs) {
        boolean isEmpty() {
            return values.isEmpty() && autowired.isEmpty() && postConstructs.isEmpty();
        }
    }
}
