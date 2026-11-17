package comedymonkey.dailies.extras;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.file.RecordingManifest;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.ui.Notices;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.settings.IKeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Tells the person, once for each recording they open, how the mods installed now differ from the mods the recording
 * was made with (specification PLAY-5): which are missing here, which are extra here, and which are another version.
 * A short notice names the key (F7 unless changed) that lists them on a screen, one line in the log holds the whole
 * list, and {@link #summary} gives the same to the MCP status tool.
 */
public final class ModDifferences {

    /** A mod and the version of it on one side */
    public record Mod(String id, String version) {
    }

    /** A mod that is on both sides in different versions */
    public record Changed(String id, String recorded, String installed) {
    }

    /** How the open recording's mods differ from those installed. At least one list has something in it */
    public record Differences(List<Mod> missing, List<Mod> extra, List<Changed> changed) {
    }

    /** Live only while a recording is being watched, so in normal play the key never sees a press */
    private static final IKeyConflictContext WATCHING = new IKeyConflictContext() {
        @Override
        public boolean isActive() {
            return Playback.loaded();
        }

        @Override
        public boolean conflicts(IKeyConflictContext other) {
            return other == this;
        }
    };

    private static volatile Differences differences = null;
    private static volatile KeyMapping key = null;

    private ModDifferences() {
    }

    static void init() {
        Playback.onOpened(ModDifferences::opened);
        Playback.onClosing(() -> differences = null);
        // Once a frame and not once a tick: a slowed-down recording slows the game's ticks down with it
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Pre event) -> keys());
    }

    static void registerKey(RegisterKeyMappingsEvent event) {
        if (key == null) {
            KeyMapping mapping = new KeyMapping("dailies.extras.key.mod_differences", WATCHING, InputConstants.Type.KEYSYM, InputConstants.KEY_F7, "key.categories.dailies");
            event.register(mapping);
            key = mapping;
        }
    }

    /** What differs for the recording that is open, or null when nothing does or none is open. Any thread */
    public static @Nullable Differences current() {
        return differences;
    }

    /**
     * For the MCP status tool: three arrays, {@code missing} and {@code extra} of {id, version} and {@code changed}
     * of {id, recorded, installed}; or null when nothing differs or no recording is open. Any thread
     */
    public static @Nullable JsonObject summary() {
        Differences now = differences;
        if (now == null) {
            return null;
        }
        JsonObject summary = new JsonObject();
        summary.add("missing", mods(now.missing()));
        summary.add("extra", mods(now.extra()));
        JsonArray changed = new JsonArray();
        for (Changed mod : now.changed()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", mod.id());
            entry.addProperty("recorded", mod.recorded());
            entry.addProperty("installed", mod.installed());
            changed.add(entry);
        }
        summary.add("changed", changed);
        return summary;
    }

    private static JsonArray mods(List<Mod> mods) {
        JsonArray array = new JsonArray();
        for (Mod mod : mods) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", mod.id());
            entry.addProperty("version", mod.version());
            array.add(entry);
        }
        return array;
    }

    /** A recording has finished opening. Client thread */
    private static void opened() {
        differences = null;
        RecordingManifest manifest = Playback.manifest();
        if (manifest == null || manifest.mods == null || manifest.mods.isEmpty()) {
            // A recording that does not say what it was made with has nothing to compare
            return;
        }
        Map<String, String> recorded = new TreeMap<>();
        for (RecordingManifest.ModEntry mod : manifest.mods) {
            if (mod != null && mod.id != null) {
                recorded.put(mod.id, mod.version == null ? "" : mod.version);
            }
        }
        Map<String, String> installed = new TreeMap<>();
        ModList.get().getMods().forEach(mod -> installed.put(mod.getModId(), mod.getVersion().toString()));

        List<Mod> missing = new ArrayList<>();
        List<Mod> extra = new ArrayList<>();
        List<Changed> changed = new ArrayList<>();
        recorded.forEach((id, version) -> {
            String here = installed.get(id);
            if (here == null) {
                missing.add(new Mod(id, version));
            } else if (!here.equals(version)) {
                changed.add(new Changed(id, version, here));
            }
        });
        installed.forEach((id, version) -> {
            if (!recorded.containsKey(id)) {
                extra.add(new Mod(id, version));
            }
        });
        if (missing.isEmpty() && extra.isEmpty() && changed.isEmpty()) {
            return;
        }
        differences = new Differences(List.copyOf(missing), List.copyOf(extra), List.copyOf(changed));
        Host.LOGGER.info("Dailies: recording {} was made with other mods than are installed now. Missing here: {}. Extra here: {}. Another version (recorded -> installed): {}",
            manifest.id, describe(missing), describe(extra),
            changed.isEmpty() ? "none" : String.join(", ", changed.stream().map(mod -> mod.id() + " " + mod.recorded() + " -> " + mod.installed()).toList()));
        KeyMapping mapping = key;
        Component keyName = mapping == null ? Component.literal("F7") : mapping.getTranslatedKeyMessage();
        Notices.show(Component.translatable("dailies.extras.mods.notice", missing.size(), extra.size(), changed.size(), keyName));
    }

    private static String describe(List<Mod> mods) {
        return mods.isEmpty() ? "none" : String.join(", ", mods.stream().map(mod -> mod.id() + " " + mod.version()).toList());
    }

    private static void keys() {
        KeyMapping mapping = key;
        if (mapping == null) {
            return;
        }
        // Read even when nothing may be done with it, so a press is never kept for later
        boolean pressed = false;
        while (mapping.consumeClick()) {
            pressed = true;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (pressed && Playback.loaded() && minecraft.screen == null) {
            try {
                minecraft.setScreen(new ModDifferencesScreen(differences));
            } catch (Throwable t) {
                Host.LOGGER.warn("Dailies: the list of mods that differ could not be shown", t);
            }
        }
    }

}
