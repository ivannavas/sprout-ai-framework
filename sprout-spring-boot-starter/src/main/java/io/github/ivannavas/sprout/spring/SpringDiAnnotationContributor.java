package io.github.ivannavas.sprout.spring;

import io.github.ivannavas.sprout.processor.DiAnnotationContributor;

import java.util.Set;

/**
 * Teaches Sprout's DI wiring to treat Spring's and JSR-330's annotations as equivalents of its own,
 * so a Sprout-managed component can be wired with, say, Spring's {@code @Autowired} or {@code @Value}
 * alongside Sprout's. Discovered via {@link java.util.ServiceLoader} (declared in
 * {@code META-INF/services}); the FQNs live here rather than in core so core stays free of any
 * dependency on Spring or the inject API. JSR-330 ({@code jakarta.inject}/{@code javax.inject}) and
 * {@code jakarta.annotation}/{@code javax.annotation} arrive as part of the Spring ecosystem, so
 * they are contributed here too.
 */
public final class SpringDiAnnotationContributor implements DiAnnotationContributor {

    @Override
    public Set<String> autowired() {
        return Set.of(
                "org.springframework.beans.factory.annotation.Autowired",
                "jakarta.inject.Inject",
                "javax.inject.Inject");
    }

    @Override
    public Set<String> value() {
        return Set.of("org.springframework.beans.factory.annotation.Value");
    }

    @Override
    public Set<String> qualifier() {
        return Set.of(
                "org.springframework.beans.factory.annotation.Qualifier",
                "jakarta.inject.Named",
                "javax.inject.Named");
    }

    @Override
    public Set<String> postConstruct() {
        return Set.of(
                "jakarta.annotation.PostConstruct",
                "javax.annotation.PostConstruct");
    }
}
