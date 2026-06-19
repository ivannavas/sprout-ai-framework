package io.github.ivannavas.sprout.container;

/**
 * Optional hook that lets an embedding container (such as the Spring Boot starter) supply beans
 * that Sprout's own container cannot resolve.
 *
 * <p>It is consulted by {@link SproutContainer} only as a fallback: a dependency is first looked up
 * among Sprout-managed components, and the external resolver is queried solely when no Sprout bean
 * matches. Implementations should return {@code null} when they cannot provide a bean, so that
 * Sprout can raise its usual "no bean found" error.
 */
public interface ExternalBeanResolver {

    /**
     * @return a bean assignable to {@code type}, or {@code null} if the external container has none.
     */
    Object resolveByType(Class<?> type);

    /**
     * @return a bean registered under {@code name}, or {@code null} if the external container has none.
     */
    Object resolveByName(String name);
}
