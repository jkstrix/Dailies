package comedymonkey.dailies.library;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The bin (specification REC-9): what goes in, how long it stays, and putting it back */
class BinTest {

    private static final long START = 1_800_000_000_000L;

    @TempDir
    Path dailies;

    /** Somewhere that is not the Dailies folder */
    @TempDir
    Path elsewhere;

    private final AtomicLong now = new AtomicLong(START);

    private Bin bin() {
        return new Bin(this.dailies, this.now::get);
    }

    private Path recording(String relative, String content) throws IOException {
        Path file = this.dailies.resolve("recordings").resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    @Test
    void aRecordingGoesInAndComesBackWhereItWas() throws IOException {
        Path file = this.recording("Episodes/Castle.dailies", "castle");
        Bin bin = this.bin();
        Bin.Item item = bin.put(Bin.Kind.RECORDING, file, "Building the castle", "Kingdom", "id-castle");
        assertFalse(Files.exists(file));
        assertEquals("recordings/Episodes/Castle.dailies", item.from);
        assertEquals(6, item.size);
        assertEquals(6, bin.size());
        assertEquals(List.of(item.id), bin.list().stream().map(each -> each.id).toList());
        assertTrue(Files.isRegularFile(bin.pathOf(item)));
        // A fresh look at the bin, as after a restart, knows it
        Bin again = this.bin();
        assertEquals("Building the castle", again.find(item.id).name);
        Path back = again.restore(item.id);
        assertEquals(file, back);
        assertEquals("castle", Files.readString(file));
        assertTrue(again.list().isEmpty());
        assertFalse(Files.exists(again.folder().resolve(item.id)));
    }

    @Test
    void aRecordingWhoseNameWasTakenComesBackBesideIt() throws IOException {
        Path file = this.recording("Castle.dailies", "old");
        Bin bin = this.bin();
        Bin.Item item = bin.put(Bin.Kind.RECORDING, file, "Castle", "", "id");
        this.recording("Castle.dailies", "new");
        Path back = bin.restore(item.id);
        assertEquals("Castle (2).dailies", back.getFileName().toString());
        assertEquals("old", Files.readString(back));
        assertEquals("new", Files.readString(file));
    }

    @Test
    void aSessionComesBackButNotOverAnother() throws IOException {
        Path session = this.dailies.resolve("moments/20261117-120000");
        Files.createDirectories(session.resolve("audio"));
        Files.writeString(session.resolve("session.json"), "{}");
        Files.writeString(session.resolve("audio/a.wav"), "12345");
        Bin bin = this.bin();
        Bin.Item item = bin.put(Bin.Kind.SESSION, session, "20261117-120000", "Kingdom", "");
        assertEquals(7, item.size);
        assertFalse(Files.exists(session));
        Files.createDirectories(session);
        assertThrows(IOException.class, () -> bin.restore(item.id));
        Files.delete(session);
        assertEquals(session, bin.restore(item.id));
        assertEquals("12345", Files.readString(session.resolve("audio/a.wav")));
    }

    @Test
    void aSceneIsKeptAsItsJson() throws IOException {
        Bin bin = this.bin();
        Bin.Item item = bin.putScene("id-castle", "Building the castle", "Scene 2", "{\"name\":\"Scene 2\"}");
        assertEquals(Bin.Kind.SCENE, item.kind());
        assertEquals("{\"name\":\"Scene 2\"}", bin.sceneJson(item));
        assertThrows(IllegalArgumentException.class, () -> bin.restore(item.id));
        bin.forget(item.id);
        assertNull(bin.find(item.id));
    }

    @Test
    void thingsGoForGoodOnlyWhenTheirDaysAreUp() throws IOException {
        Bin bin = this.bin();
        Bin.Item early = bin.put(Bin.Kind.RECORDING, this.recording("A.dailies", "a"), "A", "", "a");
        this.now.addAndGet(10 * Bin.DAY_MILLIS);
        Bin.Item later = bin.put(Bin.Kind.RECORDING, this.recording("B.dailies", "b"), "B", "", "b");
        // The most recently deleted first
        assertEquals(List.of(later.id, early.id), bin.list().stream().map(each -> each.id).toList());
        this.now.set(START + 30 * Bin.DAY_MILLIS - 1);
        assertTrue(bin.expire(30).isEmpty());
        assertEquals(1, Retention.daysLeft(early, 30, this.now.get()));
        assertEquals(11, Retention.daysLeft(later, 30, this.now.get()));
        this.now.set(START + 30 * Bin.DAY_MILLIS);
        List<Bin.Item> gone = bin.expire(30);
        assertEquals(List.of(early.id), gone.stream().map(each -> each.id).toList());
        assertEquals(0, Retention.daysLeft(early, 30, this.now.get()));
        assertFalse(Files.exists(bin.folder().resolve(early.id)));
        assertEquals(List.of(later.id), this.bin().list().stream().map(each -> each.id).toList());
        // A shorter setting takes effect on the next look
        assertEquals(1, bin.expire(5).size());
        assertTrue(bin.list().isEmpty());
    }

    @Test
    void emptyingDeletesEverything() throws IOException {
        Bin bin = this.bin();
        bin.put(Bin.Kind.RECORDING, this.recording("A.dailies", "a"), "A", "", "a");
        bin.putScene("r", "R", "S", "{}");
        assertEquals(2, bin.empty());
        assertTrue(bin.list().isEmpty());
        assertEquals(0, bin.size());
        try (var left = Files.list(bin.folder())) {
            assertEquals(List.of(Bin.FILE_NAME), left.map(path -> path.getFileName().toString()).toList());
        }
    }

    @Test
    void anItemTakenOutByHandIsNoLongerListed() throws IOException {
        Bin bin = this.bin();
        Bin.Item item = bin.put(Bin.Kind.RECORDING, this.recording("A.dailies", "a"), "A", "", "a");
        Files.delete(bin.pathOf(item));
        assertTrue(this.bin().list().isEmpty());
    }

    @Test
    void nothingIsPutBackOutsideTheDailiesFolder() throws IOException {
        Bin bin = this.bin();
        Bin.Item item = bin.put(Bin.Kind.RECORDING, this.recording("A.dailies", "a"), "A", "", "a");
        item.from = "../elsewhere/A.dailies";
        assertThrows(IOException.class, () -> bin.restore(item.id));
    }


    @Test
    void onlyWhatDailiesKeepsGoesInTheBin() throws IOException {
        Bin bin = this.bin();
        Path outside = this.elsewhere.resolve("level.dat");
        Files.writeString(outside, "someone's world");
        assertThrows(IllegalArgumentException.class, () -> bin.put(Bin.Kind.RECORDING, outside, "level", "", ""));
        assertThrows(IllegalArgumentException.class, () -> bin.put(Bin.Kind.RECORDING, this.dailies.resolve("recordings/../../" + this.elsewhere.getFileName() + "/level.dat"), "level", "", ""));
        assertThrows(IllegalArgumentException.class, () -> bin.put(Bin.Kind.SESSION, this.dailies, "everything", "", ""));
        assertTrue(Files.exists(outside), "a file from outside was moved");
        assertTrue(bin.list().isEmpty());

        // Nor what is in the bin already
        Bin.Item item = bin.put(Bin.Kind.RECORDING, this.recording("A.dailies", "a"), "A", "", "a");
        assertThrows(IllegalArgumentException.class, () -> bin.put(Bin.Kind.RECORDING, bin.pathOf(item), "A again", "", ""));
        assertEquals(1, bin.list().size());
    }

    @Test
    void anItemThatWillNotDeleteLeavesThoseDeletedBeforeItGone() throws IOException {
        Bin bin = this.bin();
        Bin.Item first = bin.put(Bin.Kind.RECORDING, this.recording("A.dailies", "a"), "A", "", "a");
        Bin.Item stuck = bin.put(Bin.Kind.RECORDING, this.recording("B.dailies", "b"), "B", "", "b");
        Bin.Item last = bin.put(Bin.Kind.RECORDING, this.recording("C.dailies", "c"), "C", "", "c");
        Path stuckFolder = bin.pathOf(stuck).getParent();
        boolean refused = false;
        // Held open (which Windows will not delete) in a folder that may not be written to (which other systems will not)
        try (InputStream held = new FileInputStream(bin.pathOf(stuck).toFile())) {
            stuckFolder.toFile().setWritable(false);
            try {
                bin.empty();
            } catch (IOException expected) {
                refused = true;
            }
        } finally {
            stuckFolder.toFile().setWritable(true);
        }
        assumeTrue(refused, "this system deleted a file that was held open in a folder that may not be written to");

        // Gone from the list at once, here and on disk, though the next item could not be deleted
        List<String> left = bin.list().stream().map(item -> item.id).toList();
        assertFalse(left.contains(first.id));
        assertTrue(left.contains(stuck.id));
        assertTrue(left.contains(last.id));
        String saved = Files.readString(bin.folder().resolve(Bin.FILE_NAME), StandardCharsets.UTF_8);
        assertFalse(saved.contains(first.id), "bin.json still lists an item deleted for good");
        assertTrue(saved.contains(last.id));

        // And once it can be, the rest goes
        assertEquals(2, bin.empty());
        assertTrue(bin.list().isEmpty());
    }

}
