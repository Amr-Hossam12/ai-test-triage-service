package com.aitriage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Thin client for a locally running Ollama server (embeddings + JSON-mode generation). */
public class OllamaClient {

    private final String baseUrl;
    private final String embedModel;
    private final String chatModel;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    public OllamaClient(String baseUrl, String embedModel, String chatModel) {
        this.baseUrl = baseUrl;
        this.embedModel = embedModel;
        this.chatModel = chatModel;
    }

    private static final int MAX_ATTEMPTS = 2;
    private static final long RETRY_BACKOFF_MS = 1000;

    public float[] embed(String text) {
        try {
            String body = mapper.writeValueAsString(Map.of("model", embedModel, "prompt", text));
            JsonNode json = postWithRetry("/api/embeddings", body, 30);
            JsonNode embeddingNode = json.get("embedding");
            float[] embedding = new float[embeddingNode.size()];
            for (int i = 0; i < embedding.length; i++) {
                embedding[i] = (float) embeddingNode.get(i).asDouble();
            }
            return embedding;
        } catch (Exception e) {
            throw new RuntimeException("Ollama embedding call failed", e);
        }
    }

    /** Generates a response constrained to valid JSON syntax (Ollama's format:"json" mode). */
    public String generateJson(String prompt) {
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "model", chatModel, "prompt", prompt, "stream", false, "format", "json"));
            JsonNode json = postWithRetry("/api/generate", body, 180);
            return json.get("response").asText();
        } catch (Exception e) {
            throw new RuntimeException("Ollama generate call failed", e);
        }
    }

    /** Retries once on transient I/O failures (connection refused, timeout) - local Ollama inference can be flaky under load. */
    private JsonNode postWithRetry(String path, String body, int timeoutSeconds) throws Exception {
        for (int attempt = 1; ; attempt++) {
            try {
                return post(path, body, timeoutSeconds);
            } catch (java.io.IOException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                Thread.sleep(RETRY_BACKOFF_MS);
            }
        }
    }

    private JsonNode post(String path, String body, int timeoutSeconds) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return mapper.readTree(response.body());
    }
}
