package com.aitriage.handlers;

import com.aitriage.FailureCase;
import com.aitriage.FailureCaseStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * GET /api/trend (optional ?projectId=) - buckets failures by calendar day (UTC) and
 * classification, so the dashboard can show whether a category is trending up or down over time
 * instead of only ever showing a single point-in-time snapshot.
 */
public class TrendHandler implements HttpHandler {

    private static final List<String> CATEGORIES = List.of(
            "SITE_RENDERING_ISSUE", "UI_OVERLAY_BLOCKING", "ASSERTION_MISMATCH", "LOCATOR_BROKEN", "UNKNOWN");

    private final FailureCaseStore store;

    public TrendHandler(FailureCaseStore store) {
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

        TreeMap<LocalDate, Map<String, Integer>> byDay = new TreeMap<>();
        for (FailureCase c : cases) {
            LocalDate day = Instant.parse(c.timestamp).atZone(ZoneOffset.UTC).toLocalDate();
            Map<String, Integer> counts = byDay.computeIfAbsent(day, d -> new LinkedHashMap<>());
            counts.merge(c.classification, 1, Integer::sum);
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<LocalDate, Map<String, Integer>> entry : byDay.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", entry.getKey().toString());
            Map<String, Integer> counts = new LinkedHashMap<>();
            int total = 0;
            for (String category : CATEGORIES) {
                int n = entry.getValue().getOrDefault(category, 0);
                counts.put(category, n);
                total += n;
            }
            m.put("counts", counts);
            m.put("total", total);
            out.add(m);
        }

        Http.sendJson(exchange, 200, out);
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
