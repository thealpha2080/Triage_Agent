import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Title: KnowledgeBase
 * Author: Ali Abbas
 * Description: Loads symptoms and aliases from a small JSON file.
 * Date: Dec 23, 2025
 * Version: 0.1.0
 */
public class KnowledgeBase {

    public final Map<String, Symptom> symptomByCode = new HashMap<>(); // Core symptom (target term)
    public final Map<String, List<String>> codesByAlias = new HashMap<>(); // Lists of String aliases
    public final List<String> allAliases = new ArrayList<>();

    public static KnowledgeBase load(Path file) throws IOException {
        String json = Files.readString(file, StandardCharsets.UTF_8);
        return parse(json);
    }

    private static KnowledgeBase parse(String json) {
        KnowledgeBase kb = new KnowledgeBase();

        // Split into symptom blocks by "code"
        // Works because the file is small + controlled.
        String[] blocks = json.split("\"code\"\\s*:\\s*\"");
        for (int i = 1; i < blocks.length; i++) {
            String block = blocks[i];

            String code = readUntil(block, "\"");
            String label = getField(block, "label");
            String category = getField(block, "category");
            double weight = getDoubleField(block, "weight", 1.0);
            boolean redFlag = getBoolField(block, "redFlag", false);
            List<String> aliases = getStringArray(block, "aliases");

            Symptom s = new Symptom(code, label, category, weight, redFlag, aliases);
            kb.symptomByCode.put(code, s);

            for (String a : aliases) {
                String aliasNorm = normalize(a);
                kb.codesByAlias.computeIfAbsent(aliasNorm, k -> new ArrayList<>()).add(code);
                kb.allAliases.add(aliasNorm);
            }
        }

        return kb;
    }

    private static String getField(String block, String key) {
        String needle = "\"" + key + "\"";
        int k = block.indexOf(needle);
        if (k < 0) return "";
        int colon = block.indexOf(":", k);
        int q1 = block.indexOf("\"", colon + 1);
        int q2 = block.indexOf("\"", q1 + 1);
        if (q1 < 0 || q2 < 0) return "";
        return block.substring(q1 + 1, q2);
    }

    private static double getDoubleField(String block, String key, double fallback) {
        String needle = "\"" + key + "\"";
        int k = block.indexOf(needle);
        if (k < 0) return fallback;
        int colon = block.indexOf(":", k);
        if (colon < 0) return fallback;
        int end = findNumberEnd(block, colon + 1);
        String num = block.substring(colon + 1, end).trim().replace(",", "");
        try { return Double.parseDouble(num); } catch (Exception e) { return fallback; }
    }

    private static boolean getBoolField(String block, String key, boolean fallback) {
        String needle = "\"" + key + "\"";
        int k = block.indexOf(needle);
        if (k < 0) return fallback;
        int colon = block.indexOf(":", k);
        if (colon < 0) return fallback;
        String tail = block.substring(colon + 1).trim();
        if (tail.startsWith("true")) return true;
        if (tail.startsWith("false")) return false;
        return fallback;
    }

    private static List<String> getStringArray(String block, String key) {
        String needle = "\"" + key + "\"";
        int k = block.indexOf(needle);
        if (k < 0) return List.of();
        int sb = block.indexOf("[", k);
        int se = block.indexOf("]", sb);
        if (sb < 0 || se < 0) return List.of();
        String inside = block.substring(sb + 1, se);

        List<String> out = new ArrayList<>();
        String[] parts = inside.split(",");
        for (String p : parts) {
            String t = p.trim();
            if (t.startsWith("\"") && t.endsWith("\"") && t.length() >= 2) {
                out.add(t.substring(1, t.length() - 1));
            }
        }
        return out;
    }

    private static int findNumberEnd(String s, int start) {
        int i = start;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (!(Character.isDigit(c) || c == '.' || c == '-' || c == ' ')) break;
            i++;
        }
        return i;
    }

    private static String readUntil(String s, String stop) {
        int idx = s.indexOf(stop);
        return (idx < 0) ? s : s.substring(0, idx);
    }

    public static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\s]", " ") // any symbol or punctuation that isn't a letter or number
                .replaceAll("\\s+", " ") // whitespaces
                .trim(); // trailing spaces
    }
}
