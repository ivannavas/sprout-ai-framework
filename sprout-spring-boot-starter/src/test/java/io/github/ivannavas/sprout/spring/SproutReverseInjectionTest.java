package io.github.ivannavas.sprout.spring;

import io.github.ivannavas.sprout.spring.reverse.SpringGreeter;
import io.github.ivannavas.sprout.spring.reverse.SproutConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class SproutReverseInjectionTest {

    @Test
    void sproutComponentCanInjectSpringBean() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SproutAutoConfiguration.class))
                .withUserConfiguration(SpringBeans.class)
                .withPropertyValues("sprout.scan.base-packages=io.github.ivannavas.sprout.spring.reverse")
                .run(context -> {
                    SproutConsumer consumer = context.getBean(SproutConsumer.class);
                    assertThat(consumer.delegate()).isEqualTo("hi from spring");
                });
    }

    @Configuration
    static class SpringBeans {
        @Bean
        SpringGreeter springGreeter() {
            return new SpringGreeter();
        }
    }
}
