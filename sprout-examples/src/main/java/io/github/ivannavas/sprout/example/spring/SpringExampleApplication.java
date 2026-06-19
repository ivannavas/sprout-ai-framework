package io.github.ivannavas.sprout.example.spring;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Standard Spring Boot entry point. Because {@code sprout-spring-boot-starter} is on the classpath,
 * the Sprout container is bootstrapped automatically and its beans are exposed to Spring.
 */
@SpringBootApplication
public class SpringExampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringExampleApplication.class, args);
    }
}
