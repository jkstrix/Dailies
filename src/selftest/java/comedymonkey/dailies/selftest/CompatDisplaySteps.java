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
import comedymonkey.dailies.windows.vanilla.VanillaCompat;
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

    /**
     * Create's value settings board, a value box, the goggles, a sequenced gearshift's screen and a clipboard, through the
     * Create compat, a mod of its own (compats/create): -PdailiesOnly=create
     */
    static void create(SelfTest test) throws Exception {
        if (Host.get().isModLoaded("create") && !Host.get().isModLoaded("dailies_create")) {
            test.skip("the Dailies Create compat (dailies_create) is not installed: Create's screens in recordings were not tried");
            return;
        }
        run(test, "create", "Create", "dailies_create:create", "Dailies Create");
    }

    /**
     * Jade's box on a chest with items in it, a furnace smelting, a pig with its hearts and (with Create) a creative motor,
     * through the Jade compat, a mod of its own (compats/jade): -PdailiesOnly=jade
     */
    static void jade(SelfTest test) throws Exception {
        if (Host.get().isModLoaded("jade") && !Host.get().isModLoaded("dailies_jade")) {
            test.skip("the Dailies Jade compat (dailies_jade) is not installed: Jade's box in recordings was not tried");
            return;
        }
        run(test, "jade", "Jade", "dailies_jade:jade", "Dailies Jade");
    }

    /**
     * Create Aeronautics' Simulated screens (the altitude sensor, the modulating linked receiver, a nameplate, the linked
     * typewriter's window with its list of keys and a key's entry), the typewriter's binding, the levers' and the steering
     * wheel's readouts, Offroad's line above the hotbar and Simulated Coasters' rows of hints and track, through the Create
     * Aeronautics compat, a mod of its own (compats/aeronautics): -PdailiesOnly=aeronautics
     */
    static void aeronautics(SelfTest test) throws Exception {
        boolean installed = Host.get().isModLoaded("simulated") || Host.get().isModLoaded("simulatedcoasters");
        if (installed && !Host.get().isModLoaded("dailies_aeronautics")) {
            test.skip("the Dailies Create Aeronautics compat (dailies_aeronautics) is not installed: Create Aeronautics' screens in recordings were not tried");
            return;
        }
        run(test, installed ? null : "simulated", "Create Aeronautics", "dailies_aeronautics:aeronautics", "Dailies Create Aeronautics");
    }

    /**
     * Supplementaries' doormat, sign post, blackboard and speaker block screens, its present, trapped present, notice board,
     * pulley, sack, cannon and red merchant windows, the slime over the screen, a quiver's row, the cannon aimed and its keys
     * above the hotbar, a Stasis slingshot's far outline, and Amendments' book and quill on a lectern, through the
     * Supplementaries compat, a mod of its own (compats/supplementaries): -PdailiesOnly=supplementaries
     */
    static void supplementaries(SelfTest test) throws Exception {
        boolean installed = Host.get().isModLoaded("supplementaries") || Host.get().isModLoaded("amendments");
        if (installed && !Host.get().isModLoaded("dailies_supplementaries")) {
            test.skip("the Dailies Supplementaries compat (dailies_supplementaries) is not installed: Supplementaries' screens in recordings were not tried");
            return;
        }
        run(test, installed ? null : "supplementaries", "Supplementaries", "dailies_supplementaries:supplementaries", "Dailies Supplementaries");
    }

    /**
     * Create's other add-ons: Create Deep Seas' electrolyzer and boat engine windows, the sonar, the PDA's message and its
     * line above the hotbar, Create Connected's sequenced pulse generator, Create Encased's automatic clutch and recaser
     * wheel, Steam 'n' Rails' bogey menu and tag wheel, Create Jetpack's controls and Create: Hypertubes' placement outline,
     * through the compat for them, a mod of its own (compats/createextras): -PdailiesOnly=createextras
     */
    static void createExtras(SelfTest test) throws Exception {
        String[] mods = {"create_submarine", "create_connected", "createcasing", "tracks", "railways", "create_jetpack", "create_hypertube"};
        boolean installed = false;
        for (String mod : mods) {
            installed |= Host.get().isModLoaded(mod);
        }
        if (installed && !Host.get().isModLoaded("dailies_createextras")) {
            test.skip("the Dailies Create add-ons compat (dailies_createextras) is not installed: the add-ons' screens in recordings were not tried");
            return;
        }
        run(test, installed ? null : "create_submarine", "Create's other add-ons", "dailies_createextras:createextras", "Dailies Create Add-ons");
    }

    /**
     * The rest of the pack, through the extras compat, a mod of its own (compats/extras): Explorer's Compass and Nature's
     * Compass held, Emotecraft's emote wheel, Vista's signal projector, viewfinder and its line above the hotbar, a backpack
     * (and one with an upgrade's tab open), the Curios window, a corpse, a Pipez pipe's settings on two tabs and a filter,
     * and a nautilus' inventory: -PdailiesOnly=extras
     */
    static void extras(SelfTest test) throws Exception {
        boolean installed = java.util.stream.Stream.of("explorerscompass", "naturescompass", "vista", "emotecraft", "sophisticatedbackpacks", "curios", "corpse",
            "pipez", "vanillabackport").anyMatch(Host.get()::isModLoaded);
        if (installed && !Host.get().isModLoaded("dailies_extras")) {
            test.skip("the Dailies extras compat (dailies_extras) is not installed: the rest of the pack's screens in recordings were not tried");
            return;
        }
        run(test, installed ? null : "vista", "The rest of the pack", "dailies_extras:extras", "Dailies Extras");
    }

    /**
     * ComedyMonkey's Create Addons' windows (with its buttons put back), its machines' settings screens, the stations, the
     * jetpack's gauge, the villager goggles, the encased lines seen through goggles and the line container picking puts up,
     * through the compat in the mod itself: -PdailiesOnly=createaddons
     */
    static void createAddons(SelfTest test) throws Exception {
        run(test, "comedymonkeysaddons", "ComedyMonkey's Create Addons", "comedymonkeysaddons:screens", "Dailies Create Addons");
    }

    /**
     * McpCraft's job board on each tab, a confirm over it, a Job Storage, its two prompts and the track markers' toggle line,
     * through the compat in the mod itself: -PdailiesOnly=mcpcraft
     */
    static void mcpCraft(SelfTest test) throws Exception {
        run(test, "mcpcraft", "McpCraft", "mcpcraft:screens", "Dailies McpCraft");
    }

    /**
     * The game's own books, lectern and signs, through Dailies' own compat (REC-29): a written book read, a lectern's book,
     * a book and quill written in and signed, a sign and a hanging sign edited. Run by the windows group, after the
     * windows, needing no other group
     */
    static void vanilla(SelfTest test) throws Exception {
        run(test, null, "Books and signs", VanillaCompat.ID, "Dailies Books and Signs");
    }

    /** @param modId the mod the compat is for, skipped with a line when it is not installed; null for one of Dailies' own */
    static void run(SelfTest test, String modId, String modName, String compatId, String world) throws Exception {
        if (modId != null && !Host.get().isModLoaded(modId)) {
            test.skip(modName + " is not installed: its screens in recordings were not tried");
            return;
        }
        DisplayTest display = Compats.displayTest(compatId);
        if (display == null) {
            test.skip("no Dailies compat " + compatId + " with a display test is registered for this version of " + modName + " (compats registered: "
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
            // Closed as the player closes it: a question asked over another screen goes back to it, which is closed in turn
            for (int closes = 0; closes < 3 && test.onClient(() -> minecraft.screen != null); closes++) {
                test.onClientRun(() -> {
                    if (minecraft.screen != null) {
                        minecraft.screen.onClose();
                    }
                });
                test.waitTicks(2);
            }
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
                    // Which ticks around it do hold it, to tell a state never recorded from one a jump loses
                    StringBuilder seen = new StringBuilder();
                    for (int t = ticks.get(i) - 24; t <= ticks.get(i) + 44; t += 2) {
                        WindowSteps.ride(test, minecraft, t);
                        seen.append(t).append(shown.drawnAs().equals(WindowLayer.held()) || Compats.held().contains(shown.drawnAs()) ? "+ " : "- ");
                    }
                    test.log(shown.label() + ": held (+) or not (-) by tick around it: " + seen);
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
                // A command the game refuses is said in the log: a display set up by one (a block state misnamed) would
                // otherwise fail later for no reason the log shows
                if (!test.commandRan(command)) {
                    test.log("a display test's command did not run: " + command);
                }
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
