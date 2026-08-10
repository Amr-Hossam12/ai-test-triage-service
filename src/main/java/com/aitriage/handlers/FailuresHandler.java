package com.aitriage.handlers;

import com.aitriage.FailureCase;
import com.aitriage.FailureCaseStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Handles GET /api/failures (list, optional ?projectId=) and GET /api/failures/{id} (detail). */
public class FailuresHandler implements HttpHandler {

    private static final String PREFIX = "/api/failures";

    private final FailureCaseStore store;

    public FailuresHandler(FailureCaseStore store) {
        this.store = store;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            Http.sendJson(exchange, 405, Map.of("error", "GET required"));
            return;
        }
        URI uri = exchange.getRequestURI();
        String path = uri.getPath();

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
