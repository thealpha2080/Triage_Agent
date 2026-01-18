import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * TestSuite
 *
 * This file runs a small set of automated checks against the ConversationEngine.
 * The goal is to simulate real chat turns and verify the triage result.
 *
 * Notes for AP-level readers:
 * - We are not using a full testing library here.
 * - Instead, we build simple "assert" helpers and print pass/fail results.
 * - Each test sends multiple messages to the engine, just like a real user.
 */
public class TestSuite {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        KnowledgeBase kb = KnowledgeBase.load(Path.of("data/kb_v1.json"));

        runScenario(kb,
                "Red flag: shortness of breath should escalate to 911",
                List.of(
                        "I have shortness of breath",
                        "2 hours",
                        "severe"
                ),
                "911"
        );

        runScenario(kb,
                "Moderate fever for 2 hours should recommend doctor visit",
                List.of(
                        "I have a fever",
                        "2 hours",
                        "moderate",
                        "that's it"
                ),
                "Doctor visit recommended"
        );

        runScenario(kb,
                "Mild runny nose for 30 minutes should be self-care",
                List.of(
                        "runny nose",
                        "30 minutes",
                        "mild",
                        "that's it"
                ),
                "Self-care / monitor"
        );

        System.out.println("\n==== Test Summary ====");
        System.out.println("Passed: " + passed);
        System.out.println("Failed: " + failed);

        if (failed > 0) {
            System.exit(1);
        }
    }

    /**
     * Runs a single scenario by sending user messages to the engine in order.
     * The last bot response should include a locked triage level.
     */
    private static void runScenario(KnowledgeBase kb, String name, List<String> inputs, String expectedLevel) {
        String sessionId = "test-" + UUID.randomUUID();
        Map<String, ConversationState> sessions = new HashMap<>();
        ConversationEngine engine = new ConversationEngine(sessions, "boot-test", kb, new JsonCaseRepository());

        String lastResponse = "";
        for (String input : inputs) {
            lastResponse = engine.handle(sessionId, input);
        }

        String triageLevel = getJsonString(lastResponse, "triageLevel");
        boolean locked = getJsonBoolean(lastResponse, "locked");

        assertTrue(name + " (locked response)", locked);
        assertEquals(name + " (triage level)", expectedLevel, triageLevel);

        if (!locked || !Objects.equals(expectedLevel, triageLevel)) {
            System.out.println("  Debug response JSON: " + lastResponse);
        }
    }

    // -----------------------------
    // Simple assertion helpers

    private static void assertEquals(String label, String expected, String actual) {
        if (Objects.equals(expected, actual)) {
            passed++;
            System.out.println("PASS: " + label);
        } else {
            failed++;
            System.out.println("FAIL: " + label);
            System.out.println("  Expected: " + expected);
            System.out.println("  Actual:   " + actual);
        }
    }

    private static void assertTrue(String label, boolean actual) {
        if (actual) {
            passed++;
            System.out.println("PASS: " + label);
        } else {
            failed++;
            System.out.println("FAIL: " + label);
            System.out.println("  Expected: true");
            System.out.println("  Actual:   false");
        }
    }

    // -----------------------------
    // Minimal JSON parsing helpers

    /**
     * Gets a string value from a flat JSON object like: {"key":"value"}.
     * This is a small helper, not a full JSON parser.
     */
    private static String getJsonString(String body, String key) {
        if (body == null) return "";

        String needle = "\"" + key + "\"";
        int k = body.indexOf(needle);
        if (k < 0) return "";

        int colon = body.indexOf(":", k + needle.length());
        if (colon < 0) return "";

        int firstQuote = body.indexOf("\"", colon + 1);
        if (firstQuote < 0) return "";

        int secondQuote = body.indexOf("\"", firstQuote + 1);
        if (secondQuote < 0) return "";

        return body.substring(firstQuote + 1, secondQuote);
    }

    /**
     * Gets a boolean value from a flat JSON object like: {"key":true}.
     */
    private static boolean getJsonBoolean(String body, String key) {
        if (body == null) return false;

        String needle = "\"" + key + "\"";
        int k = body.indexOf(needle);
        if (k < 0) return false;

        int colon = body.indexOf(":", k + needle.length());
        if (colon < 0) return false;

        String tail = body.substring(colon + 1).trim();
        return tail.startsWith("true");
    }
}
