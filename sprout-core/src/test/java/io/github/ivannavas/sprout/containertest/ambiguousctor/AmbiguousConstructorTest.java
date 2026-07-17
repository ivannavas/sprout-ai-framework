package io.github.ivannavas.sprout.containertest.ambiguousctor;

import io.github.ivannavas.sprout.SproutApplication;
import io.github.ivannavas.sprout.annotation.Component;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Own package: this component fails to start on purpose, so it must not disturb other tests' scanning.
// Falling back to "the only constructor" is unambiguous; several constructors with no way to choose is not,
// and that must be a clear error rather than an arbitrary pick.
class AmbiguousConstructorTest {

    @Component
    public static class Repository {
    }

    /** Two constructors, neither annotated, neither no-arg: the container cannot choose. */
    @Component
    public static class AmbiguousService {
        public AmbiguousService(Repository repository) {
        }

        public AmbiguousService(Repository repository, Repository other) {
        }
    }

    @Test
    void severalUnannotatedConstructorsWithNoNoArgIsAnError() {
        Throwable thrown = assertThrows(Throwable.class, () -> SproutApplication.run(AmbiguousConstructorTest.class));

        String message = messages(thrown);
        assertTrue(message.contains("AmbiguousService"),
                "the error should name the offending component, but was: " + message);
        assertTrue(message.contains("@Autowired"),
                "the error should say how to resolve the ambiguity, but was: " + message);
    }

    /** The failure can surface wrapped, so assert against the whole cause chain. */
    private static String messages(Throwable thrown) {
        StringBuilder all = new StringBuilder();
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            all.append(t.getMessage()).append('\n');
            if (t.getCause() == t) {
                break;
            }
        }
        assertNotNull(all.toString());
        return all.toString();
    }
}
