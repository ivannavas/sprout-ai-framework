package io.github.ivannavas.sprout.containertest.implicitctor;

import io.github.ivannavas.sprout.SproutApplication;
import io.github.ivannavas.sprout.annotation.Autowired;
import io.github.ivannavas.sprout.annotation.Component;
import io.github.ivannavas.sprout.annotation.Value;
import io.github.ivannavas.sprout.container.SproutContainer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

// Own package so component scanning only picks up the beans declared here.
// A component declaring a single constructor is injected through it without any annotation; @Autowired
// is only needed to choose between several.
class ImplicitConstructorInjectionTest {

    @Component
    public static class Repository {
    }

    /** The common case: one constructor, no annotation anywhere. */
    @Component
    public static class SingleConstructorService {
        final Repository repository;
        final String greeting;

        public SingleConstructorService(Repository repository, @Value("${greeting:hello}") String greeting) {
            this.repository = repository;
            this.greeting = greeting;
        }
    }

    /** A single constructor that happens to take nothing still works. */
    @Component
    public static class SingleNoArgService {
        final String marker = "built";
    }

    /** Several constructors and none annotated: the no-arg one is the unambiguous choice. */
    @Component
    public static class MultipleConstructorsService {
        Repository repository;

        public MultipleConstructorsService() {
        }

        public MultipleConstructorsService(Repository repository) {
            this.repository = repository;
        }
    }

    /** @Autowired still decides when several constructors compete. */
    @Component
    public static class AnnotatedChoiceService {
        final Repository repository;
        final boolean viaAnnotated;

        public AnnotatedChoiceService() {
            this.repository = null;
            this.viaAnnotated = false;
        }

        @Autowired
        public AnnotatedChoiceService(Repository repository) {
            this.repository = repository;
            this.viaAnnotated = true;
        }
    }

    private static SproutContainer container() {
        return SproutApplication.run(ImplicitConstructorInjectionTest.class);
    }

    @Test
    void singleConstructorIsInjectedWithoutAnnotation() {
        SproutContainer container = container();
        SingleConstructorService service = container.getSingleton(SingleConstructorService.class);

        assertSame(container.getSingleton(Repository.class), service.repository,
                "the only constructor should be used for injection without @Autowired");
        assertEquals("hello", service.greeting, "@Value parameters should still bind on an unannotated constructor");
    }

    @Test
    void singleNoArgConstructorStillWorks() {
        assertEquals("built", container().getSingleton(SingleNoArgService.class).marker);
    }

    @Test
    void noArgConstructorWinsWhenSeveralCompeteUnannotated() {
        MultipleConstructorsService service = container().getSingleton(MultipleConstructorsService.class);

        assertNull(service.repository,
                "with several unannotated constructors the no-arg one should be chosen, leaving nothing injected");
    }

    @Test
    void annotatedConstructorWinsOverTheNoArgOne() {
        SproutContainer container = container();
        AnnotatedChoiceService service = container.getSingleton(AnnotatedChoiceService.class);

        assertEquals(true, service.viaAnnotated, "@Autowired should still pick the constructor");
        assertSame(container.getSingleton(Repository.class), service.repository);
    }
}
