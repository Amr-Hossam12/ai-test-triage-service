package com.aitriage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * RAG-lite failure triage: retrieves similar past incidents (per-project) from
 * the flat-file case store, grounds a local llama3.1:8b call with them, and
 * records the new case back into the store for future retrieval.
 */
public class TriageService {

    private static final int TOP_K = 3;
    private static final int SNIPPET_MAX_LEN = 1500;
    private static final Set<String> ALLOWED_CLASSIFICATIONS = Set.of(
            "SITE_RENDERING_ISSUE", "UI_OVERLAY_BLOCKING", "ASSERTION_MISMATCH", "LOCATOR_BROKEN", "UNKNOWN");

    private final OllamaClient ollama;
    private final FailureCaseStore store;
    private final ObjectMapper mapper = new ObjectMapper();

    public TriageService(OllamaClient ollama, FailureCaseStore store) {
        this.ollama = ollama;
        this.store = store;
    }

    public TriageResult diagnose(String projectId, String testName, String exceptionType,
                                  String exceptionMessage, String pageSource, byte[] screenshotPng) {
        String snippet = truncate(pageSource, SNIPPET_MAX_LEN);
        String queryText = exceptionType + " | " + exceptionMessage + " | " + snippet;
        float[] queryEmbedding = ollama.embed(queryText);
        List<FailureCase> similar = store.findSimilar(projectId, queryEmbedding, TOP_K);

        String rawResponse = ollama.generateJson(buildPrompt(testName, exceptionType, exceptionMessage, snippet, similar));
        TriageResult result = parseResponse(rawResponse);

        String id = UUID.randomUUID().toString();
        FailureCase failureCase = new FailureCase(id, projectId, testName, exceptionType, exceptionMessage,
                snippet, screenshotPng != null && screenshotPng.length > 0, queryEmbedding,
                result.classification, result.reasoning, result.suggestion);
        store.append(failureCase, screenshotPng);
        result.id = id;
        return result;
    }

    private String buildPrompt(String testName, String exceptionType, String exceptionMessage,
                                String snippet, List<FailureCase> similar) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a failure-triage assistant for automated test suites (any framework/language). ")
                .append("Given the failed test, its exception, and a snippet of the page/response captured at the ")
                .append("moment of failure, respond with ONLY a JSON object of exactly this shape, no other text:\n")
                .append("{\"classification\": one of [SITE_RENDERING_ISSUE, UI_OVERLAY_BLOCKING, ASSERTION_MISMATCH, ")
                .append("LOCATOR_BROKEN, UNKNOWN], \"reasoning\": \"2-4 sentence explanation\", ")
                .append("\"suggestion\": \"one concrete next step to fix or investigate\"}\n\n");

        if (!similar.isEmpty()) {
            prompt.append("Similar past incidents from this project's history:\n");
            for (FailureCase c : similar) {
                prompt.append("- Exception: ").append(c.exceptionType)
                        .append(" | Classification: ").append(c.classification)
                        .append(" | Reasoning: ").append(c.reasoning).append("\n");
            }
            prompt.append("\n");
        }

        prompt.append("Current failure:\n")
                .append("Test: ").append(testName).append("\n")
                .append("Exception: ").append(exceptionType).append(": ").append(exceptionMessage).append("\n")
                .append("Page/response snippet:\n").append(snippet).append("\n");

        return prompt.toString();
    }

    private TriageResult parseResponse(String rawResponse) {
        TriageResult result = new TriageResult();
        try {
            JsonNode node = mapper.readTree(rawResponse);
            String classification = node.path("classification").asText("UNKNOWN").trim().toUpperCase();
            result.classification = ALLOWED_CLASSIFICATIONS.contains(classification) ? classification : "UNKNOWN";
            result.reasoning = node.path("reasoning").asText("");
            result.suggestion = node.path("suggestion").asText("");
        } catch (Exception e) {
            result.classification = "UNKNOWN";
            result.reasoning = "Model returned malformed output: " + rawResponse;
            result.suggestion = "Review manually - AI response could not be parsed.";
        }
        return result;
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        return text.length() > maxLen ? text.substring(0, maxLen) : text;
    }

    public static class TriageResult {
        public String id;
        public String classification;
        public String reasoning;
        public String suggestion;
    }
}
