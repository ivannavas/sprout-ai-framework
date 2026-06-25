package io.github.ivannavas.sprout.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an {@link io.github.ivannavas.sprout.embedding.EmbeddingModel} implementation as a managed
 * component, so it can be referenced as an {@link Agent#embeddingModel() agent embedding model} for RAG
 * and injected into indexing code. The retrieval-side counterpart of {@link Model @Model}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Component
public @interface Embedding {
}
