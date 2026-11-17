package comedymonkey.dailies.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads what follows "/dailies mark" (specification REC-7): an optional colour, an optional yes or no for storing the
 * recorder's position, and whatever is left as the description. Each part is recognised by what it says, so any of
 * them can be left out: "mark", "mark blue", "mark no the creeper hole" and "mark #40C0FF yes base" all mean
 * something. Kept free of the game so it can be tested by itself.
 */
public final class MarkWords {

    /** The colours that have names, in the order they are offered. 0xRRGGBB */
    private static final Map<String, Integer> COLOURS = new LinkedHashMap<>();

    static {
        COLOURS.put("red", 0xE0483A);
        COLOURS.put("orange", 0xE8902E);
        COLOURS.put("yellow", 0xF2D03B);
        COLOURS.put("green", 0x5DBB4A);
        COLOURS.put("cyan", 0x3FC8C8);
        COLOURS.put("blue", 0x4A7BE0);
        COLOURS.put("purple", 0x9A5BD8);
        COLOURS.put("white", 0xFFFFFF);
    }

    private static final List<String> YES = List.of("true", "yes", "on");
    private static final List<String> NO = List.of("false", "no", "off");
    public static final int DEFAULT_COLOUR = 0xE0483A;

    /**
     * What a marker is to be.
     *
     * @param colour       0xRRGGBB
     * @param colourName   the name the colour was given by, or "#RRGGBB"
     * @param savePosition whether the recorder's position is stored with it
     * @param description  may be empty
     */
    public record Mark(int colour, String colourName, boolean savePosition, String description) {
    }

    private MarkWords() {
    }

    public static List<String> colourNames() {
        return List.copyOf(COLOURS.keySet());
    }

    /** The colour a word stands for: one of the names, or six hexadecimal digits with or without a # in front. Null when it is neither */
    public static Integer colour(String word) {
        String wanted = word == null ? "" : word.trim().toLowerCase(Locale.ROOT);
        Integer named = COLOURS.get(wanted);
        if (named != null) {
            return named;
        }
        String digits = wanted.startsWith("#") ? wanted.substring(1) : wanted;
        // Without the # only a word that could not be anything else: "decade" is a word and "c0ffee" a colour, and
        // there is no telling, so a description that starts with six such letters needs the colour given first
        if (digits.matches("[0-9a-f]{6}")) {
            return Integer.parseInt(digits, 16);
        }
        return null;
    }

    private static Boolean flag(String word) {
        String wanted = word.toLowerCase(Locale.ROOT);
        return YES.contains(wanted) ? Boolean.TRUE : NO.contains(wanted) ? Boolean.FALSE : null;
    }

    /**
     * @param words what was typed after "mark"; may be empty or null
     */
    public static Mark parse(String words) {
        String rest = words == null ? "" : words.trim();
        int colour = DEFAULT_COLOUR;
        String colourName = "red";
        boolean savePosition = true;

        String first = firstWord(rest);
        Integer given = first.isEmpty() ? null : colour(first);
        if (given != null) {
            colour = given;
            colourName = COLOURS.containsKey(first.toLowerCase(Locale.ROOT)) ? first.toLowerCase(Locale.ROOT) : String.format(Locale.ROOT, "#%06X", given);
            rest = rest.substring(first.length()).trim();
        }
        String second = firstWord(rest);
        Boolean save = second.isEmpty() ? null : flag(second);
        if (save != null) {
            savePosition = save;
            rest = rest.substring(second.length()).trim();
        }
        return new Mark(colour, colourName, savePosition, rest);
    }

    private static String firstWord(String text) {
        int space = text.indexOf(' ');
        return space < 0 ? text : text.substring(0, space);
    }

    /**
     * What could be typed next, for tab completion.
     *
     * @param typed what follows "mark " so far, up to the cursor
     * @return the words that fit where the cursor is; none once the description has been reached
     */
    public static List<String> suggestions(String typed) {
        String text = typed == null ? "" : typed;
        int lastSpace = text.lastIndexOf(' ');
        String before = lastSpace < 0 ? "" : text.substring(0, lastSpace).trim();
        String current = (lastSpace < 0 ? text : text.substring(lastSpace + 1)).toLowerCase(Locale.ROOT);
        List<String> offered = new ArrayList<>();
        if (before.isEmpty()) {
            offered.addAll(COLOURS.keySet());
            offered.add("yes");
            offered.add("no");
        } else if (before.indexOf(' ') < 0 && colour(before) != null) {
            offered.add("yes");
            offered.add("no");
        }
        offered.removeIf(word -> !word.startsWith(current));
        return offered;
    }

    /** Where the word under the cursor starts in what was typed */
    public static int wordStart(String typed) {
        return typed == null ? 0 : typed.lastIndexOf(' ') + 1;
    }

}
