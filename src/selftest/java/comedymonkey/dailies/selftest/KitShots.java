package comedymonkey.dailies.selftest;

import com.mojang.blaze3d.platform.InputConstants;
import com.google.gson.JsonObject;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.config.DailiesConfig;
import comedymonkey.dailies.config.Setting;
import comedymonkey.dailies.export.Export;
import comedymonkey.dailies.extras.ModDifferences;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.ui.Controls;
import comedymonkey.dailies.ui.Notices;
import comedymonkey.dailies.ui.Previews;
import comedymonkey.dailies.ui.RecordingsScreen;
import comedymonkey.dailies.ui.SettingsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static comedymonkey.dailies.selftest.SelfTest.args;

/**
 * Takes pictures of every Dailies screen from the plain game, for judging the look of the interface: each screen once
 * at 1920 by 1080 and scale 2, and the busiest ones again at each of three window sizes and three interface scales.
 * Switched on with the system property dailies.test.script=kit. A stop that fails is logged and skipped.
 */
final class KitShots {

    private static final int[][] SIZES = {{1280, 720}, {1920, 1080}, {2560, 1440}};

    private KitShots() {
    }

    private interface Stop {
        void run() throws Exception;
    }

    private static void attempt(String what, Stop stop) {
        try {
            stop.run();
        } catch (Throwable t) {
            Host.LOGGER.warn("Dailies kit pictures: {} could not be pictured", what, t);
        }
    }

    /** Sets the size of the window and the interface scale, and waits for the game to have taken both in */
    private static void size(SelfTest test, int width, int height, int scale) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        test.onClientRun(() -> {
            org.lwjgl.sdl.SDLVideo.SDL_SetWindowSize(minecraft.getWindow().handle(), width, height);
            minecraft.options.guiScale().set(scale);
        });
        test.waitTicks(8);
        test.onClientRun(minecraft::resizeDisplay);
        test.waitTicks(8);
    }

    private static Set<String> groups() {
        Set<String> groups = new LinkedHashSet<>();
        for (Setting<?> setting : DailiesConfig.all()) {
            groups.add(setting.group());
        }
        return groups;
    }

    private static SettingsScreen openSettings(SelfTest test, boolean firstUnfolded) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        test.onClientRun(() -> {
            Screen behind = minecraft.gui.screen() instanceof SettingsScreen ? null : minecraft.gui.screen();
            SettingsScreen.open(behind);
            SettingsScreen screen = (SettingsScreen) minecraft.gui.screen();
            boolean first = firstUnfolded;
            for (String group : groups()) {
                screen.fold(group, !first);
                first = false;
            }
            screen.search("");
        });
        test.waitTicks(12);
        return (SettingsScreen) minecraft.gui.screen();
    }

    /**
     * The settings screen at three stops: its groups folded, a search under way, and a dropdown open. Also used for
     * the manual's pictures.
     *
     * @param prefix the start of the pictures' names
     */
    static void settings(SelfTest test, String prefix) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        Screen behind = minecraft.gui.screen();
        SettingsScreen screen = openSettings(test, false);
        ManualShots.shot(test, prefix + "-settings");
        test.onClientRun(() -> screen.search("marker colour"));
        test.waitTicks(6);
        ManualShots.shot(test, prefix + "b-settings-search");
        test.onClientRun(() -> {
            screen.search("");
            for (String group : groups()) {
                screen.fold(group, !group.equals("editor") && !group.equals("export"));
            }
        });
        test.waitTicks(8);
        test.onClientRun(() -> {
            screen.reveal("defaultInterpolation");
            screen.choose("defaultInterpolation", null);
        });
        test.waitTicks(8);
        ManualShots.shot(test, prefix + "c-settings-dropdown");
        test.onClientRun(() -> {
            Previews.closeMenu();
            minecraft.gui.setScreen(behind);
        });
        test.waitTicks(4);
    }

    static void run(SelfTest test) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        ManualShots.folder = Host.get().dataDirectory().resolve("kit");
        Files.createDirectories(ManualShots.folder);
        JsonObject none = new JsonObject();

        boolean focused = Boolean.getBoolean("dailies.kit.scales");
        size(test, 1920, 1080, 2);
        if (!focused) {
        attempt("the title screen", () -> ManualShots.shot(test, "01-title"));
        attempt("the settings screen", () -> settingsShots(test));
        attempt("the settings screen at every size", () -> {
            for (int[] size : SIZES) {
                for (int scale = 1; scale <= 3; scale++) {
                    size(test, size[0], size[1], scale);
                    SettingsScreen screen = openSettings(test, true);
                    test.onClientRun(() -> {
                        screen.reveal("controlsLocation");
                        screen.choose("controlsLocation", null);
                    });
                    test.waitTicks(8);
                    ManualShots.shot(test, "20-settings-" + size[0] + "x" + size[1] + "-scale" + scale);
                    test.onClientRun(() -> {
                        Previews.closeMenu();
                        minecraft.gui.setScreen(null);
                    });
                }
            }
            test.log("kit: the window is " + minecraft.getWindow().getWidth() + " by " + minecraft.getWindow().getHeight() + " after asking for the largest size");
        });
        size(test, 1920, 1080, 2);
        attempt("the recovery offer", () -> {
            test.onClientRun(() -> minecraft.gui.setScreen(Previews.recovery(minecraft.gui.screen())));
            test.waitTicks(8);
            ManualShots.shot(test, "30-recovery");
            test.onClientRun(() -> minecraft.gui.setScreen(null));
        });
        }

        WorldSteps.createWorld(test);
        test.command("time set 1000");
        test.command("fill -3 -61 8 9 -61 14 minecraft:smooth_quartz");
        test.command("setblock 6 -60 10 minecraft:campfire");
        test.command("tp @s 0.5 -60 0.5 -20 -25");
        test.waitTicks(20);
        test.tool("recording_start", none);
        for (int step = 0; step < 60; step++) {
            double angle = step / 60.0 * Math.PI * 2.0;
            test.command(String.format(java.util.Locale.ROOT, "tp @s %.3f -60 %.3f %.1f -20", 6.5 + Math.sin(angle) * 5.0, 10.5 - Math.cos(angle) * 5.0, Math.toDegrees(angle) - 90.0));
            if (step == 30) {
                test.tool("recording_add_marker", args("color", "#33AAFF", "description", "Half way round"));
            }
            test.waitTicks(1);
        }
        test.waitTicks(10);
        // Looking at the sky: the brightest thing a panel has to be read against
        attempt("the recording plate and a notice", () -> {
            test.onClientRun(() -> Notices.show(Component.literal("Recording paused")));
            test.waitTicks(8);
            ManualShots.shot(test, "31-recording-notice");
        });
        attempt("the pause menu", () -> {
            test.onClientRun(() -> minecraft.pauseGame(false));
            test.waitTicks(10);
            ManualShots.shot(test, "31b-pause-menu");
            test.onClientRun(() -> minecraft.gui.setScreen(Previews.ask()));
            test.waitTicks(8);
            ManualShots.shot(test, "31c-ask");
            test.onClientRun(() -> minecraft.gui.setScreen(null));
        });
        attempt("the name prompt", () -> {
            test.onClientRun(() -> minecraft.gui.setScreen(Previews.name()));
            test.waitTicks(10);
            ManualShots.shot(test, "32-name");
            test.onClientRun(() -> minecraft.gui.setScreen(null));
        });
        JsonObject saved = test.tool("recording_finish", args("name", "A walk round the fire"));
        test.waitTicks(10);
        // A few more, so that the list is a list
        for (String name : List.of("The long tunnel under the mountain, take three, with the minecart that would not stop", "Dragon fight")) {
            test.tool("recording_start", none);
            test.waitTicks(25);
            test.tool("recording_finish", args("name", name));
            test.waitTicks(10);
        }
        attempt("the recordings list", () -> {
            test.onClientRun(() -> minecraft.gui.setScreen(new RecordingsScreen(null)));
            test.waitTicks(40);
            test.onClientRun(() -> minecraft.gui.screen().mouseClicked(minecraft.gui.screen().width / 2.0, 70.0, 0));
            test.waitTicks(4);
            ManualShots.shot(test, "33-recordings");
            if (focused) {
                for (int scale : SCALES_1080) {
                    size(test, 1920, 1080, scale);
                    ManualShots.shot(test, "s-load-1080-" + scaleName(scale));
                }
            } else
            for (int[] size : SIZES) {
                for (int scale = 1; scale <= 3; scale += 2) {
                    size(test, size[0], size[1], scale);
                    ManualShots.shot(test, "34-recordings-" + size[0] + "x" + size[1] + "-scale" + scale);
                }
            }
            size(test, 1920, 1080, 2);
            test.onClientRun(() -> minecraft.gui.setScreen(null));
        });

        test.tool("replay_open", args("path", saved.get("path").getAsString()));
        test.waitFor(() -> Playback.loaded() || Playback.lastError() != null, 90, "the recording to open");
        test.waitTicks(20);
        int total = Playback.total();
        PlaybackSteps.jump(test, total / 3);
        test.tool("camera_set", args("x", 6.5, "y", -57.0, "z", 2.0, "yaw", 0.0, "pitch", -30.0));
        attempt("the playback bar", () -> {
            test.onClientRun(() -> {
                if (minecraft.gui.screen() != null) {
                    minecraft.gui.setScreen(null);
                }
                Notices.show(Component.literal("Camera: free"));
            });
            test.waitTicks(10);
            ManualShots.shot(test, "40-playback-bar");
            if (focused) {
                for (int scale : SCALES_1080) {
                    size(test, 1920, 1080, scale);
                    ManualShots.shot(test, "s-player-1080-" + scaleName(scale));
                }
                size(test, 1920, 1080, 2);
            }
        });
        if (focused) {
            attempt("the editor at every scale", () -> editorScales(test, total));
            PlaybackSteps.close(test);
            test.log("kit: pictures are in " + ManualShots.folder);
            return;
        }
        attempt("the mod differences", () -> {
            ModDifferences.Differences differences = new ModDifferences.Differences(
                List.of(new ModDifferences.Mod("create", "6.0.10"), new ModDifferences.Mod("sable", "1.4.2")),
                List.of(new ModDifferences.Mod("sodium", "0.6.13")),
                List.of(new ModDifferences.Changed("voicechat", "2.6.0", "2.6.4"), new ModDifferences.Changed("a_mod_with_a_very_long_identifier_indeed_that_goes_on", "1.21.1-12.0.0-beta.4+build.77", "1.21.1-12.1.0+build.91")));
            test.onClientRun(() -> {
                try {
                    var maker = Class.forName("comedymonkey.dailies.extras.ModDifferencesScreen").getDeclaredConstructor(ModDifferences.Differences.class);
                    maker.setAccessible(true);
                    minecraft.gui.setScreen((Screen) maker.newInstance(differences));
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            });
            test.waitTicks(8);
            ManualShots.shot(test, "41-mod-differences");
            test.onClientRun(() -> minecraft.gui.setScreen(null));
        });
        attempt("the editor", () -> editorShots(test, total));
        attempt("the export progress", () -> {
            editor(test, 2);
            Path frames = Host.get().dataDirectory().resolve("kit").resolve("frames");
            test.tool("export_start", args("output_path", frames.toString(), "container", "png_sequence", "width", 1920, "height", 1080, "framerate", 60,
                "start_tick", 0, "end_tick", total, "no_gui", true, "overwrite", true));
            Thread.sleep(1500);
            ManualShots.shot(test, "60-export-progress");
            test.tool("export_cancel", none);
            test.waitFor(() -> !Export.active(), 120, "the export to stop");
        });
        test.onClientRun(() -> {
            if (minecraft.gui.screen() != null) {
                minecraft.gui.setScreen(null);
            }
        });
        PlaybackSteps.close(test);
        test.log("kit: pictures are in " + ManualShots.folder);
    }

    /** Interface scales at 1920 by 1080: 0 is Auto, which the game turns into 4 there */
    private static final int[] SCALES_1080 = {0, 1, 2, 3, 4};

    private static String scaleName(int scale) {
        return scale == 0 ? "auto" : "scale" + scale;
    }

    /** Every editor tab, with and without its dropdown open, at each interface scale at 1920 by 1080 */
    private static void editorScales(SelfTest test, int total) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        int a = total / 3;
        test.tool("keyframe_add_camera", args("tick", a, "x", 0.5, "y", -55.0, "z", 2.0, "yaw", -40.0, "pitch", -25.0));
        test.tool("keyframe_add_camera", args("tick", a + 20, "x", 13.0, "y", -55.0, "z", 2.0, "yaw", 40.0, "pitch", -25.0));
        test.tool("scene_set_export_range", args("start_tick", a, "end_tick", a + 20));
        test.tool("camera_set", args("x", 6.5, "y", -57.0, "z", 2.0, "yaw", 0.0, "pitch", -30.0));
        String[] tabs = {"keyframe", "looks", "export"};
        boolean first = true;
        for (int scale : SCALES_1080) {
            for (int tab = 0; tab < 3; tab++) {
                test.onClientRun(() -> minecraft.gui.setScreen(null));
                size(test, 1920, 1080, scale);
                editor(test, tab);
                if (tab == 2 && first) {
                    // The tab first asks the encoder what this machine can do, which takes a few seconds
                    test.waitTicks(100);
                    first = false;
                }
                ManualShots.shot(test, "s-editor-" + tabs[tab] + "-1080-" + scaleName(scale));
                int which = tab;
                boolean opened = test.onClient(() -> Previews.editorDropdown(which == 1 ? 1 : 0));
                test.waitTicks(8);
                if (opened) {
                    ManualShots.shot(test, "s-editor-" + tabs[tab] + "-dropdown-1080-" + scaleName(scale));
                }
                test.onClientRun(Previews::closeMenu);
            }
            test.onClientRun(() -> Previews.exportMore(true));
            test.waitTicks(10);
            ManualShots.shot(test, "s-editor-export-more-1080-" + scaleName(scale));
            test.onClientRun(() -> Previews.exportMore(false));
        }
        test.onClientRun(() -> minecraft.gui.setScreen(null));
        size(test, 1920, 1080, 2);
    }

    /** The settings screen: its first group, a setting under the mouse, a colour dropdown, an address tested and the Tab ring */
    private static void settingsShots(SelfTest test) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        settings(test, "10");
        SettingsScreen screen = openSettings(test, true);
        ManualShots.shot(test, "10d-settings-first-group");
        // The mouse on a setting: its row lights and its description shows
        test.onClientRun(() -> {
            int[] place = screen.where("snapshotSeconds");
            Previews.pointAt(screen, place[0] + 40, place[1] + 6);
        });
        test.waitTicks(4);
        ManualShots.shot(test, "10e-settings-hover");
        // A colour dropdown, and an address that is written wrong
        test.onClientRun(() -> {
            Previews.pointAt(screen, -1, -1);
            screen.reveal("marker1Colour");
            screen.choose("marker1Colour", null);
        });
        test.waitTicks(8);
        ManualShots.shot(test, "10f-settings-colours");
        test.onClientRun(() -> {
            Previews.closeMenu();
            for (String group : groups()) {
                screen.fold(group, !group.equals("moments"));
            }
            screen.reveal("momentsOllamaUrl");
            screen.test("momentsOllamaUrl", "http://127.0.0.1:9/");
        });
        test.waitTicks(60);
        ManualShots.shot(test, "10g-settings-address-test");
        test.onClientRun(() -> screen.test("momentsWhisperUrl", "192.168.1.5:8000"));
        test.waitTicks(4);
        ManualShots.shot(test, "10h-settings-address-wrong");
        // The Tab key walks the controls: the ring shows where the keys are
        test.onClientRun(() -> {
            screen.abandon();
            for (String group : groups()) {
                screen.fold(group, !group.equals("recording"));
            }
            screen.reveal("autoStart");
            for (int press = 0; press < 7; press++) {
                screen.keyPressed(InputConstants.KEY_TAB, 0, 0);
            }
        });
        test.waitTicks(6);
        ManualShots.shot(test, "10i-settings-focus");
        test.onClientRun(() -> {
            screen.abandon();
            minecraft.gui.setScreen(null);
        });
    }

    /** The editor with a short camera path: each tab and its dropdown, the export tab's extras, and the export tab at every size */
    private static void editorShots(SelfTest test, int total) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        int a = total / 3;
        test.tool("keyframe_add_camera", args("tick", a, "x", 0.5, "y", -55.0, "z", 2.0, "yaw", -40.0, "pitch", -25.0));
        test.tool("keyframe_add_camera", args("tick", a + 20, "x", 13.0, "y", -55.0, "z", 2.0, "yaw", 40.0, "pitch", -25.0));
        test.tool("scene_set_export_range", args("start_tick", a, "end_tick", a + 20));
        test.tool("camera_set", args("x", 6.5, "y", -57.0, "z", 2.0, "yaw", 0.0, "pitch", -30.0));
        String[] tabs = {"keyframe", "looks", "export"};
        for (int tab = 0; tab < 3; tab++) {
            editor(test, tab);
            if (tab == 2) {
                // The tab first asks the encoder what this machine can do, which takes a few seconds
                test.waitTicks(100);
            }
            ManualShots.shot(test, "50-editor-" + tabs[tab]);
            int which = tab;
            boolean opened = test.onClient(() -> Previews.editorDropdown(which == 1 ? 1 : 0));
            test.waitTicks(8);
            if (opened) {
                ManualShots.shot(test, "51-editor-" + tabs[tab] + "-dropdown");
            }
            test.onClientRun(Previews::closeMenu);
        }
        test.onClientRun(() -> Previews.exportMore(true));
        test.waitTicks(10);
        ManualShots.shot(test, "50b-editor-export-more");
        test.onClientRun(() -> Previews.exportMore(false));
        for (int[] size : SIZES) {
            for (int scale = 1; scale <= 3; scale++) {
                test.onClientRun(() -> minecraft.gui.setScreen(null));
                size(test, size[0], size[1], scale);
                editor(test, 2);
                test.onClientRun(() -> Previews.editorDropdown(0));
                test.waitTicks(8);
                ManualShots.shot(test, "52-editor-" + size[0] + "x" + size[1] + "-scale" + scale);
                test.onClientRun(Previews::closeMenu);
            }
        }
        test.onClientRun(() -> minecraft.gui.setScreen(null));
        size(test, 1920, 1080, 2);
    }

    private static void editor(SelfTest test, int tab) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        test.onClientRun(() -> {
            if (minecraft.gui.screen() != null) {
                minecraft.gui.setScreen(null);
            }
            Previews.editorTab(tab);
            Controls.openTimeline();
        });
        test.waitTicks(20);
    }

}
