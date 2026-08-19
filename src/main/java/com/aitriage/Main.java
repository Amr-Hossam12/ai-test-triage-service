package com.aitriage;

import com.aitriage.handlers.DashboardHandler;
import com.aitriage.handlers.FailuresHandler;
import com.aitriage.handlers.MetricsHandler;
import com.aitriage.handlers.ScreenshotHandler;
import com.aitriage.handlers.TriageHandler;
import com.aitriage.notify.CompositeNotifier;
import com.aitriage.notify.LoggingNotifier;
import com.aitriage.notify.Notifier;
import com.aitriage.notify.SlackWebhookNotifier;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class Main {

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getProperty("triage.port", "8787"));
        String dataDir = System.getProperty("triage.dataDir", "data");
        String ollamaBaseUrl = System.getProperty("triage.ollamaBaseUrl", "http://localhost:11434");
        String embedModel = System.getProperty("triage.embedModel", "nomic-embed-text");
        String chatModel = System.getProperty("triage.chatModel", "llama3.1:8b");

        String slackWebhookUrl = System.getProperty("triage.slackWebhookUrl", "");

        OllamaClient ollama = new OllamaClient(ollamaBaseUrl, embedModel, chatModel);
        FailureCaseStore store = new FailureCaseStore(dataDir);

        List<Notifier> notifiers = new ArrayList<>();
        notifiers.add(new LoggingNotifier());
        if (!slackWebhookUrl.isBlank()) {
            notifiers.add(new SlackWebhookNotifier(slackWebhookUrl));
        }
        TriageService triageService = new TriageService(ollama, store, new CompositeNotifier(notifiers));

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/triage", new TriageHandler(triageService));
        server.createContext("/api/failures", new FailuresHandler(store));
        server.createContext("/api/metrics", new MetricsHandler(store));
        server.createContext("/api/screenshots/", new ScreenshotHandler(store));
        server.createContext("/", new DashboardHandler());
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        System.out.println("AI Test Triage Service running:");
        System.out.println("  Dashboard: http://localhost:" + port + "/");
        System.out.println("  API:       http://localhost:" + port + "/api/triage (POST)");
        System.out.println("  Data dir:  " + dataDir);
        System.out.println("  Ollama:    " + ollamaBaseUrl + " (embed=" + embedModel + ", chat=" + chatModel + ")");
        System.out.println("  Notifiers: logging" + (slackWebhookUrl.isBlank() ? "" : ", slack-webhook"));

        checkOllamaReachable(ollamaBaseUrl);
    }

    private static void checkOllamaReachable(String ollamaBaseUrl) {
        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(ollamaBaseUrl + "/api/tags"))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 200) {
                System.out.println("  WARNING: Ollama responded with status " + response.statusCode()
                        + " - triage requests may fail until this is resolved.");
            }
        } catch (Exception e) {
            System.out.println("  WARNING: Could not reach Ollama at " + ollamaBaseUrl + " (" + e
                    + "). Is it running? Triage requests will fail until it's reachable.");
        }
    }
}
