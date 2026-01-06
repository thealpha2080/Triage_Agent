import java.util.*;

/**
 * Title: ConversationEngine
 * Author: Ali Abbas
 * Description: Phase 1 chat UX with slot collection and deterministic replies.
 *              Adds triage scoring with red-flag overrides and summary output.
 * Date: Jan 19, 2026
 * Version: 1.2.0
 */
public class ConversationEngine {

    private static final List<String> ACKS = List.of(
            "Got it — I can help you sort this out.",
            "Okay. Let’s walk through it step by step.",
            "Thanks. I’ll keep it simple and ask one thing at a time."
    );

    private final Map<String, ConversationState> sessions;
    private final String bootId;
    private final KnowledgeBase kb;

    /**
     * Helper structure to keep both a user-friendly duration label and a numeric value in minutes.
     * This lets us display the text they typed while still scoring consistently.
     */
    private static class DurationParseResult {
        String label;
        double minutes;
        DurationParseResult(String label, double minutes) {
            this.label = label;
            this.minutes = minutes;
        }
    }

    public ConversationEngine(Map<String, ConversationState> sessions, String bootId, KnowledgeBase kb) {
        this.sessions = sessions;
        this.bootId = bootId;
        this.kb = kb;
    }

    public String handle(String sessionId, String text) {
        ConversationState st = sessions.computeIfAbsent(sessionId, ConversationState::new);

        // New server run => new case, even if browser keeps same sessionId
        if (!bootId.equals(st.bootSeen) || st.activeCase == null) {
            st.bootSeen = bootId;
            st.activeCase = new Case();
            System.out.println("[ConversationEngine] New case started for session " + sessionId);
        }

        Case c = st.activeCase;

        text = (text == null) ? "" : text.trim();
        if (text.isEmpty()) {
            return botJson("Type what’s going on (you can list symptoms like: “fever, cough, sore throat”).");
        }

        if (c.locked) {

            // When a case is locked, immediately return the existing summary so the user
            // understands the final recommendation without confusion.
            return botJson(triageSummary(c));
        }

        // Always record the raw message
        c.notes.add(text);
        extractAndStoreCandidates(c, text);

        String reply = buildReply(c, text);
        return botJson(reply);
    }

    // ------------------------------------------------------------
    // Phase 1 reply

    private String buildReply(Case c, String text) {
        String norm = normalize(text);
        boolean unclear = isUnclear(norm);
        boolean greeting = isGreeting(norm);

        // 1) First message: acknowledge + encourage symptom listing
        if (c.userMessageCount() == 1) {
            String ack = pickAck(c.caseId);

            // If they greeted us, greet back but push toward symptoms
            if (greeting) {
                return nextNonRepeating(c, "greet_pushy",
                        "Hi again! I’m here to help, but I need symptoms to guide you. Describe what you’re feeling (for example: “chest tightness for 90 minutes, moderate”).");
            }


            if (unclear) {
                c.mode = Case.Mode.CLARIFYING;
                return nextNonRepeating(c, "clarify_1",
                        ack + " I didn’t fully understand. Rephrase in ONE sentence, or list symptoms separated by commas.");
            }

            c.mode = Case.Mode.GATHER_SLOTS;

            // Try to pull slots immediately if they included them
            fillSlotsFromText(c, norm);

            // Ask only what’s missing
            return askNextMissingSlotOrCollect(c, ack);
        }

        // 2) If unclear, handle CLARIFYING path (don’t loop forever)
        if (unclear) {
            c.mode = Case.Mode.CLARIFYING;

            // If they keep sending unclear things, give a concrete format
            if ("clarify_2".equals(c.lastBotKey)) {
                return nextNonRepeating(c, "clarify_format",
                        "Try this format: “Symptoms: ____. Started: ____. Severity: mild/moderate/severe.”");
            }

            return nextNonRepeating(c, "clarify_2",
                    "I’m not fully sure I understood. Rephrase in one sentence or list symptoms separated by commas.");
        }

        // 3) If we were clarifying and now they are clear, move forward
        if (c.mode == Case.Mode.CLARIFYING) {
            c.mode = Case.Mode.GATHER_SLOTS;
        }

        // 4) Always attempt to fill slots from any clear message
        fillSlotsFromText(c, norm);

        // 5) If slots still missing, ask one at a time
        if (c.duration.isEmpty() || c.severity.isEmpty()) {
            c.mode = Case.Mode.GATHER_SLOTS;
            return askNextMissingSlotOrCollect(c, "");
        }

        // 6) Slots present => collect more symptoms until user indicates done
        c.mode = Case.Mode.COLLECT_MORE;

        // If enough info is gathered, lock in a triage decision
        String triageNow = maybeTriage(c, norm);
        if (triageNow != null) {
            return triageNow;
        }

        if (userSeemsDone(norm)) {
            c.mode = Case.Mode.READY;
            return "Alright. Here’s what I have so far:\n"
                    + "- Duration: " + c.duration + "\n"
                    + "- Severity: " + c.severity + "\n"
                    + "- Notes count: " + c.notes.size() + "\n\n"
                    + "Next phase will detect symptoms from the notes. If you want, add one more detail (age group, fever temperature, or meds taken).";
        }

        return nextNonRepeating(c, "collect_more",
                "Noted. Anything else you’re noticing? (list symptoms, even minor)");
    }

    private String askNextMissingSlotOrCollect(Case c, String prefixAck) {
        if (c.duration.isEmpty()) {
            // Ask for duration with flexible units
            return nextNonRepeating(c, "ask_duration",
                    (prefixAck.isEmpty() ? "" : prefixAck + " ")
                            + "How long has this been going on? You can answer with minutes, hours, days," +
                            " or weeks (examples: “45 minutes”, “2 hours”, “3 days”).");
        }

        if (c.severity.isEmpty()) {
            return nextNonRepeating(c, "ask_severity",
                    (prefixAck.isEmpty() ? "" : prefixAck + " ")
                            + "Overall, how bad is it right now? (mild / moderate / severe)");
        }

        return nextNonRepeating(c, "collect_more",
                (prefixAck.isEmpty() ? "" : prefixAck + " ")
                        + "Got it. List any other symptoms you’re noticing (even if they seem minor).");
    }

    private void fillSlotsFromText(Case c, String norm) {
        if (c.duration.isEmpty()) {
            // Try to parse any written duration (minutes/hours/days/weeks) so the user has flexibility
            DurationParseResult parsed = parseDuration(norm);
            if (parsed != null) {
                c.duration = parsed.label;
                c.durationMinutes = parsed.minutes;
            }
        }
        if (c.severity.isEmpty()) {
            c.severity = extractSeverity(norm);
        }
    }

    // ------------------------------------------------------------
    // Slot extraction

    private String extractSeverity(String norm) {
        if (norm.contains("mild")) return "mild";
        if (norm.contains("moderate")) return "moderate";
        if (norm.contains("severe")) return "severe";
        if (norm.contains("really bad")) return "severe";
        return "";
    }

    /**
     * Duration can be provided in minutes, hours, days, or weeks. The method extracts
     * a numeric value (in minutes) and also keeps a friendly label for display.
     * @param  norm
     * @return
     */
    private DurationParseResult parseDuration(String norm) {
        // Alternate phrases that lack numbers but imply short durations
        if (norm.contains("few minutes")) {
            return new DurationParseResult("few minutes (~10)", 10);
        }
        if (norm.contains("few hours")) {
            return new DurationParseResult("few hours (~180)", 180);
        }
        if (norm.contains("half hour") || norm.contains("half an hour")) {
            return new DurationParseResult("30 minutes", 30);
        }
        if (norm.contains("hour and a half") || norm.contains("an hour and a half")) {
            return new DurationParseResult("90 minutes", 90);
        }

        // General numeric extraction: look for "<number> <unit>"
        // Examples: "90 minutes", "1.5 hours", "2 days", "3 weeks"
        String[] tokens = norm.split(" ");
        for (int i = 0; i < tokens.length; i++) {
            Double value = tryParseDouble(tokens[i]);
            if (value == null) {
                // Handle spoken forms like "one", "two", "three" if needed in the future
                continue;
            }

            // Peek the next token to identify the unit
            if (i + 1 >= tokens.length) {
                continue;
            }
            String unit = tokens[i + 1];

            // Normalize unit text
            if (unit.startsWith("min")) {
                double minutes = value;
                return new DurationParseResult(formatDurationLabel(value, "minute"), minutes);
            }
            if (unit.startsWith("hour") || unit.startsWith("hr")) {
                double minutes = value * 60;
                return new DurationParseResult(formatDurationLabel(value, "hour"), minutes);
            }
            if (unit.startsWith("day")) {
                double minutes = value * 60 * 24;
                return new DurationParseResult(formatDurationLabel(value, "day"), minutes);
            }
            if (unit.startsWith("week")) {
                double minutes = value * 60 * 24 * 7;
                return new DurationParseResult(formatDurationLabel(value, "week"), minutes);
            }
        }

        // If nothing matched, return null to signal "not found"
        return null;
    }

    private Double tryParseDouble(String token) {
        // Handle quick word-based numbers for flexibility
        if ("a".equals(token) || "an".equals(token) || "one".equals(token)) {
            return 1.0;
        }
        if ("two".equals(token) || "couple".equals(token)) {
            return 2.0;
        }
        if ("three".equals(token)) {
            return 3.0;
        }
        if ("four".equals(token)) {
            return 4.0;
        }
        if ("five".equals(token)) {
            return 5.0;
        }

        try {
            return Double.parseDouble(token);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String formatDurationLabel(double value, String unit) {
        // Avoid showing trailing .0 for whole numbers while keeping clarity
        boolean whole = Math.abs(value - Math.round(value)) < 0.0001;
        String numberText = whole ? String.valueOf((long) Math.round(value)) : String.format(Locale.ROOT, "%.2f", value);
        // Pluralize when needed
        String unitText = (Math.abs(value) == 1.0) ? unit : unit + "s";
        return numberText + " " + unitText;
    }

    private double computeDurationFactor(Case c) {
        // Default factor if no duration is known
        double durationFactor = 1.0;

        // If we have a numeric duration in minutes, use it directly
        if (c.durationMinutes > 0) {
            double minutes = c.durationMinutes;

            // 0 - 2 hours: slight bump because it is acute and recent
            if (minutes <= 120) {
                durationFactor = 1.05;
            }
            // Same-day but longer than 2 hours
            else if (minutes <= 1440) { // 24 hours
                durationFactor = 1.10;
            }
            // 1-3 days
            else if (minutes <= 4320) { // 3 days
                durationFactor = 1.15;
            }
            // 3-7 days
            else if (minutes <= 10080) { // 7 days
                durationFactor = 1.20;
            }
            // 1-2 weeks
            else if (minutes <= 20160) { // 14 days
                durationFactor = 1.25;
            }
            // More than 2 weeks
            else {
                durationFactor = 1.30;
            }
            return durationFactor;
        }

        // Fallback: check the textual bucket for older saved cases
        String d = c.duration == null ? "" : c.duration.toLowerCase(Locale.ROOT);
        if (d.contains("today")) {
            durationFactor = 1.10;
        } else if (d.contains("1-2 days") || d.contains("1 2 days") || d.contains("yesterday")) {
            durationFactor = 1.05;
        } else if (d.contains("3-7 days") || d.contains("3 7 days")) {
            durationFactor = 1.15;
        } else if (d.contains("1-2 weeks") || d.contains("1 2 weeks")) {
            durationFactor = 1.20;
        } else if (d.contains("2+ weeks") || d.contains("2 weeks")) {
            durationFactor = 1.25;
        }

        return durationFactor;
    }

    private boolean userSeemsDone(String norm) {
        return norm.contains("that s it")
                || norm.contains("thats it")
                || norm.contains("nothing else")
                || norm.contains("no more")
                || norm.equals("done");
    }

    private void extractAndStoreCandidates(Case c, String rawText) {
        String norm = KnowledgeBase.normalize(rawText);
        if (norm.isEmpty()) return;

        List<String> phrases = makeNGrams(norm, 4);

        boolean anyExact = false;

        // Pass 1: exact phrase matches
        for (String ph : phrases) {
            List<String> codes = kb.codesByAlias.get(ph);
            if (codes == null) continue;
            anyExact = true;
            for (String code : codes) {
                bumpCandidate(c, code, 1.0);
                System.out.println("Candidates now: " + c.candidateConfidenceByCode);
            }
        }

        // Pass 2: fuzzy token matches (only if no exact)
        if (!anyExact) {
            for (String token : norm.split(" ")) {
                if (token.length() < 3) continue;
                FuzzyHit hit = bestFuzzy(token, kb.allAliases);
                if (hit != null && hit.score >= 0.80) {
                    List<String> codes = kb.codesByAlias.get(hit.alias);
                    if (codes != null) {
                        for (String code : codes) bumpCandidate(c, code, hit.score);
                        System.out.println("Candidates now (fuzzy): " + c.candidateConfidenceByCode);
                        System.out.println("[SymptomMatch] Fuzzy token=\"" + token + "\" matched alias=\"" + hit.alias + "\" score=" + hit.score);

                    }
                }
            }
        }
    }

    private void bumpCandidate(Case c, String code, double confidence) {
        double prev = c.candidateConfidenceByCode.getOrDefault(code, 0.0);
        if (confidence > prev) c.candidateConfidenceByCode.put(code, confidence);
    }

    private List<String> makeNGrams(String norm, int maxWords) {
        String[] w = norm.split(" ");
        List<String> out = new ArrayList<>();
        for (int i = 0; i < w.length; i++) {
            StringBuilder sb = new StringBuilder();
            for (int len = 1; len <= maxWords && i + len <= w.length; len++) {
                if (len == 1) sb.append(w[i]);
                else sb.append(" ").append(w[i + len - 1]);
                out.add(sb.toString());
            }
        }
        return out;
    }

    private static class FuzzyHit {
        String alias;
        double score;
        FuzzyHit(String alias, double score) {
            this.alias = alias;
            this.score = score;
        }
    }

    private FuzzyHit bestFuzzy(String token, List<String> aliases) {
        FuzzyHit best = null;
        for (String a : aliases) {
            double s = similarity(token, a);
            if (best == null || s > best.score) best = new FuzzyHit(a, s);
        }
        return best;
    }

    // Simple similarity based on edit distance ratio (deterministic)
    private double similarity(String a, String b) {
        int d = editDistance(a, b);
        int max = Math.max(a.length(), b.length());
        if (max == 0) return 1.0;
        return 1.0 - ((double) d / max);
    }

    private int editDistance(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;

        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = (a.charAt(i - 1) == b.charAt(j - 1)) ? 0 : 1;
                dp[i][j] = Math.min(
                        Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost
                );
            }
        }
        return dp[a.length()][b.length()];
    }


    // ------------------------------------------------------------
    // Unclear detection (use normalized string)

    private boolean isUnclear(String norm) {
        if (norm.isEmpty()) return true;

        Set<String> filler = Set.of("idk", "help", "please", "uh", "umm", "yo", "hey");
        if (filler.contains(norm)) return true;

        String[] parts = norm.split(" ");
        int realWords = 0;
        for (String p : parts) {
            if (p.length() >= 3) realWords++;
        }
        return realWords < 2;
    }

    private String normalize(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean isGreeting(String norm) {
        if (norm.isEmpty()) return false;
        Set<String> greetings = Set.of("hi", "hello", "hey", "hola", "greetings", "good morning", "good evening", "good afternoon");
        if (greetings.contains(norm)) return true;
        for (String g : greetings) {
            if (norm.startsWith(g + " ")) return true;
        }
        return false;
    }

    private String nextNonRepeating(Case c, String key, String msg) {
        if (key.equals(c.lastBotKey)) {
            return msg; // already said it; but we won't spam because caller uses different keys
        }
        c.lastBotKey = key;
        return msg;
    }

    private String pickAck(String caseId) {
        int idx = Math.abs(caseId.hashCode()) % ACKS.size();
        return ACKS.get(idx);
    }

    // ------------------------------------------------------------
    // JSON builder

    private String botJson(String text) {
        return "{"
                + "\"type\":\"bot\","
                + "\"text\":\"" + escapeJson(text) + "\""
                + "}";
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    // ------------------------------------------------------------
    // Triage logic

    private String maybeTriage(Case c, String norm) {
        if (c.triageComplete) return triageSummary(c);
        if (c.candidateConfidenceByCode.isEmpty()) return null;
        if (c.duration.isEmpty() || c.severity.isEmpty()) return null;

        boolean userReady = userSeemsDone(norm) || c.notes.size() >= 3;
        if (!userReady) return null;

        // Severity weighting uses standard switch syntax for clarity
        double severityFactor = switch (c.severity) {
            case "moderate" -> 1.15;
            case "severe" -> 1.35;
            default -> 1.0;
        };

        // Duration weighting uses a helper that understands minutes/hours/days/weeks
        double durationFactor = computeDurationFactor(c);

        // Collect running stats for the decision
        List<String> redFlags = new ArrayList<>(); // stores red-flag symptom labels with confidence
        List<String> reasons = new ArrayList<>();  // stores non-red-flag contributing symptoms
        double score = 0.0;                        // accumulates weighted sum used for non-red decisions

        // Walk over every candidate symptom detected in the conversation
        for (Map.Entry<String, Double> e : c.candidateConfidenceByCode.entrySet()) {
            // Look up the symptom definition (weight + redFlag)
            Symptom s = kb.symptomByCode.get(e.getKey());
            if (s == null) continue; // Safety: skip if KB is missing the code

            double confidence = e.getValue(); // value already normalized to 0..1

            // Weighted score uses symptom weight, user-provided severity, and duration
            double weighted = s.weight * confidence * severityFactor * durationFactor;
            score += weighted; // add to the total score

            // Red-flag detection: high confidence on a redFlag symptom is enough to escalate
            if (s.redFlag && confidence >= 0.60) {
                String formatted = s.label + " (conf " + String.format(Locale.ROOT, "%.0f%%", confidence * 100) + ")";
                redFlags.add(formatted);
            }
            // Non-red contributions above 40% are recorded as supporting reasons
            else if (confidence >= 0.40) {
                String formatted = s.label + " (conf " + String.format(Locale.ROOT, "%.0f%%", confidence * 100) + ")";
                reasons.add(formatted);
            }
        }

        // Decide the final level and a coarse confidence for that level
        String level;
        double confScore;

        // Branch 1: any strong red flag => immediate ER recommendation
        if (!redFlags.isEmpty()) {
            level = "ER now";
            confScore = 0.90; // high confidence because red-flag presence is decisive
            reasons.add("Red-flag symptom detected");
        }
        // Branch 2: heavy weighted score => urgent doctor evaluation
        else if (score >= 8.0) {
            level = "Doctor within 24 hours";
            confScore = Math.min(1.0, 0.70 + score / 15.0);
        }
        // Branch 3: moderate score => doctor visit recommended
        else if (score >= 4.0) {
            level = "Doctor visit recommended";
            confScore = Math.min(1.0, 0.60 + score / 12.0);
        }
        // Branch 4: low score => monitor at home with caution
        else {
            level = "Self-care / monitor";
            confScore = Math.min(1.0, 0.50 + score / 10.0);
            if (reasons.isEmpty()) {
                reasons.add("No significant symptoms detected yet");
            }
        }

        // Store final state on the case for persistence and later display
        c.triageComplete = true;
        c.locked = true;
        c.triageLevel = level;
        c.triageConfidence = confScore;

        // Keep the reasons and red flags in insertion order for readability
        c.triageReasons.clear();
        c.triageReasons.addAll(reasons);
        c.triageRedFlags.clear();
        c.triageRedFlags.addAll(redFlags);

        return triageSummary(c);
    }

    private String triageSummary(Case c) {
        // Build a readable multi-line summary so the UI can show decisions directly
        StringBuilder sb = new StringBuilder();
        sb.append("Triage result: ").append(c.triageLevel.isEmpty() ? "Pending" : c.triageLevel);
        if (c.triageConfidence > 0) {
            sb.append(" (confidence ").append(String.format(Locale.ROOT, "%.0f%%", c.triageConfidence * 100)).append(")");
        }
        // Show red flags first because they determine the strictest path
        if (!c.triageRedFlags.isEmpty()) {
            sb.append("\nRed flags: ").append(String.join(", ", c.triageRedFlags));
        }
        // Show supporting reasons so the student and user can understand the score
        if (!c.triageReasons.isEmpty()) {
            sb.append("\nReasons: ").append(String.join("; ", c.triageReasons));
        }
        if (c.duration != null && !c.duration.isEmpty()) {
            sb.append("\nDuration noted: ").append(c.duration);
        }
        sb.append("\nCase locked. Start a new session to begin another triage.");
        return sb.toString();
    }
}
