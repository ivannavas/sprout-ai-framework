package io.github.ivannavas.sprout.spring.mixed;

import io.github.ivannavas.sprout.annotation.Service;
import io.github.ivannavas.sprout.spring.reverse.SpringGreeter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * A Sprout-managed {@code @Service} that wires itself with Spring's {@code @Autowired} and
 * {@code @Value} alongside a Sprout {@code @Value} — exercising that same-named annotations from
 * either framework are all honoured within one Sprout component.
 */
@Service
public class MixedAnnotationService {

    @Autowired // Spring's @Autowired, resolving a Spring bean through the external resolver
    private SpringGreeter greeter;

    @Value("${mixed.greeting}") // Spring's @Value
    private String springValue;

    @io.github.ivannavas.sprout.annotation.Value("${mixed.other}") // Sprout's @Value
    private String sproutValue;

    public String greeting() {
        return greeter.greet();
    }

    public String springValue() {
        return springValue;
    }

    public String sproutValue() {
        return sproutValue;
    }
}
