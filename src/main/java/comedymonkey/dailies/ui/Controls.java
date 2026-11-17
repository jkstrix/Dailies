package comedymonkey.dailies.ui;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.capture.Recorder;
import comedymonkey.dailies.edit.EditHistory;
import comedymonkey.dailies.editor.EditorSession;
import comedymonkey.dailies.playback.Playback;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * What the keys and the buttons do. Both call the same methods here, so a recording behaves the same however it is
 * started, paused, finished or cancelled (specification REC-1), and playback the same from a key or the timeline.
 * Nothing here lets an exception out into the game: a refusal becomes a notice, anything else is logged as well.
 * Client thread, except {@link #offThread}.
 */
public final class Controls {

    /** The colours {@link Notices} uses, shared by everything Dailies draws */
    static final int BRASS = 0xFF9A7A34;
    static final int BRASS_BRIGHT = 0xFFD4B25A;
    static final int WALNUT = 0xFF2B1D0E;
    static final int WALNUT_GLASS = 0xB02B1D0E;
    static final int PARCHMENT = 0xFFEFE6D6;
    static final int PARCHMENT_DIM = 0xFFA89878;
    /** A panel of the editor: walnut, less see-through than the playback bar so that small print stays readable */
    static final int PANEL = 0xE02B1D0E;
    /** The inside of a box to type in */
    static final int FIELD = 0xFF1A1108;
    /** Something refused or wrong */
    static final int ALERT = 0xFFE8604A;

    /** The playback speeds the slower and faster controls move between, in ticks per second. 20 is as recorded */
    private static final double[] SPEEDS = {2.0, 5.0, 10.0, 20.0, 40.0, 80.0, 160.0};
    private static final DateTimeFormatter NAME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm.ss");
    private static final int TICKS_PER_SECOND = 20;

    /** Something that reads or writes files */
    @FunctionalInterface
    interface FileWork {
        void run() throws Exception;
    }

    private Controls() {
    }

    // --- Recording ---

    public static void toggleRecording() {
        if (Recorder.get().state() == Recorder.State.IDLE) {
            startRecording();
        } else {
            finishRecording(null);
        }
    }

    public static void startRecording() {
        Minecraft minecraft = Minecraft.getInstance();
        if (Playback.active()) {
            Notices.show(Component.translatable("dailies.ui.notice.not_while_watching"));
        } else if (Recorder.get().state() != Recorder.State.IDLE) {
            Notices.show(Component.translatable("dailies.ui.notice.already_recording"));
        } else if (minecraft.level == null || minecraft.player == null) {
            Notices.show(Component.translatable("dailies.ui.notice.join_first"));
        } else {
            guarded(() -> Recorder.get().start());
        }
    }

    public static void togglePauseRecording() {
        guarded(() -> {
            switch (Recorder.get().state()) {
                case RECORDING -> Recorder.get().pause();
                case PAUSED -> Recorder.get().resume();
                default -> Notices.show(Component.translatable("dailies.ui.notice.nothing_recording"));
            }
        });
    }

    /**
     * Finishes the recording, asking for a name first unless the "save without asking" setting is on (REC-5).
     *
     * @param back the screen to return to afterwards, or null for the game
     */
    public static void finishRecording(@Nullable Screen back) {
        Minecraft minecraft = Minecraft.getInstance();
        if (Recorder.get().state() == Recorder.State.IDLE) {
            Notices.show(Component.translatable("dailies.ui.notice.nothing_recording"));
            return;
        }
        if (Host.get().setting("saveWithoutAsking", false)) {
            finishNow(null);
            minecraft.setScreen(back);
            return;
        }
        minecraft.setScreen(new NameScreen(
                Component.translatable("dailies.ui.finish.title"),
                Component.translatable("dailies.ui.finish.explanation"),
                LocalDateTime.now().format(NAME_FORMAT),
                Component.translatable("dailies.ui.finish.save"),
                Component.translatable("dailies.ui.finish.keep_recording"),
                name -> {
                    finishNow(name);
                    minecraft.setScreen(back);
                },
                () -> minecraft.setScreen(back)));
    }

    private static void finishNow(@Nullable String name) {
        guarded(() -> {
            CompletableFuture<?> saved = Recorder.get().finish(name == null || name.isBlank() ? null : name.trim());
            // The recorder tells the player how it went; the reason for a failure belongs in the log
            saved.whenComplete((file, error) -> {
                if (error != null) {
                    Host.LOGGER.error("Dailies: the recording could not be saved", error);
                }
            });
        });
    }

    /**
     * Asks whether the recording should really be thrown away, and does it on a yes.
     *
     * @param back the screen to return to on a no, or null for the game
     */
    public static void cancelRecording(@Nullable Screen back) {
        Minecraft minecraft = Minecraft.getInstance();
        if (Recorder.get().state() == Recorder.State.IDLE) {
            Notices.show(Component.translatable("dailies.ui.notice.nothing_recording"));
            return;
        }
        minecraft.setScreen(new ConfirmScreen(
                yes -> {
                    if (yes) {
                        guarded(() -> Recorder.get().cancel());
                        minecraft.setScreen(null);
                    } else {
                        minecraft.setScreen(back);
                    }
                },
                Component.translatable("dailies.ui.cancel.title"),
                Component.translatable("dailies.ui.cancel.question"),
                Component.translatable("dailies.ui.cancel.yes"),
                Component.translatable("dailies.ui.cancel.no")));
    }

    /**
     * Drops one of the four markers (REC-30).
     *
     * @param number 1 to 4
     */
    public static void dropMarker(int number) {
        if (Recorder.get().state() == Recorder.State.IDLE) {
            Notices.show(Component.translatable("dailies.ui.notice.nothing_recording"));
            return;
        }
        guarded(() -> {
            String colour = Host.get().setting("marker" + number + "Colour", "red");
            String description = Host.get().setting("marker" + number + "Description", "");
            boolean savePosition = Host.get().setting("marker" + number + "SavePosition", true);
            Recorder.get().addMarker(colourOf(colour), description, savePosition);
            Notices.show(description.isBlank()
                    ? Component.translatable("dailies.ui.notice.marker", number)
                    : Component.translatable("dailies.ui.notice.marker_described", description));
        });
    }

    /**
     * @param setting a preset name or #RRGGBB
     * @return 0xRRGGBB; red when the setting cannot be read
     */
    static int colourOf(String setting) {
        String wanted = setting == null ? "" : setting.trim().toLowerCase(Locale.ROOT);
        switch (wanted) {
            case "red":
                return 0xE0483A;
            case "orange":
                return 0xE8902E;
            case "yellow":
                return 0xF2D03B;
            case "green":
                return 0x5DBB4A;
            case "cyan":
                return 0x3FC8C8;
            case "blue":
                return 0x4A7BE0;
            case "purple":
                return 0x9A5BD8;
            case "white":
                return 0xFFFFFF;
            default:
                break;
        }
        String digits = wanted.startsWith("#") ? wanted.substring(1) : wanted;
        if (digits.matches("[0-9a-f]{6}")) {
            return Integer.parseInt(digits, 16);
        }
        return 0xE0483A;
    }

    // --- Playback ---

    public static void togglePlay() {
        watching(() -> {
            if (Playback.paused()) {
                Playback.play();
            } else {
                Playback.pause();
            }
        });
    }

    public static void stepOneTick() {
        watching(Playback::step);
    }

    /**
     * @param seconds of recording to jump by; negative goes back
     */
    public static void skip(int seconds) {
        watching(() -> Playback.viewTo(Playback.shown() + seconds * TICKS_PER_SECOND, Playback.paused()));
    }

    /** While the playhead is dragged */
    public static void scrubTo(int tick) {
        watching(() -> Playback.scrubTo(tick));
    }

    /** When the playhead is let go */
    public static void refine() {
        watching(Playback::refine);
    }

    public static void jumpTo(int tick) {
        watching(() -> Playback.viewTo(tick, Playback.paused()));
    }

    public static void slower() {
        watching(() -> {
            double now = Playback.ticksPerSecond();
            double next = SPEEDS[0];
            for (double speed : SPEEDS) {
                if (speed < now - 0.001) {
                    next = speed;
                }
            }
            Playback.setTicksPerSecond(next);
        });
    }

    public static void faster() {
        watching(() -> {
            double now = Playback.ticksPerSecond();
            double next = SPEEDS[SPEEDS.length - 1];
            for (int i = SPEEDS.length - 1; i >= 0; i--) {
                if (SPEEDS[i] > now + 0.001) {
                    next = SPEEDS[i];
                }
            }
            Playback.setTicksPerSecond(next);
        });
    }

    /** Switches "follow the camera track" on or off (EDIT-5) */
    public static void toggleFollowCamera() {
        watching(() -> {
            boolean follow = !EditorSession.followCamera();
            EditorSession.setFollowCamera(follow);
            tell(Component.translatable(follow ? "dailies.ui.notice.camera_track" : "dailies.ui.notice.camera_free"));
        });
    }

    public static void undo() {
        watching(() -> {
            EditHistory history = EditorSession.open() ? EditorSession.history() : null;
            String what = history == null ? null : history.undo();
            tell(what == null ? Component.translatable("dailies.ui.notice.nothing_to_undo") : Component.translatable("dailies.ui.notice.undone", what));
        });
    }

    public static void redo() {
        watching(() -> {
            EditHistory history = EditorSession.open() ? EditorSession.history() : null;
            String what = history == null ? null : history.redo();
            tell(what == null ? Component.translatable("dailies.ui.notice.nothing_to_redo") : Component.translatable("dailies.ui.notice.redone", what));
        });
    }

    public static void openTimeline() {
        watching(EditorScreen::open);
    }

    /**
     * Opens the moments page in the person's web browser. The page is served by the mod's own local server, so there
     * is nothing to open while that is switched off; the notice says how to switch it on.
     */
    public static void openMomentsPage() {
        guarded(() -> {
            int port = comedymonkey.dailies.mcp.McpHttpServer.getListeningPort();
            if (port <= 0) {
                tell(Component.translatable("dailies.ui.notice.moments_page_off"));
                return;
            }
            net.minecraft.Util.getPlatform().openUri(java.net.URI.create("http://127.0.0.1:" + port + "/"));
            tell(Component.translatable("dailies.ui.notice.moments_page_opened"));
        });
    }

    /** Says something to the person: in the editor when it is open, where its top bar would cover a notice, else as a notice */
    static void tell(Component words) {
        EditorState.say(words);
    }

    private static void watching(FileWork action) {
        if (Playback.loaded()) {
            guarded(action);
        }
    }

    // --- Keeping trouble out of the game ---

    /** Runs something, turning a refusal into a notice and anything worse into a notice and a line in the log */
    static void guarded(FileWork action) {
        try {
            action.run();
        } catch (IllegalStateException | IllegalArgumentException refused) {
            String reason = refused.getMessage();
            tell(reason == null || reason.isBlank() ? Component.translatable("dailies.ui.notice.went_wrong") : Component.translatable("dailies.ui.notice.refused", reason));
        } catch (Exception | LinkageError e) {
            Host.LOGGER.error("Dailies: a control failed", e);
            tell(Component.translatable("dailies.ui.notice.went_wrong"));
        }
    }

    /**
     * Does file work away from the client thread, then comes back to it.
     *
     * @param work   runs on a background thread
     * @param failed shown as a notice when the work throws; the reason goes in the log
     * @param then   runs on the client thread afterwards, whether or not the work succeeded
     */
    static void offThread(FileWork work, Component failed, Runnable then) {
        CompletableFuture.runAsync(() -> {
            try {
                work.run();
            } catch (Exception e) {
                Host.LOGGER.error("Dailies: file work failed", e);
                tell(failed);
            }
        }, Util.ioPool()).whenComplete((nothing, error) -> {
            if (error != null) {
                Host.LOGGER.error("Dailies: file work failed", error);
                tell(failed);
            }
            Minecraft.getInstance().execute(() -> guarded(then::run));
        });
    }

    // --- Words for numbers ---

    /** A moment of a recording as minutes, seconds and tenths: 1:05.3 */
    static String time(int ticks) {
        int safe = Math.max(0, ticks);
        int seconds = safe / TICKS_PER_SECOND;
        return String.format(Locale.ROOT, "%d:%02d.%d", seconds / 60, seconds % 60, (safe % TICKS_PER_SECOND) / 2);
    }

    /** A length as minutes and seconds: 1:05 */
    static String length(int ticks) {
        int seconds = Math.max(0, ticks) / TICKS_PER_SECOND;
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    /** A speed against the recorded one: 0.5, 2, 0.25 */
    static String speed(double ticksPerSecond) {
        String text = String.format(Locale.ROOT, "%.2f", ticksPerSecond / TICKS_PER_SECOND);
        text = text.replaceAll("0+$", "");
        return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
    }

}
