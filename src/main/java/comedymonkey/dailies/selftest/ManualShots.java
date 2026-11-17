package comedymonkey.dailies.selftest;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.export.Export;
import comedymonkey.dailies.mcp.McpHttpServer;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.ui.Controls;
import comedymonkey.dailies.ui.RecordingsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

import static comedymonkey.dailies.selftest.SelfTest.args;

/**
 * Takes the pictures for the manual from the running game: it walks through recording, the menus, playback, the
 * timeline, the Smart Camera and an export, saving a picture of the screen at each stop. Switched on with the system
 * property dailies.test.script=manual. A stop that fails is logged and skipped; the rest still run.
 */
final class ManualShots {

    private static Path folder;

    private ManualShots() {
    }

    static void run(SelfTest test) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        folder = Host.get().dataDirectory().resolve("manual");
        Files.createDirectories(folder);
        JsonObject none = new JsonObject();

        test.sleepTicks(40);
        shot(test, "01-title-screen");

        WorldSteps.createWorld(test);
        test.command("time set 1000");
        test.command("summon minecraft:pig 3 -60 6 {CustomName:'\"Hamlet\"',NoAI:1b}");
        test.command("fill -3 -61 8 9 -61 14 minecraft:smooth_stone");
        test.command("setblock 6 -60 10 minecraft:campfire");
        attempt(test, "building the machines", () -> CraftSteps.build(test));
        test.command("tp @s 0.5 -60 0.5 -20 8");
        test.sleepTicks(20);

        test.tool("recording_start", none);
        test.sleepTicks(12);
        shot(test, "02-recording");

        attempt(test, "the pause menu", () -> {
            test.onClientRun(() -> minecraft.pauseGame(false));
            test.sleepTicks(15);
            shot(test, "03-pause-menu");
            test.onClientRun(() -> minecraft.setScreen(null));
        });

        // Something worth watching: the recorder walks a loop round the campfire, with a marker half way
        for (int step = 0; step < 160; step++) {
            double angle = step / 160.0 * Math.PI * 2.0;
            double x = 6.5 + Math.sin(angle) * 5.0;
            double z = 10.5 - Math.cos(angle) * 5.0;
            test.command(String.format(java.util.Locale.ROOT, "tp @s %.3f -60 %.3f %.1f 5", x, z, Math.toDegrees(angle) - 90.0));
            if (step == 80) {
                test.tool("recording_add_marker", args("color", "#33AAFF", "description", "Half way round"));
            }
            Thread.sleep(45);
        }
        test.sleepTicks(20);

        attempt(test, "the name prompt", () -> {
            test.onClientRun(() -> Controls.finishRecording(null));
            test.sleepTicks(15);
            shot(test, "04-name-the-recording");
            test.onClientRun(() -> minecraft.setScreen(null));
        });
        JsonObject saved = test.tool("recording_finish", args("name", "A walk round the fire"));
        test.sleepTicks(20);

        attempt(test, "the recordings list", () -> {
            test.onClientRun(() -> minecraft.setScreen(new RecordingsScreen(null)));
            test.sleepTicks(50);
            shot(test, "05-recordings-list");
            test.onClientRun(() -> minecraft.setScreen(null));
        });

        test.tool("replay_open", args("path", saved.get("path").getAsString()));
        test.waitFor(() -> Playback.loaded() || Playback.lastError() != null, 90, "the recording to open");
        test.sleepTicks(20);
        int total = Playback.total();
        PlaybackSteps.jump(test, total / 3);
        test.tool("camera_set", args("x", 6.5, "y", -52.0, "z", -2.0, "yaw", 0.0, "pitch", 32.0));
        test.sleepTicks(30);
        shot(test, "06-playback");

        attempt(test, "the editor", () -> {
            // Two camera keyframes and a field of view keyframe, so the timeline has something on it
            int a = total / 3;
            test.tool("keyframe_add_camera", args("tick", a, "x", 0.5, "y", -55.0, "z", 2.0, "yaw", -40.0, "pitch", 25.0));
            test.tool("keyframe_add_camera", args("tick", a + 60, "x", 13.0, "y", -55.0, "z", 2.0, "yaw", 40.0, "pitch", 25.0));
            test.tool("scene_set_export_range", args("start_tick", a, "end_tick", a + 60));
            test.tool("camera_set", args("x", 6.5, "y", -52.0, "z", -2.0, "yaw", 0.0, "pitch", 32.0));
            editorTab(test, 0, false);
            shot(test, "07-editor-keyframes");
            editorTab(test, 0, true);
            shot(test, "07b-editor-keyframe-selected");
            editorTab(test, 1, false);
            shot(test, "07c-editor-looks");
            editorTab(test, 2, false);
            shot(test, "07d-editor-export");
            test.onClientRun(Controls::openTimeline);
            test.sleepTicks(5);
            test.onClientRun(() -> {
                if (minecraft.screen != null) {
                    minecraft.setScreen(null);
                }
            });
            // The export step's camera track would hold the view through the Smart Camera pictures that follow
            test.tool("track_update", args("track", 0, "enabled", false));
        });

        attempt(test, "the Smart Camera", () -> {
            int start = total / 3;
            test.tool("smart_camera_add", args("tick", start, "mode", "chase", "distance", 5, "height", 2, "side_angle", 30, "screen_x", -0.333, "transition_seconds", 0));
            test.tool("replay_play", none);
            test.sleepTicks(50);
            shot(test, "08-smart-camera-chase");
            test.tool("replay_pause", none);
            test.tool("smart_camera_add", args("tick", Playback.position(), "mode", "orbit", "radius", 7, "height", 3, "transition_seconds", 0));
            test.tool("replay_play", none);
            test.sleepTicks(40);
            shot(test, "09-smart-camera-orbit");
            test.tool("replay_pause", none);
        });

        attempt(test, "the moments page", () -> {
            Path edge = Path.of("C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe");
            if (!Files.exists(edge)) {
                edge = Path.of("C:/Program Files/Microsoft/Edge/Application/msedge.exe");
            }
            if (Files.exists(edge)) {
                Path picture = folder.resolve("10-moments-page.png");
                Files.deleteIfExists(picture);
                Process browser = new ProcessBuilder(edge.toString(), "--headless=new", "--disable-gpu", "--hide-scrollbars", "--window-size=1280,1500",
                    "--virtual-time-budget=6000", "--screenshot=" + picture, "http://127.0.0.1:" + McpHttpServer.getListeningPort() + "/").redirectErrorStream(true).start();
                browser.getInputStream().readAllBytes();
                browser.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
                test.log("manual: moments page picture " + (Files.exists(picture) ? "saved" : "was not produced"));
            } else {
                test.log("manual: no browser found for a picture of the moments page");
            }
        });

        attempt(test, "an exported still", () -> {
            Path still = folder.resolve("11-exported-still.png");
            Files.deleteIfExists(still);
            test.tool("export_screenshot", args("output_path", still.toString(), "width", 1920, "height", 1080, "no_gui", true, "overwrite", true));
            test.waitFor(() -> !Export.active(), 60, "the still");
            small(still, folder.resolve("11-exported-still.jpg"));
        });

        attempt(test, "the settings screen", () -> {
            test.onClientRun(() -> minecraft.setScreen(new net.neoforged.neoforge.client.gui.ConfigurationScreen(
                net.neoforged.fml.ModList.get().getModContainerById("dailies").orElseThrow(), new net.minecraft.client.gui.screens.TitleScreen())));
            test.sleepTicks(20);
            shot(test, "12-settings");
            test.onClientRun(() -> minecraft.setScreen(null));
        });

        PlaybackSteps.close(test);
        test.log("manual: pictures are in " + folder);
    }

    /** Opens the editor on one of its tabs (0 keyframe, 1 looks, 2 export), with the first keyframe selected when asked */
    private static void editorTab(SelfTest test, int tab, boolean select) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        test.onClientRun(() -> {
            try {
                if (minecraft.screen != null) {
                    minecraft.setScreen(null);
                }
                Class<?> state = Class.forName("comedymonkey.dailies.ui.EditorState");
                java.lang.reflect.Field field = state.getDeclaredField("tab");
                field.setAccessible(true);
                field.setInt(null, tab);
                java.lang.reflect.Field selection = state.getDeclaredField("selection");
                selection.setAccessible(true);
                @SuppressWarnings("unchecked")
                java.util.Set<Object> chosen = (java.util.Set<Object>) selection.get(null);
                chosen.clear();
                if (select) {
                    var track = comedymonkey.dailies.editor.EditorSession.scene().track(0);
                    chosen.add(new comedymonkey.dailies.edit.KeyframeRef(0, track.keyframes().get(0).tick()));
                }
                Controls.openTimeline();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
        test.sleepTicks(25);
    }

    private interface Stop {
        void run() throws Exception;
    }

    private static void attempt(SelfTest test, String what, Stop stop) {
        try {
            stop.run();
        } catch (Throwable t) {
            Host.LOGGER.warn("Dailies manual pictures: {} could not be pictured", what, t);
            try {
                test.onClientRun(() -> {
                    if (Minecraft.getInstance().screen != null && Minecraft.getInstance().level != null) {
                        Minecraft.getInstance().setScreen(null);
                    }
                });
            } catch (Exception ignored) {
                // Carry on with the next stop
            }
        }
    }

    /** Saves what is on screen now, as the full picture and as a smaller one for the manual */
    private static void shot(SelfTest test, String name) throws Exception {
        Path full = folder.resolve(name + ".png");
        test.onClientRun(() -> {
            try (NativeImage image = Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) {
                image.writeToFile(full);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        small(full, folder.resolve(name + ".jpg"));
        test.log("manual: saved " + name);
    }

    private static void small(Path png, Path jpg) throws Exception {
        BufferedImage source = ImageIO.read(png.toFile());
        int width = Math.min(1280, source.getWidth());
        int height = source.getHeight() * width / source.getWidth();
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.drawImage(source, 0, 0, width, height, null);
        graphics.dispose();
        ImageIO.write(scaled, "jpg", jpg.toFile());
    }

}
