package comedymonkey.dailies.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Typed access to the arguments of a tool call. Every getter throws a {@link McpToolException} with a message
 * the agent can act on when an argument is missing or has the wrong type. JSON null counts as "not given".
 */
public class McpArgs {

    private final JsonObject json;

    public McpArgs(JsonObject json) {
        this.json = json;
    }

    /** The names of all arguments that were sent, including ones the tool does not know */
    public Set<String> names() {
        return this.json.keySet();
    }

    public boolean has(String name) {
        JsonElement element = this.json.get(name);
        return element != null && !element.isJsonNull();
    }

    @Nullable
    public JsonElement element(String name) {
        return this.has(name) ? this.json.get(name) : null;
    }

    private JsonPrimitive primitive(String name, String expected) {
        JsonElement element = this.json.get(name);
        if (element == null || element.isJsonNull()) {
            throw new McpToolException("Missing required argument '" + name + "' (" + expected + ")");
        }
        if (!element.isJsonPrimitive()) {
            throw new McpToolException("Argument '" + name + "' must be " + expected);
        }
        return element.getAsJsonPrimitive();
    }

    public String string(String name) {
        JsonPrimitive primitive = this.primitive(name, "a string");
        return primitive.getAsString();
    }

    @Nullable
    public String optString(String name) {
        return this.has(name) ? this.string(name) : null;
    }

    public double number(String name) {
        JsonPrimitive primitive = this.primitive(name, "a number");
        double value;
        try {
            value = primitive.getAsDouble();
        } catch (NumberFormatException e) {
            throw new McpToolException("Argument '" + name + "' must be a number");
        }
        if (primitive.isBoolean() || Double.isNaN(value) || Double.isInfinite(value)) {
            throw new McpToolException("Argument '" + name + "' must be a finite number");
        }
        return value;
    }

    public double optNumber(String name, double defaultValue) {
        return this.has(name) ? this.number(name) : defaultValue;
    }

    public int integer(String name) {
        double value = this.number(name);
        if (value != Math.rint(value) || Math.abs(value) > Integer.MAX_VALUE) {
            throw new McpToolException("Argument '" + name + "' must be an integer");
        }
        return (int) value;
    }

    public int optInteger(String name, int defaultValue) {
        return this.has(name) ? this.integer(name) : defaultValue;
    }

    public boolean bool(String name) {
        JsonPrimitive primitive = this.primitive(name, "a boolean");
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        // Some clients send booleans as strings
        String string = primitive.getAsString().toLowerCase(Locale.ROOT);
        if (string.equals("true")) {
            return true;
        } else if (string.equals("false")) {
            return false;
        }
        throw new McpToolException("Argument '" + name + "' must be a boolean");
    }

    public boolean optBool(String name, boolean defaultValue) {
        return this.has(name) ? this.bool(name) : defaultValue;
    }

    public UUID uuid(String name) {
        String string = this.string(name);
        try {
            return UUID.fromString(string);
        } catch (IllegalArgumentException e) {
            throw new McpToolException("Argument '" + name + "' must be a UUID, got '" + string + "'");
        }
    }

    public JsonObject object(String name) {
        JsonElement element = this.json.get(name);
        if (element == null || element.isJsonNull()) {
            throw new McpToolException("Missing required argument '" + name + "' (an object)");
        }
        if (!element.isJsonObject()) {
            throw new McpToolException("Argument '" + name + "' must be an object");
        }
        return element.getAsJsonObject();
    }

    /** A list of strings, null when the argument isn't given */
    @Nullable
    public List<String> optStringList(String name) {
        if (!this.has(name)) {
            return null;
        }
        JsonElement element = this.json.get(name);
        if (!element.isJsonArray()) {
            throw new McpToolException("Argument '" + name + "' must be an array of strings");
        }
        JsonArray array = element.getAsJsonArray();
        List<String> list = new ArrayList<>();
        for (JsonElement entry : array) {
            if (!entry.isJsonPrimitive()) {
                throw new McpToolException("Argument '" + name + "' must be an array of strings");
            }
            list.add(entry.getAsString());
        }
        return list;
    }

    /** Parse an enum constant by name, ignoring case and treating '-' and ' ' as '_' */
    public static <T extends Enum<T>> T parseEnum(Class<T> enumClass, String argumentName, String value) {
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        T[] constants = enumClass.getEnumConstants();
        for (T constant : constants) {
            if (constant.name().equals(normalized)) {
                return constant;
            }
        }
        // Also allow names without underscores, eg. "prores" for PRO_RES
        for (T constant : constants) {
            if (constant.name().replace("_", "").equals(normalized.replace("_", ""))) {
                return constant;
            }
        }
        StringBuilder valid = new StringBuilder();
        for (T constant : constants) {
            if (!valid.isEmpty()) valid.append(", ");
            valid.append(constant.name().toLowerCase(Locale.ROOT));
        }
        throw new McpToolException("Unknown " + argumentName + " '" + value + "'. Valid values: " + valid);
    }

    /** Parse "#RRGGBB" / "RRGGBB" / "0xRRGGBB" into an RGB integer */
    public static int parseHexColour(String argumentName, String value) {
        String hex = value.trim();
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        } else if (hex.startsWith("0x") || hex.startsWith("0X")) {
            hex = hex.substring(2);
        }
        if (hex.length() != 6) {
            throw new McpToolException("Argument '" + argumentName + "' must be a hex RGB colour like #FF5555, got '" + value + "'");
        }
        try {
            return Integer.parseInt(hex, 16);
        } catch (NumberFormatException e) {
            throw new McpToolException("Argument '" + argumentName + "' must be a hex RGB colour like #FF5555, got '" + value + "'");
        }
    }

}
