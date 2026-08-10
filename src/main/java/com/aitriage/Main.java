package com.aitriage;

import com.aitriage.handlers.DashboardHandler;
import com.aitriage.handlers.FailuresHandler;
import com.aitriage.handlers.ScreenshotHandler;
import com.aitriage.handlers.TriageHandler;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;

public class Main {

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getProperty("triage.port", "8787"));
        String dataDir = System.getProperty("triage.dataDir", "data");
        String ollamaBaseUrl = System.getProperty("triage.ollamaBaseUrl", "http://localhost:11434");
        String embedModel = System.getProperty("triage.embedModel", "nomic-embed-text");
        String chatModel = System.getProperty("triage.chatModel", "llama3.1:8b");

        OllamaClient ollama = new OllamaClient(ollamaBaseUrl, embedModel, chatModel);
        FailureCaseStore store = new FailureCaseStore(dataDir);
        TriageService triageService = new TriageService(ollama, store);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api/triage", new TriageHandler(triageService));
        server.createContext("/api/failures", new FailuresHandler(store));
        server.createContext("/api/screenshots/", new ScreenshotHandler(store));
        server.createContext("/", new DashboardHandler());
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        System.out.println("AI Test Triage Service running:");
        System.out.println("  Dashboard: http://localhost:" + port + "/");
        System.out.println("  API:       http://localhost:" + port + "/api/triage (POST)");
        System.out.println("  Data dir:  " + dataDir);
        System.out.println("  Ollama:    " + ollamaBaseUrl + " (embed=" + embedModel + ", chat=" + chatModel + ")");
    }
}
