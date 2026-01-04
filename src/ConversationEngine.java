import java.util.*;

/**
 * Title: ConversationEngine
 * Author: Ali Abbas
 * Description: Phase 1 chat UX: natural deterministic replies, collects slots (duration/severity),
 *              no symptom detection/chips yet.
 * Date: Dec 23, 2025
 * Version: 1.0.0
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
        }

        Case c = st.activeCase;

        text = (text == null) ? "" : text.trim();
        if (text.isEmpty()) {
            return botJson("Type what’s going on (you can list symptoms like: “fever, cough, sore throat”).");
        }

        if (c.locked) {
            return botJson("This case is closed. (Later you’ll be able to start a new one without restarting.)");
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

        // 1) First message: acknowledge + encourage symptom listing
        if (c.userMessageCount() == 1) {
            String ack = pickAck(c.caseId);

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
            return nextNonRepeating(c, "ask_duration",
                    (prefixAck.isEmpty() ? "" : prefixAck + " ")
                            + "How long has this been going on? (today / 1-2 days / 3-7 days / 1-2 weeks / 2+ weeks)");
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
            c.duration = extractDuration(norm);
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

    private String extractDuration(String norm) {
        if (norm.contains("1 hour") || norm.contains("one hour")) return "1 hour";
        if (norm.contains("today")) return "today";
        if (norm.contains("yesterday")) return "1-2 days";
        if (norm.contains("2 days") || norm.contains("two days")) return "1-2 days";
        if (norm.contains("3 days") || norm.contains("three days")) return "3-7 days";
        if (norm.contains("week")) return "1-2 weeks";
        if (norm.contains("weeks")) return "2+ weeks";
        if (norm.contains("month") || norm.contains("months")) return "2+ weeks";
        return "";
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
        FuzzyHit(String alias, double score) { this.alias = alias; this.score = score; }
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
}
