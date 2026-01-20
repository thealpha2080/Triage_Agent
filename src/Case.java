/**
 * Title: Case
 * Author: Ali Abbas
 * Description: One scenario transcript for a server run. Notes never overwrite. Stores
 *              symptom candidates and final triage output for the session.
 * Date: Jan 19, 2026
 * Version: 1.3.0
 */

import java.util.*;
public class Case {

    public enum Mode {
        OPENING,        // first turn / greeting logic
        CLARIFYING,     // user message unclear
        GATHER_INFO,   // we still need duration/severity
        COLLECT_MORE,   // keep collecting symptoms
        READY           // minimum info gathered (Phase 2+ will use this)
    }

    public final String caseId = UUID.randomUUID().toString();
    public final long startedEpochMs = System.currentTimeMillis();

    // symptom candidates (silent extraction)
    public final Map<String, Double> candidateConfidenceByCode = new HashMap<>();

    // Resolved/locked output
    public boolean triageComplete = false;                // true once a decision is made
    public String triageLevel = "";                       // 911, ER, Doctor, Self-care, etc.
    public double triageConfidence = 0.0;                 // 0..1 summary confidence for the decision
    public final List<String> triageReasons = new ArrayList<>(); // human-readable reasons for the outcome
    public final List<String> triageRedFlags = new ArrayList<>(); // red-flag items that triggered escalation

    public final List<String> notes = new ArrayList<>();

    public boolean locked = false; // Prevent updates after triage is finalized.

    // Triage info
    public String duration = "";         // e.g., "90 minutes", "3 days", "1-2 weeks"
    public double durationMinutes = -1;   // normalized numeric duration in minutes for ranking
    public String severity = "";         // e.g., "mild", "moderate", "severe"


    public Mode mode = Mode.OPENING;

    // Prevent repeating the exact same bot message forever
    public String lastBotKey = "";

    // Track how many times we have asked the user to clarify, so we can stop looping.
    public int unclearCount = 0;

    public int userMessageCount() {
        return notes.size();
    }
}
