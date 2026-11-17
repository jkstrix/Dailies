package comedymonkey.dailies.library;

import comedymonkey.dailies.edit.EditState;
import comedymonkey.dailies.edit.Interpolation;
import comedymonkey.dailies.edit.Keyframe;
import comedymonkey.dailies.edit.KeyframeKind;
import comedymonkey.dailies.edit.Scene;
import comedymonkey.dailies.edit.Track;
import comedymonkey.dailies.file.RecordingFile;
import comedymonkey.dailies.file.RecordingManifest;
import comedymonkey.dailies.file.RecordingWriter;
import comedymonkey.dailies.file.Records;
import comedymonkey.dailies.file.Spans;
import comedymonkey.dailies.file.TimeIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The index of recordings (specification FILE-12 and FILE-13) and how it puts itself right, and carrying scenes across an edit */
class LibraryIndexTest {

    private static final long REAL = 1_800_000_000_000L;

    @TempDir
    Path folder;

    /** A small recording of one segment with a time index: fifty milliseconds a tick from its start */
    private Path record(String file, String id, String world, int ticks, long realStart, long created, long edited, Spans.Span... spans) throws IOException {
        Path path = this.folder.resolve(file + RecordingFile.EXTENSION);
        RecordingManifest manifest = new RecordingManifest();
        manifest.id = id;
        manifest.name = file;
        manifest.created = created;
        manifest.edited = edited;
        manifest.worldName = world;
        manifest.recorderName = "Recorder";
        manifest.players = new ArrayList<>(List.of("Recorder", "Friend"));
        manifest.gameVersion = "1.21.1";
        for (Spans.Span span : spans) {
            manifest.spans.add(new RecordingManifest.SpanEntry(span));
        }
        manifest.realStart = realStart;
        manifest.realEnd = realStart + ticks * 50L;
        RecordingWriter writer = RecordingWriter.create(path, manifest);
        Records.Writer records = new Records.Writer();
        TimeIndex.Encoder times = new TimeIndex.Encoder();
        records.number(Records.SNAPSHOT_BEGIN, 0);
        records.record(Records.DISCONTINUITY);
        records.record(Records.SNAPSHOT_END);
        records.record(Records.TIME, times.next(realStart, 0));
        for (int tick = 1; tick <= ticks; tick++) {
            records.number(Records.TICK, 1);
            records.record(Records.TIME, times.next(realStart + tick * 50L, tick));
        }
        writer.beginSegment(0);
        byte[] bytes = records.take();
        writer.appendToSegment(bytes, 0, bytes.length, ticks);
        writer.manifest().times.add(new RecordingManifest.SegmentTime(0, realStart, 0));
        writer.finish();
        return path;
    }

    /** The original A, the original B, and an episode made of A with its middle cut, then B */
    private void library() throws IOException {
        this.record("A", "A", "Home", 1000, REAL, REAL, 0);
        this.record("B", "B", "Away", 400, REAL + 5_000_000, REAL + 5_000_000, 0);
        this.record("Episode", "E", "Home + Away", 1000, REAL, REAL, REAL + 9_000_000,
            new Spans.Span("A", 0, 300, 0), new Spans.Span("A", 700, 1000, 300), new Spans.Span("B", 0, 400, 600));
    }

    private static List<String> names(List<LibraryIndex.Hit> hits) {
        return hits.stream().map(hit -> hit.entry().name + "@" + hit.tick()).toList();
    }

    @Test
    void aMissingIndexIsBuiltFromTheFilesAndWrittenDown() throws IOException {
        this.library();
        LibraryIndex index = new LibraryIndex(this.folder);
        assertFalse(Files.exists(index.indexFile()));
        List<LibraryIndex.Entry> list = index.list();
        // The newest footage first
        assertEquals(List.of("B", "A", "Episode"), list.stream().map(entry -> entry.name).toList());
        assertTrue(Files.exists(index.indexFile()));
        String written = Files.readString(index.indexFile(), StandardCharsets.UTF_8);
        for (String expected : List.of("\"id\"", "\"file\"", "\"size\"", "\"modified\"", "\"spans\"", "\"name\"", "\"world\"", "\"totalTicks\"", "\"players\"", "\"created\"")) {
            assertTrue(written.contains(expected), "index.json has no " + expected);
        }
        LibraryIndex.Entry episode = list.stream().filter(entry -> entry.id.equals("E")).findFirst().orElseThrow();
        assertEquals(3, episode.spans.size());
        assertEquals(1000, episode.totalTicks);
        assertEquals(List.of("Recorder", "Friend"), episode.players);
        assertEquals(Files.size(this.folder.resolve("Episode.dailies")), episode.size);
    }

    @Test
    void aMomentIsFoundInEveryRecordingThatHoldsItOriginalFirst() throws IOException {
        this.library();
        LibraryIndex index = new LibraryIndex(this.folder);
        // In the kept part of A: the original, then the episode
        assertEquals(List.of("A@100", "Episode@100"), names(index.locate("A", 100)));
        assertTrue(index.locate("A", 100).get(0).original());
        assertFalse(index.locate("A", 100).get(1).original());
        assertEquals(List.of("A@800", "Episode@400"), names(index.locate("A", 800)));
        // In the stretch the episode cut: only the original still holds it
        assertEquals(List.of("A@500"), names(index.locate("A", 500)));
        assertEquals(List.of("B@50", "Episode@650"), names(index.locate("B", 50)));
        // The last tick of an original is a moment of it
        assertEquals("A@1000", names(index.locate("A", 1000)).get(0));
        assertTrue(index.locate("A", 1001).isEmpty());
        assertTrue(index.locate("nobody", 5).isEmpty());
        assertTrue(index.knows("A") && index.knows("E") && !index.knows("nobody"));
    }

    @Test
    void deletingAnOriginalCostsNothingOnceAnEditedRecordingHoldsItsTicks() throws IOException {
        this.library();
        LibraryIndex index = new LibraryIndex(this.folder);
        assertEquals(2, index.locate("A", 100).size());
        // Deleted behind the index's back, as a person does in a file manager
        Files.delete(this.folder.resolve("A.dailies"));
        assertEquals(List.of("Episode@100"), names(index.locate("A", 100)));
        assertTrue(index.knows("A"));
        // What only the original held is cut everywhere now
        assertTrue(index.locate("A", 500).isEmpty());
        assertNull(index.findById("A"));
        // And through the mod itself
        index.forget(this.folder.resolve("B.dailies"));
        Files.delete(this.folder.resolve("B.dailies"));
        assertEquals(List.of("Episode@650"), names(index.locate("B", 50)));
    }

    @Test
    void theNewestRecordingThatHoldsAMomentComesFirstAfterTheOriginal() throws IOException {
        this.library();
        this.record("Later cut", "L", "Home", 500, REAL, REAL, REAL + 20_000_000, new Spans.Span("A", 0, 500, 0));
        this.record("Earlier cut", "F", "Home", 500, REAL, REAL, REAL + 1_000, new Spans.Span("A", 0, 500, 0));
        LibraryIndex index = new LibraryIndex(this.folder);
        assertEquals(List.of("A@100", "Later cut@100", "Episode@100", "Earlier cut@100"), names(index.locate("A", 100)));
        Files.delete(this.folder.resolve("A.dailies"));
        assertEquals("Later cut@100", names(index.locate("A", 100)).get(0));
    }

    @Test
    void aFileRenamedOrMovedOutsideTheGameIsStillFoundByItsId() throws IOException {
        this.library();
        LibraryIndex index = new LibraryIndex(this.folder);
        assertEquals(this.folder.resolve("A.dailies"), index.findById("A"));
        Files.createDirectories(this.folder.resolve("2026/raw"));
        Path moved = this.folder.resolve("2026/raw/first night.dailies");
        Files.move(this.folder.resolve("A.dailies"), moved);
        assertEquals(List.of("A@100", "Episode@100"), names(index.locate("A", 100)));
        assertEquals(moved, index.locate("A", 100).get(0).file());
        assertEquals(moved, index.findById("A"));
        // The index on disk says so too: another game start reads it and need not open the file again
        LibraryIndex again = new LibraryIndex(this.folder);
        assertEquals(moved, again.findById("A"));
        assertTrue(Files.readString(index.indexFile(), StandardCharsets.UTF_8).contains("2026/raw/first night.dailies"));
    }

    @Test
    void anEntryWhoseFileChangedIsReadAgainFromItsHeader() throws IOException {
        this.library();
        LibraryIndex index = new LibraryIndex(this.folder);
        assertEquals("A", index.entryOf(this.folder.resolve("A.dailies")).name);
        try (RecordingFile recording = RecordingFile.openForEditing(this.folder.resolve("A.dailies"))) {
            recording.rename("First night");
        }
        index.refresh(true);
        assertEquals("First night", index.entryOf(this.folder.resolve("A.dailies")).name);
        assertEquals("A", index.entryOf(this.folder.resolve("A.dailies")).id);
    }

    @Test
    void aDeletedOrDamagedIndexIsRebuilt() throws IOException {
        this.library();
        LibraryIndex index = new LibraryIndex(this.folder);
        index.refresh(true);
        Files.delete(index.indexFile());
        assertEquals(List.of("A@100", "Episode@100"), names(index.locate("A", 100)));
        assertTrue(Files.exists(index.indexFile()), "the index was not written again");

        // Rubbish in its place, read by a game that has just started
        Files.writeString(index.indexFile(), "{ this is not json", StandardCharsets.UTF_8);
        LibraryIndex fresh = new LibraryIndex(this.folder);
        assertEquals(List.of("A@100", "Episode@100"), names(fresh.locate("A", 100)));
        fresh.refresh(true);
        assertTrue(Files.readString(index.indexFile(), StandardCharsets.UTF_8).contains("\"recordings\""));

        // An index that remembers a file wrongly (another size) does not win over the file
        String stale = Files.readString(index.indexFile(), StandardCharsets.UTF_8).replace("\"totalTicks\": 1000", "\"totalTicks\": 5").replaceAll("\"size\": \\d+", "\"size\": 1");
        Files.writeString(index.indexFile(), stale, StandardCharsets.UTF_8);
        LibraryIndex checked = new LibraryIndex(this.folder);
        checked.refresh(true);
        assertEquals(3, checked.list().stream().filter(entry -> entry.totalTicks >= 400).count());
    }

    @Test
    void filesThatAreNotRecordingsAreLeftOut() throws IOException {
        this.library();
        Files.writeString(this.folder.resolve("notes.dailies"), "not a recording", StandardCharsets.UTF_8);
        Files.writeString(this.folder.resolve("half.dailies.recording"), "being written", StandardCharsets.UTF_8);
        LibraryIndex index = new LibraryIndex(this.folder);
        assertEquals(3, index.list().size());
    }

    @Test
    void aMomentWithoutItsRecordingIsFoundByItsTimeInARecordingOfTheSameWorld() throws IOException {
        this.library();
        LibraryIndex index = new LibraryIndex(this.folder);
        // Tick 240 of A, by the clock: the original, and the episode, which holds that stretch at the same tick
        long when = REAL + 240 * 50L + 10;
        assertEquals(List.of("A@240", "Episode@240"), names(index.locateByTime(when, "Home")));
        assertEquals(List.of("A@240", "Episode@240"), names(index.locateByTime(when, null)));
        // Another world's recordings are not looked in
        assertTrue(index.locateByTime(when, "Away").stream().noneMatch(hit -> hit.entry().id.equals("A")));
        assertEquals(List.of("B@20"), names(index.locateByTime(REAL + 5_000_000 + 20 * 50L, "Away")));
        // A time no recording holds
        assertTrue(index.locateByTime(REAL + 3_000_000, "Home").isEmpty());
        assertTrue(index.locateByTime(REAL - 60_000, null).isEmpty());
    }

    // --- Scenes across an edit ---

    private static EditState edited() {
        EditState state = new EditState();
        Scene scene = state.scene(0);
        Track camera = new Track(KeyframeKind.CAMERA);
        for (int tick : new int[]{50, 250, 500, 800}) {
            camera.put(new Keyframe.Camera(tick, Interpolation.LINEAR, tick, 64, 0, 0, 0, 0));
        }
        scene.addTrack(camera);
        scene.setExportRange(200, 900);
        return state;
    }

    @Test
    void keyframesMoveWithATrimAndThoseInACutAreDropped() {
        List<Spans.Range> keep = List.of(new Spans.Range(0, 300), new Spans.Range(700, 1000));
        EditState trimmed = EditRemap.trimmed(edited(), keep);
        Track camera = trimmed.scene(0).track(0);
        assertEquals(List.of(50, 250, 400), camera.keyframes().stream().map(Keyframe::tick).toList());
        // Each keyframe still holds what it held: the one from tick 800 is the one now at 400
        assertEquals(800.0, ((Keyframe.Camera) camera.at(400)).x());
        // The export range shrinks to what is left of it: 200 to 299, then 700 to 900, back to back
        assertEquals(200, trimmed.scene(0).exportStart());
        assertEquals(500, trimmed.scene(0).exportEnd());
        // The source's own state is untouched
        assertEquals(4, edited().scene(0).track(0).size());

        EditState gone = EditRemap.trimmed(edited(), List.of(new Spans.Range(0, 100)));
        assertFalse(gone.scene(0).hasExportRange());
        assertEquals(List.of(50), gone.scene(0).track(0).keyframes().stream().map(Keyframe::tick).toList());
        assertEquals("Scene 1", gone.scene(0).name());
    }

    @Test
    void scenesOfCombinedRecordingsAllComeAlongMovedToTheirPlace() {
        EditState joined = EditRemap.joined(Arrays.asList(edited(), null, edited()), List.of(1000, 400, 1000), List.of("A", "B", "C"));
        assertNotNull(joined);
        assertEquals(List.of("Scene 1", "Scene 1 (C)"), joined.scenes().stream().map(Scene::name).toList());
        assertEquals(List.of(50, 250, 500, 800), joined.scene(0).track(0).keyframes().stream().map(Keyframe::tick).toList());
        assertEquals(List.of(1450, 1650, 1900, 2200), joined.scene(1).track(0).keyframes().stream().map(Keyframe::tick).toList());
        assertEquals(1600, joined.scene(1).exportStart());
        assertEquals(2300, joined.scene(1).exportEnd());
        assertEquals(0, joined.currentSceneIndex());
        assertNull(EditRemap.joined(Arrays.asList(null, null), List.of(10, 10), List.of("A", "B")));
    }

}
