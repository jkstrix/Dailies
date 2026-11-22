package comedymonkey.dailies.library;

import comedymonkey.dailies.file.Spans;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Searching, sorting and filtering the list of recordings, and the folders it shows (specification REC-5) */
class LibraryQueryTest {

    private static final ZoneId UTC = ZoneOffset.UTC;

    private static long at(int year, int month, int day) {
        return LocalDateTime.of(year, month, day, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    private static LibraryIndex.Entry entry(String file, String name, String world, long created, int ticks, long size, String... players) {
        LibraryIndex.Entry entry = new LibraryIndex.Entry();
        entry.id = "id-" + name;
        entry.file = file;
        entry.name = name;
        entry.world = world;
        entry.recorder = players.length > 0 ? players[0] : "";
        entry.players = new ArrayList<>(List.of(players));
        entry.created = created;
        entry.totalTicks = ticks;
        entry.size = size;
        return entry;
    }

    private final LibraryIndex.Entry castle = entry("Castle.dailies", "Building the castle", "Kingdom", at(2026, 11, 17), 6000, 300, "Steve", "Alex");
    private final LibraryIndex.Entry nether = entry("Episodes/Nether trip.dailies", "Nether trip", "Kingdom", at(2026, 10, 2), 12000, 900, "Steve");
    private final LibraryIndex.Entry farm = entry("Episodes/Old/Farm.dailies", "A farm", "Smallholding", at(2025, 3, 9), 2400, 100, "Alex");
    private final List<LibraryIndex.Entry> all = List.of(this.castle, this.nether, this.farm);

    private static List<String> names(List<LibraryIndex.Entry> entries) {
        List<String> names = new ArrayList<>();
        entries.forEach(entry -> names.add(entry.name));
        return names;
    }

    @Test
    void searchFindsNameWorldPlayerAndDate() {
        assertTrue(LibraryQuery.matches(this.castle, "castle", UTC));
        assertTrue(LibraryQuery.matches(this.castle, "KINGDOM", UTC));
        assertTrue(LibraryQuery.matches(this.castle, "alex", UTC));
        assertTrue(LibraryQuery.matches(this.castle, "2026-11-17", UTC));
        assertTrue(LibraryQuery.matches(this.castle, "17 November 2026", UTC));
        assertTrue(LibraryQuery.matches(this.castle, "nov 2026", UTC));
        assertTrue(LibraryQuery.matches(this.castle, "2026-11", UTC));
        assertFalse(LibraryQuery.matches(this.castle, "2026-10", UTC));
        assertFalse(LibraryQuery.matches(this.farm, "steve", UTC));
        // Every word must be found, each anywhere
        assertTrue(LibraryQuery.matches(this.nether, "steve kingdom nether", UTC));
        assertFalse(LibraryQuery.matches(this.nether, "steve smallholding", UTC));
        assertTrue(LibraryQuery.matches(this.farm, "   ", UTC));
        assertTrue(LibraryQuery.matches(this.farm, null, UTC));
    }

    @Test
    void sortsByDateNameLengthAndSize() {
        assertEquals(List.of("Building the castle", "Nether trip", "A farm"), names(LibraryQuery.apply(this.all, LibraryQuery.ALL, "", LibraryQuery.Sort.NEWEST, Set.of(), false, UTC)));
        assertEquals(List.of("A farm", "Nether trip", "Building the castle"), names(LibraryQuery.apply(this.all, LibraryQuery.ALL, "", LibraryQuery.Sort.OLDEST, Set.of(), false, UTC)));
        assertEquals(List.of("A farm", "Building the castle", "Nether trip"), names(LibraryQuery.apply(this.all, LibraryQuery.ALL, "", LibraryQuery.Sort.NAME, Set.of(), false, UTC)));
        assertEquals(List.of("Nether trip", "Building the castle", "A farm"), names(LibraryQuery.apply(this.all, LibraryQuery.ALL, "", LibraryQuery.Sort.LENGTH, Set.of(), false, UTC)));
        assertEquals(List.of("Nether trip", "Building the castle", "A farm"), names(LibraryQuery.apply(this.all, LibraryQuery.ALL, "", LibraryQuery.Sort.SIZE, Set.of(), false, UTC)));
    }

    @Test
    void editedRecordingsSortByWhenTheyWereMade() {
        LibraryIndex.Entry trimmed = entry("Trimmed.dailies", "Trimmed", "Smallholding", at(2025, 3, 9), 100, 10, "Alex");
        trimmed.edited = at(2026, 12, 1);
        List<LibraryIndex.Entry> with = new ArrayList<>(this.all);
        with.add(trimmed);
        assertEquals("Trimmed", LibraryQuery.apply(with, LibraryQuery.ALL, "", LibraryQuery.Sort.NEWEST, Set.of(), false, UTC).get(0).name);
    }

    @Test
    void foldersAndStarsFilterAndASearchLooksEverywhere() {
        assertEquals(List.of("Building the castle"), names(LibraryQuery.apply(this.all, LibraryQuery.TOP, "", LibraryQuery.Sort.NEWEST, Set.of(), false, UTC)));
        assertEquals(List.of("Nether trip"), names(LibraryQuery.apply(this.all, "Episodes", "", LibraryQuery.Sort.NEWEST, Set.of(), false, UTC)));
        assertEquals(List.of("A farm"), names(LibraryQuery.apply(this.all, "Episodes/Old", "", LibraryQuery.Sort.NEWEST, Set.of(), false, UTC)));
        // A search is never hidden by the folder on show
        assertEquals(List.of("A farm"), names(LibraryQuery.apply(this.all, LibraryQuery.TOP, "smallholding", LibraryQuery.Sort.NEWEST, Set.of(), false, UTC)));
        assertEquals(List.of("Nether trip"), names(LibraryQuery.apply(this.all, LibraryQuery.ALL, "", LibraryQuery.Sort.NEWEST, Set.of(this.nether.id), true, UTC)));
        assertEquals(List.of(), names(LibraryQuery.apply(this.all, LibraryQuery.ALL, "alex", LibraryQuery.Sort.NEWEST, Set.of(this.nether.id), true, UTC)));
        assertEquals("Episodes/Old", LibraryQuery.folderOf(this.farm));
        assertEquals("", LibraryQuery.folderOf(this.castle));
    }

    @Test
    void childFoldersAreThoseDirectlyInside() {
        List<String> folders = List.of("Episodes", "Episodes/Old", "Episodes/new", "Builds");
        assertEquals(List.of("Builds", "Episodes"), LibraryQuery.childrenOf(folders, LibraryQuery.TOP));
        assertEquals(List.of("Episodes/new", "Episodes/Old"), LibraryQuery.childrenOf(folders, "Episodes"));
        assertEquals(List.of(), LibraryQuery.childrenOf(folders, "Builds"));
    }

    @Test
    void folderNamesAreChecked() {
        assertNull(LibraryQuery.badFolderName("Season 2"));
        assertNotNull(LibraryQuery.badFolderName(""));
        assertNotNull(LibraryQuery.badFolderName("a/b"));
        assertNotNull(LibraryQuery.badFolderName("what?"));
        assertNotNull(LibraryQuery.badFolderName(".."));
        assertNotNull(LibraryQuery.badFolderName("ends."));
        assertNotNull(LibraryQuery.badFolderName("CON"));
        assertNotNull(LibraryQuery.badFolderName("com1.txt"));
        assertNotNull(LibraryQuery.badFolderName("x".repeat(65)));
    }

    @Test
    void typedTimesBecomeTicks() {
        assertEquals(1310, LibraryQuery.ticksFromWords("1:05.5"));
        assertEquals(1310, LibraryQuery.ticksFromWords("65.5"));
        assertEquals(1310, LibraryQuery.ticksFromWords("65,5"));
        assertEquals(1310, LibraryQuery.ticksFromWords("1310t"));
        assertEquals(20 * 3723, LibraryQuery.ticksFromWords("1:02:03"));
        assertEquals(0, LibraryQuery.ticksFromWords("0"));
        assertEquals(-1, LibraryQuery.ticksFromWords("soon"));
        assertEquals(-1, LibraryQuery.ticksFromWords("1::2"));
        assertEquals(-1, LibraryQuery.ticksFromWords("-5"));
        assertEquals(-1, LibraryQuery.ticksFromWords(""));
    }

    @Test
    void stretchesAreCutFromAndAddedToWhatIsKept() {
        List<Spans.Range> whole = List.of(new Spans.Range(0, 1000));
        List<Spans.Range> cut = LibraryQuery.cutFrom(whole, new Spans.Range(200, 300), 1000);
        assertEquals(List.of(new Spans.Range(0, 200), new Spans.Range(300, 1000)), cut);
        List<Spans.Range> again = LibraryQuery.cutFrom(cut, new Spans.Range(900, 1200), 1000);
        assertEquals(List.of(new Spans.Range(0, 200), new Spans.Range(300, 900)), again);
        assertEquals(List.of(new Spans.Range(0, 250), new Spans.Range(300, 900)), LibraryQuery.addTo(again, new Spans.Range(150, 250), 1000));
        assertEquals(List.of(new Spans.Range(0, 900)), LibraryQuery.addTo(again, new Spans.Range(200, 300), 1000));
        assertEquals(List.of(), LibraryQuery.cutFrom(whole, new Spans.Range(0, 1000), 1000));
    }

    @TempDir
    Path folder;

    @Test
    void theIndexLearnsFoldersEvenEmptyOnes() throws Exception {
        Files.createDirectories(this.folder.resolve("Episodes/Old"));
        Files.createDirectories(this.folder.resolve("Builds"));
        LibraryIndex index = new LibraryIndex(this.folder);
        assertEquals(List.of("Builds", "Episodes", "Episodes/Old"), index.folders());
        Files.delete(this.folder.resolve("Builds"));
        index.foldersChanged();
        assertEquals(List.of("Episodes", "Episodes/Old"), index.folders());
        // Remembered in index.json, as the recordings are
        assertTrue(Files.readString(index.indexFile()).contains("Episodes/Old"));
        assertEquals(List.of("Episodes", "Episodes/Old"), new LibraryIndex(this.folder).folders());
    }

    @Test
    void starsFollowTheIdAndSurviveAReload() throws Exception {
        Stars stars = new Stars(this.folder);
        assertFalse(stars.has("a"));
        stars.set("a", true);
        stars.set("b", true);
        stars.set("b", false);
        assertEquals(Set.of("a"), new Stars(this.folder).all());
        assertTrue(new Stars(this.folder).has("a"));
    }

}
