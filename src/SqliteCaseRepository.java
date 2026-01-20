import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Title: SqliteCaseRepository
 * Author: Ali Abbas
 * Description: SQLite-backed repository for case storage and display to the frontend.
 * Date: Sep 28, 2025
 * Version: 1.0.0
 */
public class SqliteCaseRepository implements CaseRepository {

    private final String dbUrl;

    // Initialize SQLite db
    public SqliteCaseRepository(Path dbPath) {
        // Ensure the driver is available before any connection attempts.
        ensureDriver();
        this.dbUrl = "jdbc:sqlite:" + dbPath.toString();
        ensureDirectory(dbPath);
        ensureSchema(); // ready to run
    }  // End SqliteCaseRepository
    
    @Override
    // Insert or update a case row in SQLite.
    public void saveCase(Case c, String sessionId) {
        if (c == null) {
            return;
        }
        String sql = "INSERT INTO cases (" +
                "case_id, session_id, started_epoch_ms, updated_epoch_ms, locked, triage_complete, " +
                "triage_level, triage_confidence, duration, duration_minutes, severity, notes_json, " +
                "triage_reasons_json, triage_red_flags_json, candidate_confidence_json" +
                ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(case_id) DO UPDATE SET " +
                "session_id=excluded.session_id, " +
                "updated_epoch_ms=excluded.updated_epoch_ms, " +
                "locked=excluded.locked, " +
                "triage_complete=excluded.triage_complete, " +
                "triage_level=excluded.triage_level, " +
                "triage_confidence=excluded.triage_confidence, " +
                "duration=excluded.duration, " +
                "duration_minutes=excluded.duration_minutes, " +
                "severity=excluded.severity, " +
                "notes_json=excluded.notes_json, " +
                "triage_reasons_json=excluded.triage_reasons_json, " +
                "triage_red_flags_json=excluded.triage_red_flags_json, " +
                "candidate_confidence_json=excluded.candidate_confidence_json";

        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            ps.setString(idx++, c.caseId);
            ps.setString(idx++, sessionId);
            ps.setLong(idx++, c.startedEpochMs);
            ps.setLong(idx++, System.currentTimeMillis());
            ps.setInt(idx++, c.locked ? 1 : 0);
            ps.setInt(idx++, c.triageComplete ? 1 : 0);
            ps.setString(idx++, c.triageLevel);
            ps.setDouble(idx++, c.triageConfidence);
            ps.setString(idx++, c.duration);
            ps.setDouble(idx++, c.durationMinutes);
            ps.setString(idx++, c.severity);
            ps.setString(idx++, toJsonArray(c.notes));
            ps.setString(idx++, toJsonArray(c.triageReasons));
            ps.setString(idx++, toJsonArray(c.triageRedFlags));
            ps.setString(idx++, toJsonMap(c.candidateConfidenceByCode));
            ps.executeUpdate();
        } catch (SQLException e) {
            System.out.println("[SqliteCaseRepository] Failed to save case: " + e.getMessage());
        }
    } // End saveCase

    @Override
    // Return a list of recent cases for the UI history view.
    public List<CaseSummary> listCases(int limit) {
        List<CaseSummary> summaries = new java.util.ArrayList<>();
        // Query only the fields required for the database list view.
        String sql = "SELECT case_id, session_id, started_epoch_ms, triage_level, triage_confidence, " +
                "duration, severity, notes_json, triage_red_flags_json " +
                "FROM cases ORDER BY started_epoch_ms DESC LIMIT ?";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String caseId = rs.getString("case_id");
                    String sessionId = rs.getString("session_id");
                    long startedEpochMs = rs.getLong("started_epoch_ms");
                    String triageLevel = rs.getString("triage_level");
                    double triageConfidence = rs.getDouble("triage_confidence");
                    String duration = rs.getString("duration");
                    String severity = rs.getString("severity");
                    String notesJson = rs.getString("notes_json");
                    String redFlagsJson = rs.getString("triage_red_flags_json");
                    int notesCount = countArrayItems(notesJson);
                    int redFlagCount = countArrayItems(redFlagsJson);
                    summaries.add(new CaseSummary(caseId, sessionId, startedEpochMs, triageLevel, triageConfidence, duration, severity, notesCount, redFlagCount));
                }
            }
        } catch (SQLException e) {
            System.out.println("[SqliteCaseRepository] Failed to list cases: " + e.getMessage());
        }
        return summaries;
    } // End listCases

    // Ensure the db directory exists before connecting.
    private void ensureDirectory(Path dbPath) {
        try {
            Path parent = dbPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception e) {
            System.out.println("[SqliteCaseRepository] Failed to create data directory: " + e.getMessage());
        }
    } // End ensureDirectory

    // Create the cases table if it does not exist.
    private void ensureSchema() {
        String ddl = "CREATE TABLE IF NOT EXISTS cases (" +
                "case_id TEXT PRIMARY KEY, " +
                "session_id TEXT, " +
                "started_epoch_ms INTEGER, " +
                "updated_epoch_ms INTEGER, " +
                "locked INTEGER, " +
                "triage_complete INTEGER, " +
                "triage_level TEXT, " +
                "triage_confidence REAL, " +
                "duration TEXT, " +
                "duration_minutes REAL, " +
                "severity TEXT, " +
                "notes_json TEXT, " +
                "triage_reasons_json TEXT, " +
                "triage_red_flags_json TEXT, " +
                "candidate_confidence_json TEXT" +
                ")";
        try (Connection conn = DriverManager.getConnection(dbUrl);
             Statement stmt = conn.createStatement()) {
            stmt.execute(ddl);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize schema: " + e.getMessage(), e);
        }
    } // End ensureSchema

    // Verify the SQLite JDBC driver is available.
    private void ensureDriver() {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("SQLite JDBC driver not found.", e);
        }
    } // End ensureDriver

    // Serialize a list of strings as a JSON array.
    private String toJsonArray(List<String> values) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(escapeJson(values.get(i))).append("\"");
        }
        sb.append("]");
        return sb.toString();
    } // End toJsonArray

    // Serialize a string/double map as a JSON object.
    private String toJsonMap(Map<String, Double> values) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        int i = 0;
        for (Map.Entry<String, Double> entry : values.entrySet()) {
            if (i++ > 0) sb.append(",");
            sb.append("\"").append(escapeJson(entry.getKey())).append("\":");
            sb.append(String.format(Locale.ROOT, "%.4f", entry.getValue()));
        }
        sb.append("}");
        return sb.toString();
    } // End toJsonMap

    // Count string values in a JSON array represented as text.
    private int countArrayItems(String jsonArray) {
        if (jsonArray == null || jsonArray.isBlank()) {
            return 0;
        }
        int count = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = 0; i < jsonArray.length(); i++) {
            char c = jsonArray.charAt(i);
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
    } // End countArrayItems

    // Escape strings for JSON storage.
    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    } // End escapeJson

} // End SqliteCaseRepository class
