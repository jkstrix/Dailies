package comedymonkey.dailies.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Tiny builder for the JSON Schema of a tool's arguments.
 */
public class McpSchema {

    private final JsonObject properties = new JsonObject();
    private final JsonArray required = new JsonArray();

    public static McpSchema object() {
        return new McpSchema();
    }

    private McpSchema property(String name, String type, String description) {
        JsonObject property = new JsonObject();
        if (type != null) {
            property.addProperty("type", type);
        }
        property.addProperty("description", description);
        this.properties.add(name, property);
        return this;
    }

    public McpSchema string(String name, String description) {
        return this.property(name, "string", description);
    }

    public McpSchema number(String name, String description) {
        return this.property(name, "number", description);
    }

    public McpSchema integer(String name, String description) {
        return this.property(name, "integer", description);
    }

    public McpSchema bool(String name, String description) {
        return this.property(name, "boolean", description);
    }

    public McpSchema obj(String name, String description) {
        return this.property(name, "object", description);
    }

    /** A property that accepts any JSON value */
    public McpSchema any(String name, String description) {
        return this.property(name, null, description);
    }

    public McpSchema stringArray(String name, String description) {
        this.property(name, "array", description);
        JsonObject items = new JsonObject();
        items.addProperty("type", "string");
        this.properties.getAsJsonObject(name).add("items", items);
        return this;
    }

    public McpSchema required(String... names) {
        for (String name : names) {
            this.required.add(name);
        }
        return this;
    }

    public JsonObject build() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", this.properties);
        if (!this.required.isEmpty()) {
            schema.add("required", this.required);
        }
        return schema;
    }

}
