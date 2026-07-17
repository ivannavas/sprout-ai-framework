package io.github.ivannavas.sprout.containertest.hierarchy;

import io.github.ivannavas.sprout.SproutApplication;
import io.github.ivannavas.sprout.annotation.Autowired;
import io.github.ivannavas.sprout.annotation.Component;
import io.github.ivannavas.sprout.annotation.PostConstruct;
import io.github.ivannavas.sprout.annotation.Value;
import io.github.ivannavas.sprout.container.SproutContainer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

// Own package so component scanning only picks up the beans declared here.
// A component's wiring is declared across its class hierarchy (a base class holding shared collaborators,
// the concrete component on top), so @Autowired/@Value fields and @PostConstruct must be honoured wherever
// they are declared - not only on the most-derived class.
class InheritedWiringTest {

    @Component
    public static class Repository {
    }

    /** Base class: declares injected state and lifecycle the concrete component never repeats. */
    public static class ServiceBase {
        @Autowired
        Repository inheritedRepository;

        @Value("${greeting:hello}")
        String inheritedGreeting;

        final List<String> callbacks = new ArrayList<>();

        @PostConstruct
        void inheritedInit() {
            callbacks.add("base");
        }
    }

    @Component
    public static class ConcreteService extends ServiceBase {
        @Autowired
        Repository ownRepository;

        @PostConstruct
        void ownInit() {
            callbacks.add("own");
        }
    }

    /** A base @PostConstruct that the subclass overrides: the override must run, and only once. */
    public static class OverridingBase {
        final List<String> callbacks = new ArrayList<>();

        @PostConstruct
        void init() {
            callbacks.add("base-init");
        }
    }

    @Component
    public static class OverridingService extends OverridingBase {
        @Override
        @PostConstruct
        void init() {
            callbacks.add("override-init");
        }
    }

    private static SproutContainer container() {
        return SproutApplication.run(InheritedWiringTest.class);
    }

    @Test
    void autowiredFieldsAreInjectedAcrossTheHierarchy() {
        SproutContainer container = container();
        ConcreteService service = container.getSingleton(ConcreteService.class);
        Repository repository = container.getSingleton(Repository.class);

        assertSame(repository, service.ownRepository, "the component's own @Autowired field should be injected");
        assertSame(repository, service.inheritedRepository, "an @Autowired field on a base class should be injected too");
    }

    @Test
    void valueFieldsAreBoundAcrossTheHierarchy() {
        ConcreteService service = container().getSingleton(ConcreteService.class);

        assertEquals("hello", service.inheritedGreeting, "a @Value field on a base class should be bound");
    }

    @Test
    void postConstructRunsForOwnAndInheritedCallbacks() {
        ConcreteService service = container().getSingleton(ConcreteService.class);

        assertEquals(List.of("own", "base"), service.callbacks,
                "@PostConstruct should run once for the component's own and its inherited callback");
    }

    @Test
    void overriddenPostConstructRunsOnceAsTheOverride() {
        OverridingService service = container().getSingleton(OverridingService.class);

        // The base and the override share a signature: they are one method, so it must not fire twice.
        assertEquals(List.of("override-init"), service.callbacks,
                "an overridden @PostConstruct should run exactly once, as the override");
    }
}
