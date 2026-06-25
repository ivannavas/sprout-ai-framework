package io.github.ivannavas.sprout.annotation;

import io.github.ivannavas.sprout.abstrct.AbstractVectorStore;
import io.github.ivannavas.sprout.embedding.EmbeddingModel;
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

    /**
     * Vector store consulted for RAG: before each turn the agent retrieves the documents most relevant to
     * the user's prompt and prepends them as context. Left unset, the agent does no retrieval; when set,
     * {@link #embeddingModel()} must be set too. The named class should normally be a managed
     * {@code @VectorStore} bean so the same instance can be populated by indexing code.
     */
    Class<?> vectorStore() default AbstractVectorStore.class;

    /** Embedding model used to vectorise prompts (and documents) for {@link #vectorStore() RAG}. */
    Class<?> embeddingModel() default EmbeddingModel.class;

    /** How many documents RAG retrieval pulls in per turn. Ignored when no {@link #vectorStore()} is set. */
    int retrievalTopK() default 4;

    /** System prompt prepended to a conversation's first turn. Empty means no system message. */
    String systemPrompt() default "";

    /** Maximum model/tool round-trips per {@code execute} call before giving up. */
    int maxIterations() default 10;
}
