package io.github.ivannavas.sprout.spring;

import io.github.ivannavas.sprout.spring.configdata.ConfigDataService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

// Reproduces a real Spring Boot app: the test property lives ONLY in a config-data file
// (sprout-configdata.properties), loaded via ConfigDataApplicationContextInitializer, NOT via
// withPropertyValues. A Sprout @Value must still resolve it — i.e. Spring's application.properties
// must be seeded into the Sprout container.
class SproutPropertyFromConfigDataTest {

    @Test
    void sproutValueResolvesFromApplicationProperties() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SproutAutoConfiguration.class))
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues(
                        "spring.config.name=sprout-configdata",
                        "sprout.scan.base-packages=io.github.ivannavas.sprout.spring.configdata")
                .run(context -> {
                    ConfigDataService svc = context.getBean(ConfigDataService.class);
                    assertThat(svc.value()).isEqualTo("from-app-properties");
                });
    }
}
