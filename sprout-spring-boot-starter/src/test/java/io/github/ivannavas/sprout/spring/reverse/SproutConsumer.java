package io.github.ivannavas.sprout.spring.reverse;

import io.github.ivannavas.sprout.annotation.Autowired;
import io.github.ivannavas.sprout.annotation.Service;

/**
 * A Sprout component that depends on a Spring bean, resolved via the external bean resolver as a
 * lazy proxy.
 */
@Service
public class SproutConsumer {

    @Autowired
    private SpringGreeter greeter;

    public String delegate() {
        return greeter.greet();
    }
}
