import java.util.*;

/**
 * Title: ConversationEngine
 * Author: Ali Abbas
 * Description: Phase 1 chat UX with slot collection and deterministic replies.
 *              Adds triage scoring with red-flag overrides and summary output.
 * Date: Jan 19, 2026
 * Version: 1.5.0
 */
public class ConversationEngine {

    private static final List<String> ACKS = List.of(
            "Got it — I can help you sort this out.",
            "Okay. Let’s walk through it step by step.",
            "Thanks. I’ll keep it simple and ask one thing at a time."
    );
    private static final Set<String> DURATION_CONTEXT = Set.of(
            "for", "since", "past", "last", "lasting", "started", "been"
    );
    private static final Set<String> CORRECTION_TOKENS = Set.of(
            "actually", "just", "only"
    );

    private final Map<String, ConversationState> sessions;
    private final String bootId;
    private final KnowledgeBase kb;
    private final CaseRepository repository;

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

    public ConversationEngine(Map<String, ConversationState> sessions, String bootId, KnowledgeBase kb, CaseRepository repository) {
        this.sessions = sessions;
        this.bootId = bootId;
        this.kb = kb;
        this.repository = repository;
    }

    public static class BotResponse {
        String text;
        List<String> options; // quick replies for severity/duration
        BotResponse(String text) { this.text = text; }
        BotResponse(String text, List<String> options) { this.text = text; this.options = options; }
    }

    public String handle(String sessionId, String text) {
        ConversationState st = sessions.computeIfAbsent(sessionId, ConversationState::new);

        // New server run => new case, even if browser keeps same sessionId
        if (!bootId.equals(st.bootSeen) || st.activeCase == null) {
            if (st.activeCase != null && !st.activeCase.notes.isEmpty()) {
                persistCase(st.activeCase, sessionId);
            }
            st.bootSeen = bootId;
            st.activeCase = new Case();
            System.out.println("[ConversationEngine] New case started for session " + sessionId);
        }

        Case c = st.activeCase;

        text = (text == null) ? "" : text.trim();
        if (text.isEmpty()) {
            return respond(new BotResponse(
                    "Type what’s going on (you can list symptoms like: “fever, cough, sore throat”)."
            ), c, sessionId);
        }

        if (c.locked) {
            // When a case is locked, immediately return the existing summary so the user
            // understands the final recommendation without confusion.
            return respond(new BotResponse(triageSummary(c)), c, sessionId);
        }

        // Always record the raw message
        c.notes.add(text);
        extractAndStoreCandidates(c, text);

        BotResponse reply = buildReply(c, text);
        return respond(reply, c, sessionId);
    }

    // ------------------------------------------------------------
    // Conversation flow

    private BotResponse buildReply(Case c, String text) {
        String norm = normalize(text);
        DurationParseResult durationAttempt = parseDuration(norm);
        String severityAttempt = extractSeverity(norm);
        boolean respondingToDuration = "ask_duration".equals(c.lastBotKey) && durationAttempt != null;
        boolean respondingToSeverity = "ask_severity".equals(c.lastBotKey) && !severityAttempt.isEmpty();
        boolean unclear = isUnclear(norm);
        if (respondingToDuration || respondingToSeverity) {
            unclear = false;
        }
        boolean greeting = isGreeting(norm);

        // 1) First message: acknowledge + encourage symptom listing
        if (c.userMessageCount() == 1) {
            // Reset clarify counter on the first real user turn
            c.unclearCount = 0;
            String ack = pickAck(c.caseId);

            // If they greeted us, greet back but push toward symptoms
            if (greeting) {
                return new BotResponse(nextNonRepeating(c, "greet_pushy",
                        "Hi again! I’m here to help, but I need symptoms to guide you. Describe what you’re feeling (for example: “chest tightness for 90 minutes, moderate”)."));
            }

            if (unclear) {
                c.mode = Case.Mode.CLARIFYING;
                return new BotResponse(nextNonRepeating(c, "clarify_1",
                        ack + " I didn’t fully understand. Tell me a few symptoms or what feels worst right now."));
            }

            c.mode = Case.Mode.GATHER_SLOTS;

            // Try to pull slots immediately if they included them
            fillSlotsFromText(c, norm);

            // Ask only what’s missing
            return askNextMissingSlotOrCollect(c, ack);
        }

        // 2) If unclear, run the clarifying logic path
        if (unclear) {
            c.mode = Case.Mode.CLARIFYING;
            c.unclearCount += 1; // track how often we asked for clarity

            // If they keep sending unclear things, give a concrete format
            if (c.unclearCount >= 3) {
                return new BotResponse(nextNonRepeating(c, "clarify_format",
                        "I’m still having trouble. Tell me the main symptoms and how long they’ve been happening."));
            }
            if ("clarify_2".equals(c.lastBotKey)) {
                return new BotResponse(nextNonRepeating(c, "clarify_2b",
                        "Can you share a couple symptoms and roughly how long they’ve been going on?"));
            }

            return new BotResponse(nextNonRepeating(c, "clarify_2",
                    "I’m not fully sure I understood. Tell me the key symptoms and when they started."));
        }

        // 3) If clarifying worked, move forth
        if (c.mode == Case.Mode.CLARIFYING) {
            c.mode = Case.Mode.GATHER_SLOTS;
            c.unclearCount = 0; // reset once we get a clear message
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
        BotResponse triageNow = maybeTriage(c, norm);
        if (triageNow != null) {
            return triageNow;
        }

        if (userSeemsDone(norm)) {
            c.mode = Case.Mode.READY;
            return new BotResponse("Alright. Here’s what I have so far:\n"
                    + "- Duration: " + c.duration + "\n"
                    + "- Severity: " + c.severity + "\n"
                    + "- Notes count: " + c.notes.size() + "\n\n"
                    + "Next phase will detect symptoms from the notes. If you want, add one more detail (age group, fever temperature, or meds taken).");
        }
        return new BotResponse(nextNonRepeating(c, "collect_more",
                "Got it. Anything else you’re noticing?"));
    }  // End of buildReply method


    private BotResponse askNextMissingSlotOrCollect(Case c, String prefixAck) {
        if (c.duration.isEmpty()) {
            // Ask for duration with flexible units so cases like “90 minutes” are covered
            return new BotResponse(nextNonRepeating(c, "ask_duration",
                    (prefixAck.isEmpty() ? "" : prefixAck + " ")
                            + "How long has this been going on? You can answer with minutes, hours, days, or weeks (examples: “45 minutes”, “2 hours”, “3 days”)."),
                    List.of("30 minutes", "2 hours", "3 days", "2 weeks"));

        }

        if (c.severity.isEmpty()) {
            return new BotResponse(nextNonRepeating(c, "ask_severity",
                    (prefixAck.isEmpty() ? "" : prefixAck + " ") + "Overall, how bad is it right now?"),
                    List.of("mild", "moderate", "severe"));
        }

        return new BotResponse(nextNonRepeating(c, "collect_more",
                (prefixAck.isEmpty() ? "" : prefixAck + " ")
                        + "Got it. List any other symptoms you’re noticing (even if they seem minor)."));
    }

    private void fillSlotsFromText(Case c, String norm) {
        DurationParseResult parsed = parseDuration(norm);
        if (parsed != null && shouldUpdateDuration(c, parsed, norm)) {
            c.duration = parsed.label;
            c.durationMinutes = parsed.minutes;
        }
        String severity = extractSeverity(norm);
        if (!severity.isEmpty() && shouldUpdateSeverity(c, norm)) {
            c.severity = severity;
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
     */
    private DurationParseResult parseDuration(String norm) {
        // Shortcut phrases that lack numbers but imply short durations
        if (norm.contains("few minutes")) {
            return new DurationParseResult("few minutes (~10)", 10);
        }
        if (norm.contains("few hours")) {
            return new DurationParseResult("few hours (~180)", 180);
        }
        if (norm.contains("few days")) {
            return new DurationParseResult("few days (~3)", 3 * 60 * 24);
        }
        if (norm.contains("few weeks")) {
            return new DurationParseResult("few weeks (~3)", 3 * 60 * 24 * 7);
        }
        if (norm.contains("past week") || norm.contains("last week")) {
            return new DurationParseResult("1 week", 7 * 60 * 24);
        }
        if (norm.contains("past day") || norm.contains("last day")) {
            return new DurationParseResult("1 day", 60 * 24);
        }
        if (norm.contains("half hour") || norm.contains("half an hour")) {
            return new DurationParseResult("30 minutes", 30);
        }
        if (norm.contains("hour and a half") || norm.contains("an hour and a half")) {
            return new DurationParseResult("90 minutes", 90);
        }

        // General numeric extraction: looks for "<number> <unit>"
        String[] tokens = norm.split(" ");
        for (int i = 0; i < tokens.length; i++) {
            Double value = tryParseDouble(tokens[i]);
            if (value == null) continue;

            if (i + 1 >= tokens.length) continue;
            String unit = tokens[i + 1];

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

        return null;
    }

    private Double tryParseDouble(String token) {
        if ("a".equals(token) || "an".equals(token) || "one".equals(token)) return 1.0;
        if ("two".equals(token) || "couple".equals(token)) return 2.0;
        if ("three".equals(token)) return 3.0;
        if ("four".equals(token)) return 4.0;
        if ("five".equals(token)) return 5.0;

        try {
            return Double.parseDouble(token);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String formatDurationLabel(double value, String unit) {
        boolean whole = Math.abs(value - Math.round(value)) < 0.0001;
        String numberText = whole
                ? String.valueOf((long) Math.round(value))
                : String.format(Locale.ROOT, "%.2f", value);

        String unitText = (Math.abs(value) == 1.0) ? unit : unit + "s";
        return numberText + " " + unitText;
    }

    private boolean shouldUpdateDuration(Case c, DurationParseResult parsed, String norm) {
        boolean askedDuration = "ask_duration".equals(c.lastBotKey);
        boolean hasContext = containsAnyToken(norm, DURATION_CONTEXT);
        boolean hasCorrection = containsAnyToken(norm, CORRECTION_TOKENS);

        if (c.duration.isEmpty()) {
            return askedDuration || hasContext || parsed.minutes > 0;
        }

        if (askedDuration) return true;
        if (parsed.minutes <= 0) return false;
        if (hasCorrection) return true;
        if (!hasContext) return false;
        if (c.durationMinutes <= 0) return true;
        return parsed.minutes >= c.durationMinutes;
    }

    private boolean shouldUpdateSeverity(Case c, String norm) {
        if (c.severity.isEmpty()) return true;
        if ("ask_severity".equals(c.lastBotKey)) return true;
        return containsAnyToken(norm, CORRECTION_TOKENS);
    }

    private boolean containsAnyToken(String norm, Set<String> tokens) {
        if (norm.isEmpty()) return false;
        String[] parts = norm.split(" ");
        for (String part : parts) {
            if (tokens.contains(part)) return true;
        }
        return false;
    }

    private double computeDurationMultiplier(Case c) {
        double durationMultiplier = 1.0;

        if (c.durationMinutes > 0) {
            double minutes = c.durationMinutes;

            if (minutes <= 30) durationMultiplier = 1.00;
            else if (minutes <= 120) durationMultiplier = 1.15;
            else if (minutes <= 360) durationMultiplier = 1.30;
            else if (minutes <= 1440) durationMultiplier = 1.45;
            else if (minutes <= 4320) durationMultiplier = 1.60;
            else durationMultiplier = 1.75;

            return durationMultiplier;
        }

        String d = c.duration == null ? "" : c.duration.toLowerCase(Locale.ROOT);
        if (d.contains("today")) durationMultiplier = 1.20;
        else if (d.contains("1-2 days") || d.contains("1 2 days") || d.contains("yesterday")) durationMultiplier = 1.15;
        else if (d.contains("3-7 days") || d.contains("3 7 days")) durationMultiplier = 1.30;
        else if (d.contains("1-2 weeks") || d.contains("1 2 weeks")) durationMultiplier = 1.45;
        else if (d.contains("2+ weeks") || d.contains("2 weeks")) durationMultiplier = 1.60;

        return durationMultiplier;
    }

    private double computeSeverityMultiplier(String severity) {
        if ("moderate".equals(severity)) return 1.30;
        if ("severe".equals(severity)) return 1.70;
        return 1.0;
    }

    private double computeDurationBoost(Case c) {
        if (c.durationMinutes <= 0) return 0.0;
        double minutes = c.durationMinutes;
        if (minutes >= 1440) return 1.6;
        if (minutes >= 360) return 1.2;
        if (minutes >= 120) return 0.8;
        return 0.0;
    }

    private double computeSeverityBoost(String severity) {
        if ("severe".equals(severity)) return 1.2;
        if ("moderate".equals(severity)) return 0.5;
        return 0.0;
    }

    private boolean isProlongedDuration(Case c) {
        if (c.durationMinutes >= 120) return true;
        String d = c.duration == null ? "" : c.duration.toLowerCase(Locale.ROOT);
        return d.contains("day") || d.contains("week") || d.contains("today");
    }

    private boolean userSeemsDone(String norm) {
        return norm.contains("that s it")
                || norm.contains("thats it")
                || norm.contains("that is it")
                || norm.contains("that's it")
                || norm.contains("that is all")
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
                System.out.println("[SymptomMatch] Exact alias hit=\"" + ph + "\" -> code=" + code);
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
        if (userSeemsDone(norm)) return false;

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
        if (key.equals(c.lastBotKey)) return msg;
        c.lastBotKey = key;
        return msg;
    }

    private String pickAck(String caseId) {
        int idx = Math.abs(caseId.hashCode()) % ACKS.size();
        return ACKS.get(idx);
    }

    // ------------------------------------------------------------
    // JSON builder

    private String responseJson(BotResponse resp, Case c) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"type\":\"bot\",");
        sb.append("\"text\":\"").append(escapeJson(resp.text)).append("\"");

        // Include locked and triage details when we know the case
        if (c != null) {
            sb.append(",\"locked\":").append(c.locked);
            if (c.triageComplete) {
                sb.append(",\"triageLevel\":\"").append(escapeJson(c.triageLevel)).append("\"");
                sb.append(",\"triageConfidence\":").append(String.format(Locale.ROOT, "%.4f", c.triageConfidence));
                sb.append(",\"redFlags\":[");
                for (int i = 0; i < c.triageRedFlags.size(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append("\"").append(escapeJson(c.triageRedFlags.get(i))).append("\"");
                }
                sb.append("],\"reasons\":[");
                for (int i = 0; i < c.triageReasons.size(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append("\"").append(escapeJson(c.triageReasons.get(i))).append("\"");
                }
                sb.append("]");
                if (c.duration != null && !c.duration.isEmpty()) {
                    sb.append(",\"duration\":\"").append(escapeJson(c.duration)).append("\"");
                }
            }
        }

        // Quick reply options for the UI
        if (resp.options != null && !resp.options.isEmpty()) {
            sb.append(",\"options\":[");
            for (int i = 0; i < resp.options.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(escapeJson(resp.options.get(i))).append("\"");
            }
            sb.append("]");
        }

        sb.append("}");
        return sb.toString();
    }

    private String respond(BotResponse resp, Case c, String sessionId) {
        persistCase(c, sessionId);
        return responseJson(resp, c);
    }

    private void persistCase(Case c, String sessionId) {
        if (repository == null || c == null) {
            return;
        }
        if (c.notes.isEmpty()) {
            return;
        }
        repository.saveCase(c, sessionId);
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    // ------------------------------------------------------------
    // Triage logic

    private BotResponse maybeTriage(Case c, String norm) {
        if (c.triageComplete) return new BotResponse(triageSummary(c));
        if (c.candidateConfidenceByCode.isEmpty()) return null;
        if (c.duration.isEmpty() || c.severity.isEmpty()) return null;

        boolean userReady = userSeemsDone(norm) || c.notes.size() >= 3;
        if (!userReady) return null;

        double severityMultiplier = computeSeverityMultiplier(c.severity);
        double durationMultiplier = computeDurationMultiplier(c);
        double severityBoost = computeSeverityBoost(c.severity);
        double durationBoost = computeDurationBoost(c);

        List<String> redFlags = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        double baseScore = 0.0;
        boolean hasNosebleed = false;

        for (Map.Entry<String, Double> e : c.candidateConfidenceByCode.entrySet()) {
            Symptom s = kb.symptomByCode.get(e.getKey());
            if (s == null) continue;

            double confidence = e.getValue();
            double weighted = s.weight * confidence;
            baseScore += weighted;

            System.out.println("[Triage] Scoring symptom code=" + s.code + " label=" + s.label
                    + " weight=" + s.weight + " conf=" + confidence + " weighted=" + weighted);

            if (s.redFlag && confidence >= 0.60) {
                String reason = s.label + " (conf " + String.format(Locale.ROOT, "%.0f%%", confidence * 100) + ")";
                redFlags.add(reason);
                reasons.add(reason);
            } else if (confidence >= 0.40) {
                reasons.add(s.label + " (conf " + String.format(Locale.ROOT, "%.0f%%", confidence * 100) + ")");
            }

            if ("NOSEBLEED".equals(s.code) && confidence >= 0.50) {
                hasNosebleed = true;
            }
        }

        double score = (baseScore * severityMultiplier * durationMultiplier) + severityBoost + durationBoost;
        boolean prolongedNosebleed = hasNosebleed && c.durationMinutes >= 120;

        if ("severe".equals(c.severity)) {
            reasons.add("Reported severity: severe");
        } else if ("moderate".equals(c.severity)) {
            reasons.add("Reported severity: moderate");
        }

        if (isProlongedDuration(c) && c.duration != null && !c.duration.isEmpty()) {
            reasons.add("Symptoms ongoing for " + c.duration);
        }

        String level;
        double confScore;

        if (!redFlags.isEmpty()) {
            level = "911";
            confScore = 0.92;
            System.out.println("[Triage] Red-flag escalation. Red flags: " + redFlags);
        } else if (prolongedNosebleed) {
            level = "severe".equals(c.severity) ? "911" : "ER now";
            confScore = 0.90;
            reasons.add("Nosebleed lasting 2+ hours");
            System.out.println("[Triage] Prolonged nosebleed escalation.");
        } else if (score >= 8.0) {
            level = "ER now";
            confScore = Math.min(1.0, 0.70 + score / 15.0);
            System.out.println("[Triage] High score path. score=" + score);
        } else if (score >= 4.0) {
            level = "Doctor visit recommended";
            confScore = Math.min(1.0, 0.60 + score / 12.0);
            System.out.println("[Triage] Moderate score path. score=" + score);
        } else {
            level = "Self-care / monitor";
            confScore = Math.min(1.0, 0.50 + score / 10.0);
            if (reasons.isEmpty()) reasons.add("No significant symptoms detected yet");
            System.out.println("[Triage] Low score path. score=" + score);
        }

        c.triageComplete = true;
        c.locked = true;
        c.triageLevel = level;
        c.triageConfidence = confScore;

        c.triageReasons.clear();
        c.triageReasons.addAll(reasons);
        c.triageRedFlags.clear();
        c.triageRedFlags.addAll(redFlags);

        return new BotResponse(triageSummary(c));
    }

    private String triageSummary(Case c) {
        StringBuilder sb = new StringBuilder();
        sb.append("Triage result: ").append(c.triageLevel.isEmpty() ? "Pending" : c.triageLevel);
        if (c.triageConfidence > 0) {
            sb.append(" (confidence ").append(String.format(Locale.ROOT, "%.0f%%", c.triageConfidence * 100)).append(")");
        }
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
