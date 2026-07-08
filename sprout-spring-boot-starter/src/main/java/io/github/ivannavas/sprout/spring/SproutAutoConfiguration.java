package io.github.ivannavas.sprout.spring;

import io.github.ivannavas.sprout.container.SproutContainer;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.ConfigurableEnvironment;

// Wires Sprout into a Spring Boot app. Active whenever this starter is on the classpath; opt out
// with sprout.enabled=false.
@AutoConfiguration
@ConditionalOnClass(SproutContainer.class)
@ConditionalOnProperty(prefix = "sprout", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SproutProperties.class)
public class SproutAutoConfiguration {

    // static so the bean-factory post-processor is created without forcing early instantiation of
    // this configuration class.
    @Bean
    static SproutBeanRegistrar sproutBeanRegistrar(ConfigurableEnvironment environment) {
        return new SproutBeanRegistrar(environment);
    }

    // Lets Spring-managed beans wire themselves with Sprout's own DI annotations (the mirror of a
    // Sprout component using Spring's). static, as befits a BeanPostProcessor definition.
    @Bean
    static SproutAnnotationBeanPostProcessor sproutAnnotationBeanPostProcessor() {
        return new SproutAnnotationBeanPostProcessor();
    }

    // Shuts the Sprout container down when the Spring context closes.
    @Bean
    DisposableBean sproutContainerShutdown(SproutContainer sproutContainer) {
        return sproutContainer::shutdown;
    }

    // Bridges Sprout events and Spring's application-event system both ways, so @EventListener beans see
    // Sprout events and Sprout subscribers see Spring-published ones. The bus comes from the container
    // (it is also exposed as the "eventBus" bean) rather than being injected by type, to stay
    // unambiguous when a custom @EventBus carries its own name too.
    @Bean
    @ConditionalOnMissingBean
    SpringEventBridge sproutSpringEventBridge(SproutContainer sproutContainer, ApplicationEventPublisher publisher) {
        return new SpringEventBridge(sproutContainer.eventBus(), publisher);
    }
}
