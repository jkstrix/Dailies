package comedymonkey.dailies.library;

import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What the list of recordings shows of the index (specification REC-5): the recordings of one folder or of all of
 * them, narrowed by words typed into the search box and put in the order asked for. Nothing here touches a file or
 * the game, so the list screen and the tools give the same answer and both can be tested on their own.
 * <p>
 * The words are matched against a recording's name, world, recorder and players and the day it was recorded, every
 * word somewhere, so "home steve 2026-11" finds Steve's recordings of the world Home from November 2026.
 */
public final class LibraryQuery {

    /** The orders the list can be put in */
    public enum Sort {
        NEWEST, OLDEST, NAME, LENGTH, SIZE
    }

    /** All recordings, whichever folder they are in */
    public static final String ALL = "*";
    /** The recordings folder itself, without its sub-folders */
    public static final String TOP = "";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);
    private static final DateTimeFormatter MONTH_WORDS = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter SHORT_MONTH = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private LibraryQuery() {
    }

    /** The folder a recording is in, relative to the recordings folder with forward slashes; "" for the folder itself */
    public static String folderOf(LibraryIndex.Entry entry) {
        int slash = entry.file.lastIndexOf('/');
        return slash < 0 ? TOP : entry.file.substring(0, slash);
    }

    /** The day a recording's footage was made, or when its file was last written when it does not say */
    public static LocalDate dayOf(LibraryIndex.Entry entry, ZoneId zone) {
        long when = entry.created > 0 ? entry.created : entry.modified;
        return Instant.ofEpochMilli(when).atZone(zone).toLocalDate();
    }

    /**
     * Whether a recording answers to what was typed. Every word must be found in its name, world, recorder, players,
     * file or the day it was made (as 2026-11-17, 17 November 2026 or 17 Nov 2026); case does not matter.
     */
    public static boolean matches(LibraryIndex.Entry entry, @Nullable String typed, ZoneId zone) {
        if (typed == null || typed.isBlank()) {
            return true;
        }
        StringBuilder all = new StringBuilder();
        all.append(entry.name).append('\n').append(entry.world).append('\n').append(entry.recorder).append('\n').append(entry.file).append('\n');
        if (entry.players != null) {
            for (String player : entry.players) {
                all.append(player).append('\n');
            }
        }
        LocalDate day = dayOf(entry, zone);
        all.append(DAY.format(day)).append('\n').append(MONTH_WORDS.format(day)).append('\n').append(SHORT_MONTH.format(day));
        String haystack = all.toString().toLowerCase(Locale.ROOT);
        for (String word : typed.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            if (!word.isEmpty() && !haystack.contains(word)) {
                return false;
            }
        }
        return true;
    }

    /** Whether a recording is in the folder asked for: {@link #ALL}, {@link #TOP}, or a folder's relative path (not its sub-folders) */
    public static boolean inFolder(LibraryIndex.Entry entry, String folder) {
        return ALL.equals(folder) || folderOf(entry).equals(folder);
    }

    public static Comparator<LibraryIndex.Entry> order(Sort sort) {
        Comparator<LibraryIndex.Entry> byFile = Comparator.comparing(entry -> entry.file);
        Comparator<LibraryIndex.Entry> newest = Comparator.comparingLong(LibraryIndex.Entry::newest).reversed();
        return switch (sort) {
            case NEWEST -> newest.thenComparing(byFile);
            case OLDEST -> Comparator.comparingLong(LibraryIndex.Entry::newest).thenComparing(byFile);
            case NAME -> Comparator.comparing((LibraryIndex.Entry entry) -> entry.name.toLowerCase(Locale.ROOT)).thenComparing(newest).thenComparing(byFile);
            case LENGTH -> Comparator.comparingInt((LibraryIndex.Entry entry) -> entry.totalTicks).reversed().thenComparing(newest).thenComparing(byFile);
            case SIZE -> Comparator.comparingLong((LibraryIndex.Entry entry) -> entry.size).reversed().thenComparing(newest).thenComparing(byFile);
        };
    }

    /**
     * The recordings to show.
     *
     * @param folder      {@link #ALL}, {@link #TOP} or a folder; a search looks in every folder whatever this is
     * @param starred     the ids of starred recordings
     * @param starredOnly whether only those are wanted
     */
    public static List<LibraryIndex.Entry> apply(List<LibraryIndex.Entry> entries, String folder, @Nullable String typed, Sort sort, Set<String> starred, boolean starredOnly,
                                                 ZoneId zone) {
        boolean searching = typed != null && !typed.isBlank();
        List<LibraryIndex.Entry> shown = new ArrayList<>();
        for (LibraryIndex.Entry entry : entries) {
            if ((searching || inFolder(entry, folder)) && matches(entry, typed, zone) && (!starredOnly || starred.contains(entry.id))) {
                shown.add(entry);
            }
        }
        shown.sort(order(sort));
        return shown;
    }

    /** The folders directly inside a folder, by name, from the full list of relative folder paths */
    public static List<String> childrenOf(List<String> folders, String parent) {
        List<String> children = new ArrayList<>();
        for (String folder : folders) {
            int slash = folder.lastIndexOf('/');
            String above = slash < 0 ? TOP : folder.substring(0, slash);
            if (above.equals(parent)) {
                children.add(folder);
            }
        }
        children.sort(Comparator.comparing(name -> name.toLowerCase(Locale.ROOT)));
        return children;
    }

    /** The ranges kept by a trim once a stretch is cut out of them as well */
    public static List<comedymonkey.dailies.file.Spans.Range> cutFrom(List<comedymonkey.dailies.file.Spans.Range> keep, comedymonkey.dailies.file.Spans.Range cut, int total) {
        List<comedymonkey.dailies.file.Spans.Range> cuts = new ArrayList<>(comedymonkey.dailies.file.Spans.invert(comedymonkey.dailies.file.Spans.normalise(keep, total), total));
        cuts.add(cut);
        return comedymonkey.dailies.file.Spans.invert(comedymonkey.dailies.file.Spans.normalise(cuts, total), total);
    }

    /** The ranges kept by a trim once a stretch is kept as well */
    public static List<comedymonkey.dailies.file.Spans.Range> addTo(List<comedymonkey.dailies.file.Spans.Range> keep, comedymonkey.dailies.file.Spans.Range more, int total) {
        List<comedymonkey.dailies.file.Spans.Range> all = new ArrayList<>(keep);
        all.add(more);
        return comedymonkey.dailies.file.Spans.normalise(all, total);
    }

    /**
     * A moment typed by a person as ticks: "1:05.5" (minutes, seconds and tenths), "65.5" (seconds), "1:02:03"
     * (hours too) or "1310t" (ticks outright). Twenty ticks to the second.
     *
     * @return the tick, or -1 when the words are not a time
     */
    public static int ticksFromWords(@Nullable String words) {
        if (words == null || words.isBlank()) {
            return -1;
        }
        String text = words.trim().toLowerCase(Locale.ROOT).replace(',', '.');
        try {
            if (text.endsWith("t")) {
                int ticks = Integer.parseInt(text.substring(0, text.length() - 1).trim());
                return ticks < 0 ? -1 : ticks;
            }
            String[] parts = text.split(":");
            if (parts.length > 3) {
                return -1;
            }
            double seconds = 0;
            for (String part : parts) {
                if (part.isEmpty()) {
                    return -1;
                }
                seconds = seconds * 60 + Double.parseDouble(part);
            }
            if (seconds < 0 || Double.isNaN(seconds) || Double.isInfinite(seconds)) {
                return -1;
            }
            return (int) Math.round(seconds * 20.0);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Whether a name will do for a folder: something to see, no slashes or characters a file system refuses, and not
     * a name Windows keeps for itself.
     *
     * @return why not, or null when it will do
     */
    public static @Nullable String badFolderName(@Nullable String name) {
        if (name == null || name.isBlank()) {
            return "A folder needs a name";
        }
        String trimmed = name.trim();
        if (trimmed.length() > 64) {
            return "A folder name can be at most 64 letters long";
        }
        for (char character : trimmed.toCharArray()) {
            if ("/\\:*?\"<>|".indexOf(character) >= 0 || character < 32) {
                return "A folder name cannot hold / \\ : * ? \" < > |";
            }
        }
        if (trimmed.equals(".") || trimmed.equals("..") || trimmed.endsWith(".")) {
            return "A folder name cannot end with a full stop";
        }
        String upper = trimmed.toUpperCase(Locale.ROOT);
        int dot = upper.indexOf('.');
        String stem = dot < 0 ? upper : upper.substring(0, dot);
        if (stem.matches("CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9]")) {
            return "Windows keeps the name " + trimmed + " for itself";
        }
        return null;
    }

}
