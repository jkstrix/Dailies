package comedymonkey.dailies.core;

import com.google.gson.JsonObject;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.capture.Recorder;
import comedymonkey.dailies.config.DailiesConfig;
import comedymonkey.dailies.config.Setting;
import comedymonkey.dailies.mcp.McpProtocol;
import comedymonkey.dailies.mcp.tools.CoreTools;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.playback.Recordings;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The core's side of {@link Host}: what Moments, the Smart Camera and the MCP server may ask of the rest of the mod.
 * Recording, playback, the editor and export are not built yet, so those answers are the "nothing is happening" ones.
 */
public final class CoreHost implements Host.Mod {

    private static final String NOT_BUILT = "This build of Dailies cannot play recordings back yet";

    private final ModContainer container;
    private final Path dataDirectory;

    public CoreHost(ModContainer container) {
        this.container = container;
        this.dataDirectory = FMLPaths.GAMEDIR.get().resolve("dailies");
        try {
            Files.createDirectories(this.dataDirectory);
        } catch (IOException e) {
            Host.LOGGER.error("Unable to create {}", this.dataDirectory, e);
        }
    }

    @Override
    public Path dataDirectory() {
        return this.dataDirectory;
    }

    @Override
    public String modVersion() {
        return this.container.getModInfo().getVersion().toString();
    }

    @Override
    public boolean isModLoaded(String modId) {
        ModList mods = ModList.get();
        return mods != null && mods.isLoaded(modId);
    }

    // --- Settings ---

    @Override
    public boolean settingsLoaded() {
        return DailiesConfig.loaded();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <V> V setting(String name, V fallback) {
        Setting<?> setting = DailiesConfig.find(name);
        if (setting == null) {
            return fallback;
        }
        Object value = setting.get();
        if (fallback instanceof Double && value instanceof Number number) {
            value = number.doubleValue();
        } else if (fallback instanceof Integer && value instanceof Number number) {
            value = number.intValue();
        } else if (fallback instanceof String && value instanceof Enum<?> constant) {
            value = constant.name();
        }
        if (fallback != null && !fallback.getClass().isInstance(value)) {
            return fallback;
        }
        return (V) value;
    }

    @Override
    public void saveSetting(String name, Object value) {
        Setting<?> setting = DailiesConfig.find(name);
        if (setting == null) {
            throw new IllegalArgumentException("There is no setting called '" + name + "'");
        }
        setting.setFrom(value);
        DailiesConfig.save();
    }

    // --- Recording ---

    @Override
    public @Nullable String recordingId() {
        return Recorder.get().recordingId();
    }

    @Override
    public int recordingTick() {
        return Recorder.get().recordingTick();
    }

    // --- Playback ---

    @Override
    public boolean inPlayback() {
        return Playback.active();
    }

    @Override
    public @Nullable String openRecordingId() {
        return Playback.loaded() ? Playback.recordingId() : null;
    }

    @Override
    public int openRecordingTicks() {
        return Playback.loaded() ? Playback.total() : 0;
    }

    @Override
    public void seek(int tick, boolean pause) {
        if (Playback.loaded()) {
            Playback.jumpTo(tick, pause);
        } else if (Playback.active()) {
            // Still opening: go there as soon as the world is up
            Playback.whenLoaded(() -> Playback.jumpTo(tick, pause));
        }
    }

    // --- Where a moment is (FILE-13, FILE-14) ---

    private static @Nullable comedymonkey.dailies.file.RecordingManifest openManifest() {
        return Playback.loaded() ? Playback.manifest() : null;
    }

    @Override
    public int openRecordingContains(String recordingId, int tick) {
        comedymonkey.dailies.file.RecordingManifest open = openManifest();
        if (open == null || recordingId == null) {
            return -1;
        }
        if (open.id.equals(recordingId) && !open.isEdited()) {
            return tick >= 0 && tick <= open.totalTicks ? tick : -1;
        }
        return comedymonkey.dailies.file.Spans.toHere(open.sourceMap(), recordingId, tick);
    }

    @Override
    public boolean openRecordingMadeFrom(String recordingId) {
        comedymonkey.dailies.file.RecordingManifest open = openManifest();
        return open != null && recordingId != null && open.sourceMap().stream().anyMatch(span -> span.source().equals(recordingId));
    }

    @Override
    public java.util.List<Host.Located> locate(String recordingId, int tick) {
        return this.locate(recordingId, tick, 0, null);
    }

    @Override
    public java.util.List<Host.Located> locate(@Nullable String recordingId, int tick, long realTime, @Nullable String world) {
        comedymonkey.dailies.library.LibraryIndex index = comedymonkey.dailies.library.Library.index();
        comedymonkey.dailies.file.RecordingManifest open = openManifest();
        java.nio.file.Path openFile = open == null || Playback.file() == null ? null : Playback.file().file();
        java.util.List<comedymonkey.dailies.library.LibraryIndex.Hit> hits = recordingId == null || recordingId.isBlank() ? java.util.List.of() : index.locate(recordingId, tick);
        if (hits.isEmpty() && realTime > 0 && (recordingId == null || recordingId.isBlank() || !index.knows(recordingId) && !this.openRecordingMadeFrom(recordingId))) {
            // Nothing here is, or is made from, that recording. The time index is the fallback (FILE-16)
            hits = index.locateByTime(realTime, world);
        }
        java.util.List<Host.Located> found = new java.util.ArrayList<>();
        boolean openListed = false;
        for (comedymonkey.dailies.library.LibraryIndex.Hit hit : hits) {
            boolean isOpen = open != null && hit.entry().id.equals(open.id);
            openListed |= isOpen;
            Host.Located located = new Host.Located(hit.file(), hit.entry().id, hit.entry().name, hit.tick(), isOpen, hit.original());
            if (isOpen) {
                found.add(0, located);
            } else {
                found.add(located);
            }
        }
        if (!openListed && open != null && recordingId != null) {
            // The open recording may have been opened from outside the recordings folder, where the index does not look
            int here = this.openRecordingContains(recordingId, tick);
            if (here >= 0 && openFile != null) {
                found.add(0, new Host.Located(openFile, open.id, open.name, here, true, open.id.equals(recordingId) && !open.isEdited()));
            }
        }
        return found;
    }

    @Override
    public java.util.List<Host.ClipLocation> locateClip(String recordingId, int startTick, int endTick) {
        comedymonkey.dailies.file.RecordingManifest open = openManifest();
        java.util.List<Host.ClipLocation> found = new java.util.ArrayList<>();
        if (recordingId == null || recordingId.isBlank()) {
            return found;
        }
        for (comedymonkey.dailies.library.LibraryIndex.ClipHit hit : comedymonkey.dailies.library.Library.index().locateClip(recordingId, startTick, endTick)) {
            java.util.List<Host.Stretch> stretches = new java.util.ArrayList<>();
            for (comedymonkey.dailies.file.Spans.Stretch stretch : hit.clip().stretches()) {
                stretches.add(new Host.Stretch(stretch.here(), stretch.hereEnd(), stretch.sourceStart(), stretch.sourceEnd()));
            }
            boolean isOpen = open != null && hit.entry().id.equals(open.id);
            Host.ClipLocation location = new Host.ClipLocation(hit.file(), hit.entry().id, hit.entry().name, stretches, hit.clip().cut(), hit.clip().removed(), isOpen, hit.original());
            if (isOpen) {
                found.add(0, location);
            } else {
                found.add(location);
            }
        }
        return found;
    }

    @Override
    public void openRecording(Path file) throws Exception {
        if (Recorder.get().state() != Recorder.State.IDLE) {
            throw new IllegalStateException("A recording is being made. Finish it first");
        }
        Playback.open(file);
    }

    @Override
    public boolean exportRunning() {
        return comedymonkey.dailies.export.Export.active();
    }

    @Override
    public java.util.Map<String, String> exportPresets() {
        java.util.Map<String, String> presets = new java.util.LinkedHashMap<>();
        for (comedymonkey.dailies.export.ExportPreset preset : comedymonkey.dailies.export.Export.presets().all()) {
            presets.put(preset.name(), preset.summary());
        }
        return presets;
    }

    // --- Editor ---

    @Override
    public @Nullable Double sceneFov() {
        if (!comedymonkey.dailies.editor.EditorSession.open()) {
            return null;
        }
        double fov = comedymonkey.dailies.editor.EditorSession.scene().looks().fov;
        return fov > 0.0 ? fov : null;
    }

    @Override
    public @Nullable Double exportAspectRatio() {
        Double exporting = comedymonkey.dailies.export.Export.aspectRatio();
        if (exporting != null) {
            return exporting;
        }
        // A scene held to a frame shape is composed for that shape in the editor too, between its bars
        double framed = comedymonkey.dailies.looks.LooksRenderer.framedRatio();
        return framed > 0.0 ? framed : null;
    }

    @Override
    public void addSmartCamera(JsonObject arguments) throws Exception {
        comedymonkey.dailies.editor.SmartCameraDriver.add(arguments);
    }

    @Override
    public int createScene(String name, int startTick, int endTick) {
        var history = this.openHistory();
        int total = Playback.total();
        int start = Math.max(0, Math.min(total, startTick));
        int end = Math.max(start, Math.min(total, endTick));
        // The scene and its range are one thing to the person who asked, so one thing to undo
        history.beginGroup("Add scene \"" + name + "\" for ticks " + start + " to " + end);
        try {
            int index = history.addScene(name);
            history.setExportRange(start, end);
            return index;
        } finally {
            history.endGroup();
        }
    }

    @Override
    public void setSceneLooks(JsonObject values) {
        this.openHistory().setLooks(values);
    }

    @Override
    public void asOneEdit(String description, Host.EditWork edits) throws Exception {
        var history = this.openHistory();
        history.beginGroup(description);
        try {
            edits.run();
        } finally {
            history.endGroup();
        }
    }

    private comedymonkey.dailies.edit.EditHistory openHistory() {
        if (!Playback.loaded() || !comedymonkey.dailies.editor.EditorSession.open()) {
            throw new IllegalStateException("No recording is open");
        }
        return comedymonkey.dailies.editor.EditorSession.history();
    }

    // --- MCP ---

    @Override
    public void registerTools(McpProtocol.Registrar registrar) {
        CoreTools.register(registrar);
    }

}
