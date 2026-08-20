package com.aitriage.notify;

import com.aitriage.FailureCase;
import com.aitriage.TriageService;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Posts an MS Teams incoming-webhook message (classic "MessageCard" format, which the Office 365
 * Connector webhook still accepts) for each triaged failure - this is the messaging tool actually
 * used internally, so this is the notifier expected to matter in practice, with Slack kept as an
 * equally pluggable but secondary option. Includes a "View in Dashboard" button that deep-links
 * straight to the case (see index.html's ?case= handling) and an inline screenshot when one was
 * captured, so the card is actionable from Teams itself rather than just a text summary.
 * Disabled unless -Dtriage.teamsWebhookUrl is set (see Main) - construct it and it just works, no
 * feature flag to thread through the rest of the app.
 */
public class TeamsWebhookNotifier implements Notifier {

    private static final Map<String, String> THEME_COLORS = Map.of(
            "SITE_RENDERING_ISSUE", "7C3AED",
            "UI_OVERLAY_BLOCKING", "B45309",
            "ASSERTION_MISMATCH", "1D4ED8",
            "LOCATOR_BROKEN", "C81E1E",
            "UNKNOWN", "6B7280");

    private final String webhookUrl;
    private final String dashboardBaseUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    public TeamsWebhookNotifier(String webhookUrl, String dashboardBaseUrl) {
        this.webhookUrl = webhookUrl;
        this.dashboardBaseUrl = dashboardBaseUrl;
    }

    @Override
    public void onDiagnosis(FailureCase failureCase, TriageService.TriageResult result) {
        try {
            String caseUrl = dashboardBaseUrl + "/?case=" + failureCase.id;

            List<Map<String, String>> facts = new ArrayList<>();
            facts.add(Map.of("name", "Project", "value", failureCase.projectId));
            facts.add(Map.of("name", "Classification", "value", result.classification));
            if (result.confidence != null) {
                facts.add(Map.of("name", "Confidence", "value", result.confidence));
            }
            if (result.selfCheckFlag != null) {
                facts.add(Map.of("name", "Self-check", "value", result.selfCheckFlag));
            }

            Map<String, Object> section = new java.util.LinkedHashMap<>();
            section.put("activityTitle", "🚨 " + failureCase.testName);
            section.put("activitySubtitle", "Classified as " + result.classification);
            section.put("facts", facts);
            section.put("text", "**Reasoning:** " + result.reasoning + "\n\n**Suggestion:** " + result.suggestion);
            if (failureCase.hasScreenshot) {
                section.put("images", List.of(Map.of(
                        "image", dashboardBaseUrl + "/api/screenshots/" + failureCase.id)));
            }

            Map<String, Object> card = new java.util.LinkedHashMap<>();
            card.put("@type", "MessageCard");
            card.put("@context", "http://schema.org/extensions");
            card.put("summary", failureCase.testName + " classified as " + result.classification);
            card.put("themeColor", THEME_COLORS.getOrDefault(result.classification, "6B7280"));
            card.put("sections", List.of(section));
            card.put("potentialAction", List.of(Map.of(
                    "@type", "OpenUri",
                    "name", "View in Dashboard",
                    "targets", List.of(Map.of("os", "default", "uri", caseUrl)))));

            String body = mapper.writeValueAsString(card);
            HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 300) {
                System.out.println("[notify] Teams webhook returned status " + response.statusCode());
            }
        } catch (Exception e) {
            // Notification failures must never affect the triage response itself.
            System.out.println("[notify] Failed to post to Teams webhook: " + e);
        }
    }
}
