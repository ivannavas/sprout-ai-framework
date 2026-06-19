package io.github.ivannavas.sprout.spring;

import io.github.ivannavas.sprout.container.SproutContainer;
import io.github.ivannavas.sprout.spring.sample.SampleBean;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class SproutAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SproutAutoConfiguration.class))
            .withPropertyValues("sprout.scan.base-packages=io.github.ivannavas.sprout.spring.sample");

    @Test
    void exposesSproutContainerAndScannedComponentsAsSpringBeans() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(SproutContainer.class);
            assertThat(context).hasSingleBean(SampleBean.class);
            assertThat(context.getBean(SampleBean.class).hello()).isEqualTo("hello from sprout");
        });
    }

    @Test
    void bridgesSpringEnvironmentIntoSproutProperties() {
        runner.withPropertyValues("greeting.message=Hola").run(context -> {
            SproutContainer container = context.getBean(SproutContainer.class);
            assertThat(container.getProperty("greeting.message")).isEqualTo("Hola");
        });
    }

    @Test
    void canBeDisabled() {
        runner.withPropertyValues("sprout.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(SproutContainer.class);
            assertThat(context).doesNotHaveBean(SampleBean.class);
        });
    }
}
