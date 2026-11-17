package comedymonkey.dailies.edit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;

/**
 * Reads typed values out of JSON and checks them. Every failure is an {@link IllegalArgumentException} whose message
 * names the field and says what is allowed, because the MCP tools show these messages to an AI agent as they are.
 */
final class JsonFields {

    private JsonFields() {
    }

    /** Reads JSON text that has to be an object; {@code what} names it in the error */
    static JsonObject object(@Nullable String text, String what) {
        try {
            JsonElement parsed = text == null ? null : JsonParser.parseString(text);
            if (parsed != null && parsed.isJsonObject()) {
                return parsed.getAsJsonObject();
            }
        } catch (JsonParseException ignored) {
            // Reported below
        }
        throw new IllegalArgumentException("'" + what + "' must be a JSON object, got " + (text == null ? "nothing" : show(new JsonPrimitive(text))));
    }

    /** A number without a pointless ".0" */
    static String plain(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    /** Checks a number that is already a number */
    static double range(String field, double value, double min, double max) {
        if (Double.isNaN(value) || value < min || value > max) {
            throw new IllegalArgumentException("'" + field + "' must be a number from " + plain(min) + " to " + plain(max) + ", got " + plain(value));
        }
        return value;
    }

    static double number(JsonElement element, String field, double min, double max) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("'" + field + "' must be a number from " + plain(min) + " to " + plain(max) + ", got " + show(element));
        }
        return range(field, element.getAsDouble(), min, max);
    }

    /** A number that has to be there */
    static double number(JsonObject json, String field, double min, double max) {
        if (!json.has(field)) {
            throw new IllegalArgumentException("'" + field + "' is required: a number from " + plain(min) + " to " + plain(max));
        }
        return number(json.get(field), field, min, max);
    }

    /** A number that may be left out */
    static double number(JsonObject json, String field, double min, double max, double fallback) {
        return json.has(field) ? number(json.get(field), field, min, max) : fallback;
    }

    static int integer(JsonElement element, String field, int min, int max) {
        boolean number = element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber();
        double value = number ? element.getAsDouble() : Double.NaN;
        if (!number || value != Math.rint(value) || value < min || value > max) {
            throw new IllegalArgumentException("'" + field + "' must be a whole number from " + min + " to " + max + ", got " + show(element));
        }
        return (int) value;
    }

    static int integer(JsonObject json, String field, int min, int max) {
        if (!json.has(field)) {
            throw new IllegalArgumentException("'" + field + "' is required: a whole number from " + min + " to " + max);
        }
        return integer(json.get(field), field, min, max);
    }

    static int integer(JsonObject json, String field, int min, int max, int fallback) {
        return json.has(field) ? integer(json.get(field), field, min, max) : fallback;
    }

    static boolean bool(JsonElement element, String field) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("'" + field + "' must be true or false, got " + show(element));
        }
        return element.getAsBoolean();
    }

    static String text(JsonElement element, String field) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("'" + field + "' must be a string, got " + show(element));
        }
        return element.getAsString();
    }

    /** Text that has to be there and may not be empty */
    static String text(JsonObject json, String field) {
        if (!json.has(field)) {
            throw new IllegalArgumentException("'" + field + "' is required: a string that is not empty");
        }
        String value = text(json.get(field), field);
        if (value.isBlank()) {
            throw new IllegalArgumentException("'" + field + "' must be a string that is not empty");
        }
        return value;
    }

    static UUID uuid(String text, String field) {
        try {
            if (text.length() == 36) {
                return UUID.fromString(text);
            }
        } catch (IllegalArgumentException ignored) {
            // Reported below
        }
        throw new IllegalArgumentException("'" + field + "' must be a UUID like 0b1e6f0e-5a52-4c61-9a37-0c1d2e3f4a5b, got \"" + text + "\"");
    }

    /** One of an enum's values, by its lower case name, whatever case was given */
    static <E extends Enum<E>> E choice(JsonElement element, String field, E[] values, Function<E, String> ids) {
        if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            String wanted = element.getAsString().trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
            for (E value : values) {
                if (ids.apply(value).equals(wanted)) {
                    return value;
                }
            }
        }
        throw new IllegalArgumentException("'" + field + "' must be one of " + String.join(", ", ids(values, ids)) + ", got " + show(element));
    }

    static <E extends Enum<E>> E choice(JsonObject json, String field, E[] values, @Nullable E fallback) {
        if (!json.has(field)) {
            if (fallback != null) {
                return fallback;
            }
            throw new IllegalArgumentException("'" + field + "' is required: one of " + String.join(", ", ids(values, JsonFields::id)));
        }
        return choice(json.get(field), field, values, JsonFields::id);
    }

    static <E extends Enum<E>> List<String> ids(E[] values, Function<E, String> ids) {
        List<String> list = new ArrayList<>();
        for (E value : values) {
            list.add(ids.apply(value));
        }
        return list;
    }

    static String id(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    /** A colour as 0xRRGGBB, given as "#RRGGBB" or as [r, g, b] with each part from 0 to 1 */
    static int colour(JsonElement element, String field) {
        if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            String text = element.getAsString().trim();
            if (text.matches("#?[0-9a-fA-F]{6}")) {
                return Integer.parseInt(text.startsWith("#") ? text.substring(1) : text, 16);
            }
        } else if (element != null && element.isJsonArray() && element.getAsJsonArray().size() == 3) {
            int colour = 0;
            boolean valid = true;
            for (JsonElement part : element.getAsJsonArray()) {
                boolean number = part.isJsonPrimitive() && part.getAsJsonPrimitive().isNumber();
                double value = number ? part.getAsDouble() : Double.NaN;
                valid &= number && value >= 0.0 && value <= 1.0;
                colour = colour << 8 | (int) Math.round((valid ? value : 0.0) * 255.0);
            }
            if (valid) {
                return colour;
            }
        }
        throw new IllegalArgumentException("'" + field + "' must be a colour, either \"#RRGGBB\" or [r, g, b] with each part from 0 to 1, got " + show(element));
    }

    static String colourText(int colour) {
        return String.format(Locale.ROOT, "#%06X", colour & 0xFFFFFF);
    }

    /** A list of strings, none of them empty */
    static List<String> texts(JsonElement element, String field) {
        if (element != null && element.isJsonArray()) {
            List<String> list = new ArrayList<>();
            boolean valid = true;
            for (JsonElement entry : element.getAsJsonArray()) {
                valid &= entry.isJsonPrimitive() && entry.getAsJsonPrimitive().isString() && !entry.getAsString().isBlank();
                if (valid && !list.contains(entry.getAsString())) {
                    list.add(entry.getAsString());
                }
            }
            if (valid) {
                return list;
            }
        }
        throw new IllegalArgumentException("'" + field + "' must be a list of strings, for example [\"minecraft:bat\"], got " + show(element));
    }

    static JsonArray array(List<String> texts) {
        JsonArray array = new JsonArray();
        for (String text : texts) {
            array.add(new JsonPrimitive(text));
        }
        return array;
    }

    /** A value as it is quoted in an error message, cut short when it is long */
    static String show(@Nullable JsonElement element) {
        if (element == null) {
            return "nothing";
        }
        String text = element.toString();
        return text.length() > 80 ? text.substring(0, 77) + "..." : text;
    }

}
