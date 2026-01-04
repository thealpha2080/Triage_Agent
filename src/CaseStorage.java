import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Title: CaseStorage
 * Author: Ali Abbas
 * Description: Saves cases to disk as JSON so they persist after server stops.
 * Date: Dec 23, 2025
 * Version: 1.0.0
 */
public class CaseStorage {

    // set the directory for cases to save in
    private static final Path CASES_DIR = Path.of("data/cases");

    public static void saveCase(Case c, String sessionId) {
        try {
            Files.createDirectories(CASES_DIR);

            String filename = "case_" + System.currentTimeMillis() + ".json";
            Path out = CASES_DIR.resolve(filename);

            String json = toJson(c, sessionId);
            Files.writeString(out, json, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            System.out.println("[CaseStorage] Saved: " + out);
        } catch (IOException e) {
            System.out.println("[CaseStorage] Failed to save case: " + e.getMessage());
        }
    }

    private static String toJson(Case c, String sessionId) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"caseId\":\"").append(escape(c.caseId)).append("\",");
        sb.append("\"sessionId\":\"").append(escape(sessionId)).append("\",");
        sb.append("\"startedEpochMs\":").append(c.startedEpochMs).append(",");
        sb.append("\"locked\":").append(c.locked).append(",");
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
