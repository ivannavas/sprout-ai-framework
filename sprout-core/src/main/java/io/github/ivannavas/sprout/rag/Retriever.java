package io.github.ivannavas.sprout.rag;

import io.github.ivannavas.sprout.abstrct.AbstractVectorStore;
import io.github.ivannavas.sprout.embedding.EmbeddingModel;
import io.github.ivannavas.sprout.model.Document;
import io.github.ivannavas.sprout.model.SearchResult;

import java.util.List;

/**
 * Ties an {@link EmbeddingModel} to an {@link AbstractVectorStore} to perform both halves of RAG:
 * {@link #index(Document) indexing} embeds a document's text before storing it, and
 * {@link #retrieve(String) retrieval} embeds a query before searching. An agent that declares a
 * {@link io.github.ivannavas.sprout.annotation.Agent#vectorStore() vector store} gets one of these built
 * for it and consults it before each turn; application code can construct one directly to populate the
 * store ahead of time.
 */
public final class Retriever {

    private final EmbeddingModel embeddingModel;
    private final AbstractVectorStore vectorStore;
    private final int topK;

    public Retriever(EmbeddingModel embeddingModel, AbstractVectorStore vectorStore, int topK) {
        this.embeddingModel = embeddingModel;
        this.vectorStore = vectorStore;
        this.topK = topK;
    }

    /** Embeds {@code document}'s text (replacing any embedding it already carries) and indexes it. */
    public void index(Document document) {
        vectorStore.add(document.withEmbedding(embeddingModel.embed(document.text())));
    }

    /** Embeds each document's text in one batch and indexes them all. */
    public void index(List<Document> documents) {
        List<String> texts = documents.stream().map(Document::text).toList();
        List<float[]> vectors = embeddingModel.embedAll(texts);
        List<Document> embedded = new java.util.ArrayList<>(documents.size());
        for (int i = 0; i < documents.size(); i++) {
            embedded.add(documents.get(i).withEmbedding(vectors.get(i)));
        }
        vectorStore.add(embedded);
    }

    /** Embeds {@code query} and returns the {@code topK} most similar indexed documents. */
    public List<SearchResult> retrieve(String query) {
        return vectorStore.search(embeddingModel.embed(query), topK);
    }

    public int topK() {
        return topK;
    }
}
