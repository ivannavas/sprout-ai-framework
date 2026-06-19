package io.github.ivannavas.sprout.spring.reverse;

/**
 * A plain Spring bean (registered via the test configuration) that a Sprout component injects.
 */
public class SpringGreeter {

    public String greet() {
        return "hi from spring";
    }
}
