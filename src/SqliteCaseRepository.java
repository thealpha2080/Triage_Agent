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

public class SqliteCaseRepository implements CaseRepository {

    private final String dbUrl;

    public SqliteCaseRepository(Path dbPath) {
        this.dbUrl = "jdbc:sqlite:" + dbPath.toString();
        ensureDirectory(dbPath);
        ensureSchema();
    }

    @Override
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
    }

    private void ensureDirectory(Path dbPath) {
        try {
            Path parent = dbPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception e) {
            System.out.println("[SqliteCaseRepository] Failed to create data directory: " + e.getMessage());
        }
    }

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
            System.out.println("[SqliteCaseRepository] Failed to initialize schema: " + e.getMessage());
        }
    }

    private String toJsonArray(List<String> values) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(escapeJson(values.get(i))).append("\"");
        }
        sb.append("]");
        return sb.toString();
    }

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
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
