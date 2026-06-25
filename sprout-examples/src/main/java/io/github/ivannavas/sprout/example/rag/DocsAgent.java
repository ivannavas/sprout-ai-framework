package io.github.ivannavas.sprout.example.rag;

import io.github.ivannavas.sprout.annotation.Agent;
import io.github.ivannavas.sprout.executor.AgentExecutor;
import io.github.ivannavas.sprout.impl.HashingEmbeddingModel;
import io.github.ivannavas.sprout.impl.InMemoryVectorStore;

/**
 * A documentation agent with RAG enabled. It reuses the two built-in defaults shipped in
 * {@code sprout-core} — {@link InMemoryVectorStore} and {@link HashingEmbeddingModel} — as its retrieval
 * stack: before each turn the agent embeds the question, pulls the {@code retrievalTopK} closest passages
 * from the store and prepends them to the prompt. The very same store singleton is populated at startup by
 * {@link KnowledgeBaseService}, which is what makes retrieval find anything.
 */
@Agent(
        model = KnowledgeBaseModel.class,
        vectorStore = InMemoryVectorStore.class,
        embeddingModel = HashingEmbeddingModel.class,
        retrievalTopK = 2,
        systemPrompt = "You answer questions about the Sprout framework using the retrieved context."
)
public class DocsAgent extends AgentExecutor {
}
