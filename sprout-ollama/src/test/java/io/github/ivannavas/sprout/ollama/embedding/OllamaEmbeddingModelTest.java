package io.github.ivannavas.sprout.ollama.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Drives the embedding model against a loopback HTTP server, exercising request building and response
 *  parsing end to end without touching the network. */
class OllamaEmbeddingModelTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private OllamaEmbeddingModel modelReturning(String responseJson, AtomicReference<String> capturedBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/embed", exchange -> {
            byte[] requestBytes = exchange.getRequestBody().readAllBytes();
            capturedBody.set(new String(requestBytes, StandardCharsets.UTF_8));
            byte[] response = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        return new OllamaEmbeddingModel("nomic-test", 5,
                "http://localhost:" + server.getAddress().getPort() + "/api/embed");
    }

    @Test
    void embedsASingleText() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        OllamaEmbeddingModel model = modelReturning(
                "{\"embeddings\":[[0.1,0.2,0.3]]}", body);

        float[] vector = model.embed("hello");

        assertArrayEquals(new float[]{0.1f, 0.2f, 0.3f}, vector, 1e-6f);
        JsonNode sent = JSON.readTree(body.get());
        assertEquals("nomic-test", sent.path("model").asText());
        assertEquals("hello", sent.path("input").get(0).asText());
    }

    @Test
    void embedsABatchInInputOrder() throws Exception {
        OllamaEmbeddingModel model = modelReturning(
                "{\"embeddings\":[[1.0],[9.0]]}", new AtomicReference<>());

        List<float[]> vectors = model.embedAll(List.of("first", "second"));

        assertEquals(2, vectors.size());
        assertArrayEquals(new float[]{1.0f}, vectors.get(0), 1e-6f);
        assertArrayEquals(new float[]{9.0f}, vectors.get(1), 1e-6f);
    }

    @Test
    void surfacesHttpErrorBody() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/embed", exchange -> {
            byte[] response = "{\"error\":\"model not found\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        OllamaEmbeddingModel model = new OllamaEmbeddingModel("nomic-test", 5,
                "http://localhost:" + server.getAddress().getPort() + "/api/embed");

        RuntimeException error = assertThrows(RuntimeException.class, () -> model.embed("hi"));
        assertTrue(error.getMessage().contains("404"));
        assertTrue(error.getMessage().contains("model not found"));
    }
}
