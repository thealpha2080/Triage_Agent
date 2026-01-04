import java.util.*;

public class Question {
    String id;
    String prompt;
    List<String> options;

    public Question(String id, String prompt, List<String> options) {
        this.id = id;
        this.prompt = prompt;
        this.options = options;
    }
}
