# sprout-pgvector

A durable, database-backed vector store for Sprout's RAG: `PgVectorStore` indexes and searches document
embeddings in **PostgreSQL** using the [pgvector](https://github.com/pgvector/pgvector) extension. It is a
drop-in replacement for `sprout-core`'s in-memory store — the same `@VectorStore` SPI — so an agent gains
persistent, scalable retrieval without any code change beyond naming the store.

## What it provides

`PgVectorStore` implements `AbstractVectorStore` and is marked `@VectorStore`, so the container discovers it
automatically once the jar is on the classpath (it contributes its own scan package). Documents are stored
in a table whose `embedding` column is a pgvector `vector`; similarity search runs **in the database**, ranked
by an HNSW index, and returns the nearest documents with a similarity `score` on the same scale as the
in-memory store (cosine similarity by default).

The extension, table and index are created **on first use**, so nothing is required at startup — an
unconfigured or unreachable database only fails when the store is actually called (the same
config-optional-at-startup contract as the model/embedding providers).

## Requirements

- PostgreSQL with the [pgvector](https://github.com/pgvector/pgvector) extension available (`CREATE EXTENSION vector`).
  The store runs `CREATE EXTENSION IF NOT EXISTS vector`, which needs a role allowed to create it (or an
  extension a superuser has already installed).
- pgvector ≥ 0.5.0 for the HNSW index.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `pgvector.url` | — (required) | JDBC URL, e.g. `jdbc:postgresql://localhost:5432/mydb`. |
| `pgvector.username` | — | Database user (optional if the URL carries credentials). |
| `pgvector.password` | — | Database password. |
| `pgvector.table` | `sprout_documents` | Table name (a plain SQL identifier). |
| `pgvector.dimension` | inferred | Embedding size; inferred from the first indexed document when unset. |
| `pgvector.distance` | `cosine` | Similarity metric: `cosine`, `l2` or `inner_product`. |

Set these in `sprout.properties`, system properties or environment variables (Spring's `Environment` too,
under the Spring Boot starter) — the usual Sprout configuration sources.

## Usage

Point an agent at the store and index into the **same managed bean**:

```java
@Agent(
        model = AnthropicModelExecutor.class,
        vectorStore = PgVectorStore.class,
        embeddingModel = VoyageEmbeddingModel.class,
        retrievalTopK = 4)
public class DocsAgent extends AgentExecutor {
}
```

```java
SproutContainer container = SproutApplication.run(MyApp.class);

// Index your documents once, into the same PgVectorStore the agent retrieves from.
EmbeddingModel embeddings = container.getSingleton("voyageEmbeddingModel");
AbstractVectorStore store = container.getSingleton("pgVectorStore");
new Retriever(embeddings, store, 4).index(List.of(
        Document.of("intro", "Sprout is a dependency-injection framework for AI agents in Java."),
        Document.of("rag",   "An agent enables RAG by declaring a vector store and an embedding model.")));

// The agent now grounds its answers in whatever is indexed in PostgreSQL.
AgentExecutor agent = container.getSingleton("docsAgentExecutor");
System.out.println(agent.execute("s1", "How does an agent enable RAG?").response());
```

Re-indexing a document with an existing `id` upserts it (replaces text, metadata and embedding). Document
`metadata` is stored as `jsonb`.

## Connections

The store opens a JDBC connection per operation via `DriverManager` (a batch `add` uses one connection for
the whole batch). That keeps the module dependency-light and safe for concurrent use. For high throughput,
front your database with a connection pooler (e.g. PgBouncer) — the JDBC URL points at it transparently.

## Notes

The unit tests cover the database-free surface (vector formatting/parsing, identifier validation, metric
resolution); exercising the JDBC path needs a real PostgreSQL + pgvector instance.
