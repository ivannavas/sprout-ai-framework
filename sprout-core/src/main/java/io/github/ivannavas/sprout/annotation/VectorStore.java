package io.github.ivannavas.sprout.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an {@link io.github.ivannavas.sprout.abstrct.AbstractVectorStore} implementation as a managed
 * component, so it can be referenced as an {@link Agent#vectorStore() agent vector store} for RAG and
 * injected elsewhere (e.g. into an indexing service that populates it).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Component
public @interface VectorStore {
}
