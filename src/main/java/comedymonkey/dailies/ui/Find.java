package comedymonkey.dailies.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Finding things by typing part of their name: the search box of the settings screen and the list of a dropdown.
 * Plain Java, so it can be tested without the game.
 */
final class Find {

    private Find() {
    }

    /** Lower case, with the capitals inside a camelCase name set apart as words of their own: "mcpPort" as "mcp port" */
    static String plain(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder spaced = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char now = text.charAt(i);
            if (i > 0 && Character.isUpperCase(now) && Character.isLowerCase(text.charAt(i - 1))) {
                spaced.append(' ');
            }
            spaced.append(now == '_' || now == '-' ? ' ' : now);
        }
        return spaced.toString().toLowerCase(Locale.ROOT).trim();
    }

    /**
     * Whether every word of what was typed is found somewhere in the texts. The words may be in any order and need
     * not be whole: "sec stored" finds "Seconds between Stored Worlds". Nothing typed finds everything.
     */
    static boolean matches(String query, String... texts) {
        String wanted = plain(query);
        if (wanted.isEmpty()) {
            return true;
        }
        StringBuilder all = new StringBuilder();
        for (String text : texts) {
            if (text != null) {
                // Both as written and with camelCase set apart, so "mcpport" and "mcp port" both find mcpPort
                all.append(text.toLowerCase(Locale.ROOT)).append('\n').append(plain(text)).append('\n');
            }
        }
        String hay = all.toString();
        for (String word : wanted.split("\\s+")) {
            if (!hay.contains(word)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The lines of a list that fit what was typed, best first: those that start with it, then those with a word that
     * starts with it, then those that merely hold it. Within each, the list's own order is kept.
     *
     * @return places in {@code labels}
     */
    static List<Integer> filter(List<String> labels, String query) {
        String wanted = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        List<Integer> found = new ArrayList<>();
        if (wanted.isEmpty()) {
            for (int i = 0; i < labels.size(); i++) {
                found.add(i);
            }
            return found;
        }
        List<Integer> words = new ArrayList<>();
        List<Integer> holds = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++) {
            String label = labels.get(i) == null ? "" : labels.get(i).toLowerCase(Locale.ROOT);
            if (label.startsWith(wanted)) {
                found.add(i);
            } else if (label.contains(" " + wanted) || label.contains("(" + wanted) || label.contains("_" + wanted)) {
                words.add(i);
            } else if (label.contains(wanted)) {
                holds.add(i);
            }
        }
        found.addAll(words);
        found.addAll(holds);
        return found;
    }

}
