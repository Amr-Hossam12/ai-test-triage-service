package com.aitriage.notify;

import com.aitriage.FailureCase;
import com.aitriage.TriageService;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Posts a Slack incoming-webhook message for each triaged failure. Disabled unless
 * -Dtriage.slackWebhookUrl is set (see Main) - construct it and it just works, no feature flag
 * to thread through the rest of the app.
 */
public class SlackWebhookNotifier implements Notifier {

    private final String webhookUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    public SlackWebhookNotifier(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    @Override
    public void onDiagnosis(FailureCase failureCase, TriageService.TriageResult result) {
        try {
            String text = String.format(
                    ":rotating_light: *%s* classified as `%s`\n*Project:* %s\n*Reasoning:* %s\n*Suggestion:* %s",
                    failureCase.testName, result.classification, failureCase.projectId,
                    result.reasoning, result.suggestion);

            String body = mapper.writeValueAsString(Map.of("text", text));
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
