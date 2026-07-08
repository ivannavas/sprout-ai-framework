package io.github.ivannavas.sprout.spring;

import io.github.ivannavas.sprout.spring.reversemixed.SpringConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class SproutReverseAnnotationsTest {

    @Test
    void springBeanHonoursSproutAnnotations() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SproutAutoConfiguration.class))
                .withUserConfiguration(SpringBeans.class)
                .withPropertyValues(
                        "sprout.scan.base-packages=io.github.ivannavas.sprout.spring.reversemixed",
                        "reverse.greeting=hello-from-config")
                .run(context -> {
                    SpringConsumer consumer = context.getBean(SpringConsumer.class);
                    // Sprout's @Autowired resolved the Sprout-managed bean into the Spring bean.
                    assertThat(consumer.delegate()).isEqualTo("hi from sprout");
                    // Sprout's @Value was resolved from configuration.
                    assertThat(consumer.greeting()).isEqualTo("hello-from-config");
                    // Sprout's @PostConstruct ran after wiring.
                    assertThat(consumer.initialised()).isTrue();
                });
    }

    @Configuration
    static class SpringBeans {
        @Bean
        SpringConsumer springConsumer() {
            return new SpringConsumer();
        }
    }
}
