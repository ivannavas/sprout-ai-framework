package io.github.ivannavas.sprout.example.rag;

import io.github.ivannavas.sprout.SproutApplication;
import io.github.ivannavas.sprout.container.SproutContainer;
import io.github.ivannavas.sprout.executor.AgentExecutor;

import java.util.List;

/**
 * Retrieval-augmented generation end to end: a {@link KnowledgeBaseService} indexes a few documents into
 * the built-in {@code InMemoryVectorStore} at startup, then {@link DocsAgent} answers questions by
 * retrieving the relevant ones and feeding them to its (offline) model. Everything is deterministic and
 * needs no API key.
 *
 * <p>Note the scan path: besides this example's own package it includes
 * {@code io.github.ivannavas.sprout.impl}, so {@code sprout-core}'s default {@code InMemoryVectorStore}
 * and {@code HashingEmbeddingModel} are registered as managed singletons — letting the indexing service
 * and the agent share one store. (Provide your own {@code @VectorStore}/{@code @Embedding} beans instead
 * to use a real database or a provider-backed embedding model.)
 */
public final class RagExampleApplication {

    private static final String DEFAULT_SCAN_PACKAGES =
            "io.github.ivannavas.sprout.example.rag,io.github.ivannavas.sprout.impl";

    public static void main(String[] args) {
        if (System.getProperty("sprout.scan.base-packages") == null) {
            System.setProperty("sprout.scan.base-packages", DEFAULT_SCAN_PACKAGES);
        }

        SproutContainer container = SproutApplication.run(RagExampleApplication.class);

        AgentExecutor docs = container.getSingleton("docsAgentExecutor");
        List<String> questions = List.of(
                "What does Sprout use dependency injection for?",
                "How does an agent retrieve relevant documents?",
                "What vector store and embedding model does Sprout ship by default?");

        System.out.println("== RAG: agent answers from an indexed knowledge base ==");
        for (String question : questions) {
            String answer = docs.execute(question, question).response();
            System.out.println("  " + question);
            System.out.println("    -> " + answer);
        }
    }
}
