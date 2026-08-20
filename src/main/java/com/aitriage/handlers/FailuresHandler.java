package com.aitriage.handlers;

import com.aitriage.FailureCase;
import com.aitriage.FailureCaseStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Handles GET /api/failures (list, optional ?projectId=), GET /api/failures/{id} (detail), and
 * POST /api/failures/{id}/verify (record a human confirmation/correction of a classification).
 */
public class FailuresHandler implements HttpHandler {

    private static final String PREFIX = "/api/failures";
    private static final Set<String> ALLOWED_CLASSIFICATIONS = Set.of(
            "SITE_RENDERING_ISSUE", "UI_OVERLAY_BLOCKING", "ASSERTION_MISMATCH", "LOCATOR_BROKEN", "UNKNOWN");

    private final FailureCaseStore store;
    private final ObjectMapper mapper = new ObjectMapper();

    public FailuresHandler(FailureCaseStore store) {
        this.store = store;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        URI uri = exchange.getRequestURI();
        String path = uri.getPath();
        String method = exchange.getRequestMethod();

        if ("POST".equalsIgnoreCase(method) && path.endsWith("/verify")) {
            handleVerify(exchange, path);
            return;
        }
        if (!"GET".equalsIgnoreCase(method)) {
            Http.sendJson(exchange, 405, Map.of("error", "GET required"));
            return;
        }

        if (path.equals(PREFIX) || path.equals(PREFIX + "/")) {
            String projectId = queryParam(uri.getQuery(), "projectId");
            List<FailureCase> all = store.listAll(projectId);
            // Strip embeddings from the list payload - not needed by the dashboard, keeps it light.
            List<Map<String, Object>> summaries = all.stream().map(this::toSummary).toList();
            Http.sendJson(exchange, 200, summaries);
            return;
        }

        String id = path.substring((PREFIX + "/").length());
        Optional<FailureCase> found = store.findById(id);
        if (found.isEmpty()) {
            Http.sendJson(exchange, 404, Map.of("error", "not found"));
            return;
        }
        Http.sendJson(exchange, 200, toSummary(found.get()));
    }

    private void handleVerify(HttpExchange exchange, String path) throws IOException {
        String id = path.substring((PREFIX + "/").length(), path.length() - "/verify".length());
        JsonNode body = mapper.readTree(exchange.getRequestBody());
        String classification = body.path("classification").asText("").trim().toUpperCase();
        if (!ALLOWED_CLASSIFICATIONS.contains(classification)) {
            Http.sendJson(exchange, 400, Map.of("error", "invalid classification"));
            return;
        }
        boolean updated = store.verify(id, classification);
        if (!updated) {
            Http.sendJson(exchange, 404, Map.of("error", "case not found"));
            return;
        }
        Http.sendJson(exchange, 200, Map.of("id", id, "verified", true, "verifiedClassification", classification));
    }

    private Map<String, Object> toSummary(FailureCase c) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("id", c.id);
        map.put("timestamp", c.timestamp);
        map.put("projectId", c.projectId);
        map.put("testName", c.testName);
        map.put("exceptionType", c.exceptionType);
        map.put("exceptionMessage", c.exceptionMessage);
        map.put("pageSourceSnippet", c.pageSourceSnippet);
        map.put("hasScreenshot", c.hasScreenshot);
        map.put("classification", c.classification);
        map.put("reasoning", c.reasoning);
        map.put("suggestion", c.suggestion);
        map.put("confidence", c.confidence);
        map.put("selfCheckFlag", c.selfCheckFlag);
        map.put("failingLocator", c.failingLocator);
        map.put("verified", c.verified);
        map.put("verifiedClassification", c.verifiedClassification);
        map.put("similarCases", c.similarCases);
        return map;
    }

    private static String queryParam(String query, String name) {
        if (query == null) {
            return null;
        }
        for (String part : query.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2 && kv[0].equals(name)) {
                return java.net.URLDecoder.decode(kv[1], java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
