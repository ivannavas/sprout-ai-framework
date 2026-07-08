package io.github.ivannavas.sprout.spring.reversemixed;

import io.github.ivannavas.sprout.annotation.Service;

/**
 * A Sprout-managed component, exposed to Spring by the starter, that a Spring bean injects using
 * Sprout's own {@code @Autowired}.
 */
@Service
public class SproutGreeter {

    public String greet() {
        return "hi from sprout";
    }
}
