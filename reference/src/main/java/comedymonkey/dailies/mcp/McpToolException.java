package comedymonkey.dailies.mcp;

/**
 * A tool failure that should be shown to the agent (bad arguments, wrong game state, ...).
 * Reported as a normal tool result with isError: true, not as a JSON-RPC error.
 */
public class McpToolException extends RuntimeException {

    public McpToolException(String message) {
        super(message);
    }

}
