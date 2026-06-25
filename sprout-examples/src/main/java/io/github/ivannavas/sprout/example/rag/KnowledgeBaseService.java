package io.github.ivannavas.sprout.example.rag;

import io.github.ivannavas.sprout.annotation.Autowired;
import io.github.ivannavas.sprout.annotation.PostConstruct;
import io.github.ivannavas.sprout.annotation.Service;
import io.github.ivannavas.sprout.impl.HashingEmbeddingModel;
import io.github.ivannavas.sprout.impl.InMemoryVectorStore;
import io.github.ivannavas.sprout.model.Document;
import io.github.ivannavas.sprout.rag.Retriever;

import java.util.List;

/**
 * Loads the agent's knowledge base at startup. It constructor-injects the very same {@code sprout-core}
 * defaults the {@link DocsAgent} retrieves from — the {@link InMemoryVectorStore} and
 * {@link HashingEmbeddingModel} singletons — and wraps them in a {@link Retriever} to index a handful of
 * documents. Because the store is a shared managed bean, everything indexed here is what the agent finds
 * when it queries. This is the standard RAG split: ingestion is one concern, querying another, joined by
 * one store instance.
 */
@Service
public class KnowledgeBaseService {

    private final Retriever retriever;

    @Autowired
    public KnowledgeBaseService(HashingEmbeddingModel embeddingModel, InMemoryVectorStore vectorStore) {
        // topK only matters for retrieval (the agent's job); for indexing any value is fine.
        this.retriever = new Retriever(embeddingModel, vectorStore, 1);
    }

    @PostConstruct
    void loadDocuments() {
        retriever.index(List.of(
                Document.of("intro",
                        "Sprout is a dependency injection framework for building AI agents in Java, "
                                + "using annotations such as Agent, Model and Service."),
                Document.of("rag",
                        "An agent enables RAG by declaring a vector store and an embedding model, then it "
                                + "retrieves the most relevant documents and adds them to the prompt before every turn."),
                Document.of("defaults",
                        "By default Sprout ships an in memory vector store that ranks documents by cosine "
                                + "similarity, and a hashing embedding model that turns text into vectors without any "
                                + "API key, so retrieval runs offline."),
                Document.of("spring",
                        "Sprout integrates with Spring Boot through the sprout spring boot starter module, so "
                                + "Spring beans and Sprout agents can inject each other.")));
    }
}
