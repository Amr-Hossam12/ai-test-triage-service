package com.aitriage.handlers;

import com.aitriage.FailureCaseStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;

/** Handles GET /api/screenshots/{id} - raw PNG bytes for a failure case. */
public class ScreenshotHandler implements HttpHandler {

    private static final String PREFIX = "/api/screenshots/";

    private final FailureCaseStore store;

    public ScreenshotHandler(FailureCaseStore store) {
        this.store = store;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String id = path.substring(PREFIX.length());
        var file = store.screenshotFile(id);
        if (!file.exists()) {
            Http.sendJson(exchange, 404, Map.of("error", "no screenshot for this case"));
            return;
        }
        Http.sendBytes(exchange, 200, "image/png", Files.readAllBytes(file.toPath()));
    }
}
