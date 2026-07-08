package io.github.ivannavas.sprout.spring;

import io.github.ivannavas.sprout.spring.mixed.MixedAnnotationService;
import io.github.ivannavas.sprout.spring.reverse.SpringGreeter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class SproutMixedAnnotationsTest {

    @Test
    void sproutComponentHonoursSpringAndSproutAnnotations() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SproutAutoConfiguration.class))
                .withUserConfiguration(SpringBeans.class)
                .withPropertyValues(
                        "sprout.scan.base-packages=io.github.ivannavas.sprout.spring.mixed",
                        "mixed.greeting=hello-spring",
                        "mixed.other=hello-sprout")
                .run(context -> {
                    MixedAnnotationService service = context.getBean(MixedAnnotationService.class);
                    // Spring's @Autowired resolved a Spring bean into the Sprout component.
                    assertThat(service.greeting()).isEqualTo("hi from spring");
                    // Both the Spring @Value and the Sprout @Value were resolved.
                    assertThat(service.springValue()).isEqualTo("hello-spring");
                    assertThat(service.sproutValue()).isEqualTo("hello-sprout");
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
