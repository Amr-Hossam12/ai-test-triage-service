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
 * Posts a Slack incoming-webhook message for each triaged failure, using Block Kit so the message
 * is actionable from Slack itself: a "View in Dashboard" button that deep-links straight to the
 * case (see index.html's ?case= handling), and an inline screenshot thumbnail when one was
 * captured - not just a text summary someone still has to go open the dashboard to act on.
 * Disabled unless -Dtriage.slackWebhookUrl is set (see Main) - construct it and it just works, no
 * feature flag to thread through the rest of the app.
 */
public class SlackWebhookNotifier implements Notifier {

    private final String webhookUrl;
    private final String dashboardBaseUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    public SlackWebhookNotifier(String webhookUrl, String dashboardBaseUrl) {
        this.webhookUrl = webhookUrl;
        this.dashboardBaseUrl = dashboardBaseUrl;
    }

    @Override
    public void onDiagnosis(FailureCase failureCase, TriageService.TriageResult result) {
        try {
            String caseUrl = dashboardBaseUrl + "/?case=" + failureCase.id;
            String fallbackText = failureCase.testName + " classified as " + result.classification;

            List<Map<String, Object>> blocks = new ArrayList<>();
            blocks.add(Map.of("type", "section", "text", Map.of("type", "mrkdwn", "text", String.format(
                    ":rotating_light: *%s* classified as `%s`%s\n*Project:* %s%s",
                    failureCase.testName, result.classification,
                    result.confidence != null ? " (" + result.confidence + " confidence)" : "",
                    failureCase.projectId,
                    result.selfCheckFlag != null ? "\n:warning: " + result.selfCheckFlag : ""))));
            blocks.add(Map.of("type", "section", "text", Map.of("type", "mrkdwn", "text", String.format(
                    "*Reasoning:* %s\n*Suggestion:* %s", result.reasoning, result.suggestion))));
            if (failureCase.hasScreenshot) {
                blocks.add(Map.of("type", "image",
                        "image_url", dashboardBaseUrl + "/api/screenshots/" + failureCase.id,
                        "alt_text", "Failure screenshot"));
            }
            blocks.add(Map.of("type", "actions", "elements", List.of(Map.of(
                    "type", "button",
                    "text", Map.of("type", "plain_text", "text", "View in Dashboard"),
                    "url", caseUrl))));

            String body = mapper.writeValueAsString(Map.of("text", fallbackText, "blocks", blocks));
            HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 300) {
                System.out.println("[notify] Slack webhook returned status " + response.statusCode());
            }
        } catch (Exception e) {
            // Notification failures must never affect the triage response itself.
            System.out.println("[notify] Failed to post to Slack webhook: " + e);
        }
    }
}
