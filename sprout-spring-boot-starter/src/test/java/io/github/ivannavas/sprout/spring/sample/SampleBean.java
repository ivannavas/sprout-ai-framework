package io.github.ivannavas.sprout.spring.sample;

import io.github.ivannavas.sprout.annotation.Service;

/**
 * A minimal Sprout-managed component used to verify that Sprout beans are exposed to Spring.
 */
@Service
public class SampleBean {

    public String hello() {
        return "hello from sprout";
    }
}
