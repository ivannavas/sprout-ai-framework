package io.github.ivannavas.sprout.anthropic.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ivannavas.sprout.annotation.Embedding;
import io.github.ivannavas.sprout.annotation.Value;
import io.github.ivannavas.sprout.embedding.EmbeddingModel;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link EmbeddingModel} backed by Voyage AI — the embedding provider Anthropic recommends, since the
 * Anthropic API itself is chat-only and has no first-party embeddings endpoint. It gives Sprout's RAG a
 * semantic embedding to pair with the Anthropic chat model, in contrast to {@code sprout-core}'s lexical
 * {@code HashingEmbeddingModel}. Configure {@code voyage.api.key} (also resolved from the
 * {@code VOYAGE_API_KEY} environment variable) and optionally {@code voyage.embedding.model.name}
 * (default {@code voyage-3}), {@code voyage.timeout.seconds} and {@code voyage.api.url}.
 */
@Embedding
public class VoyageEmbeddingModel extends EmbeddingModel {

    @Value("${voyage.api.key}")
    protected String apiKey;

    @Value("${voyage.embedding.model.name:voyage-3}")
    protected String modelName;

    @Value("${voyage.timeout.seconds:60}")
    protected int requestTimeoutSeconds;

    @Value("${voyage.api.url:https://api.voyageai.com/v1/embeddings}")
    protected String apiUrl;

    protected HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public float[] embed(String text) {
        return embedAll(List.of(text)).getFirst();
    }

    /** Embeds every text in a single request — Voyage's embeddings endpoint accepts a batch input. */
    @Override
    public List<float[]> embedAll(List<String> texts) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Voyage API key is not configured. Set 'voyage.api.key' "
                    + "(for example via the VOYAGE_API_KEY environment variable).");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);
        body.put("input", texts);
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RuntimeException("Voyage API error " + response.statusCode() + ": " + response.body());
            }

            return parseEmbeddings(objectMapper.readTree(response.body()), texts.size());
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Voyage embedding request failed", e);
        }
    }

    // The API may return embeddings in any order, so each entry is placed by its declared "index".
    private List<float[]> parseEmbeddings(JsonNode root, int expected) {
        float[][] vectors = new float[expected][];
        for (JsonNode entry : root.get("data")) {
            int index = entry.get("index").asInt();
            JsonNode values = entry.get("embedding");
            float[] vector = new float[values.size()];
            for (int i = 0; i < values.size(); i++) {
                vector[i] = (float) values.get(i).asDouble();
            }
            vectors[index] = vector;
        }
        return List.of(vectors);
    }
}
