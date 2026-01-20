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

    // 1) Reads the knowledgebase in the data folder as UTF-8,
    // then returns it, parsed into json.
    public static KnowledgeBase load(Path file) throws IOException {
        String json = Files.readString(file, StandardCharsets.UTF_8);
        return parse(json);
    } // End load

    // Build the JSON knowldgebase via manual parsing in the memory
    private static KnowledgeBase parse(String json) {
        KnowledgeBase kb = new KnowledgeBase();

        // Split into symptom blocks by "code"
        String[] blocks = json.split("\"code\"\\s*:\\s*\"");
        for (int i = 1; i < blocks.length; i++) {
            String block = blocks[i];

            // extraction of labels, categories, wieghts, redFlags, and all aliases
            String code = readUntil(block, "\"");
            String label = getField(block, "label");
            String category = getField(block, "category");
            double weight = getDoubleField(block, "weight", 1.0);
            boolean redFlag = getBoolField(block, "redFlag", false);
            List<String> aliases = getStringArray(block, "aliases");

            // Create a new object to store the symptom
            Symptom s = new Symptom(code, label, category, weight, redFlag, aliases);
            kb.symptomByCode.put(code, s);

            // assign aliases to list
            for (String a : aliases) {
                String aliasNorm = normalize(a);
                kb.codesByAlias.computeIfAbsent(aliasNorm, k -> new ArrayList<>()).add(code);
                kb.allAliases.add(aliasNorm);
            }
        }

        return kb;
    } // End parse

    // extract text from the json
    private static String getField(String block, String key) {
        String needle = "\"" + key + "\"";
        int k = block.indexOf(needle);
        if (k < 0) return "";
        int colon = block.indexOf(":", k);
        int q1 = block.indexOf("\"", colon + 1);
        int q2 = block.indexOf("\"", q1 + 1);
        if (q1 < 0 || q2 < 0) return "";
        return block.substring(q1 + 1, q2);
    } // End getField

    // Extract numbers form json
    private static double getDoubleField(String block, String key, double fallback) {
        String needle = "\"" + key + "\"";
        int k = block.indexOf(needle);
        if (k < 0) return fallback;
        int colon = block.indexOf(":", k);
        if (colon < 0) return fallback;
        int end = findNumberEnd(block, colon + 1);
        String num = block.substring(colon + 1, end).trim().replace(",", "");
        try {
            return Double.parseDouble(num);
        } catch (NumberFormatException e) {
            return fallback;
        }
    } // End getDoubleField

    // extract a boolean from json
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
    } // End getBoolField

    // extract a array of strings from json
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
    } // End getStringArray

    // Find the end index of a number token within a string.
    private static int findNumberEnd(String s, int start) {
        int i = start;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (!(Character.isDigit(c) || c == '.' || c == '-' || c == ' ')) break;
            i++;
        }
        return i;
    } // End findNumberEnd

    // Read the substring until the stop marker is found.
    private static String readUntil(String s, String stop) {
        int idx = s.indexOf(stop);
        return (idx < 0) ? s : s.substring(0, idx);
    } // End readUntil

    // Convert the text into a standardized format
    public static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\s]", " ") // any symbol or punctuation that isn't a letter or number
                .replaceAll("\\s+", " ") // whitespaces
                .trim(); // trailing spaces
    } // End normalize
} // End KnowledgeBase class
