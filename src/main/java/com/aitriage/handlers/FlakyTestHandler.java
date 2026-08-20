package com.aitriage.handlers;

import com.aitriage.FailureCase;
import com.aitriage.FailureCaseStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * GET /api/flaky-tests (optional ?projectId=) - groups failures by (project, test) and flags any
 * test that has failed with more than one *distinct* classification across its recorded runs.
 * That pattern (same test, different failure shape each time) points to flakiness or an
 * intermittent root cause, which is a different problem from a test that fails the same way every
 * time - it shouldn't be triaged as a series of unrelated fresh incidents.
 */
public class FlakyTestHandler implements HttpHandler {

    private final FailureCaseStore store;

    public FlakyTestHandler(FailureCaseStore store) {
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

        Map<String, TestGroup> groups = new LinkedHashMap<>();
        for (FailureCase c : cases) {
            String key = c.projectId + " " + c.testName;
            TestGroup group = groups.computeIfAbsent(key, k -> new TestGroup(c.projectId, c.testName));
            group.occurrences++;
            group.classificationCounts.merge(c.classification, 1, Integer::sum);
            if (group.lastSeen == null || c.timestamp.compareTo(group.lastSeen) > 0) {
                group.lastSeen = c.timestamp;
                group.lastCaseId = c.id;
            }
        }

        List<Map<String, Object>> out = new ArrayList<>();
        groups.values().stream()
                .filter(g -> g.classificationCounts.size() > 1)
                .sorted(Comparator.comparingInt((TestGroup g) -> g.classificationCounts.size())
                        .thenComparingInt(g -> g.occurrences).reversed())
                .forEach(g -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("projectId", g.projectId);
                    m.put("testName", g.testName);
                    m.put("occurrences", g.occurrences);
                    m.put("distinctClassifications", g.classificationCounts.size());
                    m.put("classificationCounts", g.classificationCounts);
                    m.put("lastSeen", g.lastSeen);
                    m.put("lastCaseId", g.lastCaseId);
                    out.add(m);
                });

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

    private static class TestGroup {
        final String projectId;
        final String testName;
        int occurrences;
        final Map<String, Integer> classificationCounts = new TreeMap<>();
        String lastSeen;
        String lastCaseId;

        TestGroup(String projectId, String testName) {
            this.projectId = projectId;
            this.testName = testName;
        }
    }
}
