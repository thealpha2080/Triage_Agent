/**
 * Title: JsonCaseRepository
 * Author: Ali Abbas
 * Description: JSON-backed repository that reads/writes case files on disk.
 * Date: Sep 28, 2025
 * Version: 1.0.0
 */
public class JsonCaseRepository implements CaseRepository {

    @Override
    public void saveCase(Case c, String sessionId) {
        CaseStorage.saveCase(c, sessionId);
    }

    @Override
    public java.util.List<CaseSummary> listCases(int limit) {
        java.util.List<CaseSummary> summaries = new java.util.ArrayList<>();
        java.nio.file.Path casesDir = java.nio.file.Path.of("data/cases");
        if (!java.nio.file.Files.exists(casesDir)) {
            return summaries;
        }

        try (java.util.stream.Stream<java.nio.file.Path> paths = java.nio.file.Files.list(casesDir)) {
            paths.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .forEach(path -> {
                        try {
                            String json = java.nio.file.Files.readString(path);
                            summaries.add(parseSummary(json));
                        } catch (Exception e) {
                            System.out.println("[JsonCaseRepository] Failed to read " + path + ": " + e.getMessage());
                        }
                    });
        } catch (Exception e) {
            System.out.println("[JsonCaseRepository] Failed to list cases: " + e.getMessage());
        }

        // Sort newest-first so the Database tab shows most recent items.
        summaries.sort((a, b) -> Long.compare(b.startedEpochMs, a.startedEpochMs));
        if (summaries.size() > limit) {
            return new java.util.ArrayList<>(summaries.subList(0, limit));
        }
        return summaries;
    }

    private CaseSummary parseSummary(String json) {
        // Parse a minimal subset of fields needed for the history list UI.
        String caseId = getString(json, "caseId");
        String sessionId = getString(json, "sessionId");
        long startedEpochMs = getLong(json, "startedEpochMs");
        String triageLevel = getString(json, "triageLevel");
        double triageConfidence = getDouble(json, "triageConfidence");
        String duration = getString(json, "duration");
        String severity = getString(json, "severity");
        int notesCount = countArrayItems(json, "notes");
        int redFlagCount = countArrayItems(json, "triageRedFlags");
        return new CaseSummary(caseId, sessionId, startedEpochMs, triageLevel, triageConfidence, duration, severity, notesCount, redFlagCount);
    }

    private String getString(String json, String key) {
        String needle = "\"" + key + "\":\"";
        int start = json.indexOf(needle);
        if (start < 0) return "";
        int valueStart = start + needle.length();
        int end = json.indexOf("\"", valueStart);
        if (end < 0) return "";
        return json.substring(valueStart, end);
    }

    private long getLong(String json, String key) {
        String needle = "\"" + key + "\":";
        int start = json.indexOf(needle);
        if (start < 0) return 0L;
        int valueStart = start + needle.length();
        int end = findNumberEnd(json, valueStart);
        if (end < 0) return 0L;
        return parseLongSafe(json.substring(valueStart, end).trim());
    }

    private double getDouble(String json, String key) {
        String needle = "\"" + key + "\":";
        int start = json.indexOf(needle);
        if (start < 0) return 0.0;
        int valueStart = start + needle.length();
        int end = findNumberEnd(json, valueStart);
        if (end < 0) return 0.0;
        return parseDoubleSafe(json.substring(valueStart, end).trim());
    }

    private int findNumberEnd(String json, int start) {
        int i = start;
        while (i < json.length()) {
            char c = json.charAt(i);
            if ((c >= '0' && c <= '9') || c == '.' || c == '-') {
                i++;
                continue;
            }
            break;
        }
        return i;
    }

    private long parseLongSafe(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private double parseDoubleSafe(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private int countArrayItems(String json, String key) {
        String needle = "\"" + key + "\":[";
        int start = json.indexOf(needle);
        if (start < 0) return 0;
        int arrayStart = start + needle.length();
        int arrayEnd = json.indexOf("]", arrayStart);
        if (arrayEnd < 0) return 0;
        String arrayBody = json.substring(arrayStart, arrayEnd).trim();
        if (arrayBody.isEmpty()) return 0;

        int count = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = 0; i < arrayBody.length(); i++) {
            char c = arrayBody.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (c == '\\') {
                escape = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                if (!inString) {
                    count++;
                }
            }
        }
        return count;
    }
}
