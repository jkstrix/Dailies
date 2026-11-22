package comedymonkey.dailies.moments;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.Locale;

/**
 * Small helpers for the JSON records the moments system stores. Records are plain {@link JsonObject}s so that
 * everything that was captured stays available to later tooling.
 */
public final class MJson {

    public static final Gson GSON = new GsonBuilder().serializeSpecialFloatingPointValues().disableHtmlEscaping().create();
    public static final Gson PRETTY = new GsonBuilder().serializeSpecialFloatingPointValues().disableHtmlEscaping().setPrettyPrinting().create();

    private MJson() {
    }

    @Nullable
    public static String str(@Nullable JsonObject json, String key) {
        if (json == null) {
            return null;
        }
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        return element.getAsString();
    }

    public static String str(@Nullable JsonObject json, String key, String fallback) {
        String value = str(json, key);
        return value == null ? fallback : value;
    }

    public static long lng(@Nullable JsonObject json, String key, long fallback) {
        if (json == null) {
            return fallback;
        }
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsLong();
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static double dbl(@Nullable JsonObject json, String key, double fallback) {
        if (json == null) {
            return fallback;
        }
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return element.getAsDouble();
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static boolean bool(@Nullable JsonObject json, String key) {
        if (json == null) {
            return false;
        }
        JsonElement element = json.get(key);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean() && element.getAsBoolean();
    }

    @Nullable
    public static JsonObject obj(@Nullable JsonObject json, String key) {
        if (json == null) {
            return null;
        }
        JsonElement element = json.get(key);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    public static JsonArray arr(@Nullable JsonObject json, String key) {
        if (json == null) {
            return new JsonArray();
        }
        JsonElement element = json.get(key);
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
    }

    /** The record's "metadata" object, created when missing */
    public static JsonObject meta(JsonObject record) {
        JsonObject metadata = obj(record, "metadata");
        if (metadata == null) {
            metadata = new JsonObject();
            record.add("metadata", metadata);
        }
        return metadata;
    }

    public static JsonObject player(@Nullable String uuid, @Nullable String name) {
        JsonObject json = new JsonObject();
        json.addProperty("uuid", uuid);
        json.addProperty("name", name);
        return json;
    }

    public static JsonObject position(double x, double y, double z) {
        JsonObject json = new JsonObject();
        json.addProperty("x", round(x));
        json.addProperty("y", round(y));
        json.addProperty("z", round(z));
        return json;
    }

    public static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    public static String iso(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).toString();
    }

    /** When the record happened (or started), in epoch millis */
    public static long time(JsonObject record) {
        return lng(record, "real_time", 0);
    }

    /** When the record ended. Same as {@link #time} for records without a duration */
    public static long endTime(JsonObject record) {
        return lng(record, "end_real_time", time(record));
    }

    public static boolean containsIgnoreCase(@Nullable String haystack, String needleLowerCase) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needleLowerCase);
    }

    public static int countWords(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return text.trim().split("\\s+").length;
    }

}
