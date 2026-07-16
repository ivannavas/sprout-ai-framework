package io.github.ivannavas.sprout.ollama.embedding;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link EmbeddingModel} backed by a local Ollama server's {@code /api/embed} endpoint. Ollama runs
 * models locally, so no API key is needed; it gives Sprout's RAG a semantic embedding — computed
 * entirely on the machine — to pair with the Ollama chat model, in contrast to {@code sprout-core}'s
 * lexical {@code HashingEmbeddingModel}. Configure {@code ollama.embedding.model.name} (default
 * {@code nomic-embed-text}) and optionally {@code ollama.embedding.api.url} and
 * {@code ollama.embedding.timeout.seconds}.
 */
@Embedding
public class OllamaEmbeddingModel extends EmbeddingModel {

    @Value("${ollama.embedding.model.name:nomic-embed-text}")
    protected String modelName;

    @Value("${ollama.embedding.timeout.seconds:60}")
    protected int requestTimeoutSeconds;

    @Value("${ollama.embedding.api.url:http://localhost:11434/api/embed}")
    protected String apiUrl;

    protected HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public float[] embed(String text) {
        return embedAll(List.of(text)).getFirst();
    }

    /** Embeds every text in a single request — Ollama's embed endpoint accepts a batch {@code input} array. */
    @Override
    public List<float[]> embedAll(List<String> texts) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelName);
        body.put("input", texts);
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RuntimeException("Ollama API error " + response.statusCode() + ": " + response.body());
            }

            return parseEmbeddings(objectMapper.readTree(response.body()));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Ollama embedding request failed", e);
        }
    }

    // Ollama returns embeddings under "embeddings" in the same order as the input texts.
    private List<float[]> parseEmbeddings(JsonNode root) {
        JsonNode embeddings = root.get("embeddings");
        if (embeddings == null || !embeddings.isArray()) {
            throw new RuntimeException("Ollama embedding response had no 'embeddings' array: " + root);
        }
        List<float[]> vectors = new ArrayList<>(embeddings.size());
        for (JsonNode values : embeddings) {
            float[] vector = new float[values.size()];
            for (int i = 0; i < values.size(); i++) {
                vector[i] = (float) values.get(i).asDouble();
            }
            vectors.add(vector);
        }
        return vectors;
    }
}
