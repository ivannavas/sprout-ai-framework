package io.github.ivannavas.sprout.containertest.cycle;

import io.github.ivannavas.sprout.SproutApplication;
import io.github.ivannavas.sprout.annotation.Autowired;
import io.github.ivannavas.sprout.annotation.Component;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Isolated in its own package so the cyclic beans only affect this test's container.
class CircularDependencyTest {

    @Component
    public static class A {
        final B b;

        @Autowired
        public A(B b) {
            this.b = b;
        }
    }

    @Component
    public static class B {
        final A a;

        @Autowired
        public B(A a) {
            this.a = a;
        }
    }

    @Test
    void bootstrapFailsOnConstructorCycle() {
        RuntimeException error = assertThrows(RuntimeException.class,
                () -> SproutApplication.run(CircularDependencyTest.class));
        assertTrue(messageChain(error).contains("circular"),
                "the failure should explain the circular dependency, was: " + messageChain(error));
    }

    private static String messageChain(Throwable t) {
        StringBuilder chain = new StringBuilder();
        for (Throwable current = t; current != null; current = current.getCause()) {
            chain.append(String.valueOf(current.getMessage()).toLowerCase()).append(" | ");
        }
        return chain.toString();
    }
}
