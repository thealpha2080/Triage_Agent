/**
 * Title: CaseSummary
 * Author: Ali Abbas
 * Description: Lightweight case snapshot used for history listings.
 * Date: Sep 28, 2025
 * Version: 1.0.0
 */
public class CaseSummary {
    // Core identifiers for list rows and drill-in.
    public final String caseId;
    public final String sessionId;
    public final long startedEpochMs;
    public final String triageLevel;
    public final double triageConfidence;
    public final String duration;
    public final String severity;
    public final int notesCount;
    public final int redFlagCount;

    // Constructor
    public CaseSummary(String caseId,
                       String sessionId,
                       long startedEpochMs,
                       String triageLevel,
                       double triageConfidence,
                       String duration,
                       String severity,
                       int notesCount,
                       int redFlagCount) {
        this.caseId = caseId;
        this.sessionId = sessionId;
        this.startedEpochMs = startedEpochMs;
        this.triageLevel = triageLevel;
        this.triageConfidence = triageConfidence;
        this.duration = duration;
        this.severity = severity;
        this.notesCount = notesCount;
        this.redFlagCount = redFlagCount;
    }
}
