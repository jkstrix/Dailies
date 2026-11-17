package comedymonkey.dailies.core;

import com.google.gson.JsonObject;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.capture.Recorder;
import comedymonkey.dailies.config.DailiesConfig;
import comedymonkey.dailies.config.Setting;
import comedymonkey.dailies.mcp.McpProtocol;
import comedymonkey.dailies.mcp.tools.CoreTools;
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
        return false;
    }

    @Override
    public @Nullable String openRecordingId() {
        return null;
    }

    @Override
    public int openRecordingTicks() {
        return 0;
    }

    @Override
    public void seek(int tick, boolean pause) {
    }

    @Override
    public @Nullable Path findRecording(String recordingId) {
        return null;
    }

    @Override
    public void openRecording(Path file) throws Exception {
        throw new IllegalStateException(NOT_BUILT);
    }

    @Override
    public boolean exportRunning() {
        return false;
    }

    // --- Editor ---

    @Override
    public @Nullable Double sceneFov() {
        return null;
    }

    @Override
    public @Nullable Double exportAspectRatio() {
        return null;
    }

    @Override
    public void addSmartCamera(JsonObject arguments) throws Exception {
        throw new IllegalStateException(NOT_BUILT);
    }

    // --- MCP ---

    @Override
    public void registerTools(McpProtocol.Registrar registrar) {
        CoreTools.register(registrar);
    }

}
