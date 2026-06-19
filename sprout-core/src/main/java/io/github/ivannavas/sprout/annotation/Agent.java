package io.github.ivannavas.sprout.annotation;

import io.github.ivannavas.sprout.impl.InMemoryConversationStore;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as an AI agent. The annotated class must extend
 * {@link io.github.ivannavas.sprout.executor.AgentExecutor} — just as a {@code @Model} class extends
 * {@code ModelExecutor} — so the agent <em>is</em> its own executor. The container instantiates it,
 * configures it from this annotation and registers it (also under the alias {@code <className>Executor},
 * e.g. {@code WeatherAgent} yields {@code weatherAgentExecutor}). The agent's own {@code @Tool}
 * methods become callable by the model; override {@code execute} to customise the loop.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Component
public @interface Agent {

    /** The {@link io.github.ivannavas.sprout.executor.ModelExecutor} type that backs this agent. */
    Class<?> model();

    /** Store used to persist conversation history, keyed by conversation id. */
    Class<?> conversationStore() default InMemoryConversationStore.class;

    /** System prompt prepended to a conversation's first turn. Empty means no system message. */
    String systemPrompt() default "";

    /** Maximum model/tool round-trips per {@code execute} call before giving up. */
    int maxIterations() default 10;
}
