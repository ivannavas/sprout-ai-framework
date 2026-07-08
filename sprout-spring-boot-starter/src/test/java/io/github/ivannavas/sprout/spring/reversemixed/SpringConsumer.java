package io.github.ivannavas.sprout.spring.reversemixed;

import io.github.ivannavas.sprout.annotation.Autowired;
import io.github.ivannavas.sprout.annotation.PostConstruct;
import io.github.ivannavas.sprout.annotation.Value;

/**
 * A Spring-managed bean wired entirely with Sprout's DI annotations, exercising that Spring beans
 * can consume Sprout components and properties through the starter's post-processor.
 */
public class SpringConsumer {

    @Autowired // Sprout's @Autowired, resolving a Sprout-managed bean
    private SproutGreeter greeter;

    @Value("${reverse.greeting}") // Sprout's @Value
    private String greeting;

    private boolean initialised;

    @PostConstruct // Sprout's @PostConstruct
    void init() {
        this.initialised = true;
    }

    public String delegate() {
        return greeter.greet();
    }

    public String greeting() {
        return greeting;
    }

    public boolean initialised() {
        return initialised;
    }
}
