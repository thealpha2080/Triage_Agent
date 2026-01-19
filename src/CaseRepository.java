/**
 * Title: CaseRepository
 * Author: Ali Abbas
 * Description: Storage abstraction for saving cases and listing summary data.
 * Date: Sep 28, 2025
 * Version: 1.0.0
 */
public interface CaseRepository {
    void saveCase(Case c, String sessionId);

    // Return lightweight summaries for recent cases (used in Database tab)
    java.util.List<CaseSummary> listCases(int limit);
} // End CaseRepository
