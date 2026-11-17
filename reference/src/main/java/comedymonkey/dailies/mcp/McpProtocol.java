package comedymonkey.dailies.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.mcp.tools.MomentsTools;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * JSON-RPC 2.0 handling for MCP plus the tool registry. Transport independent: takes the request body as a
 * string and returns the response body (or null when there is nothing to reply with).
 */
public class McpProtocol {

    private static final List<String> SUPPORTED_PROTOCOL_VERSIONS = List.of("2025-06-18", "2025-03-26", "2024-11-05");
    private static final String FALLBACK_PROTOCOL_VERSION = "2025-03-26";

    private static final int PARSE_ERROR = -32700;
    private static final int INVALID_REQUEST = -32600;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;

    // Compact output. Special floats can show up in editor state (eg. NaN in a keyframe) and shouldn't break a reply
    private static final Gson GSON = new GsonBuilder().serializeSpecialFloatingPointValues().disableHtmlEscaping().create();

    private final Map<String, McpTool> tools = new LinkedHashMap<>();

    public McpProtocol() {
        Registrar registrar = new Registrar();
        MomentsTools.register(registrar);
        // The other tool groups of the specification (status, recording, playback, editing, looks, export, smart camera)
        // belong to the new core and register here in the same way
        Host.get().registerTools(registrar);
    }

    /** Passed to the tools classes so they can add their tools */
    public class Registrar {
        /** A tool that runs on the client thread with the default timeout */
        public void add(String name, String description, McpSchema schema, McpTool.Handler handler) {
            this.add(name, description, schema, McpTool.ThreadMode.CLIENT, McpTool.DEFAULT_TIMEOUT_SECONDS, handler);
        }

        public void add(String name, String description, McpSchema schema, McpTool.ThreadMode thread, int timeoutSeconds, McpTool.Handler handler) {
            if (McpProtocol.this.tools.put(name, new McpTool(name, description, schema.build(), thread, timeoutSeconds, handler)) != null) {
                throw new IllegalStateException("Duplicate MCP tool: " + name);
            }
        }
    }

    /**
     * @return the JSON to reply with, or null if the message only contained notifications/responses
     */
    @Nullable
    public String handleMessage(String body) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(body);
        } catch (Exception e) {
            return GSON.toJson(error(JsonNull.INSTANCE, PARSE_ERROR, "Parse error: " + e.getMessage()));
        }

        if (parsed.isJsonArray()) {
            JsonArray batch = parsed.getAsJsonArray();
            if (batch.isEmpty()) {
                return GSON.toJson(error(JsonNull.INSTANCE, INVALID_REQUEST, "Empty batch"));
            }
            JsonArray responses = new JsonArray();
            for (JsonElement element : batch) {
                JsonObject response = this.handleSingle(element);
                if (response != null) {
                    responses.add(response);
                }
            }
            return responses.isEmpty() ? null : GSON.toJson(responses);
        }

        JsonObject response = this.handleSingle(parsed);
        return response == null ? null : GSON.toJson(response);
    }

    @Nullable
    private JsonObject handleSingle(JsonElement element) {
        if (!element.isJsonObject()) {
            return error(JsonNull.INSTANCE, INVALID_REQUEST, "Expected a JSON-RPC object");
        }
        JsonObject message = element.getAsJsonObject();

        JsonElement id = message.get("id");
        boolean isNotification = id == null;

        JsonElement methodElement = message.get("method");
        if (methodElement == null) {
            // A response to a request we never sent (this server doesn't send any). Ignore it
            if (message.has("result") || message.has("error")) {
                return null;
            }
            return error(isNotification ? JsonNull.INSTANCE : id, INVALID_REQUEST, "Missing method");
        }
        if (!methodElement.isJsonPrimitive()) {
            return error(isNotification ? JsonNull.INSTANCE : id, INVALID_REQUEST, "Method must be a string");
        }
        String method = methodElement.getAsString();

        JsonObject params = message.has("params") && message.get("params").isJsonObject() ? message.getAsJsonObject("params") : new JsonObject();

        if (isNotification) {
            // notifications/initialized, notifications/cancelled, ...: nothing to do, and notifications never get a reply
            return null;
        }

        try {
            JsonElement result = switch (method) {
                case "initialize" -> this.initialize(params);
                case "ping" -> new JsonObject();
                case "tools/list" -> this.listTools();
                case "tools/call" -> this.callTool(params);
                default -> throw new RpcError(METHOD_NOT_FOUND, "Method not found: " + method);
            };

            JsonObject response = new JsonObject();
            response.addProperty("jsonrpc", "2.0");
            response.add("id", id);
            response.add("result", result);
            return response;
        } catch (RpcError e) {
            return error(id, e.code, e.getMessage());
        }
    }

    private JsonObject initialize(JsonObject params) {
        String requested = params.has("protocolVersion") && params.get("protocolVersion").isJsonPrimitive()
            ? params.get("protocolVersion").getAsString() : null;

        JsonObject capabilities = new JsonObject();
        capabilities.add("tools", new JsonObject());

        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", "dailies");
        serverInfo.addProperty("title", "Dailies");
        serverInfo.addProperty("version", getModVersion());

        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", SUPPORTED_PROTOCOL_VERSIONS.contains(requested) ? requested : FALLBACK_PROTOCOL_VERSION);
        result.add("capabilities", capabilities);
        result.add("serverInfo", serverInfo);
        result.addProperty("instructions", "Controls the Dailies replay mod inside a running Minecraft client. "
            + "Call dailies_status first to see whether the game is recording, in a replay or exporting. "
            + "Recording tools need a world to be open; replay, camera, keyframe, editor and export tools need an open replay (replay_list, replay_open). "
            + "Ticks are replay ticks (20 per second). "
            + "The moments_* tools query what happened while playing (gameplay events, who was with whom, voice chat transcripts) and build clip candidates; "
            + "they work on stored sessions whether or not a replay is open, start with moments_status.");
        return result;
    }

    private JsonObject listTools() {
        JsonArray array = new JsonArray();
        for (McpTool tool : this.tools.values()) {
            JsonObject json = new JsonObject();
            json.addProperty("name", tool.name());
            json.addProperty("description", tool.description());
            json.add("inputSchema", tool.inputSchema());
            array.add(json);
        }
        JsonObject result = new JsonObject();
        result.add("tools", array);
        return result;
    }

    private JsonObject callTool(JsonObject params) throws RpcError {
        if (!params.has("name") || !params.get("name").isJsonPrimitive()) {
            throw new RpcError(INVALID_PARAMS, "tools/call requires a tool name");
        }
        String name = params.get("name").getAsString();
        McpTool tool = this.tools.get(name);
        if (tool == null) {
            throw new RpcError(INVALID_PARAMS, "Unknown tool: " + name);
        }

        JsonObject arguments;
        JsonElement argumentsElement = params.get("arguments");
        if (argumentsElement == null || argumentsElement.isJsonNull()) {
            arguments = new JsonObject();
        } else if (argumentsElement.isJsonObject()) {
            arguments = argumentsElement.getAsJsonObject();
        } else {
            throw new RpcError(INVALID_PARAMS, "Tool arguments must be an object");
        }

        try {
            JsonElement output = this.runTool(tool, new McpArgs(arguments));
            return toolResult(GSON.toJson(output), false);
        } catch (McpToolException e) {
            return toolResult(e.getMessage(), true);
        } catch (Throwable t) {
            Host.LOGGER.error("MCP tool {} failed", name, t);
            return toolResult("Internal error in " + name + ": " + t, true);
        }
    }

    /** Unknown argument names are refused with the list of valid ones (specification MCP-5) */
    private static void checkArgumentNames(McpTool tool, McpArgs args) {
        JsonObject properties = tool.inputSchema().getAsJsonObject("properties");
        java.util.Set<String> known = properties == null ? java.util.Set.of() : new java.util.TreeSet<>(properties.keySet());
        for (String name : args.names()) {
            if (!known.contains(name)) {
                throw new McpToolException("Unknown argument '" + name + "'. " + (known.isEmpty() ? "This tool takes no arguments" : "Valid arguments: " + String.join(", ", known)));
            }
        }
    }

    private JsonElement runTool(McpTool tool, McpArgs args) throws Exception {
        checkArgumentNames(tool, args);
        if (tool.thread() == McpTool.ThreadMode.HTTP) {
            return tool.handler().call(args);
        }

        // All game state belongs to the client thread (the editor UI runs there too), so hop over and wait
        CompletableFuture<JsonElement> future = Minecraft.getInstance().submit(() -> {
            try {
                return tool.handler().call(args);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        try {
            return future.get(tool.timeoutSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new McpToolException("Timed out after " + tool.timeoutSeconds() + "s waiting for the game's client thread (the game may be loading, saving or exporting). "
                + "The action was still queued and may complete; check dailies_status.");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof McpToolException toolException) {
                throw toolException;
            }
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw e;
        }
    }

    private static JsonObject toolResult(String text, boolean isError) {
        JsonObject content = new JsonObject();
        content.addProperty("type", "text");
        content.addProperty("text", text);

        JsonArray contentArray = new JsonArray();
        contentArray.add(content);

        JsonObject result = new JsonObject();
        result.add("content", contentArray);
        result.addProperty("isError", isError);
        return result;
    }

    private static JsonObject error(JsonElement id, int code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);

        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id);
        response.add("error", error);
        return response;
    }

    public static String getModVersion() {
        return Host.get().modVersion();
    }

    private static class RpcError extends Exception {
        final int code;

        RpcError(int code, String message) {
            super(message);
            this.code = code;
        }
    }

}
