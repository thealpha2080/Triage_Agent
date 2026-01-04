/**
 * Title: Server
 * Author: Ali Abbas
 * Description: This file runs the Triage Agent server locally and handles all logic.
 * Date: Dec 22, 2025
 * Version: 1.0.0
 */

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;

import java.net.InetSocketAddress;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;


public class Server {

    private static final int PORT = 8080;

    // File paths (relative to project root)
    private static final Path INDEX_HTML = Path.of("web/index.html");
    private static final Path STYLES_CSS = Path.of("web/styles.css");
    private static final Path APP_JS     = Path.of("web/app.js");

    static final String BOOT_ID = UUID.randomUUID().toString();
    static final Map<String, ConversationState> SESSIONS = new ConcurrentHashMap<>();

    static final KnowledgeBase KB = loadKbOrDie();
    private static final ConversationEngine ENGINE = new ConversationEngine(SESSIONS, BOOT_ID, KB);


    public static void main(String[] args) throws IOException {
        // Make the program run through this PORT doorway
        // Knock on the door in the browser at "http://localhost:8080/"
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

        // Static routes
        server.createContext("/", Server::handleRoot);            // catches "/" and anything else not matched
        server.createContext("/styles.css", Server::handleCss);
        server.createContext("/app.js", Server::handleJs);
        server.createContext("/favicon.ico", Server::handleFavicon);

        // API routes
        server.createContext("/api/message", Server::handleApiMessage);

        server.setExecutor(null); // default executor

        // Save case and ensure the editable one doesn't have triage results (archived)
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[Shutdown] Saving active cases...");
            for (ConversationState st : SESSIONS.values()) {
                if (st != null && st.activeCase != null && !st.activeCase.notes.isEmpty()) {
                    CaseStorage.saveCase(st.activeCase, st.sessionId);
                }
            }
        }));

        // Start the server
        server.start();

        System.out.println("Server running at http://localhost:" + PORT);
        System.out.println("Working dir: " + System.getProperty("user.dir"));
    }

    // =====================================================================================
    // Core helpers


    /**
     * Generic sender: you provide a body object and an encoder to convert it to bytes.
     * This prevents duplicated "set headers + send + write + close" code.
     * An exchange is when an Http request is received and a response is to be generated
     */
    private static <T> void send(HttpExchange exchange,
                                 int statusCode,
                                 String contentType,
                                 T body,
                                 Function<T, byte[]> encoder) throws IOException {

        byte[] bytes = encoder.apply(body);

        // Putting on the metadata labels
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");

        // length needed to know when the request is complete
        exchange.sendResponseHeaders(statusCode, bytes.length); // (fixed length best in this case)
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sendText(HttpExchange exchange, int statusCode, String text) throws IOException {
        send(exchange, statusCode, "text/plain; charset=utf-8", text, s -> s.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendJson(HttpExchange exchange, int statusCode, String json) throws IOException {
        send(exchange, statusCode, "application/json; charset=utf-8", json, s -> s.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendFile(HttpExchange exchange, int statusCode, String contentType, Path file) throws IOException {
        try {
            byte[] bytes = Files.readAllBytes(file);
            send(exchange, statusCode, contentType, bytes, b -> b);
        } catch (IOException e) {
            // Always respond even if file missing -> prevents the browser from loading forever
            sendText(exchange, 500, "Failed to read file: " + file + "\n" + e.getMessage());
        }
    }

    private static String method(HttpExchange exchange) {
        return exchange.getRequestMethod();
    }

    private static String path(HttpExchange exchange) {
        return exchange.getRequestURI().getPath();
    }

    // ======================================================================================
    // Route handlers

    private static void handleRoot(HttpExchange exchange) throws IOException {
        System.out.println(method(exchange) + " " + path(exchange));

        // Only serve index on exact "/"
        if (!"GET".equals(method(exchange))) {
            sendText(exchange, 405, "Method Not Allowed");
            return;
        }

        if ("/".equals(path(exchange))) {
            sendFile(exchange, 200, "text/html; charset=utf-8", INDEX_HTML);
        } else {
            // Anything else that wasn't matched by other contexts
            sendText(exchange, 404, "Not Found: " + path(exchange));
        }
    }

    private static void handleCss(HttpExchange exchange) throws IOException {
        System.out.println(method(exchange) + " " + path(exchange));

        if (!"GET".equals(method(exchange))) {
            sendText(exchange, 405, "Method Not Allowed");
            return;
        }

        sendFile(exchange, 200, "text/css; charset=utf-8", STYLES_CSS);
    }

    private static void handleJs(HttpExchange exchange) throws IOException {
        System.out.println(method(exchange) + " " + path(exchange));

        if (!"GET".equals(method(exchange))) {
            sendText(exchange, 405, "Method Not Allowed");
            return;
        }

        sendFile(exchange, 200, "application/javascript; charset=utf-8", APP_JS);
    }

    private static void handleFavicon(HttpExchange exchange) throws IOException {
        // Respond with "No Content" so browsers stop spamming requests.
        if (!"GET".equals(method(exchange))) {
            sendText(exchange, 405, "Method Not Allowed");
            return;
        }
        exchange.sendResponseHeaders(204, -1); // No body
        exchange.close();
    }

    private static void handleApiMessage(HttpExchange exchange) throws IOException {
        String body = readAll(exchange.getRequestBody());

        String sessionId = JsonMini.getString(body, "sessionId");
        String text      = JsonMini.getString(body, "text");

        if (sessionId == null || sessionId.isBlank()) sessionId = "anon-" + System.currentTimeMillis();
        if (text == null) text = "";

        String json = ENGINE.handle(sessionId, text);
        sendJson(exchange, 200, json);

    }

    // ================================================================================================
    // Utilities

    static class JsonMini {

        static String getString(String body, String key) {
            if (body == null) return null;

            // Find: "key"
            String needle = "\"" + key + "\"";
            int k = body.indexOf(needle);
            if (k < 0) return null;

            // Find the colon after the key
            int colon = body.indexOf(":", k + needle.length());
            if (colon < 0) return null;

            // Find the first quote after the colon (value must be a string)
            int firstQuote = body.indexOf("\"", colon + 1);
            if (firstQuote < 0) return null;

            int secondQuote = body.indexOf("\"", firstQuote + 1);
            if (secondQuote < 0) return null;

            return body.substring(firstQuote + 1, secondQuote);
        }

        static String getNestedString(String body, String parentKey, String key) {
            if (body == null) return null;

            String parentNeedle = "\"" + parentKey + "\"";
            int p = body.indexOf(parentNeedle);
            if (p < 0) return null;

            int braceStart = body.indexOf("{", p);
            if (braceStart < 0) return null;

            int braceEnd = body.indexOf("}", braceStart);
            if (braceEnd < 0) return null;

            String sub = body.substring(braceStart, braceEnd + 1);
            return getString(sub, key);
        }
    }

    private static KnowledgeBase loadKbOrDie() {
        try {
            // Change filename to match yours
            return KnowledgeBase.load(Path.of("data/kb_v1.json"));
        } catch (Exception e) {
            System.out.println("FATAL: Could not load knowledge base.");
            System.out.println("Expected at: data/kb_symptoms.json");
            e.printStackTrace();
            System.exit(1);
            return null; // unreachable, but required by Java
        }
    }


    private static String readAll(InputStream in) throws IOException {
        byte[] bytes = in.readAllBytes();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String escapeJson(String s) {
        // Minimal JSON escaping for quotes/backslashes/newlines
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
