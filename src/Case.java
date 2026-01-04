import java.util.*;

/**
 * Title: Case
 * Author: Ali Abbas
 * Description: One scenario transcript for a server run. Notes never overwrite.
 * Date: Dec 23, 2025
 * Version: 1.0.0
 */
public class Case {

    public enum Mode {
        OPENING,        // first turn / greeting logic
        CLARIFYING,     // user message unclear
        GATHER_SLOTS,   // we still need duration/severity
        COLLECT_MORE,   // keep collecting symptoms
        READY           // minimum info gathered (Phase 2+ will use this)
    }

    public final String caseId = UUID.randomUUID().toString();
    public final long startedEpochMs = System.currentTimeMillis();

    // symptom candidates (silent extraction)
    public final Map<String, Double> candidateConfidenceByCode = new HashMap<>();


    public final List<String> notes = new ArrayList<>();

    public boolean locked = false; // Phase 3 will use this

    // Phase 1 slots (later used in triage math)
    public String duration = "";  // e.g., "today", "3-7 days"
    public String severity = "";  // e.g., "mild", "moderate", "severe"

    public Mode mode = Mode.OPENING;

    // Prevent repeating the exact same bot message forever
    public String lastBotKey = "";

    public int userMessageCount() {
        return notes.size();
    }
}
