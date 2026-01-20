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

    private static final int PORT = 8000;

    // File paths (relative to project root)
    private static final Path INDEX_HTML = Path.of("web/index.html");
    private static final Path STYLES_CSS = Path.of("web/styles.css");
    private static final Path APP_JS     = Path.of("web/app.js");
    private static final Path KB_PATH    = Path.of("data/kb_v1.json");
    private static final Path DB_PATH    = Path.of("data/triage_history.db");

    static final String BOOT_ID = UUID.randomUUID().toString();
    static final Map<String, ConversationState> SESSIONS = new ConcurrentHashMap<>();

    static final KnowledgeBase KB = loadKbOrDie();
    private static final CaseRepository CASE_REPOSITORY = initRepository();
    private static final ConversationEngine ENGINE = new ConversationEngine(SESSIONS, BOOT_ID, KB, CASE_REPOSITORY);

    // Start the local HTTP server and register all routes.
    public static void main(String[] args) throws IOException {
        // Bind the local HTTP server.
        // Open the UI in the browser at "http://localhost:8000/".
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

        // Static routes
        server.createContext("/", Server::handleRoot);            // catches "/" and anything else not matched
        server.createContext("/styles.css", Server::handleCss);
        server.createContext("/app.js", Server::handleJs);
        server.createContext("/favicon.ico", Server::handleFavicon);

        // API routes
        server.createContext("/api/message", Server::handleApiMessage);
        server.createContext("/api/cases", Server::handleApiCases);

        server.setExecutor(null); // default executor

        // Save active cases on shutdown so sessions are archived.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[Shutdown] Saving active cases...");
            for (ConversationState st : SESSIONS.values()) {
                if (st != null && st.activeCase != null && !st.activeCase.notes.isEmpty()) {
                    CASE_REPOSITORY.saveCase(st.activeCase, st.sessionId);
                }
            }
        }));

        // Start the server
        server.start();

        System.out.println("Server running at http://localhost:" + PORT);
        System.out.println("Working dir: " + System.getProperty("user.dir"));
    } // End main

    // =====================================================================================
    // Core helpers


    /**
     * Generic sender: provide a body object and an encoder to convert it to bytes.
     * This prevents duplicated "set headers + send + write + close" code.
     */
    private static <T> void send(HttpExchange exchange,
                                 int statusCode,
                                 String contentType,
                                 T body,
                                 Function<T, byte[]> encoder) throws IOException {

        byte[] bytes = encoder.apply(body);

        // Set response metadata.
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");

        // Fixed-length response body for predictable client behavior.
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    } // End send

    private static void sendText(HttpExchange exchange, int statusCode, String text) throws IOException {
        send(exchange, statusCode, "text/plain; charset=utf-8", text, s -> s.getBytes(StandardCharsets.UTF_8));
    }  // End sendText

    private static void sendJson(HttpExchange exchange, int statusCode, String json) throws IOException {
        send(exchange, statusCode, "application/json; charset=utf-8", json, s -> s.getBytes(StandardCharsets.UTF_8));
    } // End sendText

    private static void sendFile(HttpExchange exchange, int statusCode, String contentType, Path file) throws IOException {
        try {
            byte[] bytes = Files.readAllBytes(file);
            send(exchange, statusCode, contentType, bytes, b -> b);
        } catch (IOException e) {
            // Always respond even if file missing -> prevents the browser from loading forever
            sendText(exchange, 500, "Failed to read file: " + file + "\n" + e.getMessage());
        }
    } // End sendFile

    private static String method(HttpExchange exchange) {
        return exchange.getRequestMethod();
    } // End method


    private static String path(HttpExchange exchange) {
        return exchange.getRequestURI().getPath();
    }// End path

    // ======================================================================================
    // Route handlers

    // Serve the main index page or a 404 for unmatched root paths.
    private static void handleRoot(HttpExchange exchange) throws IOException {
        System.out.println(method(exchange) + " " + path(exchange));

        // Only serve index on exact "/".
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
    } // End handleRoot

    private static void handleCss(HttpExchange exchange) throws IOException {
        System.out.println(method(exchange) + " " + path(exchange));

        if (!"GET".equals(method(exchange))) {
            sendText(exchange, 405, "Method Not Allowed");
            return;
        }

        sendFile(exchange, 200, "text/css; charset=utf-8", STYLES_CSS);
    } // End handleCss

    private static void handleJs(HttpExchange exchange) throws IOException {
        System.out.println(method(exchange) + " " + path(exchange));

        if (!"GET".equals(method(exchange))) {
            sendText(exchange, 405, "Method Not Allowed");
            return;
        }

        sendFile(exchange, 200, "application/javascript; charset=utf-8", APP_JS);
    } // End handleJs

    // Handle favicon requests with a no-content response (Favicons are the little logo on the website's tab)
    private static void handleFavicon(HttpExchange exchange) throws IOException {
        // Respond with "No Content" so browsers stop requesting the favicon.
        if (!"GET".equals(method(exchange))) {
            sendText(exchange, 405, "Method Not Allowed");
            return;
        }
        exchange.sendResponseHeaders(204, -1); // No body
        exchange.close();
    } // End handleFavicon

    // Handle chat message API requests and return bot responses.
    private static void handleApiMessage(HttpExchange exchange) throws IOException {
        String body = readAll(exchange.getRequestBody());

        String sessionId = JsonMini.getString(body, "sessionId");
        String text      = JsonMini.getString(body, "text");

        if (sessionId == null || sessionId.isBlank()) sessionId = "anon-" + System.currentTimeMillis();
        if (text == null) text = "";

        String json = ENGINE.handle(sessionId, text);
        sendJson(exchange, 200, json);

    } // End handleApiMessage

    // Handle case listing API requests for the database tab.
    private static void handleApiCases(HttpExchange exchange) throws IOException {
        if (!"GET".equals(method(exchange))) {
            sendText(exchange, 405, "Method Not Allowed");
            return;
        }
        List<CaseSummary> summaries = CASE_REPOSITORY.listCases(50);
        sendJson(exchange, 200, summariesToJson(summaries));
    } // End handleApiCases

    // ================================================================================================
    // Utilities

    static class JsonMini {

        static String getString(String body, String key) {
            // Extract a string value by key from a flat JSON string.
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
        } // End getString

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
        } // End getNestedString
    } // End JsonMini

    // Initialize the case repository, preferring SQLite with a JSON fallback.
    private static CaseRepository initRepository() {
        try {
            System.out.println("[Persistence] Initializing SQLite storage at " + DB_PATH);
            return new SqliteCaseRepository(DB_PATH);
        } catch (Exception e) {
            System.out.println("[Persistence] Falling back to JSON storage: " + e.getMessage());
            return new JsonCaseRepository();
        }
    } // End initRepository

    // Serialize case summaries into a JSON array for the UI.
    private static String summariesToJson(List<CaseSummary> summaries) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < summaries.size(); i++) {
            if (i > 0) sb.append(",");
            CaseSummary s = summaries.get(i);
            sb.append("{");
            sb.append("\"caseId\":\"").append(escapeJson(s.caseId)).append("\",");
            sb.append("\"sessionId\":\"").append(escapeJson(s.sessionId)).append("\",");
            sb.append("\"startedEpochMs\":").append(s.startedEpochMs).append(",");
            sb.append("\"triageLevel\":\"").append(escapeJson(s.triageLevel)).append("\",");
            sb.append("\"triageConfidence\":").append(String.format(Locale.ROOT, "%.4f", s.triageConfidence)).append(",");
            sb.append("\"duration\":\"").append(escapeJson(s.duration)).append("\",");
            sb.append("\"severity\":\"").append(escapeJson(s.severity)).append("\",");
            sb.append("\"notesCount\":").append(s.notesCount).append(",");
            sb.append("\"redFlagCount\":").append(s.redFlagCount);
            sb.append("}");
        }
        sb.append("]");
        return sb.toString();
    } // End summariesToJson

    // Escape a string for JSON output.
    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    } // End escapeJson

    // Load the knowledge base or stop the server with a fatal message.
    private static KnowledgeBase loadKbOrDie() {
        try {
            return KnowledgeBase.load(KB_PATH);
        } catch (Exception e) {
            System.out.println("FATAL: Could not load knowledge base.");
            System.out.println("Expected at: " + KB_PATH);
            e.printStackTrace();
            System.exit(1);
            return null; // unreachable, but required by Java
        }
    } // End loadKbOrDie

    // Read an entire request body into a UTF-8 string.
    private static String readAll(InputStream in) throws IOException {
        byte[] bytes = in.readAllBytes();
        return new String(bytes, StandardCharsets.UTF_8);
    } // End readAll

} // End Server
