package io.github.ivannavas.sprout.pgvector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit-tests the database-free surface of {@link PgVectorStore}: vector literal formatting/parsing, table
 * identifier validation and distance-metric resolution. The JDBC path is exercised against a real
 * PostgreSQL+pgvector instance rather than here.
 */
class PgVectorStoreTest {

    @Test
    void formatsVectorAsPgvectorLiteral() {
        assertEquals("[1.0,2.5,-3.0]", PgVectorStore.formatVector(new float[]{1.0f, 2.5f, -3.0f}));
        assertEquals("[]", PgVectorStore.formatVector(new float[0]));
    }

    @Test
    void parseIsInverseOfFormat() {
        float[] vector = {0.125f, -4.0f, 10.0f};
        assertArrayEquals(vector, PgVectorStore.parseVector(PgVectorStore.formatVector(vector)));
    }

    @Test
    void parsesVectorWithWhitespaceAndEmpty() {
        assertArrayEquals(new float[]{1.0f, 2.0f}, PgVectorStore.parseVector(" [1, 2] "));
        assertArrayEquals(new float[0], PgVectorStore.parseVector("[]"));
    }

    @Test
    void validatesTableIdentifiers() {
        assertTrue(PgVectorStore.isValidIdentifier("sprout_documents"));
        assertTrue(PgVectorStore.isValidIdentifier("_docs2"));
        assertFalse(PgVectorStore.isValidIdentifier("docs; DROP TABLE users"));
        assertFalse(PgVectorStore.isValidIdentifier("2docs"));
        assertFalse(PgVectorStore.isValidIdentifier(""));
        assertFalse(PgVectorStore.isValidIdentifier(null));
    }

    @Test
    void resolvesDistanceMetricsAndDefaults() {
        assertEquals(PgVectorStore.Distance.COSINE, PgVectorStore.Distance.from(null));
        assertEquals(PgVectorStore.Distance.COSINE, PgVectorStore.Distance.from(""));
        assertEquals(PgVectorStore.Distance.COSINE, PgVectorStore.Distance.from("CoSiNe"));
        assertEquals(PgVectorStore.Distance.L2, PgVectorStore.Distance.from("euclidean"));
        assertEquals(PgVectorStore.Distance.INNER_PRODUCT, PgVectorStore.Distance.from("inner_product"));
        assertThrows(IllegalArgumentException.class, () -> PgVectorStore.Distance.from("manhattan"));
    }

    @Test
    void searchReturnsEmptyBeforeAnyIndexingWhenDimensionUnknown() {
        // No schema, no configured dimension: nothing could have been indexed, so no connection is opened.
        assertTrue(newStore().search(new float[]{1f, 2f}, 5).isEmpty());
    }

    @Test
    void addRejectsDocumentWithoutEmbedding() {
        PgVectorStore store = newStore();
        assertThrows(IllegalArgumentException.class,
                () -> store.add(io.github.ivannavas.sprout.model.Document.of("a", "text")));
    }

    // A store with the same defaults the container would inject from configuration.
    private static PgVectorStore newStore() {
        return new PgVectorStore("", "", "", "sprout_documents", 0, "cosine");
    }
}
