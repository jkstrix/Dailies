package comedymonkey.dailies.selftest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import comedymonkey.dailies.capture.Recorder;
import comedymonkey.dailies.file.RecordingFile;
import comedymonkey.dailies.file.RecordingManifest;
import comedymonkey.dailies.file.RecordingWriter;
import comedymonkey.dailies.file.Records;
import comedymonkey.dailies.file.Spans;
import comedymonkey.dailies.library.Bin;
import comedymonkey.dailies.library.DiskGuard;
import comedymonkey.dailies.library.Library;
import comedymonkey.dailies.library.LibraryIndex;
import comedymonkey.dailies.library.LibraryJob;
import comedymonkey.dailies.library.LibraryQuery;
import comedymonkey.dailies.library.Retention;
import comedymonkey.dailies.library.StorageReport;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.ui.CombineScreen;
import comedymonkey.dailies.ui.Previews;
import comedymonkey.dailies.ui.RecordingsScreen;
import comedymonkey.dailies.ui.TrimScreen;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static comedymonkey.dailies.selftest.SelfTest.args;
import static comedymonkey.dailies.selftest.SelfTest.check;

/**
 * The library's screens and the bin (specification REC-5, REC-8 and REC-9, with the screens of FILE-9 and FILE-10).
 * Small recordings of its own are written straight to the recordings folder, so the list has names, worlds, players,
 * dates and lengths that are known: they are searched for, sorted, put in folders, starred, looked at in the pane of
 * details, deleted into the bin and restored, by the screen and by the tools. A scene removed in the editor goes to the
 * bin and comes back. The combine screen reorders and combines; the trim screen starts from the export range and
 * shows the moments. The storage view and the retention rule are tried, and emptying the bin asks first.
 * <p>
 * Comes in at the title screen with nothing open and leaves there, with the settings it changed put back.
 */
final class LibraryScreenSteps {

    private static final String CASTLE = "Library Castle";
    private static final String NETHER = "Library Nether";
    private static final String FARM = "Library Farm";
    private static final String EDITED = "Library Edited";
    private static final String EPISODES = "Library Episodes";

    private LibraryScreenSteps() {
    }

    static void run(SelfTest test) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        check(!Playback.active() && Recorder.get().state() == Recorder.State.IDLE, "the library steps want nothing open and nothing being recorded");
        Path folder = Recorder.folder();
        Files.createDirectories(folder.resolve(EPISODES));
        String castleId = UUID.randomUUID().toString();
        String netherId = UUID.randomUUID().toString();
        String farmId = UUID.randomUUID().toString();
        String editedId = UUID.randomUUID().toString();
        Path castle = tiny(folder.resolve(CASTLE + RecordingFile.EXTENSION), castleId, CASTLE, "Kingdom", List.of("Steve", "Alex"), at(2026, 11, 17), 10, List.of());
        Path nether = tiny(folder.resolve(EPISODES).resolve(NETHER + RecordingFile.EXTENSION), netherId, NETHER, "Kingdom", List.of("Steve"), at(2026, 10, 2), 40, List.of());
        Path farm = tiny(folder.resolve(FARM + RecordingFile.EXTENSION), farmId, FARM, "Smallholding", List.of("Alex"), at(2025, 3, 9), 20, List.of());
        Path edited = tiny(folder.resolve(EDITED + RecordingFile.EXTENSION), editedId, EDITED, "Kingdom + Smallholding", List.of("Steve", "Alex"), at(2026, 11, 18), 30,
            List.of(new Spans.Span(castleId, 0, 10, 0), new Spans.Span(farmId, 0, 20, 10)));
        for (Path file : List.of(castle, nether, farm, edited)) {
            Library.changed(file);
        }
        Library.index().foldersChanged();
        check(Library.index().folders().contains(EPISODES), "the index does not know the folder " + EPISODES + ": " + Library.index().folders());

        // --- The list: search and sort (REC-5) ---
        test.onClientRun(() -> minecraft.setScreen(new RecordingsScreen(null)));
        RecordingsScreen list = listScreen(test, minecraft);
        test.waitFor(() -> !list.stillLooking() && list.namesShown().contains(FARM), 30, "the list to show the recordings made for it");
        expectNames(test, list, "library kingdom", "NEWEST", List.of(EDITED, CASTLE, NETHER));
        expectNames(test, list, "library smallholding", "NEWEST", List.of(EDITED, FARM));
        expectNames(test, list, "library steve", "NEWEST", List.of(EDITED, CASTLE, NETHER));
        expectNames(test, list, "library 2025-03", "NEWEST", List.of(FARM));
        expectNames(test, list, "library 17 nov 2026", "NEWEST", List.of(CASTLE));
        expectNames(test, list, "library", "LENGTH", List.of(NETHER, EDITED, FARM, CASTLE));
        expectNames(test, list, "library", "NAME", List.of(CASTLE, EDITED, FARM, NETHER));
        expectNames(test, list, "library", "OLDEST", List.of(FARM, NETHER, CASTLE, EDITED));
        test.onClientRun(() -> {
            list.setSearch("");
            list.setSort("NEWEST");
            list.setFolder(EPISODES);
        });
        check(test.onClient(list::namesShown).equals(List.of(NETHER)), "the folder " + EPISODES + " should hold only " + NETHER + ": " + test.onClient(list::namesShown));
        test.onClientRun(() -> list.setFolder(LibraryQuery.ALL));

        // Every control explains itself and none overlaps another, in each view
        for (String view : List.of("recordings", "bin", "storage")) {
            test.onClientRun(() -> list.showView(view));
            test.sleepTicks(4);
            List<String> missing = test.onClient(list::controlsWithoutTips);
            check(missing.isEmpty(), "controls of the " + view + " view without an explanation: " + missing);
            if (minecraft.getWindow().getGuiScaledWidth() >= 640) {
                List<String> crowded = test.onClient(list::crowded);
                check(crowded.isEmpty(), "the " + view + " view is crowded at " + minecraft.getWindow().getGuiScaledWidth() + " wide: " + crowded);
            } else {
                test.skip("the window is narrower than 640 at its GUI scale, so the library's layout was not checked for crowding");
            }
        }
        test.onClientRun(() -> list.showView("recordings"));
        test.waitFor(() -> !list.stillLooking(), 30, "the list to look again");

        // --- The pane of details, with the spans of an edited recording ---
        test.onClientRun(() -> {
            list.setSearch("library");
            list.chooseByName(EDITED);
        });
        test.waitFor(list::detailsRead, 30, "the details of " + EDITED + " to be read");
        test.sleepTicks(4);
        List<String> details = test.onClient(list::detailsLines);
        check(details.stream().anyMatch(line -> line.contains("Kingdom + Smallholding")), "the details do not give the world: " + details);
        check(details.stream().anyMatch(line -> line.contains("Steve, Alex")), "the details do not give the players: " + details);
        check(details.stream().filter(line -> line.contains(CASTLE) || line.contains(FARM)).count() == 2, "the details do not give the two stretches it is made from: " + details);

        // --- Folders and stars ---
        Library.star(castleId, true);
        String season = Library.createFolder(LibraryQuery.TOP, "Library Season");
        Path moved = Library.moveInto(List.of(castle), season).get(0);
        check(Files.isRegularFile(moved) && !Files.exists(castle) && moved.getParent().getFileName().toString().equals("Library Season"), "the recording was not moved into the folder: " + moved);
        check(Library.starred(castleId), "the star was lost in the move");
        String renamed = Library.renameFolder(season, "Library Season 2");
        Path inRenamed = folder.resolve(renamed).resolve(castle.getFileName());
        check(Files.isRegularFile(inRenamed) && Library.index().findById(castleId).equals(inRenamed), "the recording did not go with its folder when it was renamed");
        boolean refused = false;
        try {
            Library.deleteFolder(renamed);
        } catch (IllegalStateException expected) {
            refused = true;
        }
        check(refused && Files.isDirectory(folder.resolve(renamed)), "a folder with a recording in it was deleted");
        castle = Library.moveInto(List.of(inRenamed), LibraryQuery.TOP).get(0);
        Library.deleteFolder(renamed);
        check(!Files.exists(folder.resolve(renamed)) && !Library.index().folders().contains(renamed), "the empty folder was not deleted");

        // --- The bin, from the screen (REC-9) ---
        Path farmFile = farm;
        test.onClientRun(() -> {
            list.lookNow();
        });
        test.waitFor(() -> !list.stillLooking(), 30, "the list to look again");
        test.onClientRun(() -> {
            list.setSearch("library");
            list.chooseByName(FARM);
            check(list.press("delete"), "Delete could not be pressed with one recording chosen");
        });
        test.waitFor(() -> !Files.exists(farmFile) && !list.stillLooking(), 30, FARM + " to go to the bin");
        check(Library.index().findById(farmId) == null, "a recording in the bin is still in the index");
        for (LibraryIndex.Hit hit : Library.index().locate(farmId, 5)) {
            check(!hit.original(), "a moment of a recording in the bin is still found in it");
        }
        check(Library.bin().list().stream().anyMatch(item -> item.name.equals(FARM) && item.kind() == Bin.Kind.RECORDING), "the bin does not hold " + FARM);
        test.onClientRun(() -> list.showView("bin"));
        test.waitFor(() -> !list.stillLooking() && list.binNames().contains(FARM), 30, "the bin view to show " + FARM);
        test.onClientRun(() -> {
            check(list.chooseInBin(FARM), FARM + " could not be chosen in the bin");
            check(list.press("restore"), "Restore could not be pressed");
        });
        test.waitFor(() -> Files.isRegularFile(farmFile) && !list.stillLooking(), 30, FARM + " to come back from the bin");
        check(farmFile.equals(Library.index().findById(farmId)), "the restored recording is not where it was");
        check(Library.index().locate(farmId, 5).stream().anyMatch(LibraryIndex.Hit::original), "a moment of the restored recording is not found in it again");

        // --- The bin, through the tools ---
        JsonObject deleted = test.tool("replay_delete", args("recording", NETHER));
        check(deleted.get("kind").getAsString().equals("recording") && !Files.exists(nether), "replay_delete did not move the recording to the bin: " + deleted);
        JsonObject binList = test.tool("replay_bin_list", new JsonObject());
        int days = binList.get("bin_days").getAsInt();
        JsonObject listed = null;
        for (JsonElement item : binList.getAsJsonArray("items")) {
            if (item.getAsJsonObject().get("id").getAsString().equals(deleted.get("id").getAsString())) {
                listed = item.getAsJsonObject();
            }
        }
        check(listed != null && listed.get("days_left").getAsInt() == days, "replay_bin_list does not show the recording with all its days left: " + binList);
        test.tool("replay_bin_restore", args("id", deleted.get("id").getAsString()));
        check(Files.isRegularFile(nether), "replay_bin_restore did not put the recording back in its folder");
        check(test.toolError("replay_bin_empty", args("confirm", false)).contains("confirm"), "emptying the bin without confirming was not refused");

        // --- A scene removed in the editor goes to the bin and comes back ---
        Path real = null;
        for (LibraryIndex.Entry entry : Library.list()) {
            if (!entry.name.startsWith("Library ") && entry.totalTicks >= 200 && !entry.isEdited()) {
                real = Library.fileOf(entry);
                break;
            }
        }
        if (real == null) {
            test.skip("no recording of 200 ticks or more was left to open, so a scene was not put in the bin and back, and the trim screen was not opened from the editor");
        } else {
            sceneAndTrim(test, minecraft, real);
        }

        // --- The combine screen ---
        test.onClientRun(() -> minecraft.setScreen(new RecordingsScreen(null)));
        RecordingsScreen again = listScreen(test, minecraft);
        test.waitFor(() -> !again.stillLooking() && again.namesShown().contains(FARM), 30, "the list to come back");
        test.onClientRun(() -> {
            again.setSearch("library");
            again.chooseByName(CASTLE, FARM);
            check(again.chosenCount() == 2, "two recordings could not be chosen together");
            check(again.press("combine"), "Combine could not be pressed with two recordings chosen");
        });
        test.waitFor(() -> minecraft.screen instanceof CombineScreen, 10, "the combine screen to open");
        CombineScreen combine = (CombineScreen) minecraft.screen;
        check(test.onClient(combine::orderShown).equals(List.of(FARM, CASTLE)), "the combine screen should start with the oldest footage first: " + test.onClient(combine::orderShown));
        check(test.onClient(combine::boundariesShown) == 1, "two recordings should have one boundary between them");
        test.onClientRun(() -> {
            combine.moveSource(1, 0);
            combine.name("Library Combined");
        });
        check(test.onClient(combine::orderShown).equals(List.of(CASTLE, FARM)), "moving a recording up did not reorder the list");
        test.sleepTicks(4);
        LibraryJob job = test.onClient(combine::startNow);
        check(job != null, "the combine did not start");
        test.waitFor(() -> job.state() != LibraryJob.State.RUNNING, 120, "the combine to finish");
        check(job.state() == LibraryJob.State.DONE && job.resultTicks() == 30, "the combine did not make a recording of 30 ticks: " + job.state() + " " + job.error());
        Path combined = job.result();
        RecordingManifest made = RecordingFile.readManifest(combined);
        check(made.markers.stream().anyMatch(marker -> marker.kind == RecordingManifest.Marker.BOUNDARY && marker.tick == 10), "the combined recording has no boundary at tick 10");
        test.onClientRun(combine::onClose);

        // --- Storage (REC-8) ---
        test.waitFor(() -> minecraft.screen instanceof RecordingsScreen, 10, "the list to come back after combining");
        RecordingsScreen storage = (RecordingsScreen) minecraft.screen;
        test.onClientRun(() -> storage.showView("storage"));
        test.waitFor(() -> storage.storageShown() != null && !storage.stillLooking(), 60, "the storage view to measure");
        StorageReport report = test.onClient(storage::storageShown);
        check(report.recordings > 0 && report.worlds.containsKey("Kingdom") && report.worlds.get("Kingdom").recordingCount >= 2,
            "the storage view does not count the recordings of Kingdom: " + report.worlds.keySet());
        JsonObject measured = test.tool("replay_storage", new JsonObject());
        check(measured.get("recordings_bytes").getAsLong() > 0 && measured.getAsJsonArray("worlds").size() >= 2, "replay_storage says too little: " + measured);
        test.tool("config_set", args("key", "diskWarnGigabytes", "value", 100000));
        long free = DiskGuard.free();
        check(free < 0 || free >= 100000L * Retention.GIGABYTE || DiskGuard.state(free) == Retention.Disk.LOW, "below the warning threshold the disk is not called low");
        test.tool("config_set", args("key", "diskWarnGigabytes", "value", 20));
        test.skip("pausing a recording when the disk is nearly full needs a recording in a world; the decision is unit tested (REC-8)");

        // --- Retention: old recordings go to the bin unless starred (REC-8) ---
        long old = System.currentTimeMillis() - 10 * Bin.DAY_MILLIS;
        String oldId = UUID.randomUUID().toString();
        String keptId = UUID.randomUUID().toString();
        Path oldFile = tiny(folder.resolve("Library Old" + RecordingFile.EXTENSION), oldId, "Library Old", "Kingdom", List.of("Steve"), old, 10, List.of());
        Path keptFile = tiny(folder.resolve("Library Old Starred" + RecordingFile.EXTENSION), keptId, "Library Old Starred", "Kingdom", List.of("Steve"), old, 10, List.of());
        Library.changed(oldFile);
        Library.changed(keptFile);
        Library.star(keptId, true);
        test.tool("config_set", args("key", "retentionDays", "value", 5));
        try {
            Library.housekeeping();
        } finally {
            test.tool("config_set", args("key", "retentionDays", "value", 0));
        }
        check(!Files.exists(oldFile) && Library.bin().list().stream().anyMatch(item -> item.recordingId.equals(oldId)), "a recording older than retentionDays was not moved to the bin");
        check(Files.isRegularFile(keptFile), "a starred recording was moved to the bin by the retention rule");

        // --- An item past its days goes for good at the next tidy ---
        Bin.Item expiring = Library.delete(keptFile);
        Library.bin().find(expiring.id).binned -= (Library.binDays() + 1L) * Bin.DAY_MILLIS;
        Library.housekeeping();
        check(Library.bin().find(expiring.id) == null && !Files.exists(Library.bin().folder().resolve(expiring.id)), "an item older than binDays is still in the bin");

        // --- Clear up, then empty the bin, which asks first ---
        for (Path file : List.of(castle, nether, farm, edited, combined)) {
            if (Files.exists(file)) {
                Library.delete(file);
            }
        }
        Files.deleteIfExists(folder.resolve(EPISODES));
        Library.index().foldersChanged();
        test.onClientRun(() -> storage.showView("bin"));
        test.waitFor(() -> !storage.stillLooking() && !storage.binNames().isEmpty(), 30, "the bin view to show what was deleted");
        test.onClientRun(() -> check(storage.press("empty_bin"), "Empty bin could not be pressed"));
        test.waitFor(Previews::asking, 10, "emptying the bin to ask first");
        test.onClientRun(() -> Previews.answerAsk(false));
        test.sleepTicks(4);
        check(!Library.bin().list().isEmpty(), "answering no still emptied the bin");
        test.waitFor(() -> minecraft.screen instanceof RecordingsScreen, 10, "the list to come back after the question");
        RecordingsScreen last = (RecordingsScreen) minecraft.screen;
        test.waitFor(() -> !last.stillLooking(), 30, "the list to look again");
        test.onClientRun(() -> check(last.press("empty_bin"), "Empty bin could not be pressed a second time"));
        test.waitFor(Previews::asking, 10, "emptying the bin to ask again");
        test.onClientRun(() -> Previews.answerAsk(true));
        test.waitFor(() -> Library.bin().list().isEmpty(), 60, "the bin to be emptied");
        test.onClientRun(() -> minecraft.setScreen(null));
        test.log("library: searched, sorted, foldered, starred and detailed; deleted into the bin and restored by screen and tool; combined by the screen; storage measured; "
            + "old recordings binned unless starred; an expired item went; emptying asked first");
    }

    /** With a real recording open: a scene through the bin and back, then the trim screen from the export range */
    private static void sceneAndTrim(SelfTest test, Minecraft minecraft, Path real) throws Exception {
        String id = RecordingFile.readManifest(real).id;
        test.tool("replay_open", args("path", real.toString()));
        test.waitFor(() -> Playback.lastError() != null || Playback.loaded() && !Playback.jumping() && id.equals(Playback.recordingId()), 120, "a recording to open for the scene steps");
        check(Playback.lastError() == null, "the recording did not open: " + Playback.lastError());
        JsonObject added = test.tool("scene_add", args("name", "Library scene"));
        int index = sceneIndex(test.tool("scene_list", new JsonObject()), "Library scene");
        check(index >= 0, "the scene was not added: " + added);
        test.tool("scene_remove", args("index", index, "force", true));
        test.waitFor(() -> Library.bin().list().stream().anyMatch(item -> item.kind() == Bin.Kind.SCENE && item.name.equals("Library scene")), 20, "the removed scene to reach the bin");
        Bin.Item scene = Library.bin().list().stream().filter(item -> item.kind() == Bin.Kind.SCENE && item.name.equals("Library scene")).findFirst().orElseThrow();
        check(scene.recordingId.equals(id), "the scene in the bin does not know its recording");
        test.tool("replay_bin_restore", args("id", scene.id));
        JsonObject scenes = test.tool("scene_list", new JsonObject());
        check(sceneIndex(scenes, "Library scene") >= 0, "the scene did not come back into the editor: " + scenes);
        check(Library.bin().find(scene.id) == null, "a scene put back is still in the bin");
        test.tool("editor_undo", new JsonObject());
        check(sceneIndex(test.tool("scene_list", new JsonObject()), "Library scene") < 0, "undo did not take the restored scene away again");

        // The trim screen opened from the editor starts from the scene's export range
        test.tool("scene_set_export_range", args("start_tick", 40, "end_tick", 139));
        test.onClientRun(() -> minecraft.setScreen(TrimScreen.fromEditor(null)));
        test.waitFor(() -> minecraft.screen instanceof TrimScreen, 10, "the trim screen to open");
        TrimScreen trim = (TrimScreen) minecraft.screen;
        List<int[]> kept = test.onClient(trim::kept);
        check(kept.size() == 1 && kept.get(0)[0] == 40 && kept.get(0)[1] == 140, "the trim screen did not start from the export range 40 to 139: " + describe(kept));
        test.onClientRun(() -> {
            trim.select(60, 80);
            trim.cutSelection();
        });
        kept = test.onClient(trim::kept);
        check(kept.size() == 2 && kept.get(0)[1] == 60 && kept.get(1)[0] == 80, "cutting a stretch from what is kept did not split it: " + describe(kept));
        test.sleepTicks(20);
        test.log("the trim timeline shows " + test.onClient(trim::momentsShown) + " moment marks and clip bands for the open recording");
        test.onClientRun(() -> minecraft.setScreen(null));
        test.tool("scene_set_export_range", args("clear", true));
        test.tool("replay_close", new JsonObject());
        test.waitFor(() -> !Playback.active() && minecraft.level == null, 60, "the recording to close after the scene steps");
    }

    private static int sceneIndex(JsonObject scenes, String name) {
        JsonArray list = scenes.getAsJsonArray("scenes");
        if (list == null) {
            return -1;
        }
        for (JsonElement element : list) {
            JsonObject scene = element.getAsJsonObject();
            if (scene.get("name").getAsString().equals(name)) {
                return scene.get("index").getAsInt();
            }
        }
        return -1;
    }

    private static String describe(List<int[]> ranges) {
        StringBuilder words = new StringBuilder();
        ranges.forEach(range -> words.append('[').append(range[0]).append(", ").append(range[1]).append(']'));
        return words.toString();
    }

    private static RecordingsScreen listScreen(SelfTest test, Minecraft minecraft) throws Exception {
        test.waitFor(() -> minecraft.screen instanceof RecordingsScreen, 10, "the list of recordings to open");
        return (RecordingsScreen) minecraft.screen;
    }

    private static void expectNames(SelfTest test, RecordingsScreen list, String search, String sort, List<String> wanted) throws Exception {
        test.onClientRun(() -> {
            list.setSearch(search);
            list.setSort(sort);
        });
        List<String> shown = test.onClient(list::namesShown);
        check(shown.equals(wanted), "searching '" + search + "' sorted " + sort.toLowerCase() + " should show " + wanted + ", not " + shown);
    }

    private static long at(int year, int month, int day) {
        return LocalDateTime.of(year, month, day, 12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /** A recording of a few empty ticks under a known description, written straight to the recordings folder */
    private static Path tiny(Path file, String id, String name, String world, List<String> players, long created, int ticks, List<Spans.Span> spans) throws Exception {
        RecordingManifest manifest = new RecordingManifest();
        manifest.id = id;
        manifest.name = name;
        manifest.worldName = world;
        manifest.players = new ArrayList<>(players);
        manifest.recorderName = players.isEmpty() ? "" : players.get(0);
        manifest.created = created;
        manifest.gameVersion = net.minecraft.SharedConstants.getCurrentVersion().getName();
        manifest.dataVersion = net.minecraft.SharedConstants.getCurrentVersion().getDataVersion().getVersion();
        manifest.protocolVersion = net.minecraft.SharedConstants.getProtocolVersion();
        if (!spans.isEmpty()) {
            manifest.edited = created;
            for (Spans.Span span : spans) {
                manifest.spans.add(new RecordingManifest.SpanEntry(span));
                RecordingManifest.SourceEntry source = new RecordingManifest.SourceEntry();
                source.id = span.source();
                source.name = span.source().equals(spans.get(0).source()) ? CASTLE : FARM;
                source.totalTicks = span.length();
                manifest.sources.add(source);
            }
        }
        RecordingWriter writer = RecordingWriter.create(file, manifest);
        Records.Writer few = new Records.Writer();
        few.number(Records.SNAPSHOT_BEGIN, 0);
        few.record(Records.DISCONTINUITY);
        few.record(Records.SNAPSHOT_END);
        few.number(Records.TICK, ticks);
        byte[] bytes = few.take();
        writer.beginSegment(0);
        writer.appendToSegment(bytes, 0, bytes.length, ticks);
        writer.finish();
        return file;
    }

}
