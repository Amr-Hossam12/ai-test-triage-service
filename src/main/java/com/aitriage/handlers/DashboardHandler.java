package com.aitriage.handlers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;

/** Serves the single-page dashboard from the classpath resource. */
public class DashboardHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/dashboard/index.html")) {
            if (in == null) {
                Http.sendText(exchange, 404, "text/plain", "dashboard not found");
                return;
            }
            byte[] bytes = in.readAllBytes();
            Http.sendBytes(exchange, 200, "text/html; charset=utf-8", bytes);
        }
    }
}
