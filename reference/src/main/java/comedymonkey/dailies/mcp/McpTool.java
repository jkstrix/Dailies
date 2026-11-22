package comedymonkey.dailies.mcp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * A single MCP tool.
 *
 * @param thread where the handler runs, see {@link ThreadMode}
 * @param timeoutSeconds how long the HTTP thread waits for a {@link ThreadMode#CLIENT} handler
 */
public record McpTool(String name, String description, JsonObject inputSchema, ThreadMode thread, int timeoutSeconds, Handler handler) {

    public static final int DEFAULT_TIMEOUT_SECONDS = 10;

    public enum ThreadMode {
        /** Runs on the Minecraft client thread, like the editor UI does. Required for anything that touches game state */
        CLIENT,
        /** Runs on the HTTP worker thread. Only for file IO and state that is safe to read from any thread */
        HTTP
    }

    @FunctionalInterface
    public interface Handler {
        JsonElement call(McpArgs args) throws Exception;
    }

}
