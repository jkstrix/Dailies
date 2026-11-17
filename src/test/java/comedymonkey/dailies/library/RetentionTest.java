package comedymonkey.dailies.library;

import comedymonkey.dailies.file.RecordingManifest;
import comedymonkey.dailies.file.Spans;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The storage settings (specification REC-8): which recordings go to the bin, what the free space calls for, and what was exported */
class RetentionTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final long DAY = Bin.DAY_MILLIS;

    @TempDir
    Path folder;

    private static LibraryIndex.Entry entry(String id, long created, int ticks, Spans.Span... spans) {
        LibraryIndex.Entry entry = new LibraryIndex.Entry();
        entry.id = id;
        entry.file = id + ".dailies";
        entry.name = id;
        entry.created = created;
        entry.totalTicks = ticks;
        for (Spans.Span span : spans) {
            entry.spans.add(new RecordingManifest.SpanEntry(span));
        }
        return entry;
    }

    @Test
    void theDefaultKeepsEverything() {
        LibraryIndex.Entry ancient = entry("old", NOW - 4000 * DAY, 100);
        assertFalse(Retention.tooOld(ancient, NOW, 0, false, false));
        assertTrue(Retention.toBin(List.of(ancient), NOW, 0, Set.of(), List.of()).isEmpty());
    }

    @Test
    void oldRecordingsGoUnlessStarredOrExported() {
        LibraryIndex.Entry old = entry("old", NOW - 31 * DAY, 100);
        LibraryIndex.Entry young = entry("young", NOW - 29 * DAY, 100);
        assertTrue(Retention.tooOld(old, NOW, 30, false, false));
        assertFalse(Retention.tooOld(young, NOW, 30, false, false));
        assertFalse(Retention.tooOld(old, NOW, 30, true, false));
        assertFalse(Retention.tooOld(old, NOW, 30, false, true));
        // An edited recording counts from when it was made
        LibraryIndex.Entry remade = entry("remade", NOW - 100 * DAY, 100);
        remade.edited = NOW - DAY;
        assertFalse(Retention.tooOld(remade, NOW, 30, false, false));
    }

    @Test
    void toBinSparesStarsAndExportedFootageAndGoesOldestFirst() {
        LibraryIndex.Entry a = entry("a", NOW - 50 * DAY, 1000);
        LibraryIndex.Entry b = entry("b", NOW - 80 * DAY, 1000);
        LibraryIndex.Entry starred = entry("starred", NOW - 90 * DAY, 1000);
        LibraryIndex.Entry exported = entry("exported", NOW - 90 * DAY, 1000);
        // Trimmed from 'exported' later; it holds the exported stretch too, so it stays as well
        LibraryIndex.Entry trimmed = entry("trimmed", NOW - 90 * DAY, 300, new Spans.Span("exported", 400, 700, 0));
        LibraryIndex.Entry trimmedElsewhere = entry("elsewhere", NOW - 90 * DAY, 100, new Spans.Span("exported", 900, 1000, 0));
        ExportLog.Exported video = new ExportLog.Exported();
        video.recordingId = "something-else";
        video.stretches.add(new ExportLog.Stretch("exported", 500, 600));
        List<LibraryIndex.Entry> going = Retention.toBin(List.of(a, b, starred, exported, trimmed, trimmedElsewhere), NOW, 30, Set.of("starred"), List.of(video));
        assertEquals(List.of("elsewhere", "b", "a"), going.stream().map(entry -> entry.id).toList());
    }

    @Test
    void theFreeSpaceCallsForAWarningThenAPause() {
        long gigabyte = Retention.GIGABYTE;
        assertEquals(Retention.Disk.FINE, Retention.disk(100 * gigabyte, 20, 5));
        assertEquals(Retention.Disk.LOW, Retention.disk(19 * gigabyte, 20, 5));
        assertEquals(Retention.Disk.FULL, Retention.disk(4 * gigabyte, 20, 5));
        assertEquals(Retention.Disk.FINE, Retention.disk(gigabyte, 0, 0));
        assertEquals(Retention.Disk.LOW, Retention.disk(gigabyte, 20, 0));
        // Not knowing is not a reason to stop a recording
        assertEquals(Retention.Disk.FINE, Retention.disk(-1, 20, 5));
    }

    @Test
    void anExportIsRememberedByOriginalAndTick() throws Exception {
        ExportLog log = new ExportLog(this.folder);
        // An episode made of 'a' 0..1000 then 'b' 0..500; ticks 900 to 1100 of it were exported
        List<Spans.Span> map = List.of(new Spans.Span("a", 0, 1000, 0), new Spans.Span("b", 0, 500, 1000));
        log.add("episode", "Kingdom", "exports/clip.mp4", 1234, NOW, map, 900, 1100);
        List<ExportLog.Exported> read = new ExportLog(this.folder).all();
        assertEquals(1, read.size());
        assertEquals(2, read.get(0).stretches.size());
        assertEquals("a", read.get(0).stretches.get(0).source);
        assertEquals(900, read.get(0).stretches.get(0).from);
        assertEquals(1000, read.get(0).stretches.get(0).to);
        assertEquals("b", read.get(0).stretches.get(1).source);
        assertEquals(0, read.get(0).stretches.get(1).from);
        assertEquals(100, read.get(0).stretches.get(1).to);
        assertTrue(ExportLog.holdsExported(entry("a", NOW, 1000), read));
        assertTrue(ExportLog.holdsExported(entry("b", NOW, 500), read));
        assertTrue(ExportLog.holdsExported(entry("episode", NOW, 1500, map.toArray(new Spans.Span[0])), read));
        assertFalse(ExportLog.holdsExported(entry("c", NOW, 500), read));
        assertFalse(ExportLog.holdsExported(entry("bTail", NOW, 300, new Spans.Span("b", 200, 500, 0)), read));
    }

    @Test
    void storageIsMeasuredPerWorld() throws Exception {
        Path dailies = this.folder.resolve("dailies");
        Path session = dailies.resolve("moments/20261117-120000");
        Files.createDirectories(session.resolve("audio"));
        Files.writeString(session.resolve("session.json"), "{\"world_name\":\"Kingdom\"}");
        Files.write(session.resolve("audio/a.wav"), new byte[300]);
        Path exports = dailies.resolve("exports");
        Files.createDirectories(exports);
        Files.write(exports.resolve("clip.mp4"), new byte[500]);
        Files.write(exports.resolve("stray.png"), new byte[70]);
        ExportLog log = new ExportLog(dailies.resolve("recordings"));
        log.add("x", "Kingdom", "exports/clip.mp4", 500, NOW, List.of(new Spans.Span("x", 0, 10, 0)), 0, 10);
        Bin bin = new Bin(dailies, () -> NOW);
        Path binned = dailies.resolve("recordings/Gone.dailies");
        Files.createDirectories(binned.getParent());
        Files.write(binned, new byte[40]);
        bin.put(Bin.Kind.RECORDING, binned, "Gone", "Smallholding", "gone");
        LibraryIndex.Entry castle = entry("castle", NOW, 100);
        castle.world = "Kingdom";
        castle.size = 1000;
        LibraryIndex.Entry farm = entry("farm", NOW, 100);
        farm.world = "Smallholding";
        farm.size = 200;

        StorageReport report = StorageReport.measure(dailies, new ArrayList<>(List.of(castle, farm)), bin, log);
        assertEquals(1200, report.recordings);
        assertEquals(300, report.audio);
        assertEquals(570, report.exports);
        assertEquals(40, report.bin);
        StorageReport.World kingdom = report.worlds.get("Kingdom");
        assertEquals(1000, kingdom.recordings);
        assertEquals(300, kingdom.audio);
        assertEquals(500, kingdom.exports);
        assertEquals(1, kingdom.recordingCount);
        assertEquals(40, report.worlds.get("Smallholding").bin);
        assertEquals(70, report.worlds.get("").exports);
        assertEquals("Kingdom", report.byWorld().get(0).name);
        assertEquals("", report.byWorld().get(report.byWorld().size() - 1).name);
        assertTrue(report.free > 0);
    }

    @Test
    void bytesReadAsAPersonReadsThem() {
        assertEquals("0 KB", StorageReport.bytes(0));
        assertEquals("1 KB", StorageReport.bytes(10));
        assertEquals("512 KB", StorageReport.bytes(512 * 1024));
        assertEquals("3.5 MB", StorageReport.bytes(3 * 1024 * 1024 + 512 * 1024));
        assertEquals("2.0 GB", StorageReport.bytes(2 * Retention.GIGABYTE));
    }

}
