package com.aitriage.handlers;

import com.aitriage.TriageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

public class TriageHandler implements HttpHandler {

    private final TriageService triageService;
    private final ObjectMapper mapper = new ObjectMapper();

    public TriageHandler(TriageService triageService) {
        this.triageService = triageService;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            Http.sendJson(exchange, 405, Map.of("error", "POST required"));
            return;
        }
        try {
            JsonNode body = mapper.readTree(exchange.getRequestBody());
            String projectId = body.path("projectId").asText("default");
            String testName = body.path("testName").asText("");
            String exceptionType = body.path("exceptionType").asText("Unknown");
            String exceptionMessage = body.path("exceptionMessage").asText("");
            String pageSource = body.path("pageSource").asText("");
            String screenshotBase64 = body.path("screenshotBase64").asText("");
            byte[] screenshot = screenshotBase64.isBlank() ? new byte[0] : Base64.getDecoder().decode(screenshotBase64);

            TriageService.TriageResult result = triageService.diagnose(
                    projectId, testName, exceptionType, exceptionMessage, pageSource, screenshot);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("id", result.id);
            response.put("classification", result.classification);
            response.put("reasoning", result.reasoning);
            response.put("suggestion", result.suggestion);
            response.put("confidence", result.confidence);
            response.put("selfCheckFlag", result.selfCheckFlag);
            response.put("similarCases", result.similarCases);
            Http.sendJson(exchange, 200, response);
        } catch (Exception e) {
            Http.sendJson(exchange, 500, Map.of("error", e.getMessage()));
        }
    }
}
