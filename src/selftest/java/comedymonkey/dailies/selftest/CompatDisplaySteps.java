package comedymonkey.dailies.selftest;

import com.google.gson.JsonObject;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.api.compat.DisplayTest;
import comedymonkey.dailies.capture.Recorder;
import comedymonkey.dailies.looks.Drawn;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.playback.Viewer;
import comedymonkey.dailies.windows.Compats;
import comedymonkey.dailies.windows.WindowLayer;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static comedymonkey.dailies.selftest.SelfTest.args;
import static comedymonkey.dailies.selftest.SelfTest.check;

/**
 * A compat's display test (specification TEST-8, CPT-3), shaped as the windows' ({@link WindowSteps}) and kept as small:
 * the compat's own {@link DisplayTest} sets the stage in a flat world of its own, then while recording opens each of its
 * displays in turn for a couple of seconds. Then in playback, riding along with the recorder in first person with the
 * HUD on, each must be drawn at a tick in the middle of it, by the window layer (a server window Dailies draws, with or
 * without the compat's extras) or by the compat's own layer; and switching the camera to third person, or flying free,
 * must stop anything being drawn. Skipped with a line when the mod is not installed, or is a version without the compat.
 */
final class CompatDisplaySteps {

    private CompatDisplaySteps() {
    }

    /** Post Rider's mailbox, reading a letter and writing one: -PdailiesOnly=postrider */
    static void postRider(SelfTest test) throws Exception {
        run(test, "postrider", "Post Rider", "postrider:screens", "Dailies Post Rider");
    }

    static void run(SelfTest test, String modId, String modName, String compatId, String world) throws Exception {
        if (!Host.get().isModLoaded(modId)) {
            test.skip(modName + " is not installed: its screens in recordings were not tried");
            return;
        }
        DisplayTest display = Compats.displayTest(compatId);
        if (display == null) {
            test.skip("this version of " + modName + " registers no Dailies compat " + compatId + " with a display test (compats registered: "
                + Compats.registered() + "): its screens in recordings were not tried");
            return;
        }
        List<DisplayTest.Display> displays = display.displays();
        Minecraft minecraft = Minecraft.getInstance();
        if (Playback.active()) {
            PlaybackSteps.close(test);
        }
        if (minecraft.level != null) {
            WorldSteps.leaveWorld(test);
        }
        WorldSteps.createWorld(test, world);
        test.onClientRun(() -> minecraft.options.setCameraType(CameraType.FIRST_PERSON));
        DisplayTest.Stage stage = stage(test);
        test.command("gamerule sendCommandFeedback false");
        test.command("gamemode survival");
        test.command("effect give @s minecraft:resistance 600 4 true");
        test.command("item replace entity @s weapon.mainhand with minecraft:air");
        test.command("tp @s 0.5 -60 0.5 0 0");
        display.stage(stage);
        test.waitTicks(20);

        // Recording: each display open for two seconds, the tick in the middle of it noted
        test.tool("recording_start", new JsonObject());
        List<Integer> ticks = new ArrayList<>();
        test.waitTicks(30);
        for (DisplayTest.Display shown : displays) {
            shown.opener().open(stage);
            test.waitTicks(20);
            ticks.add(test.onClient(() -> Recorder.get().recordingTick()));
            test.waitTicks(20);
            test.onClientRun(() -> {
                if (minecraft.screen != null) {
                    minecraft.screen.onClose();
                }
            });
            test.waitFor(() -> minecraft.screen == null, 10, shown.label() + " to close in the live game");
            test.waitTicks(20);
        }
        JsonObject finished = test.tool("recording_finish", args("name", modName));
        Path recording = Path.of(finished.get("path").getAsString());
        WorldSteps.leaveWorld(test);

        // Playback, riding along with the recorder in first person, the HUD on (no editor: every look as it starts)
        test.tool("replay_open", args("path", recording.toString()));
        test.waitFor(() -> Playback.loaded() || Playback.lastError() != null, 120, "the " + modName + " recording to open");
        check(Playback.lastError() == null, "the " + modName + " recording did not open: " + Playback.lastError());
        List<String> problems = new ArrayList<>();
        try {
            for (int i = 0; i < displays.size(); i++) {
                DisplayTest.Display shown = displays.get(i);
                WindowSteps.ride(test, minecraft, ticks.get(i));
                if (!shown.drawnAs().equals(WindowLayer.held()) && !Compats.held().contains(shown.drawnAs())) {
                    problems.add(shown.label() + ": the recording holds " + WindowLayer.held() + " and " + Compats.held() + " at tick " + ticks.get(i) + ", not " + shown.drawnAs());
                    continue;
                }
                if (!drawn(test, shown.drawnAs())) {
                    problems.add(shown.label() + ": it was not drawn riding along in first person (last drawn: " + WindowLayer.lastDrawn() + ", " + Compats.lastDrawn() + ")");
                }
            }

            // Not drawn in third person, nor with the camera flying free: the same playback, the camera switched
            if (!displays.isEmpty()) {
                WindowSteps.ride(test, minecraft, ticks.get(0));
                check(drawn(test, displays.get(0).drawnAs()), displays.get(0).label() + " was not drawn before the camera was switched");
                test.onClientRun(() -> minecraft.options.setCameraType(CameraType.THIRD_PERSON_BACK));
                if (!notDrawn(test)) {
                    problems.add("something was still drawn in third person");
                }
                test.onClientRun(() -> {
                    minecraft.options.setCameraType(CameraType.FIRST_PERSON);
                    Viewer.ride(null);
                });
                if (!notDrawn(test)) {
                    problems.add("something was still drawn with the camera flying free");
                }
            }
        } finally {
            test.onClientRun(() -> minecraft.options.setCameraType(CameraType.FIRST_PERSON));
            PlaybackSteps.close(test);
        }
        check(problems.isEmpty(), modName + ": " + String.join("; ", problems));
        test.log(modName + ": " + displays.size() + " displays recorded and drawn riding along in first person, none in third person or flying free");
    }

    /** What the compat's display test may do in the live game */
    private static DisplayTest.Stage stage(SelfTest test) {
        return new DisplayTest.Stage() {
            @Override
            public void command(String command) throws Exception {
                test.command(command);
            }

            @Override
            public void onServer(Consumer<ServerPlayer> action) throws Exception {
                WindowSteps.onServer(test, action);
            }

            @Override
            public void onClient(Runnable action) throws Exception {
                test.onClientRun(action);
            }

            @Override
            public void waitTicks(int ticks) throws Exception {
                test.waitTicks(ticks);
            }
        };
    }

    /** Whether the window layer or a compat's layer drew this, by its name, in the frames just drawn */
    private static boolean drawn(SelfTest test, String name) throws Exception {
        long from = Drawn.frames();
        WindowSteps.frames(test, 4);
        return name.equals(WindowLayer.lastDrawn()) && WindowLayer.lastDrawnFrame() > from || Compats.lastDrawnFrame(name) > from;
    }

    /** Whether neither layer drew anything in the frames just drawn, once the camera has had a frame to change */
    private static boolean notDrawn(SelfTest test) throws Exception {
        WindowSteps.frames(test, 2);
        long from = Drawn.frames();
        WindowSteps.frames(test, 4);
        return WindowLayer.lastDrawnFrame() <= from && Compats.lastDrawnFrame() <= from;
    }

}
