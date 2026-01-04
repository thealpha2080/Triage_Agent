import java.util.*;

/**
 * Title: Symptom
 * Author: Ali Abbas
 * Description: A single symptom entry from the knowledge base.
 * Date: Dec 23, 2025
 * Version: 0.1.0
 */
public class Symptom {
    public final String code;
    public final String label;
    public final String category;
    public final double weight;
    public final boolean redFlag;
    public final List<String> aliases;

    public Symptom(String code, String label, String category, double weight, boolean redFlag, List<String> aliases) {
        this.code = code;
        this.label = label;
        this.category = category;
        this.weight = weight;
        this.redFlag = redFlag;
        this.aliases = aliases;
    }
}
