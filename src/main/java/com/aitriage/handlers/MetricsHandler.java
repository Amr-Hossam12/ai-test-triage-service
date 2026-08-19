package com.aitriage.handlers;

import com.aitriage.FailureCase;
import com.aitriage.FailureCaseStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GET /api/metrics (optional ?projectId=) - aggregates human verification outcomes per
 * classification so accuracy trends are visible instead of inferred anecdotally.
 */
public class MetricsHandler implements HttpHandler {

    private final FailureCaseStore store;

    public MetricsHandler(FailureCaseStore store) {
        this.store = store;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            Http.sendJson(exchange, 405, Map.of("error", "GET required"));
            return;
        }

        URI uri = exchange.getRequestURI();
        String projectId = queryParam(uri.getQuery(), "projectId");
        List<FailureCase> cases = store.listAll(projectId);

        int totalCases = cases.size();
        int verifiedCases = 0;
        int confirmedAsIs = 0;
        int corrected = 0;

        Map<String, CategoryStats> byCategory = new LinkedHashMap<>();
        for (String category : List.of(
                "SITE_RENDERING_ISSUE", "UI_OVERLAY_BLOCKING", "ASSERTION_MISMATCH", "LOCATOR_BROKEN", "UNKNOWN")) {
            byCategory.put(category, new CategoryStats());
        }

        for (FailureCase c : cases) {
            CategoryStats predictedStats = byCategory.computeIfAbsent(c.classification, k -> new CategoryStats());
            predictedStats.predicted++;

            if (c.verified) {
                verifiedCases++;
                predictedStats.verified++;
                if (c.classification.equals(c.verifiedClassification)) {
                    confirmedAsIs++;
                    predictedStats.confirmed++;
                } else {
                    corrected++;
                    predictedStats.corrected++;
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalCases", totalCases);
        result.put("verifiedCases", verifiedCases);
        result.put("confirmedAsIs", confirmedAsIs);
        result.put("corrected", corrected);
        result.put("accuracy", verifiedCases == 0 ? null : (double) confirmedAsIs / verifiedCases);

        Map<String, Object> categoryOut = new LinkedHashMap<>();
        for (Map.Entry<String, CategoryStats> entry : byCategory.entrySet()) {
            CategoryStats s = entry.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("predicted", s.predicted);
            m.put("verified", s.verified);
            m.put("confirmed", s.confirmed);
            m.put("corrected", s.corrected);
            m.put("accuracy", s.verified == 0 ? null : (double) s.confirmed / s.verified);
            categoryOut.put(entry.getKey(), m);
        }
        result.put("byCategory", categoryOut);

        Http.sendJson(exchange, 200, result);
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

    private static class CategoryStats {
        int predicted;
        int verified;
        int confirmed;
        int corrected;
    }
}
