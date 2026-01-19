/**
 * Title: CaseStorage
 * Author: Ali Abbas
 * Description: Saves cases to disk as JSON so they persist after server stops.
 * Date: Jan 19, 2026
 * Version: 1.2.0
 */

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class CaseStorage {

    // set the directory for cases to save in
    private static final Path CASES_DIR = Path.of("data/cases");

    public static void saveCase(Case c, String sessionId) {
        try {
            // Make sure the parent directory exists so writes never fail silently
            Files.createDirectories(CASES_DIR);

            // Build a filename that is unique per save
            String filename = "case_" + System.currentTimeMillis() + ".json";
            Path out = CASES_DIR.resolve(filename);

            // Convert to JSON and write to disk atomically for this run
            String json = toJson(c, sessionId);
            Files.writeString(out, json, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            System.out.println("[CaseStorage] Saved: " + out);
        } catch (IOException e) {
            System.out.println("[CaseStorage] Failed to save case: " + e.getMessage());
        }
    }

    private static String toJson(Case c, String sessionId) {
        StringBuilder sb = new StringBuilder();
        // Start object
        sb.append("{");

        // Core identifiers
        sb.append("\"caseId\":\"").append(escape(c.caseId)).append("\",");
        sb.append("\"sessionId\":\"").append(escape(sessionId)).append("\",");
        sb.append("\"startedEpochMs\":").append(c.startedEpochMs).append(",");

        // Lock / triage state
        sb.append("\"locked\":").append(c.locked).append(",");
        sb.append("\"triageComplete\":").append(c.triageComplete).append(",");
        sb.append("\"triageLevel\":\"").append(escape(c.triageLevel)).append("\",");
        sb.append("\"triageConfidence\":").append(String.format(Locale.ROOT, "%.4f", c.triageConfidence)).append(",");

        // Reasons list (constructing the final report)
        sb.append("\"triageReasons\":[");
        for (int i = 0; i < c.triageReasons.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(escape(c.triageReasons.get(i))).append("\"");
        }
        sb.append("],");

        // Emergency symptoms are 'red flags' that vastly add to the triage results.
        sb.append("\"triageRedFlags\":[");
        for (int i = 0; i < c.triageRedFlags.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(escape(c.triageRedFlags.get(i))).append("\"");
        }
        sb.append("],");

        // Slots collected during the conversation
        sb.append("\"duration\":\"").append(escape(c.duration)).append("\",");
        sb.append("\"durationMinutes\":").append(String.format(Locale.ROOT, "%.2f", c.durationMinutes)).append(",");
        sb.append("\"severity\":\"").append(escape(c.severity)).append("\",");

        // Candidate symptom confidences
        sb.append("\"candidateConfidenceByCode\":{");
        int idx = 0;
        for (Map.Entry<String, Double> e : c.candidateConfidenceByCode.entrySet()) {
            if (idx++ > 0) sb.append(",");
            sb.append("\"").append(escape(e.getKey())).append("\":").append(String.format(Locale.ROOT, "%.4f", e.getValue()));
        }
        sb.append("},");

        // Raw notes (in order)
        sb.append("\"notes\":[");
        for (int i = 0; i < c.notes.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(escape(c.notes.get(i))).append("\"");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
