package io.github.ivannavas.sprout.abstrct;

import io.github.ivannavas.sprout.model.Document;
import io.github.ivannavas.sprout.model.SearchResult;

import java.util.List;

/**
 * Stores {@link Document}s by their vector {@code embedding} and retrieves the ones nearest a query
 * vector — the retrieval half of a RAG pipeline. It works purely in embedding space: turning text into
 * vectors is the job of an {@link io.github.ivannavas.sprout.embedding.EmbeddingModel}, and
 * {@link io.github.ivannavas.sprout.rag.Retriever} ties the two together.
 *
 * <p>Implement it (and annotate with {@link io.github.ivannavas.sprout.annotation.VectorStore
 * &#64;VectorStore}) to back retrieval with a database such as pgvector or a managed vector service;
 * {@link io.github.ivannavas.sprout.impl.InMemoryVectorStore} is the default. An agent enables RAG by
 * naming a store in {@link io.github.ivannavas.sprout.annotation.Agent#vectorStore() &#64;Agent}.
 */
public interface AbstractVectorStore {

    /** Indexes documents, each of which must carry a non-null {@link Document#embedding()}. */
    void add(List<Document> documents);

    /** Indexes a single document; convenience for {@link #add(List)}. */
    default void add(Document document) {
        add(List.of(document));
    }

    /**
     * Returns up to {@code topK} indexed documents whose embeddings are closest to {@code queryEmbedding},
     * ordered from the best match down. Fewer are returned when the store holds fewer documents.
     */
    List<SearchResult> search(float[] queryEmbedding, int topK);
}
