package io.github.ivannavas.sprout.containertest.ctor;

import io.github.ivannavas.sprout.SproutApplication;
import io.github.ivannavas.sprout.annotation.Autowired;
import io.github.ivannavas.sprout.annotation.Component;
import io.github.ivannavas.sprout.annotation.Value;
import io.github.ivannavas.sprout.container.SproutContainer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

// Own package so component scanning only picks up the beans declared here.
class ConstructorValueInjectionTest {

    @Component
    public static class Repository {
    }

    @Component
    public static class Service {
        final Repository repository;
        final String greeting;
        final int max;

        @Autowired
        public Service(Repository repository,
                       @Value("${greeting:hello}") String greeting,
                       @Value("${max:7}") int max) {
            this.repository = repository;
            this.greeting = greeting;
            this.max = max;
        }
    }

    @Test
    void constructorInjectionResolvesBeansAndValueParameters() {
        SproutContainer container = SproutApplication.run(ConstructorValueInjectionTest.class);

        Service service = container.getSingleton(Service.class);
        // A plain parameter is resolved as a bean by type...
        assertSame(container.getSingleton(Repository.class), service.repository);
        // ...while @Value parameters bind configuration, converted to the parameter's type.
        assertEquals("hello", service.greeting);
        assertEquals(7, service.max);
    }
}
