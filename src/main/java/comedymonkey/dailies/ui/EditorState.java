package comedymonkey.dailies.ui;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.edit.EditHistory;
import comedymonkey.dailies.edit.Interpolation;
import comedymonkey.dailies.edit.Keyframe;
import comedymonkey.dailies.edit.KeyframeClipboard;
import comedymonkey.dailies.edit.KeyframeRef;
import comedymonkey.dailies.edit.Scene;
import comedymonkey.dailies.edit.Track;
import comedymonkey.dailies.editor.EditorSession;
import comedymonkey.dailies.playback.Playback;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * What the editor screen remembers between the moments it is on screen: which keyframes are selected, which tab is
 * open, how far the timeline is zoomed, what was last typed into the export panel. The game takes the screen away
 * whenever a jump backwards rebuilds the world, and a new one is put up afterwards; keeping this here means the
 * person notices nothing. All of it belongs to the recording that is open and is forgotten when another is opened.
 * Client thread.
 */
final class EditorState {

    /** The tabs of the side panel */
    static final int TAB_KEYFRAME = 0;
    static final int TAB_LOOKS = 1;
    static final int TAB_EXPORT = 2;

    /**
     * What the next click in the view is for.
     *
     * @param prompt      what the person is asked to click
     * @param block       whether a block is wanted and not an entity
     * @param playersOnly whether only players can be chosen
     * @param extras      other ways to answer, offered as buttons beside the list
     */
    record Pick(Component prompt, boolean block, boolean playersOnly, Consumer<Entity> entity, Consumer<BlockPos> blockAt, List<Menu.Item> extras) {
    }

    private static @Nullable String recording = null;

    /** Counts its changes, so the screen can tell when the selection has changed */
    static final RevisedSet<KeyframeRef> selection = new RevisedSet<>();
    static @Nullable KeyframeClipboard clipboard = null;
    static int tab = TAB_KEYFRAME;
    static boolean sideFolded = false;
    static boolean timelineFolded = false;
    static boolean viewerFolded = false;
    static boolean playersFolded = false;
    /** The first tick the timeline shows and how many it shows; a span of 0 or less means the whole recording */
    static double viewStart = 0.0;
    static double viewSpan = 0.0;
    static int laneScroll = 0;
    /** Whether the left mouse button is dragging the playhead; outlives the screen across a jump backwards */
    static boolean scrubbing = false;
    /** Frames to wait before believing where the mouse is, after the pointer has been put back somewhere */
    static int pointerSettling = 0;
    static @Nullable Pick pick = null;

    // The keyframe panel
    static boolean audioDraft = false;
    static String audioFile = "";
    static double audioVolume = 1.0;

    // The looks panel
    static @Nullable UUID overrideEntity = null;
    static String overrideEntityName = "";
    static String overrideEntityType = "";
    static String entityTypeDraft = "";
    static String particleTypeDraft = "";

    // The export panel
    static int exportWidth = 1920;
    static int exportHeight = 1080;
    static double exportRate = 60.0;
    static String exportContainer = "mp4";
    static String exportCodec = "h264";
    /** Empty for "choose for me" */
    static String exportEncoder = "";
    static String exportBitrate = "20m";
    static boolean exportSound = false;
    static boolean exportStereo = true;
    static String exportSoundCodec = "auto";
    static boolean exportHideHud = false;
    static boolean exportSupersample = false;
    static boolean exportTransparent = false;
    /** Null until first shown, when the setting's default is taken */
    static @Nullable String exportName = null;

    private static @Nullable Component notice = null;
    private static long noticeAt = 0;

    private EditorState() {
    }

    /** Forgets what belonged to another recording */
    static void attach() {
        String now = Playback.recordingId();
        if (!Objects.equals(now, recording)) {
            recording = now;
            selection.clear();
            clipboard = null;
            viewStart = 0.0;
            viewSpan = 0.0;
            laneScroll = 0;
            scrubbing = false;
            pick = null;
            audioDraft = false;
            overrideEntity = null;
            overrideEntityName = "";
            overrideEntityType = "";
            tab = TAB_KEYFRAME;
        }
    }

    // ------------------------------------------------------------------ Notices

    /** Shows a line of words in the editor for a few seconds, or as an ordinary notice while the editor is closed */
    static void say(Component words) {
        notice = words;
        noticeAt = System.currentTimeMillis();
        if (!(net.minecraft.client.Minecraft.getInstance().gui.screen() instanceof EditorScreen)) {
            // The editor's top bar would cover it
            Notices.show(words);
        }
    }

    /** The words to show now, or null */
    static @Nullable Component notice() {
        if (notice != null && System.currentTimeMillis() - noticeAt > 4500) {
            notice = null;
        }
        return notice;
    }

    /** Runs something, turning a refusal into a notice and anything worse into a notice and a line in the log */
    static void guard(Runnable action) {
        try {
            action.run();
        } catch (IllegalStateException | IllegalArgumentException | IndexOutOfBoundsException refused) {
            String reason = refused.getMessage();
            say(reason == null || reason.isBlank() ? Component.translatable("dailies.ui.notice.went_wrong") : Component.literal(reason));
        } catch (RuntimeException | LinkageError e) {
            Host.LOGGER.error("Dailies: a control of the editor failed", e);
            say(Component.translatable("dailies.ui.notice.went_wrong"));
        }
    }

    // ------------------------------------------------------------------ The scene and the selection

    static EditHistory history() {
        return EditorSession.history();
    }

    static Scene scene() {
        return EditorSession.scene();
    }

    /** The keyframe a reference points at, or null when it is gone */
    static @Nullable Keyframe keyframe(KeyframeRef ref) {
        Scene scene = scene();
        if (ref.track() < 0 || ref.track() >= scene.tracks().size()) {
            return null;
        }
        return scene.track(ref.track()).at(ref.tick());
    }

    /** Drops references to keyframes that are no longer there: after an undo, a change of scene, an MCP tool */
    static void validate() {
        if (!EditorSession.open()) {
            selection.clear();
            return;
        }
        selection.removeIf(ref -> keyframe(ref) == null);
    }

    /** The one selected keyframe, or null when none or several are */
    static @Nullable KeyframeRef single() {
        return selection.size() == 1 ? selection.iterator().next() : null;
    }

    static void select(int track, int tick) {
        selection.clear();
        selection.add(new KeyframeRef(track, tick));
        audioDraft = false;
        tab = TAB_KEYFRAME;
        sideFolded = false;
    }

    static void removeSelected() {
        if (selection.isEmpty()) {
            return;
        }
        Set<KeyframeRef> going = new LinkedHashSet<>(selection);
        selection.clear();
        history().removeKeyframes(going);
    }

    static void copySelected() {
        if (selection.isEmpty()) {
            say(Kit.t("notice.nothing_selected"));
            return;
        }
        clipboard = history().copy(selection);
        say(Kit.t("notice.copied", clipboard.size()));
    }

    /** Pastes what was copied so that its first keyframe lands on the playhead, and selects what was pasted */
    static void pasteAtPlayhead() {
        KeyframeClipboard copied = clipboard;
        if (copied == null || copied.isEmpty()) {
            say(Kit.t("notice.nothing_copied"));
            return;
        }
        int offset = Playback.position() - copied.firstTick();
        if (copied.lastTick() + offset > Playback.total()) {
            say(Kit.t("notice.paste_past_end"));
            return;
        }
        history().paste(copied, offset);
        selection.clear();
        Scene scene = scene();
        for (KeyframeClipboard.Entry entry : copied.entries()) {
            int tick = entry.keyframe().tick() + offset;
            for (int index = 0; index < scene.tracks().size(); index++) {
                Track track = scene.track(index);
                Keyframe there = track.at(tick);
                if (track.kind() == entry.keyframe().kind() && there != null && there.equals(entry.keyframe().withTick(tick))) {
                    selection.add(new KeyframeRef(index, tick));
                    break;
                }
            }
        }
    }

    static void undo() {
        String what = history().undo();
        say(what == null ? Component.translatable("dailies.ui.notice.nothing_to_undo") : Component.translatable("dailies.ui.notice.undone", what));
    }

    static void redo() {
        String what = history().redo();
        say(what == null ? Component.translatable("dailies.ui.notice.nothing_to_redo") : Component.translatable("dailies.ui.notice.redone", what));
    }

    // ------------------------------------------------------------------ Settings

    /** A setting of the mod, as it will be once anything changed a moment ago has been saved */
    @SuppressWarnings("unchecked")
    static <V> V setting(String name, V fallback) {
        Object pending = SettingsWriter.pending(name);
        if (pending != null && fallback != null && fallback.getClass().isInstance(pending)) {
            return (V) pending;
        }
        return Host.get().setting(name, fallback);
    }

    /** Changes a setting of the mod. The settings file is written away from the client thread */
    static void saveSetting(String name, Object value) {
        SettingsWriter.save(name, value, trouble -> {
            if (trouble != null) {
                Controls.tell(Kit.t("notice.setting_failed"));
            }
        });
    }

    /** How a new keyframe blends towards the next one: the "defaultInterpolation" setting */
    static Interpolation defaultInterpolation() {
        Object pending = SettingsWriter.pending("defaultInterpolation");
        Object setting = pending != null ? pending : Host.get().<Object>setting("defaultInterpolation", "SMOOTH");
        try {
            return Interpolation.parse(String.valueOf(setting));
        } catch (IllegalArgumentException e) {
            return Interpolation.SMOOTH;
        }
    }

}
