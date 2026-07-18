package io.github.ivannavas.sprout.containertest.lifecycleorder;

import io.github.ivannavas.sprout.SproutApplication;
import io.github.ivannavas.sprout.annotation.Autowired;
import io.github.ivannavas.sprout.annotation.Component;
import io.github.ivannavas.sprout.annotation.PostConstruct;
import io.github.ivannavas.sprout.annotation.Value;
import io.github.ivannavas.sprout.container.SproutContainer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

// Own package so component scanning only picks up the beans declared here.
// @PostConstruct exists to derive state from what was injected - opening a connection from an @Value url,
// building a collaborator out of two @Autowired ones. That only works if injection has already happened when
// the callback fires, so the lifecycle order is part of the contract, not an implementation detail.
class PostConstructSeesInjectedFieldsTest {

    @Component
    public static class Repository {
    }

    /** Records what the callback could see, rather than asserting inside it, so a failure reads clearly. */
    public static class ServiceBase {
        @Autowired
        Repository inheritedRepository;

        Repository repositorySeenByInheritedCallback;

        @PostConstruct
        void inheritedInit() {
            repositorySeenByInheritedCallback = inheritedRepository;
        }
    }

    @Component
    public static class ConcreteService extends ServiceBase {
        @Autowired
        Repository ownRepository;

        @Value("${greeting:hello}")
        String greeting;

        Repository repositorySeenByCallback;
        String greetingSeenByCallback;

        @PostConstruct
        void init() {
            repositorySeenByCallback = ownRepository;
            greetingSeenByCallback = greeting;
        }
    }

    private static SproutContainer container() {
        return SproutApplication.run(PostConstructSeesInjectedFieldsTest.class);
    }

    @Test
    void postConstructSeesAutowiredFields() {
        ConcreteService service = container().getSingleton(ConcreteService.class);

        assertNotNull(service.repositorySeenByCallback,
                "@PostConstruct should run after @Autowired fields are injected, not before");
    }

    @Test
    void postConstructSeesValueFields() {
        ConcreteService service = container().getSingleton(ConcreteService.class);

        assertEquals("hello", service.greetingSeenByCallback,
                "@PostConstruct should run after @Value fields are bound, not before");
    }

    @Test
    void inheritedPostConstructSeesInheritedAutowiredFields() {
        ConcreteService service = container().getSingleton(ConcreteService.class);

        assertNotNull(service.repositorySeenByInheritedCallback,
                "a base class @PostConstruct should see the base class @Autowired fields");
    }
}
