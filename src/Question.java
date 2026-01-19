import java.util.*;

public class Question {
    final String id;
    final String prompt;
    final List<String> options;

    public Question(String id, String prompt, List<String> options) {
        this.id = id;
        this.prompt = prompt;
        this.options = options;
    }
}
