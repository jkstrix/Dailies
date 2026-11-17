package comedymonkey.dailies.export;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The name of an exported file, from a pattern with places to fill in (specification EXP-1): {date} and {time} for
 * when the export starts, and whatever else the caller knows, such as {preset}, {scene}, {recording}, {size}, {fps}
 * and {codec}. The result is only ever a name: nothing in it can lead out of the folder it is meant for.
 */
public final class NamePattern {

    /** The places an export can fill in, for labels and explanations */
    public static final String PLACES = "{date} {time} {preset} {scene} {recording} {size} {fps} {codec}";

    private NamePattern() {
    }

    /**
     * @param values what to put for each place, by its name without braces. A place nobody gave a value for is left out
     * @return a name without an ending, never empty
     */
    public static String fill(String pattern, LocalDateTime now, Map<String, String> values) {
        String filled = pattern == null ? "" : pattern;
        filled = filled.replace("{date}", now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))).replace("{time}", now.format(DateTimeFormatter.ofPattern("HH.mm.ss")));
        for (Map.Entry<String, String> value : values.entrySet()) {
            filled = filled.replace("{" + value.getKey() + "}", value.getValue() == null ? "" : value.getValue());
        }
        // A place that means nothing here would otherwise end up in the name as it stands
        filled = filled.replaceAll("\\{[a-z_]*}", "");
        return safe(filled);
    }

    /** Only a name: nothing that would lead out of a folder or that a file system refuses */
    public static String safe(String name) {
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").replaceAll("\\s+", " ").trim();
        while (cleaned.endsWith(".") || cleaned.endsWith(" ")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        while (cleaned.startsWith(".")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.length() > 120) {
            cleaned = cleaned.substring(0, 120).trim();
        }
        return cleaned.isBlank() ? "export" : cleaned;
    }

    /**
     * The name, or the name with a number after it, that is not yet taken.
     *
     * @param name  a file name, with or without an ending
     * @param taken says whether a name is in use
     */
    public static String unused(String name, Predicate<String> taken) {
        if (!taken.test(name)) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ending = dot > 0 ? name.substring(dot) : "";
        for (int number = 2; number < 10000; number++) {
            String next = stem + " (" + number + ")" + ending;
            if (!taken.test(next)) {
                return next;
            }
        }
        return stem + " (" + System.currentTimeMillis() + ")" + ending;
    }

}
