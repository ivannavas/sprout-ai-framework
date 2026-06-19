package io.github.ivannavas.sprout.containertest.di;

import io.github.ivannavas.sprout.SproutApplication;
import io.github.ivannavas.sprout.annotation.Autowired;
import io.github.ivannavas.sprout.annotation.Component;
import io.github.ivannavas.sprout.annotation.PostConstruct;
import io.github.ivannavas.sprout.annotation.Qualifier;
import io.github.ivannavas.sprout.annotation.Value;
import io.github.ivannavas.sprout.container.SproutContainer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Lives in its own package so component scanning (which scans the entry point's package) only picks
// up the beans declared here.
class ContainerWiringTest {

    @Component
    public static class Repository {
        String describe() {
            return "repo";
        }
    }

    @Component
    public static class PrimaryGreeter {
        String greet() {
            return "primary";
        }
    }

    @Component
    public static class SecondaryGreeter {
        String greet() {
            return "secondary";
        }
    }

    @Component
    public static class Service {
        @Autowired
        Repository repository;

        @Qualifier("secondaryGreeter")
        @Autowired
        Object greeter;

        @Value("${greeting:hello}")
        String greeting;

        boolean initialized;

        @PostConstruct
        void init() {
            initialized = true;
        }
    }

    @Test
    void injectsByType_resolvesQualifier_bindsValue_andRunsPostConstruct() {
        SproutContainer container = SproutApplication.run(ContainerWiringTest.class);

        Service service = container.getSingleton(Service.class);
        assertNotNull(service, "the scanned @Component should be managed");

        // @Autowired by type returns the same singleton the container holds.
        assertSame(container.getSingleton(Repository.class), service.repository);
        // @Qualifier selects a specific bean by name.
        assertSame(container.getSingleton(SecondaryGreeter.class), service.greeter);
        // @Value resolves the placeholder default.
        assertEquals("hello", service.greeting);
        // @PostConstruct ran after injection.
        assertTrue(service.initialized);
        // The bean is also addressable by its conventional name.
        assertSame(service, container.getSingleton("service"));
    }
}
