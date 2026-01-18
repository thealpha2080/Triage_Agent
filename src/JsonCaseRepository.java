public class JsonCaseRepository implements CaseRepository {

    @Override
    public void saveCase(Case c, String sessionId) {
        CaseStorage.saveCase(c, sessionId);
    }
}
