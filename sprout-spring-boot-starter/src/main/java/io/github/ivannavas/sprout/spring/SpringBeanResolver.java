package io.github.ivannavas.sprout.spring;

import io.github.ivannavas.sprout.container.ExternalBeanResolver;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.target.AbstractLazyCreationTargetSource;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

import java.util.function.Supplier;

// ExternalBeanResolver backed by the Spring BeanFactory, letting Sprout components depend on Spring
// beans. Sprout bootstraps during bean-factory post-processing (before Spring instantiates its own
// beans), so this returns a lazy proxy whose target is fetched on first use, once the context is
// fully initialised — keeping Spring bean creation at its normal time and preserving AOP proxies.
// The requested type must be proxyable (interfaces via JDK proxy, concrete classes via CGLIB), so
// final classes/methods and records cannot be injected this way.
class SpringBeanResolver implements ExternalBeanResolver {

    private final ConfigurableListableBeanFactory beanFactory;

    SpringBeanResolver(ConfigurableListableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public Object resolveByType(Class<?> type) {
        // allowEagerInit = false: only consult bean definitions, never instantiate during this phase.
        String[] names = beanFactory.getBeanNamesForType(type, true, false);
        if (names.length == 0) {
            return null;
        }
        return lazyProxy(type, () -> beanFactory.getBean(type));
    }

    @Override
    public Object resolveByName(String name) {
        if (!beanFactory.containsBean(name)) {
            return null;
        }
        Class<?> type = beanFactory.getType(name, false);
        if (type == null) {
            type = Object.class;
        }
        return lazyProxy(type, () -> beanFactory.getBean(name));
    }

    private Object lazyProxy(Class<?> type, Supplier<Object> supplier) {
        AbstractLazyCreationTargetSource targetSource = new AbstractLazyCreationTargetSource() {
            @Override
            public Class<?> getTargetClass() {
                return type;
            }

            @Override
            protected Object createObject() {
                return supplier.get();
            }
        };

        ProxyFactory proxyFactory = new ProxyFactory();
        proxyFactory.setTargetSource(targetSource);
        if (type.isInterface()) {
            proxyFactory.addInterface(type);
        } else {
            proxyFactory.setProxyTargetClass(true);
        }
        return proxyFactory.getProxy(beanFactory.getBeanClassLoader());
    }
}
