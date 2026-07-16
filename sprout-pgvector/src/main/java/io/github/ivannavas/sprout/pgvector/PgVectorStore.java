package io.github.ivannavas.sprout.pgvector;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ivannavas.sprout.abstrct.AbstractVectorStore;
import io.github.ivannavas.sprout.annotation.Autowired;
import io.github.ivannavas.sprout.annotation.VectorStore;
import io.github.ivannavas.sprout.annotation.Value;
import io.github.ivannavas.sprout.model.Document;
import io.github.ivannavas.sprout.model.SearchResult;
import org.postgresql.util.PGobject;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Durable {@link AbstractVectorStore} backed by PostgreSQL and the
 * <a href="https://github.com/pgvector/pgvector">pgvector</a> extension: documents live in a table whose
 * {@code embedding} column is a {@code vector}, and similarity search runs in the database with an HNSW
 * index — the persistent, scalable counterpart to {@code sprout-core}'s in-memory store. Point an agent
 * at it with {@code @Agent(vectorStore = PgVectorStore.class, ...)} and share the same managed bean with
 * the code that indexes your documents.
 *
 * <p>Configure at minimum {@code pgvector.url} (a JDBC URL, e.g.
 * {@code jdbc:postgresql://localhost:5432/mydb}); {@code pgvector.username} and {@code pgvector.password}
 * are optional if the URL carries credentials. Optional knobs: {@code pgvector.table} (default
 * {@code sprout_documents}), {@code pgvector.dimension} (embedding size; inferred from the first indexed
 * document when left unset) and {@code pgvector.distance} ({@code cosine} (default), {@code l2} or
 * {@code inner_product}). The extension, table and index are created on demand, so no configuration is
 * required at startup — a missing or unreachable database only fails when the store is actually used.
 */
@VectorStore
public class PgVectorStore implements AbstractVectorStore {

    private final String url;
    private final String username;
    private final String password;
    private final String table;
    private final int dimension;
    private final String distance;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public PgVectorStore(
            @Value("${pgvector.url:}") String url,
            @Value("${pgvector.username:}") String username,
            @Value("${pgvector.password:}") String password,
            @Value("${pgvector.table:sprout_documents}") String table,
            @Value("${pgvector.dimension:0}") int dimension,
            @Value("${pgvector.distance:cosine}") String distance) {
        this.url = url;
        this.username = username;
        this.password = password;
        this.table = table;
        this.dimension = dimension;
        this.distance = distance;
    }

    // Guards lazy schema creation: the extension/table/index are created once, on first use.
    private volatile boolean schemaReady;

    @Override
    public void add(List<Document> documents) {
        if (documents.isEmpty()) {
            return;
        }
        for (Document document : documents) {
            if (document.embedding() == null) {
                throw new IllegalArgumentException("Document " + document.id() + " has no embedding; embed it before indexing");
            }
        }
        int dim = dimension > 0 ? dimension : documents.getFirst().embedding().length;
        ensureSchema(dim);

        String sql = "INSERT INTO " + table + " (id, text, metadata, embedding) VALUES (?, ?, ?, ?) "
                + "ON CONFLICT (id) DO UPDATE SET text = EXCLUDED.text, metadata = EXCLUDED.metadata, embedding = EXCLUDED.embedding";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Document document : documents) {
                statement.setString(1, document.id());
                statement.setString(2, document.text());
                statement.setObject(3, jsonb(writeMetadata(document.metadata())));
                statement.setObject(4, vector(document.embedding()));
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to index " + documents.size() + " document(s) into " + table, e);
        }
    }

    @Override
    public List<SearchResult> search(float[] queryEmbedding, int topK) {
        if (topK <= 0) {
            return List.of();
        }
        // Nothing can have been indexed yet if the schema was never created and no dimension is known.
        if (!schemaReady && dimension <= 0) {
            return List.of();
        }
        ensureSchema(dimension > 0 ? dimension : queryEmbedding.length);

        Distance metric = Distance.from(distance);
        String sql = "SELECT id, text, metadata, embedding, " + metric.scoreExpression() + " AS score "
                + "FROM " + table + " ORDER BY embedding " + metric.operator() + " ? LIMIT ?";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, vector(queryEmbedding));   // score expression
            statement.setObject(2, vector(queryEmbedding));   // ORDER BY distance
            statement.setInt(3, topK);
            try (ResultSet rs = statement.executeQuery()) {
                List<SearchResult> results = new ArrayList<>();
                while (rs.next()) {
                    Document document = new Document(
                            rs.getString("id"),
                            rs.getString("text"),
                            readMetadata(rs.getString("metadata")),
                            parseVector(rs.getString("embedding")));
                    results.add(new SearchResult(document, rs.getDouble("score")));
                }
                return results;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Similarity search against " + table + " failed", e);
        }
    }

    // --- schema ---------------------------------------------------------------------------------------

    private void ensureSchema(int dim) {
        if (schemaReady) {
            return;
        }
        synchronized (this) {
            if (schemaReady) {
                return;
            }
            validateTable();
            Distance metric = Distance.from(distance);
            try (Connection connection = openConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
                statement.execute("CREATE TABLE IF NOT EXISTS " + table + " ("
                        + "id TEXT PRIMARY KEY, "
                        + "text TEXT NOT NULL, "
                        + "metadata JSONB NOT NULL DEFAULT '{}'::jsonb, "
                        + "embedding vector(" + dim + ") NOT NULL)");
                statement.execute("CREATE INDEX IF NOT EXISTS " + table + "_embedding_idx ON " + table
                        + " USING hnsw (embedding " + metric.indexOps() + ")");
            } catch (SQLException e) {
                throw new RuntimeException("Failed to initialise pgvector schema for table " + table, e);
            }
            schemaReady = true;
        }
    }

    private Connection openConnection() throws SQLException {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("pgvector is not configured. Set 'pgvector.url' to a JDBC URL, "
                    + "for example jdbc:postgresql://localhost:5432/mydb.");
        }
        if (username != null && !username.isBlank()) {
            return DriverManager.getConnection(url, username, password == null ? "" : password);
        }
        return DriverManager.getConnection(url);
    }

    /** Wraps a pgvector text literal in a typed {@code vector} parameter so the driver binds it correctly. */
    private static PGobject vector(float[] embedding) {
        PGobject object = new PGobject();
        object.setType("vector");
        try {
            object.setValue(formatVector(embedding));
        } catch (SQLException e) {
            throw new RuntimeException("Failed to bind vector parameter", e);
        }
        return object;
    }

    /** Wraps a JSON string in a typed {@code jsonb} parameter. */
    private static PGobject jsonb(String json) {
        PGobject object = new PGobject();
        object.setType("jsonb");
        try {
            object.setValue(json);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to bind jsonb parameter", e);
        }
        return object;
    }

    private String writeMetadata(Map<String, String> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialise document metadata", e);
        }
    }

    private Map<String, String> readMetadata(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse document metadata: " + json, e);
        }
    }

    /**
     * The table name is interpolated into DDL/DML, so it must be a plain SQL identifier — validated here to
     * keep configuration from injecting SQL. Called once the effective table name is set.
     */
    private void validateTable() {
        if (!isValidIdentifier(table)) {
            throw new IllegalStateException("Invalid 'pgvector.table' value: " + table
                    + ". Use letters, digits and underscores, starting with a letter or underscore.");
        }
    }

    // --- pure helpers (no database) -------------------------------------------------------------------

    /** Renders a vector as the pgvector text literal {@code [f0,f1,...]}. */
    static String formatVector(float[] embedding) {
        StringBuilder sb = new StringBuilder(embedding.length * 8 + 2);
        sb.append('[');
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(embedding[i]);
        }
        return sb.append(']').toString();
    }

    /** Parses a pgvector text literal {@code [f0,f1,...]} back into a float array. */
    static float[] parseVector(String literal) {
        String trimmed = literal.trim();
        if (trimmed.startsWith("[")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("]")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isBlank()) {
            return new float[0];
        }
        String[] parts = trimmed.split(",");
        float[] values = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            values[i] = Float.parseFloat(parts[i].trim());
        }
        return values;
    }

    /** Whether {@code name} is a bare SQL identifier safe to interpolate into a statement. */
    static boolean isValidIdentifier(String name) {
        return name != null && name.matches("[A-Za-z_][A-Za-z0-9_]*");
    }

    /**
     * Similarity metric, mapping to a pgvector distance operator, an index operator class and the SQL that
     * turns the operator's distance into a score where higher means a closer match (parity with the
     * in-memory store's cosine similarity).
     */
    enum Distance {
        // Cosine distance in [0,2]; 1 - distance yields cosine similarity in [-1,1].
        COSINE("<=>", "vector_cosine_ops", "1 - (embedding <=> ?)"),
        // Euclidean distance in [0,inf); negate so nearer (smaller distance) scores higher.
        L2("<->", "vector_l2_ops", "-(embedding <-> ?)"),
        // pgvector's <#> is the negative inner product; negate it to recover the inner product itself.
        INNER_PRODUCT("<#>", "vector_ip_ops", "-(embedding <#> ?)");

        private final String operator;
        private final String indexOps;
        private final String scoreExpression;

        Distance(String operator, String indexOps, String scoreExpression) {
            this.operator = operator;
            this.indexOps = indexOps;
            this.scoreExpression = scoreExpression;
        }

        String operator() {
            return operator;
        }

        String indexOps() {
            return indexOps;
        }

        String scoreExpression() {
            return scoreExpression;
        }

        static Distance from(String value) {
            if (value == null) {
                return COSINE;
            }
            return switch (value.trim().toLowerCase()) {
                case "", "cosine" -> COSINE;
                case "l2", "euclidean" -> L2;
                case "ip", "inner_product", "dot" -> INNER_PRODUCT;
                default -> throw new IllegalArgumentException("Unknown 'pgvector.distance': " + value
                        + ". Use one of cosine, l2, inner_product.");
            };
        }
    }
}
