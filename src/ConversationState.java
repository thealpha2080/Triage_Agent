/**
 * Title: ConversationState
 * Author: Ali Abbas
 * Description: Holds per-session state (current case + which server run it belongs to).
 * Date: Dec 23, 2025
 * Version: 1.0.0
 */
public class ConversationState {
    public final String sessionId; // Each case gets a unique session id

    // Used to force a new case when the server restarts
    public String bootSeen = "";

    public Case activeCase;

    // Initialize state with the incoming session id.
    public ConversationState(String sessionId) {
        this.sessionId = sessionId;
    }
}
