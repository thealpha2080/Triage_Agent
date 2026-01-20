import java.util.*;

/**
 * Title: Symptom
 * Author: Ali Abbas
 * Description: A single symptom entry from the knowledge base.
 * Date: Dec 23, 2025
 * Version: 0.1.0
 */
public class Symptom {
    // Every new symptom made has these properties:
    public final String code;
    public final String label;
    public final String category;
    public final double weight;
    public final boolean redFlag;
    public final List<String> aliases;

    // Build a symptom entry
    public Symptom(String code, String label, String category, double weight, boolean redFlag, List<String> aliases) {
        this.code = code;
        this.label = label;
        this.category = category;
        this.weight = weight;
        this.redFlag = redFlag;
        this.aliases = aliases;
    }
}
