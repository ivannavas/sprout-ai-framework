package io.github.ivannavas.sprout.openai.embedding;

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
class OpenaiEmbeddingModelTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private OpenaiEmbeddingModel modelReturning(String responseJson, AtomicReference<String> capturedBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            byte[] requestBytes = exchange.getRequestBody().readAllBytes();
            capturedBody.set(new String(requestBytes, StandardCharsets.UTF_8));
            byte[] response = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        return new OpenaiEmbeddingModel("test-key", "text-embedding-test", 5,
                "http://localhost:" + server.getAddress().getPort() + "/v1/embeddings");
    }

    @Test
    void embedsASingleText() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        OpenaiEmbeddingModel model = modelReturning(
                "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2,0.3]}]}", body);

        float[] vector = model.embed("hello");

        assertArrayEquals(new float[]{0.1f, 0.2f, 0.3f}, vector, 1e-6f);
        JsonNode sent = JSON.readTree(body.get());
        assertEquals("text-embedding-test", sent.path("model").asText());
        assertEquals("hello", sent.path("input").get(0).asText());
    }

    @Test
    void embedsABatchAndReordersByIndex() throws Exception {
        // The second entry comes back first; parsing must place each vector by its declared index.
        OpenaiEmbeddingModel model = modelReturning(
                "{\"data\":[{\"index\":1,\"embedding\":[9.0]},{\"index\":0,\"embedding\":[1.0]}]}",
                new AtomicReference<>());

        List<float[]> vectors = model.embedAll(List.of("first", "second"));

        assertEquals(2, vectors.size());
        assertArrayEquals(new float[]{1.0f}, vectors.get(0), 1e-6f);
        assertArrayEquals(new float[]{9.0f}, vectors.get(1), 1e-6f);
    }

    @Test
    void surfacesHttpErrorBody() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            byte[] response = "{\"error\":\"rate_limited\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        OpenaiEmbeddingModel model = new OpenaiEmbeddingModel("test-key", "text-embedding-test", 5,
                "http://localhost:" + server.getAddress().getPort() + "/v1/embeddings");

        RuntimeException error = assertThrows(RuntimeException.class, () -> model.embed("hi"));
        assertTrue(error.getMessage().contains("429"));
        assertTrue(error.getMessage().contains("rate_limited"));
    }

    @Test
    void failsFastWhenApiKeyMissing() {
        OpenaiEmbeddingModel model = new OpenaiEmbeddingModel(null, "text-embedding-test", 5,
                "http://localhost/v1/embeddings");

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> model.embed("hi"));
        assertTrue(error.getMessage().contains("openai.api.key"));
    }
}
