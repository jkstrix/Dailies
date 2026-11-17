package comedymonkey.dailies.selftest;

import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.suggestion.Suggestion;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.capture.Recorder;
import comedymonkey.dailies.command.DailiesCommand;
import comedymonkey.dailies.edit.EditHistory;
import comedymonkey.dailies.edit.Evaluator;
import comedymonkey.dailies.edit.Interpolation;
import comedymonkey.dailies.edit.Keyframe;
import comedymonkey.dailies.edit.KeyframeKind;
import comedymonkey.dailies.editor.EditorSession;
import comedymonkey.dailies.export.Export;
import comedymonkey.dailies.file.RecordingFile;
import comedymonkey.dailies.file.RecordingManifest;
import comedymonkey.dailies.looks.Drawn;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.playback.Viewer;
import comedymonkey.dailies.ui.Controls;
import comedymonkey.dailies.ui.MarkerRings;
import comedymonkey.dailies.ui.Notices;
import comedymonkey.dailies.ui.PathPreview;
import comedymonkey.dailies.ui.PlayerList;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.ClientCommandHandler;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.common.NeoForge;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

import static comedymonkey.dailies.selftest.SelfTest.args;
import static comedymonkey.dailies.selftest.SelfTest.check;

/**
 * What is seen and typed while recording and watching: the "/dailies" command and the moment key (REC-7), markers as
 * rings in the world (REC-32), the camera path preview (EDIT-8), the looks added to chapter V with the frame that is
 * the picture itself, and the players panel (PLAY-3). {@link #record} makes a short recording of its own with the
 * command, in the live test world; {@link #watch} opens it and looks.
 */
final class WatchingSteps {

    private static final double X = 32.5;
    private static final double Y = -60.0;
    private static final double Z = 32.5;
    private static final int BLUE = 0x4A7BE0;
    private static final String SIGHT = "that was good";

    /** The recording made here, kept outside the recordings folder so the other steps see only their own */
    private static Path recording;
    private static int walkStart;
    private static int walkEnd;
    /** The tick just before a line was put on the action bar */
    private static int actionBar;

    private WatchingSteps() {
    }

    /** Dailies' own settings screen (CFG-4) */
    private static boolean settingsOpen(Minecraft minecraft) {
        return minecraft.screen instanceof comedymonkey.dailies.ui.SettingsScreen;
    }

    // ------------------------------------------------------------------ Recording, by command

    /** Runs "/dailies ..." the way the chat does, and returns the key of its answer */
    private static String dailies(SelfTest test, String words) throws Exception {
        boolean handled = test.onClient(() -> ClientCommandHandler.runCommand(DailiesCommand.ROOT + (words.isEmpty() ? "" : " " + words)));
        check(handled, "'/dailies " + words + "' was not taken by the game as a command of its own, so it would have gone to the server");
        return DailiesCommand.lastAnswer();
    }

    private static void expect(SelfTest test, String words, String answer) throws Exception {
        String got = dailies(test, words);
        check(got.equals(answer), "'/dailies " + words + "' answered '" + got + "', not '" + answer + "'");
    }

    private static List<String> completions(SelfTest test, String typed) throws Exception {
        return test.onClient(() -> {
            CommandDispatcher<CommandSourceStack> dispatcher = ClientCommandHandler.getDispatcher();
            List<String> offered = new ArrayList<>();
            for (Suggestion suggestion : dispatcher.getCompletionSuggestions(dispatcher.parse(typed, ClientCommandHandler.getSource())).get().getList()) {
                offered.add(suggestion.getText());
            }
            return offered;
        });
    }

    static void record(SelfTest test) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        Path keep = Host.get().dataDirectory().resolve("watching-test");
        Files.createDirectories(keep);
        recording = keep.resolve("watching.dailies");
        Files.deleteIfExists(recording);

        // Tab completion: the sub-commands, and the colours of a marker
        List<String> subCommands = completions(test, "dailies ");
        check(subCommands.containsAll(List.of("start", "finish", "pause", "resume", "cancel", "mark", "moment", "moments", "config", "hide")), "tab completion does not offer every sub-command: " + subCommands);
        check(completions(test, "dailies mark bl").equals(List.of("blue")), "tab completion does not offer the colour being typed: " + completions(test, "dailies mark bl"));
        check(completions(test, "dailies mark blue ").containsAll(List.of("yes", "no")), "tab completion does not offer yes and no after a colour");

        // Nothing to act on yet, and each says so
        expect(test, "", "usage");
        expect(test, "pause", "nothing_recording");
        expect(test, "resume", "nothing_recording");
        expect(test, "finish", "nothing_recording");
        expect(test, "cancel", "nothing_recording");
        expect(test, "mark red", "nothing_recording");
        expect(test, "moment", "moment.usage");
        expect(test, "moments", "moments");

        // Start, and cancel after the question is answered with a yes (REC-1)
        expect(test, "start", "started");
        check(Recorder.get().state() == Recorder.State.RECORDING, "'/dailies start' did not start a recording");
        expect(test, "start", "already_recording");
        test.sleepTicks(20);
        expect(test, "cancel", "cancel_asking");
        test.waitFor(comedymonkey.dailies.ui.Previews::asking, 10, "the question before a recording is thrown away");
        check(Recorder.get().state() != Recorder.State.IDLE, "'/dailies cancel' threw the recording away without asking");
        test.onClientRun(() -> comedymonkey.dailies.ui.Previews.answerAsk(true));
        test.waitFor(() -> Recorder.get().state() == Recorder.State.IDLE, 10, "the cancelled recording to stop");
        test.sleepTicks(20);
        check(Recorder.unfinished().isEmpty(), "a recording cancelled by command left its file behind");

        // A stage of its own, away from where the other steps build: a pig, a beacon, a scoreboard, and room to walk
        test.command("tp @s " + X + " " + Y + " " + Z + " -90 10");
        test.command("fill 37 -60 37 39 -60 39 minecraft:iron_block");
        test.command("setblock 38 -59 38 minecraft:beacon");
        test.command("setblock 41 -57 38 minecraft:end_gateway");
        // A room with no light in it, to see the camera path in the dark
        test.command("fill 30 -60 14 44 -54 22 minecraft:stone hollow");
        test.command("summon minecraft:pig 34.5 -60 36.5 {NoAI:1b,Silent:1b,PersistenceRequired:1b,Tags:[\"dailies_watching\"]}");
        test.command("scoreboard objectives add dailies_watch dummy \"Watching\"");
        test.command("scoreboard objectives setdisplay sidebar dailies_watch");
        test.command("scoreboard players set @s dailies_watch 7");
        test.sleepTicks(20);

        boolean hudBefore = Host.get().setting("recordHud", false);
        test.tool("config_set", args("key", "recordHud", "value", true));
        expect(test, "start", "started");
        test.sleepTicks(30);

        // A marker with its colour, its position and its description; and one with nothing
        expect(test, "mark blue yes the well", "marked_described_here");
        test.sleepTicks(5);
        expect(test, "mark no", "marked");

        // A moment by command and one by the key (the same code as the moments_event_add tool)
        String moment = dailies(test, "moment " + SIGHT);
        check(moment.equals("moment"), "'/dailies moment' answered '" + moment + "'");
        check(test.tool("moments_events_query", args("session", "latest", "keyword", SIGHT)).toString().contains(SIGHT), "'/dailies moment' logged nothing");
        String label = "Key moment " + System.nanoTime() % 100000;
        test.tool("config_set", args("key", "momentKeyLabel", "value", label));
        test.onClientRun(Controls::logMoment);
        check(test.tool("moments_events_query", args("session", "latest", "keyword", label)).toString().contains(label), "the moment key logged nothing");
        test.tool("config_set", args("key", "momentKeyLabel", "value", "Good moment"));

        // Pause and resume
        expect(test, "pause", "paused");
        check(Recorder.get().state() == Recorder.State.PAUSED, "'/dailies pause' did not pause the recording");
        expect(test, "pause", "already_paused");
        expect(test, "resume", "resumed");
        check(Recorder.get().state() == Recorder.State.RECORDING, "'/dailies resume' did not resume the recording");
        expect(test, "resume", "not_paused");

        // Hide and show
        check(!Notices.hidden(), "the interface starts out hidden");
        expect(test, "hide", "hidden");
        check(Notices.hidden(), "'/dailies hide' did not hide the interface");
        expect(test, "hide", "shown");
        check(!Notices.hidden(), "'/dailies hide' did not bring the interface back");

        // The settings
        expect(test, "config", "config");
        test.waitFor(() -> settingsOpen(minecraft), 10, "the settings screen");
        test.onClientRun(() -> minecraft.setScreen(null));

        // The recorder walks, so there is something to follow
        test.sleepTicks(10);
        walkStart = test.onClient(() -> Recorder.get().recordingTick());
        for (int step = 1; step <= 30; step++) {
            test.command("tp @s " + (X + step * 0.3) + " " + Y + " " + Z + " -90 10");
            test.sleepTicks(2);
        }
        test.sleepTicks(10);
        actionBar = test.onClient(() -> Recorder.get().recordingTick());
        test.command("title @s actionbar {\"text\":\"Dailies action bar\",\"color\":\"yellow\",\"bold\":true}");
        test.sleepTicks(30);
        walkEnd = test.onClient(() -> Recorder.get().recordingTick());
        test.sleepTicks(20);

        // Finish under a name, with no question asked
        List<Path> before = recordings();
        expect(test, "finish Watching", "finished");
        test.waitFor(() -> Recorder.get().state() == Recorder.State.IDLE && recordings().size() == before.size() + 1 && Recorder.unfinished().isEmpty(), 60,
            "the recording finished by command to be saved");
        List<Path> after = recordings();
        after.removeAll(before);
        Path saved = after.get(0);
        test.waitFor(() -> {
            try {
                return RecordingFile.readManifest(saved).finished;
            } catch (Exception notYet) {
                return false;
            }
        }, 30, "the saved recording to be whole");
        RecordingManifest manifest = RecordingFile.readManifest(saved);
        check(manifest.name.equals("Watching"), "'/dailies finish Watching' saved the recording as '" + manifest.name + "'");
        check(manifest.markers.size() == 2, "the recording holds " + manifest.markers.size() + " markers, not the two dropped by command");
        RecordingManifest.Marker well = manifest.markers.get(0);
        check(well.colour == BLUE && well.description.equals("the well"), "the marker dropped by command is not blue and described: " + Integer.toHexString(well.colour) + " '" + well.description + "'");
        check(well.position != null && Math.abs(well.position[0] - X) < 0.01 && Math.abs(well.position[1] - Y) < 0.01 && Math.abs(well.position[2] - Z) < 0.01,
            "the marker dropped by command does not hold where the recorder stood: " + Arrays.toString(well.position));
        RecordingManifest.Marker plain = manifest.markers.get(1);
        check(plain.position == null && plain.colour == 0xE0483A && plain.description.isEmpty(), "'/dailies mark no' did not drop a plain red marker without a position");
        Files.move(saved, recording);
        test.tool("config_set", args("key", "recordHud", "value", hudBefore));

        // The stage is struck, so the steps that follow find the world as they expect it
        test.command("kill @e[tag=dailies_watching]");
        test.command("setblock 38 -59 38 minecraft:air");
        test.command("setblock 41 -57 38 minecraft:air");
        test.command("fill 30 -60 14 44 -54 22 minecraft:air");
        test.command("fill 37 -60 37 39 -60 39 minecraft:air");
        test.command("scoreboard objectives remove dailies_watch");
        test.sleepTicks(5);
        test.command("kill @e[type=minecraft:item]");
        test.command("tp @s 0.5 -60 0.5 0 0");
        test.sleepTicks(20);
        test.log("a recording of " + manifest.totalTicks + " ticks was made, marked, paused, resumed and finished by /dailies");
    }

    private static List<Path> recordings() {
        try (Stream<Path> files = Files.walk(Recorder.folder())) {
            return new ArrayList<>(files.filter(file -> file.getFileName().toString().endsWith(".dailies")).toList());
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    // ------------------------------------------------------------------ Watching

    private static void frames(SelfTest test, int count) throws Exception {
        long from = Drawn.frames();
        test.waitFor(() -> Drawn.frames() >= from + count, 30, count + " frames to be drawn");
    }

    private static void jump(SelfTest test, int tick) throws Exception {
        test.onClientRun(() -> Playback.jumpTo(tick, true));
        test.waitFor(() -> !Playback.jumping() && Playback.position() == tick, 60, "the jump to tick " + tick);
        frames(test, 4);
    }

    /** Puts the editor on screen, or waits for it to come back after a jump took it away */
    private static void editor(SelfTest test) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        test.waitFor(() -> !Playback.jumping(), 30, "playback to settle");
        test.onClientRun(() -> {
            if (!(minecraft.screen instanceof comedymonkey.dailies.ui.EditorScreen)) {
                Controls.openTimeline();
            }
        });
        test.waitFor(() -> minecraft.screen instanceof comedymonkey.dailies.ui.EditorScreen, 10, "the editor");
        frames(test, 4);
    }

    /** Keeps a picture of the window as it is, interface and all, in the screenshots folder: for a person to judge how it looks */
    private static void picture(SelfTest test, String name) throws Exception {
        frames(test, 3);
        test.onClientRun(() -> {
            Minecraft minecraft = Minecraft.getInstance();
            net.minecraft.client.Screenshot.grab(minecraft.gameDirectory, name + ".png", minecraft.getMainRenderTarget(), said -> { });
        });
    }

    /** Stands the camera somewhere and turns it to look at a point */
    private static void lookFrom(SelfTest test, Vec3 feet, Vec3 target) throws Exception {
        test.onClientRun(() -> {
            Viewer.ride(null);
            Viewer.follow(null);
            Vec3 look = target.subtract(feet.add(0.0, 1.62, 0.0));
            float yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
            float pitch = (float) Math.toDegrees(-Math.atan2(look.y, Math.sqrt(look.x * look.x + look.z * look.z)));
            Viewer.set(feet.x, feet.y, feet.z, yaw, pitch);
        });
        frames(test, 4);
    }

    private static void looks(SelfTest test, Object... pairs) throws Exception {
        test.tool("visuals_set", args("values", args(pairs)));
        frames(test, 4);
    }

    private static void setting(SelfTest test, String name, Object value) throws Exception {
        test.tool("config_set", args("key", name, "value", value));
        // Settings are written to their file and read back from it
        test.waitFor(() -> String.valueOf(Host.get().setting(name, value)).equals(String.valueOf(value)), 10, "the " + name + " setting to change");
        frames(test, 4);
    }

    private static Path still(SelfTest test, String name, int width, int height, boolean gui) throws Exception {
        Path file = Host.get().dataDirectory().resolve("exports").resolve("watching-" + name + ".png");
        Files.createDirectories(file.getParent());
        Files.deleteIfExists(file);
        test.waitFor(() -> !Playback.jumping(), 30, "playback to settle before a still");
        test.tool("export_screenshot", args("output_path", file.toString(), "width", width, "height", height, "overwrite", true, "no_gui", !gui));
        test.waitFor(() -> !Export.active(), 60, "the still " + name);
        JsonObject done = test.tool("export_status", new JsonObject());
        check(!done.has("last_error"), "the still " + name + " failed: " + done);
        check(Files.exists(file), "the still " + name + " was not written");
        frames(test, 4);
        return file;
    }

    /** How many pixels of two pictures of one size differ by more than a trace */
    private static int differing(Path first, Path second, double fromX, double fromY, double toX, double toY) throws Exception {
        BufferedImage a = ImageIO.read(first.toFile());
        BufferedImage b = ImageIO.read(second.toFile());
        check(a.getWidth() == b.getWidth() && a.getHeight() == b.getHeight(), "two stills that should be one size are not: " + first.getFileName() + " and " + second.getFileName());
        int count = 0;
        for (int y = (int) (a.getHeight() * fromY); y < (int) (a.getHeight() * toY); y++) {
            for (int x = (int) (a.getWidth() * fromX); x < (int) (a.getWidth() * toX); x++) {
                int p = a.getRGB(x, y);
                int q = b.getRGB(x, y);
                if (Math.abs((p >> 16 & 0xFF) - (q >> 16 & 0xFF)) + Math.abs((p >> 8 & 0xFF) - (q >> 8 & 0xFF)) + Math.abs((p & 0xFF) - (q & 0xFF)) > 24) {
                    count++;
                }
            }
        }
        return count;
    }

    private static int differing(Path first, Path second) throws Exception {
        return differing(first, second, 0.0, 0.0, 1.0, 1.0);
    }

    private static boolean layerCancelled(SelfTest test, ResourceLocation layer) throws Exception {
        return test.onClient(() -> {
            Minecraft minecraft = Minecraft.getInstance();
            GuiGraphics graphics = new GuiGraphics(minecraft, minecraft.renderBuffers().bufferSource());
            return NeoForge.EVENT_BUS.post(new RenderGuiLayerEvent.Pre(graphics, DeltaTracker.ZERO, layer, (drawn, delta) -> { })).isCanceled();
        });
    }

    /** The angle, in degrees, between where the camera looks and where a point is */
    private static double offAim(Vec3 target) {
        Vec3 look = Vec3.directionFromRotation(Viewer.pitch(), Viewer.yaw());
        Vec3 to = target.subtract(Viewer.position().add(0.0, 1.62, 0.0)).normalize();
        return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, look.dot(to)))));
    }

    static void watch(SelfTest test) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        check(recording != null && Files.exists(recording), "the recording made by command is missing");
        test.tool("replay_open", args("path", recording.toString()));
        test.waitFor(() -> (Playback.loaded() && !Playback.jumping() && Playback.file() != null && recording.equals(Playback.file().file())) || Playback.lastError() != null, 90,
            "the recording made by command to open");
        check(Playback.lastError() == null, "the recording made by command did not open: " + Playback.lastError());
        test.sleepTicks(10);
        RecordingManifest manifest = Playback.manifest();
        UUID recorderId = UUID.fromString(manifest.recorderId);
        check(walkEnd > walkStart && walkEnd < Playback.total(), "the walk was noted at ticks " + walkStart + " to " + walkEnd + " of " + Playback.total());

        commandsWhileWatching(test, minecraft);
        markers(test, minecraft, manifest);
        path(test, minecraft);
        newLooks(test, minecraft, recorderId);
        frame(test, minecraft);
        players(test, minecraft, manifest, recorderId);
        pictures(test, minecraft, manifest, recorderId);

        test.tool("replay_close", new JsonObject());
        test.waitFor(() -> !Playback.active() && minecraft.level == null, 60, "the recording to close");
        machines(test);
    }

    // --- REC-7 and PLAY-4: the command while a recording is watched ---

    private static void commandsWhileWatching(SelfTest test, Minecraft minecraft) throws Exception {
        for (String refused : List.of("start", "finish", "finish A name", "pause", "resume", "cancel", "mark blue", "moment nothing")) {
            expect(test, refused, "not_while_watching");
        }
        expect(test, "hide", "hidden");
        check(Notices.hidden(), "'/dailies hide' did not hide the interface while watching");
        frames(test, 3);
        check(MarkerRings.drawn().isEmpty(), "marker rings are still drawn with the interface hidden");
        expect(test, "hide", "shown");
        expect(test, "moments", "moments");
        expect(test, "config", "config");
        test.waitFor(() -> settingsOpen(minecraft), 10, "the settings screen while watching");
        test.onClientRun(() -> minecraft.setScreen(null));

        // Any other command is turned away before it could be sent anywhere (PLAY-4)
        test.onClientRun(() -> minecraft.player.connection.sendCommand("time set day"));
        check(DailiesCommand.lastAnswer().equals("only_own"), "a command that is not Dailies' own was not turned away while watching");
        check(test.onClient(() -> minecraft.player.connection.sendUnsignedCommand("gamemode creative")) && DailiesCommand.lastAnswer().equals("only_own"),
            "an unsigned command that is not Dailies' own was not turned away while watching");
        check(minecraft.player.isSpectator(), "the viewer is no longer a spectator after a command");
    }

    // --- REC-32: markers in the world ---

    private static void markers(SelfTest test, Minecraft minecraft, RecordingManifest manifest) throws Exception {
        RecordingManifest.Marker well = manifest.markers.get(0);
        Vec3 spot = new Vec3(well.position[0], well.position[1], well.position[2]);
        jump(test, Math.min(Playback.total() - 5, well.tick + 60));
        editor(test);
        lookFrom(test, spot.add(-5.0, 2.0, -5.0), spot);

        List<MarkerRings.Ring> rings = MarkerRings.drawn();
        check(rings.size() == 1, rings.size() + " marker rings are drawn, not one for the one marker that stored a position");
        MarkerRings.Ring ring = rings.get(0);
        check(ring.colour() == BLUE, "the ring is drawn in " + Integer.toHexString(ring.colour()) + ", not the marker's blue");
        check(Math.abs(ring.x() - spot.x) < 0.01 && Math.abs(ring.z() - spot.z) < 0.01 && Math.abs(ring.y() - spot.y) < 0.1, "the ring is drawn at " + ring + ", not where the marker was dropped");
        check(ring.tick() == well.tick && ring.description().equals("the well"), "the ring does not carry its marker's tick and description: " + ring);
        check(MarkerRings.describe(ring).getString().contains("the well"), "the words shown over a ring leave out its description");

        // A click on it jumps to the marker's tick
        check(Playback.position() != well.tick, "the playhead is already on the marker");
        test.onClientRun(() -> {
            double[] at = MarkerRings.onScreen(ring);
            check(at != null, "the ring is behind the camera that was turned to it");
            minecraft.screen.mouseClicked(at[0], at[1], 0);
            minecraft.screen.mouseReleased(at[0], at[1], 0);
        });
        test.waitFor(() -> !Playback.jumping() && Playback.position() == well.tick, 60, "the jump a click on a marker's ring makes");
        editor(test);

        // Hidden by its switch
        setting(test, "showMarkersInWorld", false);
        check(MarkerRings.drawn().isEmpty(), "marker rings are drawn although they are switched off");
        setting(test, "showMarkersInWorld", true);
        check(MarkerRings.drawn().size() == 1, "the marker ring did not come back when switched on again");
    }

    // --- EDIT-8: the camera path ---

    private static void path(SelfTest test, Minecraft minecraft) throws Exception {
        int from = walkStart;
        Vec3 base = new Vec3(X, Y, Z);
        int[] ticks = {from, from + 20, from + 40, from + 60};
        int track = test.onClient(() -> {
            EditHistory history = EditorSession.history();
            int index = history.setKeyframe(new Keyframe.Camera(ticks[0], Interpolation.SMOOTH, base.x, base.y + 3.0, base.z - 6.0, 0.0, 20.0, 0.0));
            history.setKeyframe(index, new Keyframe.Camera(ticks[1], Interpolation.SMOOTH, base.x + 4.0, base.y + 5.0, base.z - 8.0, -10.0, 25.0, 0.0));
            history.setKeyframe(index, new Keyframe.Camera(ticks[2], Interpolation.LINEAR, base.x + 8.0, base.y + 3.0, base.z - 5.0, -20.0, 20.0, 0.0));
            history.setKeyframe(index, new Keyframe.Camera(ticks[3], Interpolation.SMOOTH, base.x + 10.0, base.y + 4.0, base.z - 9.0, -30.0, 20.0, 0.0));
            return index;
        });
        lookFrom(test, base.add(4.0, 6.0, -18.0), base.add(4.0, 5.0, -7.0));
        check(minecraft.screen != null && PathPreview.drawn(), "the camera path is not drawn while the editor is open");
        picture(test, "watching-path");

        test.onClientRun(() -> {
            List<PathPreview.Mark> marks = PathPreview.marks().stream().filter(mark -> mark.kind() == KeyframeKind.CAMERA).toList();
            check(marks.size() == 4, marks.size() + " keyframe marks for four camera keyframes");
            for (int i = 0; i < 4; i++) {
                PathPreview.Mark mark = marks.get(i);
                Evaluator.CameraPose there = Evaluator.camera(EditorSession.scene(), ticks[i]);
                check(mark.tick() == ticks[i] && mark.track() == track && Math.abs(mark.x() - there.x()) < 1.0e-6 && Math.abs(mark.y() - there.y()) < 1.0e-6
                    && Math.abs(mark.z() - there.z()) < 1.0e-6, "keyframe mark " + i + " is not on its keyframe: " + mark);
            }
            int points = 0;
            double longest = 0.0;
            for (PathPreview.Stretch stretch : PathPreview.stretches()) {
                check(!stretch.smartCamera(), "a Smart Camera stretch is drawn where there is no Smart Camera");
                PathPreview.Point last = null;
                for (PathPreview.Point point : stretch.points()) {
                    Evaluator.CameraPose there = Evaluator.camera(EditorSession.scene(), point.tick());
                    check(there != null && Math.abs(point.x() - there.x()) < 1.0e-9 && Math.abs(point.y() - there.y()) < 1.0e-9 && Math.abs(point.z() - there.z()) < 1.0e-9,
                        "a point of the drawn path at tick " + point.tick() + " is not where the camera goes");
                    if (last != null) {
                        longest = Math.max(longest, point.tick() - last.tick());
                    }
                    last = point;
                    points++;
                }
            }
            check(points >= 4 * 60 && longest <= 0.25 + 1.0e-9, "the path is sampled too coarsely to show its curve: " + points + " points, " + longest + " ticks apart at most");
        });

        // A click on a keyframe's mark selects that keyframe
        test.onClientRun(() -> {
            check(PathPreview.selectedMarks().isEmpty(), "a keyframe is selected before anything was clicked");
            PathPreview.Mark second = PathPreview.marks().get(1);
            double[] at = PathPreview.onScreen(second);
            check(at != null, "a keyframe mark is behind the camera that was turned to the path");
            minecraft.screen.mouseClicked(at[0], at[1], 0);
            minecraft.screen.mouseReleased(at[0], at[1], 0);
            List<PathPreview.Mark> selected = PathPreview.selectedMarks();
            check(selected.size() == 1 && selected.get(0).tick() == ticks[1], "a click on the second keyframe's mark selected " + selected);
        });

        // A keyframe that holds breaks the line: the camera is not flown from it to the next
        test.onClientRun(() -> {
            EditorSession.history().setInterpolation(track, ticks[1], Interpolation.HOLD);
            check(PathPreview.stretches().size() == 2, "a held keyframe did not break the path in two: " + PathPreview.stretches().size() + " runs");
            EditorSession.history().undo();
            check(PathPreview.stretches().size() == 1, "undoing the held keyframe did not join the path again");
        });

        // Its switch, and only while editing
        setting(test, "showCameraPath", false);
        check(!PathPreview.drawn(), "the camera path is drawn although it is switched off");
        setting(test, "showCameraPath", true);
        check(PathPreview.drawn(), "the camera path did not come back when switched on again");

        // Nothing of the path, the rings or the panels is in an exported picture: with and without them the file is the same
        check(!MarkerRings.drawn().isEmpty() && PathPreview.drawn(), "the path and a marker ring should both be on screen before the comparison");
        Path with = still(test, "overlays-on", 640, 360, false);
        setting(test, "showCameraPath", false);
        setting(test, "showMarkersInWorld", false);
        test.onClientRun(() -> minecraft.setScreen(null));
        frames(test, 4);
        check(!PathPreview.drawn() && MarkerRings.drawn().isEmpty(), "the path or the rings are still drawn after being switched off");
        Path without = still(test, "overlays-off", 640, 360, false);
        check(Arrays.equals(Files.readAllBytes(with), Files.readAllBytes(without)),
            "a still exported with the camera path, marker rings and editor on screen is not the same file as one without (" + differing(with, without) + " pixels differ)");
        setting(test, "showCameraPath", true);
        setting(test, "showMarkersInWorld", true);
        check(!PathPreview.drawn(), "the camera path is drawn with the editor closed");
        editor(test);

        // A Smart Camera stretch has a path once it has been played, paler, and stale once its keyframe changes
        test.onClientRun(() -> EditorSession.history().clearAllTracks());
        int smartTick = walkStart;
        int smartTrack = test.tool("smart_camera_add", args("tick", smartTick, "mode", "chase")).get("track").getAsInt();
        check(test.onClient(() -> PathPreview.stretches().isEmpty()), "a Smart Camera stretch has a path before it was ever played");
        jump(test, smartTick);
        test.onClientRun(() -> {
            EditorSession.setFollowCamera(true);
            Playback.playTo(smartTick + 30);
        });
        test.waitFor(() -> Playback.paused() && Playback.position() == smartTick + 30, 30, "play under the Smart Camera");
        Thread.sleep(300);
        editor(test);
        test.onClientRun(() -> {
            List<PathPreview.Stretch> stretches = PathPreview.stretches();
            check(stretches.size() == 1 && stretches.get(0).smartCamera() && !stretches.get(0).stale(), "the played Smart Camera stretch is not drawn as one fresh path: " + stretches.size());
            List<PathPreview.Point> points = stretches.get(0).points();
            check(points.size() >= 10 && points.get(0).tick() >= smartTick && points.get(points.size() - 1).tick() <= smartTick + 30.01,
                "the Smart Camera's path does not cover the stretch that was played: " + points.size() + " points");
            // The last of them is where the camera was left
            PathPreview.Point end = points.get(points.size() - 1);
            check(new Vec3(end.x(), end.y(), end.z()).distanceTo(Viewer.position()) < 0.75, "the Smart Camera's path does not end where the camera is");
            check(PathPreview.marks().stream().filter(mark -> mark.kind() == KeyframeKind.SMART_CAMERA).count() == 1, "the Smart Camera keyframe has no mark on its path");

            Keyframe.SmartCamera keyframe = (Keyframe.SmartCamera) EditorSession.scene().track(smartTrack).at(smartTick);
            JsonObject changed = keyframe.settings().deepCopy();
            changed.addProperty("distance", 11.0);
            EditorSession.history().setKeyframe(smartTrack, new Keyframe.SmartCamera(smartTick, Interpolation.HOLD, changed));
            check(PathPreview.stretches().size() == 1 && PathPreview.stretches().get(0).stale(), "the Smart Camera's path is not marked stale after its keyframe changed");
            EditorSession.history().undo();
            check(!PathPreview.stretches().get(0).stale(), "the Smart Camera's path is still stale after the change was undone");
            EditorSession.history().clearAllTracks();
            check(PathPreview.stretches().isEmpty() && PathPreview.marks().isEmpty(), "a path is left after every track was cleared");
            minecraft.setScreen(null);
        });
        frames(test, 4);
    }

    // --- Chapter V: the looks that were added ---

    private static void newLooks(SelfTest test, Minecraft minecraft, UUID recorderId) throws Exception {
        jump(test, walkEnd);
        Vec3 recorder = test.onClient(() -> minecraft.level.getPlayerByUUID(recorderId).position());
        // The pig, the beacon and the recorder all in the picture
        lookFrom(test, new Vec3(36.5, Y + 3.0, 25.0), new Vec3(37.5, Y, 36.0));
        test.sleepTicks(10);
        frames(test, 4);
        check(recorder.distanceTo(new Vec3(X + 9.0, Y, Z)) < 0.5, "the recorder is not where the walk ended: " + recorder);

        int entities = Drawn.entities();
        int players = Drawn.players();
        int terrain = Drawn.terrainLayers();
        int blockEntities = Drawn.blockEntities();
        check(entities > 0 && players > 0, "with every look on, " + entities + " entities and " + players + " players are drawn");
        if (terrain == 0) {
            // Another renderer mod has taken the method the terrain hook stands in
            test.skip("the terrain hook is not in this game's renderer (another mod draws the terrain its own way): 'show_blocks' was checked by its exported picture only");
        }
        check(blockEntities > 0, "the beacon in the picture is not drawn as a block entity");
        int beams = Drawn.beams();
        check(beams >= 1, "no beam is drawn where a beacon stands in the picture");
        if (beams < 2) {
            test.skip("the end gateway in the test recording shows no beam in playback, so only the beacon's beam was hidden and counted");
        }
        check(Drawn.terrainLayersHidden() == 0 && Drawn.blockEntitiesHidden() == 0 && Drawn.beamsHidden() == 0, "something is kept out of the picture with every look on");
        Path all = still(test, "looks-all", 640, 360, false);

        looks(test, "show_entities", false);
        check(Drawn.entities() == 0 && Drawn.players() == players, "with entities off, " + Drawn.entities() + " entities and " + Drawn.players() + " players are drawn");
        Path noEntities = still(test, "looks-no-entities", 640, 360, false);
        looks(test, "show_entities", true);
        check(Drawn.entities() == entities, "the entities did not come back");

        looks(test, "show_players", false);
        check(Drawn.players() == 0 && Drawn.entities() == entities, "with players off, " + Drawn.players() + " players and " + Drawn.entities() + " entities are drawn");
        Path noPlayers = still(test, "looks-no-players", 640, 360, false);
        looks(test, "show_players", true);
        check(Drawn.players() == players, "the players did not come back");

        looks(test, "show_beacon_beams", false);
        check(Drawn.beams() == 0 && Drawn.beamsHidden() == beams, "with beacon beams off, " + Drawn.beams() + " beams are drawn and " + Drawn.beamsHidden() + " of " + beams + " turned away");
        check(Drawn.blockEntities() == blockEntities && Drawn.blockEntitiesHidden() == 0, "with beacon beams off, the beacon itself or another block entity is kept out of the picture");
        Path noBeams = still(test, "looks-no-beams", 640, 360, false);
        looks(test, "show_beacon_beams", true);
        check(Drawn.beams() == beams && Drawn.beamsHidden() == 0, "the beacon beams did not come back");

        looks(test, "show_blocks", false);
        if (terrain > 0) {
            check(Drawn.terrainLayers() == 0 && Drawn.terrainLayersHidden() > 0, "with blocks off, " + Drawn.terrainLayers() + " layers of terrain are still drawn");
        }
        check(Drawn.blockEntities() == 0 && Drawn.beams() == 0 && Drawn.entities() == entities && Drawn.players() == players, "with blocks off, block entities are drawn or entities are not");
        Path noBlocks = still(test, "looks-no-blocks", 640, 360, false);
        looks(test, "show_blocks", true);
        check(Drawn.terrainLayers() == terrain && Drawn.blockEntities() == blockEntities, "the blocks did not come back");

        // The exported pictures differ where they should, and the look is back afterwards
        int pixels = 640 * 360;
        int withoutBlocks = differing(all, noBlocks);
        int withoutEntities = differing(all, noEntities);
        int withoutPlayers = differing(all, noPlayers);
        int withoutBeams = differing(all, noBeams);
        check(withoutBlocks > pixels / 5, "a still without blocks differs from one with them in only " + withoutBlocks + " pixels");
        check(withoutEntities > 30 && withoutEntities < pixels / 5, "a still without entities differs from one with them in " + withoutEntities + " pixels");
        check(withoutPlayers > 30 && withoutPlayers < pixels / 5, "a still without players differs from one with them in " + withoutPlayers + " pixels");
        check(withoutBeams > 30 && withoutBeams < pixels / 5, "a still without beacon beams differs from one with them in " + withoutBeams + " pixels");
        Path again = still(test, "looks-all-again", 640, 360, false);
        check(differing(all, again) < 30, "with every look back on, the picture is not what it was (" + differing(all, again) + " pixels differ)");
        test.log("looks in exported stills: blocks off changes " + withoutBlocks + " pixels of " + pixels + ", entities " + withoutEntities + ", players " + withoutPlayers
            + ", beacon beams " + withoutBeams + "; drawn with all on: " + entities + " entities, " + players + " players, " + terrain + " terrain layers, " + blockEntities + " block entities, " + beams + " beams");

        // The interface layers: each look turns its own layer away and no other
        ResourceLocation[] layers = {VanillaGuiLayers.HOTBAR, VanillaGuiLayers.SCOREBOARD_SIDEBAR, VanillaGuiLayers.OVERLAY_MESSAGE};
        String[] names = {"show_hotbar", "show_scoreboard", "show_action_bar"};
        for (ResourceLocation layer : layers) {
            check(!layerCancelled(test, layer), "the " + layer + " layer is turned away with every look on");
        }
        for (int i = 0; i < names.length; i++) {
            looks(test, names[i], false);
            for (int j = 0; j < layers.length; j++) {
                check(layerCancelled(test, layers[j]) == (i == j), names[i] + " off " + (i == j ? "does not turn away" : "turns away") + " the " + layers[j] + " layer");
            }
            check(!layerCancelled(test, VanillaGuiLayers.PLAYER_HEALTH), names[i] + " off turns away the health bar");
            looks(test, names[i], true);
        }

        // The recorder's own hotbar, riding along, and the scoreboard, in exported pictures
        test.onClientRun(() -> Viewer.ride(minecraft.level.getPlayerByUUID(recorderId)));
        frames(test, 4);
        Path hud = still(test, "hud-all", 1280, 720, true);
        looks(test, "show_hotbar", false);
        Path noHotbar = still(test, "hud-no-hotbar", 1280, 720, true);
        looks(test, "show_hotbar", true, "show_scoreboard", false);
        Path noScoreboard = still(test, "hud-no-scoreboard", 1280, 720, true);
        looks(test, "show_scoreboard", true);
        test.onClientRun(() -> Viewer.ride(null));
        frames(test, 4);
        int hotbarGone = differing(hud, noHotbar, 0.3, 0.9, 0.7, 1.0);
        int hotbarElsewhere = differing(hud, noHotbar, 0.0, 0.0, 1.0, 0.8);
        check(hotbarGone > 200 && hotbarElsewhere < 30, "a still without the hotbar differs in " + hotbarGone + " pixels where the hotbar is and " + hotbarElsewhere + " elsewhere");
        int scoreboardGone = differing(hud, noScoreboard, 0.8, 0.3, 1.0, 0.7);
        int scoreboardElsewhere = differing(hud, noScoreboard, 0.0, 0.0, 0.7, 1.0);
        check(scoreboardGone > 200 && scoreboardElsewhere < 30,
            "a still without the scoreboard differs in " + scoreboardGone + " pixels where the scoreboard is and " + scoreboardElsewhere + " elsewhere");

        // The action bar: played up to a moment when a line stands on it, which a jump alone would not show
        jump(test, Math.max(0, actionBar - 5));
        test.tool("replay_set_speed", args("tick_rate", 20));
        test.onClientRun(() -> Playback.playTo(actionBar + 12));
        test.waitFor(() -> Playback.paused() && Playback.position() == actionBar + 12, 30, "play up to the line on the action bar");
        frames(test, 4);
        Path bar = still(test, "hud-action-bar", 1280, 720, true);
        looks(test, "show_action_bar", false);
        Path noBar = still(test, "hud-no-action-bar", 1280, 720, true);
        looks(test, "show_action_bar", true);
        Path barAgain = still(test, "hud-action-bar-again", 1280, 720, true);
        int barGone = differing(bar, noBar, 0.25, 0.7, 0.75, 0.95);
        int barElsewhere = differing(bar, noBar, 0.0, 0.0, 1.0, 0.6);
        check(barGone > 200 && barElsewhere < 30, "a still without the action bar differs in " + barGone + " pixels where its line is and " + barElsewhere + " elsewhere");
        check(differing(bar, barAgain, 0.25, 0.7, 0.75, 0.95) < barGone / 4, "the line on the action bar did not come back when the look was switched on again");
        test.log("the action bar look takes " + barGone + " pixels of its line out of an exported still");
    }

    // --- Chapter V: the frame that is the picture itself ---

    private static void frame(SelfTest test, Minecraft minecraft) throws Exception {
        double wide = 21.0 / 9.0;
        double window = (double) minecraft.getWindow().getWidth() / minecraft.getWindow().getHeight();
        Path plain = still(test, "frame-plain", 640, 360, false);
        check(Host.get().exportAspectRatio() == null, "a scene with no frame of its own gives the Smart Camera a frame shape");

        // Only a guide: dimmed bars, the picture as asked
        looks(test, "aspect_guide", "21:9");
        double[] guide = Drawn.clearFrame();
        check(guide != null && !Drawn.solidBars(), "the frame guide is not drawn as a guide");
        BufferedImage guided = ImageIO.read(still(test, "frame-guide", 640, 360, false).toFile());
        check(guided.getWidth() == 640 && guided.getHeight() == 360, "a frame shape that is only a guide changed the size of an export");

        // Held to: solid bars in the view, and exports in that shape
        looks(test, "letterbox", true);
        double[] clear = Drawn.clearFrame();
        check(clear != null && Drawn.solidBars(), "a scene held to its frame shape has no solid bars in the view");
        int height = minecraft.getWindow().getGuiScaledHeight();
        int width = minecraft.getWindow().getGuiScaledWidth();
        if (wide > window) {
            double share = (clear[3] - clear[1]) / height;
            check(clear[0] == 0 && clear[2] == width && clear[1] > 0 && Math.abs(clear[1] - (height - clear[3])) <= 1 && Math.abs(share - window / wide) < 0.02,
                "the bars do not leave a 21:9 frame clear in the middle of the view: " + Arrays.toString(clear) + " of " + width + "x" + height);
        } else {
            test.skip("the test window is wider than 21:9, so the bars above and below the view were not measured");
        }
        picture(test, "watching-letterbox");
        Double framed = Host.get().exportAspectRatio();
        check(framed != null && Math.abs(framed - wide) < 1.0e-6, "the Smart Camera is not given the frame's shape to compose for: " + framed);

        BufferedImage held = ImageIO.read(still(test, "frame-held", 640, 360, false).toFile());
        check(held.getWidth() == 640 && held.getHeight() == 274, "a still of a scene held to 21:9, asked for at 640x360, is " + held.getWidth() + "x" + held.getHeight() + ", not 640x274");
        BufferedImage large = ImageIO.read(still(test, "frame-held-large", 1920, 1080, false).toFile());
        check(large.getWidth() == 1920 && large.getHeight() == 822, "a still held to 21:9 at 1920x1080 is " + large.getWidth() + "x" + large.getHeight() + ", not 1920x822");

        // A frame narrower than the window: bars at the sides
        looks(test, "aspect_guide", "1:1");
        double[] square = Drawn.clearFrame();
        check(square != null && Drawn.solidBars() && square[1] == 0 && square[3] == height && square[0] > 0 && Math.abs((square[2] - square[0]) - height * (double) width / height / window) <= 2,
            "the bars do not leave a square clear in the middle of the view: " + Arrays.toString(square));
        BufferedImage squared = ImageIO.read(still(test, "frame-square", 640, 360, false).toFile());
        check(squared.getWidth() == 360 && squared.getHeight() == 360, "a still held to 1:1 at 640x360 is " + squared.getWidth() + "x" + squared.getHeight() + ", not 360x360");

        looks(test, "letterbox", false, "aspect_guide", "off");
        check(Drawn.clearFrame() == null && Host.get().exportAspectRatio() == null, "the frame is still there after it was switched off");
        Path back = still(test, "frame-plain-again", 640, 360, false);
        check(differing(plain, back) < 30, "with the frame switched off again the picture is not what it was");

        // Held to its shape through a run of pictures too, which is the way a video is drawn. Last, since a run moves
        // the recording and puts it back, and the walker is not then mid-stride exactly as before
        looks(test, "letterbox", true, "aspect_guide", "21:9");
        Path folder = Host.get().dataDirectory().resolve("exports").resolve("watching-frame-run");
        if (Files.isDirectory(folder)) {
            try (Stream<Path> files = Files.list(folder)) {
                for (Path file : files.toList()) {
                    Files.delete(file);
                }
            }
        }
        test.tool("export_start", args("output_path", folder.toString(), "container", "png_sequence", "width", 640, "height", 360, "framerate", 20,
            "start_tick", walkStart, "end_tick", walkStart + 4, "no_gui", true, "overwrite", true));
        test.waitFor(() -> !Export.active(), 120, "the framed run of pictures");
        check(!test.tool("export_status", new JsonObject()).has("last_error"), "the framed run of pictures failed");
        test.waitFor(() -> !Playback.jumping(), 30, "playback to be put back");
        try (Stream<Path> files = Files.list(folder)) {
            List<Path> pictures = files.filter(file -> file.getFileName().toString().endsWith(".png")).sorted().toList();
            check(pictures.size() == 4, "the framed run wrote " + pictures.size() + " pictures, not 4");
            BufferedImage first = ImageIO.read(pictures.get(0).toFile());
            check(first.getWidth() == 640 && first.getHeight() == 274, "a run of pictures held to 21:9 came out " + first.getWidth() + "x" + first.getHeight() + ", not 640x274");
        }
        frames(test, 4);
        looks(test, "letterbox", false, "aspect_guide", "off");
    }

    // --- PLAY-3: the players panel ---

    private static void players(SelfTest test, Minecraft minecraft, RecordingManifest manifest, UUID recorderId) throws Exception {
        jump(test, walkStart);
        lookFrom(test, new Vec3(X - 2.0, Y + 2.0, Z - 7.0), new Vec3(X + 20.0, Y + 8.0, Z - 30.0));
        editor(test);

        picture(test, "watching-players");
        test.onClientRun(() -> {
            List<PlayerList.Listed> listed = PlayerList.players();
            check(listed.size() == 1 && listed.get(0).id().equals(recorderId) && listed.get(0).name().equals(manifest.recorderName) && listed.get(0).inWorld(),
                "the players panel does not list the recorder and only the recorder: " + listed);
        });

        // Look at
        Player recorder = test.onClient(() -> minecraft.level.getPlayerByUUID(recorderId));
        check(offAim(recorder.getEyePosition()) > 30.0, "the camera already faces the recorder");
        test.onClientRun(() -> PlayerList.lookAt(recorderId));
        frames(test, 3);
        check(test.onClient(() -> offAim(minecraft.level.getPlayerByUUID(recorderId).getEyePosition())) < 1.0, "'look at' did not turn the camera to the recorder");

        // Hide: a look of the scene, so it holds in the picture and can be undone
        frames(test, 3);
        check(Drawn.players() > 0, "the recorder is not drawn before being hidden");
        test.onClientRun(() -> PlayerList.hide(recorderId));
        frames(test, 4);
        check(Drawn.players() == 0 && PlayerList.hidden(recorderId), "'hide' left " + Drawn.players() + " players in the picture");
        check(test.onClient(() -> EditorSession.scene().looks().hiddenEntities().contains(recorderId)), "'hide' is not a look of the scene");
        check(test.onClient(() -> PlayerList.players().size()) == 1, "a hidden player dropped out of the list, so could not be shown again");
        test.onClientRun(() -> PlayerList.hide(recorderId));
        frames(test, 4);
        check(Drawn.players() > 0 && !PlayerList.hidden(recorderId), "pressing 'hide' again did not bring the player back");

        // Ride along, and leave
        test.onClientRun(() -> PlayerList.ride(recorderId));
        check(test.onClient(() -> Viewer.riding() != null && Viewer.riding().getUUID().equals(recorderId)) && PlayerList.riding(recorderId), "'ride' did not put the view behind the recorder's eyes");
        test.onClientRun(() -> PlayerList.ride(recorderId));
        check(test.onClient(() -> Viewer.riding() == null), "pressing 'ride' again did not leave the recorder");

        // Follow keeps them in view while they walk
        lookFrom(test, new Vec3(X - 2.0, Y + 2.0, Z - 7.0), new Vec3(X, Y + 1.0, Z));
        Vec3 standingAt = test.onClient(() -> minecraft.level.getPlayerByUUID(recorderId).position());
        test.onClientRun(() -> PlayerList.follow(recorderId));
        frames(test, 4);
        check(PlayerList.following(recorderId), "'follow' did not start following");
        Vec3 cameraBefore = test.onClient(Viewer::position);
        test.tool("replay_set_speed", args("tick_rate", 20));
        test.onClientRun(() -> Playback.playTo(walkEnd));
        double worst = 0.0;
        int looked = 0;
        while (!(Playback.paused() && Playback.position() == walkEnd)) {
            worst = Math.max(worst, test.onClient(() -> {
                Player there = minecraft.level.getPlayerByUUID(recorderId);
                return there == null ? 0.0 : offAim(there.getPosition(minecraft.getTimer().getGameTimeDeltaPartialTick(true)).add(0.0, there.getEyeHeight(), 0.0));
            }));
            looked++;
            check(looked < 2000, "the walk never finished playing");
            Thread.sleep(40);
        }
        frames(test, 4);
        Vec3 walkedTo = test.onClient(() -> minecraft.level.getPlayerByUUID(recorderId).position());
        Vec3 cameraAfter = test.onClient(Viewer::position);
        check(walkedTo.distanceTo(standingAt) > 8.0, "the recorder did not walk: " + standingAt + " to " + walkedTo);
        check(looked > 10 && worst < 8.0, "while following, the recorder was up to " + worst + " degrees off the middle of the view (looked " + looked + " times)");
        check(cameraAfter.subtract(cameraBefore).distanceTo(walkedTo.subtract(standingAt)) < 0.5, "the following camera did not go where the recorder went: it moved " + cameraAfter.subtract(cameraBefore));
        check(test.onClient(() -> offAim(minecraft.level.getPlayerByUUID(recorderId).getEyePosition())) < 1.0, "the following camera does not face the recorder at the end of the walk");

        // Across a jump back the list and the following hold: players are known by id, not as the entities a jump replaces
        jump(test, walkStart);
        frames(test, 6);
        check(PlayerList.following(recorderId) && test.onClient(() -> PlayerList.players().size() == 1 && PlayerList.players().get(0).inWorld()), "the players panel lost the recorder across a jump");
        check(test.onClient(() -> offAim(minecraft.level.getPlayerByUUID(recorderId).getEyePosition())) < 1.0, "after a jump back the following camera does not face the recorder");
        check(test.onClient(Viewer::position).distanceTo(cameraBefore) < 0.5, "after a jump back the following camera is not back beside the recorder");
        test.onClientRun(() -> PlayerList.follow(recorderId));
        check(!PlayerList.following(recorderId) && Viewer.following() == null, "pressing 'follow' again did not stop following");
        morePlayers(test, minecraft, recorderId);
        test.onClientRun(() -> minecraft.setScreen(null));
        test.log("following kept the recorder within " + String.format("%.1f", worst) + " degrees of the middle of the view over " + looked + " looks");
    }

    // --- PLAY-3 with more than the recorder ---

    private static final UUID ADA = UUID.nameUUIDFromBytes("dailies-test-ada".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private static final UUID BRUNEL = UUID.nameUUIDFromBytes("dailies-test-brunel".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    /** As long as a player's name can be, to see that it is cut short tidily */
    private static final String ADA_NAME = "Ada_Lovelace_815";

    /**
     * Puts two more players into the playback world the way a server does: the packet that lists players, for both,
     * and the packet that brings a player into sight, for one of them. So one is there to be seen and one is only
     * known of, as someone far off is. A singleplayer test world has nobody else to record.
     */
    private static void addPlayers(SelfTest test, Vec3 at) throws Exception {
        test.onClientRun(() -> {
            Minecraft minecraft = Minecraft.getInstance();
            net.minecraft.client.multiplayer.ClientPacketListener listener = minecraft.getConnection();
            comedymonkey.dailies.capture.GameCodec codec = new comedymonkey.dailies.capture.GameCodec(listener.registryAccess(), listener.getConnectionType());
            List<net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Entry> entries = List.of(
                new net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Entry(ADA, new com.mojang.authlib.GameProfile(ADA, ADA_NAME), true, 0,
                    net.minecraft.world.level.GameType.SURVIVAL, null, null),
                new net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Entry(BRUNEL, new com.mojang.authlib.GameProfile(BRUNEL, "Brunel"), true, 0,
                    net.minecraft.world.level.GameType.SURVIVAL, null, null));
            listener.handlePlayerInfoUpdate(comedymonkey.dailies.capture.PlayerInfoPackets.build(codec, comedymonkey.dailies.capture.PlayerInfoPackets.allButChat(), entries));
            listener.handleAddEntity(new net.minecraft.network.protocol.game.ClientboundAddEntityPacket(90001, ADA, at.x, at.y, at.z, 0.0F, 90.0F,
                net.minecraft.world.entity.EntityType.PLAYER, 0, Vec3.ZERO, 90.0));
        });
        frames(test, 6);
    }

    private static void morePlayers(SelfTest test, Minecraft minecraft, UUID recorderId) throws Exception {
        Vec3 recorder = test.onClient(() -> minecraft.level.getPlayerByUUID(recorderId).position());
        Vec3 beside = recorder.add(3.0, 0.0, 4.0);
        lookFrom(test, recorder.add(1.5, 2.0, -7.0), recorder.add(1.5, 1.0, 2.0));
        editor(test);
        int before = Drawn.players();
        addPlayers(test, beside);

        test.onClientRun(() -> {
            List<PlayerList.Listed> listed = PlayerList.players();
            check(listed.size() == 3, "with two more players the panel lists " + listed);
            check(listed.get(0).id().equals(recorderId), "the recorder is not first in the players panel: " + listed);
            check(listed.get(1).id().equals(ADA) && listed.get(1).name().equals(ADA_NAME) && listed.get(1).inWorld(), "the player in sight is not listed as in sight: " + listed);
            check(listed.get(2).id().equals(BRUNEL) && !listed.get(2).inWorld(), "the player who is only known of is not listed as out of sight: " + listed);
        });
        check(Drawn.players() == before + 1, "a second player in sight is not drawn: " + Drawn.players() + " players, " + before + " before");
        picture(test, "watching-players-three");

        // Each row acts on its own player
        test.onClientRun(() -> PlayerList.lookAt(ADA));
        frames(test, 3);
        check(test.onClient(() -> offAim(minecraft.level.getPlayerByUUID(ADA).getEyePosition())) < 1.0, "'look at' on the second row did not turn the camera to that player");
        test.onClientRun(() -> PlayerList.hide(ADA));
        frames(test, 4);
        check(Drawn.players() == before && PlayerList.hidden(ADA) && !PlayerList.hidden(recorderId), "hiding the second player left " + Drawn.players() + " players drawn, or hid the recorder");
        test.onClientRun(() -> PlayerList.hide(ADA));
        frames(test, 4);
        check(Drawn.players() == before + 1, "the second player did not come back");
        test.onClientRun(() -> PlayerList.follow(ADA));
        frames(test, 3);
        check(PlayerList.following(ADA) && !PlayerList.following(recorderId), "'follow' on the second row does not follow that player");
        test.onClientRun(() -> PlayerList.ride(ADA));
        check(test.onClient(() -> Viewer.riding() != null && Viewer.riding().getUUID().equals(ADA)), "'ride' on the second row did not ride with that player");
        check(Viewer.following() == null, "riding along did not end the following");
        test.onClientRun(() -> PlayerList.ride(ADA));

        // Out of sight: nothing to look at, follow or ride with, and none of them does anything; hide still works,
        // so that someone can be kept out of a scene before they walk into it
        Vec3 cameraAt = test.onClient(Viewer::position);
        float yawAt = test.onClient(Viewer::yaw);
        test.onClientRun(() -> {
            PlayerList.lookAt(BRUNEL);
            PlayerList.follow(BRUNEL);
            PlayerList.ride(BRUNEL);
        });
        frames(test, 3);
        check(Viewer.following() == null && test.onClient(() -> Viewer.riding() == null) && test.onClient(Viewer::position).distanceTo(cameraAt) < 1.0e-6
            && Math.abs(test.onClient(Viewer::yaw) - yawAt) < 1.0e-6, "a player who is out of sight could be looked at, followed or ridden with");
        test.onClientRun(() -> PlayerList.hide(BRUNEL));
        check(PlayerList.hidden(BRUNEL) && test.onClient(() -> EditorSession.scene().looks().hiddenEntities().contains(BRUNEL)), "a player who is out of sight could not be hidden");
        test.onClientRun(() -> PlayerList.hide(BRUNEL));
        check(!PlayerList.hidden(BRUNEL), "a player who is out of sight could not be shown again");

        // They came by hand and a jump takes them away again: the panel follows who is there
        jump(test, walkStart + 1);
        frames(test, 6);
        List<PlayerList.Listed> after = test.onClient(PlayerList::players);
        check(!after.isEmpty() && after.get(0).id().equals(recorderId) && after.stream().noneMatch(listed -> listed.id().equals(ADA) && listed.inWorld()),
            "after a jump the panel still shows a player who is no longer in the world: " + after);
        test.log("players panel with three players: one in sight, one out of sight; after a jump it lists " + after.size());
    }

    // --- Chapter V with Create: what is a block and what is an entity ---

    /** How many pixels of a picture are not the plain green a scene without its sky is filled with */
    private static int notBackground(Path picture) throws Exception {
        BufferedImage image = ImageIO.read(picture.toFile());
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                if ((rgb >> 16 & 0xFF) > 40 || (rgb >> 8 & 0xFF) < 215 || (rgb & 0xFF) > 40) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * The recording the other steps made holds Create's machines: a row of shafts and cogs, a bearing on a motor,
     * and the contraption the bearing carries. Flywheel draws all of them. Here they are looked at with blocks off
     * and with entities off, counted in Flywheel's own lists and proved in exported pictures.
     */
    static void machines(SelfTest test) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        if (!Host.get().isModLoaded("create")) {
            test.skip("Create is not installed: the blocks and entities looks were not tried on its machines");
            return;
        }
        test.tool("replay_open", args("path", RecordingSteps.recording.toString()));
        test.waitFor(() -> (Playback.loaded() && !Playback.jumping() && Playback.file() != null && RecordingSteps.recording.equals(Playback.file().file())) || Playback.lastError() != null, 120,
            "the recording with the machines to open");
        check(Playback.lastError() == null, "the recording with the machines did not open: " + Playback.lastError());
        jump(test, RecordingSteps.notes.get(RecordingSteps.notes.size() / 2).tick());
        lookFrom(test, new Vec3(13.5, -56.0, -17.0), new Vec3(13.5, -59.0, -7.0));
        test.sleepTicks(20);
        frames(test, 8);

        int[] drawn = comedymonkey.dailies.compat.create.FlywheelLooks.drawn();
        if (drawn == null) {
            test.skip("Flywheel's lists were not found: Create's machines were checked by exported pictures only");
        } else {
            check(drawn[0] >= 4 && drawn[1] >= 1, "Flywheel is drawing " + drawn[0] + " block entities and " + drawn[1] + " entities where a row of kinetics and a contraption are in view");
        }

        // Against a plain background, so that anything left in the picture can be counted
        looks(test, "sky", false, "background_colour", "#00FF00", "show_particles", false, "show_players", false);
        frames(test, 6);
        Path all = still(test, "machines-all", 640, 360, false);

        looks(test, "show_blocks", false);
        frames(test, 6);
        int[] noBlocks = comedymonkey.dailies.compat.create.FlywheelLooks.drawn();
        if (drawn != null) {
            check(noBlocks[0] == 0 && noBlocks[1] == drawn[1], "with blocks off Flywheel still draws " + noBlocks[0] + " block entities, and " + noBlocks[1] + " entities of " + drawn[1]);
        }
        Path blocksOff = still(test, "machines-no-blocks", 640, 360, false);
        int contraptionLeft = notBackground(blocksOff);
        check(contraptionLeft > 150, "with blocks off the contraption, which is an entity, is gone from the picture too (" + contraptionLeft + " pixels are not background)");

        looks(test, "show_entities", false);
        frames(test, 6);
        int[] nothing = comedymonkey.dailies.compat.create.FlywheelLooks.drawn();
        if (drawn != null) {
            check(nothing[0] == 0 && nothing[1] == 0, "with blocks and entities off Flywheel still draws " + nothing[0] + " block entities and " + nothing[1] + " entities");
        }
        Path bare = still(test, "machines-nothing", 640, 360, false);
        int left = notBackground(bare);
        check(left == 0, "with blocks, entities, players, particles and sky off, " + left + " pixels of an exported picture are still not background: see " + bare);

        looks(test, "show_blocks", true);
        frames(test, 6);
        int[] noEntities = comedymonkey.dailies.compat.create.FlywheelLooks.drawn();
        if (drawn != null) {
            check(noEntities[0] == drawn[0] && noEntities[1] == 0, "with entities off Flywheel draws " + noEntities[0] + " block entities of " + drawn[0] + " and " + noEntities[1] + " entities");
        }
        Path entitiesOff = still(test, "machines-no-entities", 640, 360, false);
        int contraptionGone = differing(all, entitiesOff);
        check(contraptionGone > 150, "with entities off the picture differs from the whole one in only " + contraptionGone + " pixels: the contraption is still there");

        looks(test, "show_entities", true);
        frames(test, 8);
        int[] back = comedymonkey.dailies.compat.create.FlywheelLooks.drawn();
        if (drawn != null) {
            check(back[0] == drawn[0] && back[1] == drawn[1], "Flywheel does not draw everything again once the looks are back on: " + back[0] + " and " + back[1] + " of " + drawn[0] + " and " + drawn[1]);
        }
        Path again = still(test, "machines-all-again", 640, 360, false);
        check(differing(all, again) < 400, "with every look back on the machines are not all back in the picture (" + differing(all, again) + " pixels differ)");
        looks(test, "sky", true, "show_particles", true, "show_players", true);
        test.log("Create's machines: Flywheel drew " + (drawn == null ? "?" : drawn[0] + " block entities and " + drawn[1] + " entities") + "; blocks off leaves " + contraptionLeft
            + " pixels of contraption, entities off changes " + contraptionGone + ", both off leaves " + left);

        test.tool("replay_close", new JsonObject());
        test.waitFor(() -> !Playback.active() && minecraft.level == null, 60, "the recording to close");
    }

    // --- For the eye: pictures at 1920 by 1080 and interface scale 2 ---

    private interface Stop {
        void run() throws Exception;
    }

    private static void attempt(String what, Stop stop) {
        try {
            stop.run();
        } catch (Throwable t) {
            Host.LOGGER.warn("Dailies self test: the picture of {} could not be taken", what, t);
        }
    }

    private static void size(SelfTest test, int width, int height, int scale) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        test.onClientRun(() -> {
            org.lwjgl.glfw.GLFW.glfwSetWindowSize(minecraft.getWindow().getWindow(), width, height);
            minecraft.options.guiScale().set(scale);
        });
        test.sleepTicks(8);
        test.onClientRun(minecraft::resizeDisplay);
        test.sleepTicks(8);
    }

    /**
     * Pictures of everything this class draws, at the size and scale the interface is judged at, kept in the
     * screenshots folder. Nothing here can fail the run: whether it looks right is for a person to say.
     */
    private static void pictures(SelfTest test, Minecraft minecraft, RecordingManifest manifest, UUID recorderId) throws Exception {
        int widthBefore = test.onClient(() -> minecraft.getWindow().getScreenWidth());
        int heightBefore = test.onClient(() -> minecraft.getWindow().getScreenHeight());
        int scaleBefore = minecraft.options.guiScale().get();
        size(test, 1920, 1080, 2);
        Vec3 base = new Vec3(X, Y, Z);
        try {
            attempt("the path against the sky", () -> {
                jump(test, walkStart);
                test.onClientRun(() -> {
                    EditHistory history = EditorSession.history();
                    history.clearAllTracks();
                    int index = history.setKeyframe(new Keyframe.Camera(walkStart, Interpolation.SMOOTH, base.x - 6.0, base.y + 14.0, base.z + 4.0, 0.0, 30.0, 0.0));
                    history.setKeyframe(index, new Keyframe.Camera(walkStart + 20, Interpolation.SMOOTH, base.x, base.y + 18.0, base.z + 8.0, -40.0, 35.0, 0.0));
                    history.setKeyframe(index, new Keyframe.Camera(walkStart + 40, Interpolation.SMOOTH, base.x + 6.0, base.y + 13.0, base.z + 5.0, -90.0, 30.0, 0.0));
                    history.setKeyframe(index, new Keyframe.Camera(walkStart + 60, Interpolation.SMOOTH, base.x + 12.0, base.y + 17.0, base.z + 9.0, -120.0, 40.0, 0.0));
                });
                lookFrom(test, base.add(3.0, 0.0, -8.0), base.add(3.0, 17.0, 6.0));
                editor(test);
                // One keyframe selected, to see the brighter mark
                test.onClientRun(() -> {
                    double[] at = PathPreview.onScreen(PathPreview.marks().get(1));
                    if (at != null) {
                        minecraft.screen.mouseClicked(at[0], at[1], 0);
                        minecraft.screen.mouseReleased(at[0], at[1], 0);
                    }
                });
                picture(test, "eye-path-sky");
            });
            attempt("the path in the dark", () -> {
                test.onClientRun(() -> {
                    EditHistory history = EditorSession.history();
                    history.clearAllTracks();
                    int index = history.setKeyframe(new Keyframe.Camera(walkStart, Interpolation.SMOOTH, 33.0, -59.0, 18.0, -90.0, 0.0, 0.0));
                    history.setKeyframe(index, new Keyframe.Camera(walkStart + 20, Interpolation.SMOOTH, 37.0, -58.2, 16.6, -90.0, 10.0, 0.0));
                    history.setKeyframe(index, new Keyframe.Camera(walkStart + 40, Interpolation.SMOOTH, 41.0, -59.0, 19.0, -60.0, 0.0, 0.0));
                });
                lookFrom(test, new Vec3(37.0, -59.0, 20.6), new Vec3(37.0, -57.0, 17.0));
                editor(test);
                picture(test, "eye-path-dark");
            });
            attempt("a stale Smart Camera stretch", () -> {
                test.onClientRun(() -> EditorSession.history().clearAllTracks());
                int track = test.tool("smart_camera_add", args("tick", walkStart, "mode", "chase")).get("track").getAsInt();
                jump(test, walkStart);
                test.onClientRun(() -> {
                    EditorSession.setFollowCamera(true);
                    Playback.playTo(walkStart + 60);
                });
                test.waitFor(() -> Playback.paused() && Playback.position() == walkStart + 60, 30, "play under the Smart Camera");
                lookFrom(test, base.add(4.0, 7.0, -13.0), base.add(4.0, 2.0, -3.0));
                editor(test);
                picture(test, "eye-smart-fresh");
                test.onClientRun(() -> {
                    Keyframe.SmartCamera keyframe = (Keyframe.SmartCamera) EditorSession.scene().track(track).at(walkStart);
                    JsonObject changed = keyframe.settings().deepCopy();
                    changed.addProperty("distance", 11.0);
                    EditorSession.history().setKeyframe(track, new Keyframe.SmartCamera(walkStart, Interpolation.HOLD, changed));
                });
                picture(test, "eye-smart-stale");
                test.onClientRun(() -> EditorSession.history().clearAllTracks());
            });
            attempt("the marker rings", () -> {
                RecordingManifest.Marker well = manifest.markers.get(0);
                Vec3 spot = new Vec3(well.position[0], well.position[1], well.position[2]);
                jump(test, walkEnd);
                lookFrom(test, spot.add(-3.0, 1.0, -4.0), spot);
                editor(test);
                picture(test, "eye-ring-near");
                lookFrom(test, spot.add(-45.0, 12.0, -55.0), spot);
                picture(test, "eye-ring-far");
            });
            attempt("the letterboxed editor", () -> {
                lookFrom(test, new Vec3(36.5, Y + 3.0, 25.0), new Vec3(37.5, Y, 36.0));
                looks(test, "aspect_guide", "21:9", "letterbox", true, "thirds_guide", true);
                editor(test);
                picture(test, "eye-letterbox-wide");
                looks(test, "aspect_guide", "9:16");
                picture(test, "eye-letterbox-tall");
                looks(test, "aspect_guide", "off", "letterbox", false, "thirds_guide", false);
            });
            attempt("the players panel", () -> {
                Vec3 recorder = test.onClient(() -> minecraft.level.getPlayerByUUID(recorderId).position());
                lookFrom(test, recorder.add(1.5, 2.0, -7.0), recorder.add(1.5, 1.0, 2.0));
                addPlayers(test, recorder.add(3.0, 0.0, 4.0));
                editor(test);
                test.onClientRun(() -> {
                    PlayerList.hide(BRUNEL);
                    PlayerList.follow(ADA);
                });
                picture(test, "eye-players");
                test.onClientRun(() -> {
                    PlayerList.hide(BRUNEL);
                    PlayerList.follow(ADA);
                });
            });
        } finally {
            test.onClientRun(() -> minecraft.setScreen(null));
            size(test, widthBefore, heightBefore, scaleBefore);
        }
    }

}
