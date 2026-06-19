package io.github.ivannavas.sprout.spi;

import io.github.ivannavas.sprout.SproutApplication;
import io.github.ivannavas.sprout.annotation.Autowired;
import io.github.ivannavas.sprout.annotation.Component;
import io.github.ivannavas.sprout.annotation.Processor;
import io.github.ivannavas.sprout.container.SproutContainer;
import io.github.ivannavas.sprout.processor.ComponentProcessor;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

// Exercises the public extension SPI from the outside: a custom stereotype + its ComponentProcessor,
// exactly as a third-party module would add one. This doubles as a reference example for the SPI.
class CustomProcessorSpiTest {

    /** A custom stereotype. Meta-annotated with {@code @Component} so the scanner discovers it. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @Component
    public @interface Plugin {
    }

    @Component
    public static class Helper {
        String hi() {
            return "hi";
        }
    }

    @Plugin
    public static class GreeterPlugin {
        @Autowired
        Helper helper;

        String greet() {
            return helper.hi();
        }
    }

    /** Registered for {@code @Plugin} via {@link Processor}; adds a name alias on top of the standard wiring. */
    @Processor(Plugin.class)
    public static class PluginProcessor extends ComponentProcessor {
        public PluginProcessor(Class<?> component, SproutContainer container) {
            super(component, container);
        }

        @Override
        public Set<String> beanNames() {
            Set<String> names = new HashSet<>(super.beanNames());
            names.add("plugin:" + component.getSimpleName());
            return names;
        }
    }

    @Test
    void thirdPartyProcessorIsDiscoveredAndCustomisesTheBean() {
        SproutContainer container = SproutApplication.run(CustomProcessorSpiTest.class);

        GreeterPlugin plugin = container.getSingleton("greeterPlugin");
        assertNotNull(plugin, "the custom stereotype should be scanned and managed");

        // The alias added by the custom processor resolves to the same singleton.
        assertSame(plugin, container.getSingleton("plugin:GreeterPlugin"));

        // The inherited @Autowired lifecycle still runs under the custom processor.
        assertEquals("hi", plugin.greet());
    }
}
