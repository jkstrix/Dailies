package comedymonkey.dailies.selftest;

import com.google.gson.JsonObject;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.compat.create.FlywheelFrames;
import comedymonkey.dailies.compat.distanthorizons.DistantHorizonsCompat;
import comedymonkey.dailies.edit.EditHistory;
import comedymonkey.dailies.edit.Interpolation;
import comedymonkey.dailies.edit.Keyframe;
import comedymonkey.dailies.editor.EditorSession;
import comedymonkey.dailies.export.Export;
import comedymonkey.dailies.playback.Playback;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.function.IntSupplier;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

import static comedymonkey.dailies.selftest.SelfTest.args;
import static comedymonkey.dailies.selftest.SelfTest.check;

/**
 * Chapter XIV, "Other renderers" (chapter XVI's REN requirements and CMP-1): with the scripted recording open, that the
 * terrain an export waits for is reported by whichever renderer draws it, that Create's contraption moves in every
 * exported frame at an ordinary and at a fast export speed, that Iris's frame timer follows the export's clock, that
 * thrown-away frames are drawn, and, when asked for, the same with a shader pack in use and Distant Horizons'
 * far terrain kept apart. Each part is skipped, with a line saying why, when the mod it is about is not installed.
 * <p>
 * Run in the plain game (where nearly all of it is skipped) and in the modded game beside Sodium, Iris, Flywheel and
 * Veil. The shader pack part needs the setting testShaderPack naming a pack in the test game's shaderpacks folder.
 */
final class RendererSteps {

    private static final String IRIS = "net.irisshaders.iris.";

    private static boolean hooked = false;
    private static volatile boolean counting = false;
    private static volatile int drawn = 0;
    /** Iris's frame timer at each frame drawn while counting; NaN when Iris is absent */
    private static final List<Float> timer = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Iris's frame counter at each frame drawn while counting */
    private static final List<Integer> counter = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static IntSupplier irisCounter = null;
    private static Object irisTimer = null;
    private static Method irisTimerValue = null;

    private RendererSteps() {
    }

    static void run(SelfTest test) throws Exception {
        check(Playback.loaded(), "the renderer steps need the scripted recording open");
        boolean sodium = loaded("sodium") || loaded("embeddium");
        boolean iris = loaded("iris") || loaded("oculus");
        boolean create = loaded("create");
        boolean veil = loaded("veil");
        test.log("renderers here: " + (sodium ? "Sodium or Embeddium" : "the game's own terrain renderer") + (iris ? ", Iris or Oculus" : "")
            + (create ? ", Flywheel (with Create)" : "") + (veil ? ", Veil" : "") + (loaded("distanthorizons") ? ", Distant Horizons" : ""));
        hook(iris);

        terrain(test, sodium);
        contraption(test, create);
        irisClock(test, iris);
        throwaway(test, iris);
        shaderPack(test, iris);
        if (veil) {
            test.skip("Veil is installed: its render time follows the export's clock (REN-4), which only a person can judge, in a video of an Aeronautics effect");
        } else {
            test.skip("Veil is not installed: nothing of REN-4 for Veil to try");
        }
        distantHorizons(test);
    }

    private static boolean loaded(String mod) {
        return Host.get().isModLoaded(mod);
    }

    private static synchronized void hook(boolean iris) {
        if (hooked) {
            return;
        }
        hooked = true;
        if (iris) {
            try {
                Class<?> uniforms = Class.forName(IRIS + "uniforms.SystemTimeUniforms");
                irisTimer = uniforms.getField("TIMER").get(null);
                irisTimerValue = irisTimer.getClass().getMethod("getFrameTimeCounter");
                irisCounter = (IntSupplier) uniforms.getField("COUNTER").get(null);
            } catch (Throwable t) {
                Host.LOGGER.warn("Dailies self test: Iris's frame timer could not be found", t);
                irisTimer = null;
            }
        }
        // Only frames an export draws: those before it starts and after it ends keep the real clock, as they should
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Post event) -> {
            if (counting && Export.holdsClock()) {
                drawn++;
                if (irisTimer != null) {
                    timer.add(irisTimerNow());
                }
                if (irisCounter != null) {
                    counter.add(irisCounter.getAsInt());
                }
            }
        });
    }

    private static float irisTimerNow() {
        try {
            return (float) irisTimerValue.invoke(irisTimer);
        } catch (Throwable t) {
            return Float.NaN;
        }
    }

    // --- REN-1: the terrain an export waits for is reported by whichever renderer draws it

    private static void terrain(SelfTest test, boolean sodium) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        int far = RecordingSteps.notes.get(RecordingSteps.notes.size() - 1).tick();
        PlaybackSteps.jump(test, far);
        PlaybackSteps.jump(test, RecordingSteps.notes.get(0).tick());
        test.waitFor(() -> {
            try {
                return test.onClient(() -> minecraft.levelRenderer.hasRenderedAllSections() && minecraft.levelRenderer.countRenderedSections() > 0);
            } catch (Exception e) {
                return false;
            }
        }, 30, "the terrain renderer to say all is drawn after a jump");
        Path still = exports().resolve("renderers-terrain.png");
        Files.deleteIfExists(still);
        test.tool("export_screenshot", args("output_path", still.toString(), "width", 640, "height", 360, "overwrite", true));
        test.waitFor(() -> !Export.active(), 60, "the still after a jump");
        BufferedImage picture = ImageIO.read(still.toFile());
        check(picture != null && spread(picture) > 12.0, "a still taken straight after a jump is blank: the export did not wait for " + (sodium ? "Sodium's" : "the game's") + " terrain");
        test.log("after a jump " + (sodium ? "Sodium" : "the game's own renderer") + " reported its terrain drawn, and a still showed it");
    }

    // --- REN-2, REN-3: a contraption moves in every exported frame, at an ordinary and at a fast export speed

    private static void contraption(SelfTest test, boolean create) throws Exception {
        if (!create || !CraftSteps.builtContraption || CraftSteps.contraptionNotesFrom < 0) {
            test.skip(create ? "the bearing's contraption was not built: a contraption in every exported frame (REN-3) was not tried"
                : "Create is not installed: Flywheel's contraptions in exports (REN-2, REN-3) were not tried");
            return;
        }
        Boolean limiting = FlywheelFrames.limiting();
        if (limiting == null) {
            test.skip("Flywheel's update limit was not found: whether it skips far-off frames in playback was not checked (REN-2)");
        } else {
            check(!limiting, "Flywheel still skips far-off things on some frames while a recording is open (REN-2)");
        }
        int from = RecordingSteps.notes.get(CraftSteps.contraptionNotesFrom).tick();
        int to = from + 40;
        // Seventy blocks off, looking down on the arm so that nothing else in the picture moves, and zoomed in to see it
        // move a pixel or two a frame. At that distance Flywheel, left to itself, would move it on one frame in three
        double x = 14.5 - 50.0, y = -58.0 + 50.0, z = -5.5;
        EditHistory history = EditorSession.history();
        test.onClientRun(() -> {
            history.beginGroup("Renderer checks");
            history.addScene("Renderer checks");
            history.setKeyframe(new Keyframe.Camera(from, Interpolation.LINEAR, x, y, z, -90.0, 45.0, 0.0));
            history.setKeyframe(new Keyframe.Camera(to, Interpolation.LINEAR, x, y, z, -90.0, 45.0, 0.0));
            history.setKeyframe(new Keyframe.FieldOfView(from, Interpolation.LINEAR, 15.0));
            history.setKeyframe(new Keyframe.FieldOfView(to, Interpolation.LINEAR, 15.0));
            history.endGroup();
        });
        try {
            // An ordinary speed, sixty frames a second: three frames to a tick, each between two ticks
            List<Path> ordinary = pictures(test, "renderers-contraption", from, from + 20, 60);
            int still = stillPairs(ordinary);
            check(still == 0, still + " of " + (ordinary.size() - 1) + " neighbouring frames at sixty frames a second show the contraption standing still: it is not moved on every frame (REN-3)");
            // Four times as fast: each frame of a thirty-a-second video runs two or three ticks
            test.onClientRun(() -> history.setKeyframe(new Keyframe.Speed(from, Interpolation.LINEAR, 80.0)));
            try {
                List<Path> fast = pictures(test, "renderers-contraption-fast", from, to, 30);
                check(fast.size() >= 10, "forty ticks at 80 ticks a second and thirty frames a second gave " + fast.size() + " frames");
                int stillFast = stillPairs(fast);
                check(stillFast == 0, stillFast + " of " + (fast.size() - 1) + " neighbouring frames at four times speed show the contraption standing still (REN-3)");
                test.log("the contraption moved in all " + (ordinary.size() - 1) + " steps at sixty frames a second and all " + (fast.size() - 1) + " at four times speed");
            } finally {
                test.onClientRun(() -> check(history.undo() != null, "the speed keyframe could not be undone"));
            }
        } finally {
            test.onClientRun(() -> check(history.undo() != null, "the renderer checks' scene could not be undone"));
        }
    }

    /** How many neighbouring pictures are the same where it matters: fewer than a handful of pixels changed */
    private static int stillPairs(List<Path> pictures) throws Exception {
        int still = 0;
        BufferedImage before = ImageIO.read(pictures.get(0).toFile());
        for (int i = 1; i < pictures.size(); i++) {
            BufferedImage now = ImageIO.read(pictures.get(i).toFile());
            if (changedPixels(before, now) < 3) {
                still++;
            }
            before = now;
        }
        return still;
    }

    // --- REN-4: Iris's frame timer follows the export's clock, a frame's length per frame, and never runs back afterwards

    private static void irisClock(SelfTest test, boolean iris) throws Exception {
        if (!iris || irisTimer == null) {
            test.skip(iris ? "Iris's frame timer was not found: the export clock for shader packs (REN-4) was not checked" : "Iris is not installed: the export clock for shader packs (REN-4) was not tried");
            return;
        }
        int from = RecordingSteps.notes.get(RecordingSteps.notes.size() / 2).tick();
        timer.clear();
        counting = true;
        try {
            pictures(test, "renderers-iris-clock", from, from + 20, 30);
        } finally {
            counting = false;
        }
        TreeSet<Float> distinct = new TreeSet<>();
        for (float value : timer) {
            if (!Float.isNaN(value)) {
                distinct.add(value);
            }
        }
        check(distinct.size() >= 20, "Iris's frame timer took only " + distinct.size() + " values over an export of 30 frames");
        float previous = Float.NaN;
        double worst = 0;
        int steps = 0;
        for (float value : distinct) {
            // The timer goes round to zero every hour; a step across that is not a step of the export
            if (!Float.isNaN(previous) && value - previous < 1.0F) {
                worst = Math.max(worst, Math.abs((value - previous) - 1.0 / 30.0));
                steps++;
            }
            previous = value;
        }
        // Iris counts whole milliseconds, so a thirtieth of a second comes out as 33 or 34 ms
        check(worst <= 0.0015, String.format(Locale.ROOT, "Iris's frame timer moved by up to %.4f s more or less than a thirtieth of a second between exported frames: it follows the real clock, not the video (REN-4)", worst));
        float after = test.onClient(RendererSteps::irisTimerNow);
        test.sleepTicks(4);
        float later = test.onClient(RendererSteps::irisTimerNow);
        check(later >= after || after - later > 3000, "Iris's frame timer ran backwards after the export");
        test.log(String.format(Locale.ROOT, "Iris's frame timer stepped a thirtieth of a second (within %.4f s) on each of %d exported frames", worst, steps));
    }

    // --- REN-5: throwawayFrames draws its frames, and shader packs see them as frames

    private static void throwaway(SelfTest test, boolean iris) throws Exception {
        int from = RecordingSteps.notes.get(RecordingSteps.notes.size() / 2).tick();
        test.tool("config_set", args("key", "throwawayFrames", "value", 3));
        drawn = 0;
        counter.clear();
        counting = true;
        List<Path> frames;
        try {
            frames = pictures(test, "renderers-throwaway", from, from + 10, 20);
        } finally {
            counting = false;
            test.tool("config_set", args("key", "throwawayFrames", "value", 0));
        }
        check(frames.size() == 10, "ten ticks at twenty frames a second wrote " + frames.size() + " pictures, not 10");
        check(drawn >= 40, "with three frames thrown away before each of ten, only " + drawn + " frames were drawn (REN-5)");
        if (irisCounter != null) {
            // Read at every frame the export drew rather than once before and once after: Iris puts its counter back to
            // nought when it makes a pipeline for a new level, and playback changes the level on a jump back
            int counted = 0;
            int backwards = 0;
            for (int i = 1; i < counter.size(); i++) {
                int step = Math.floorMod(counter.get(i) - counter.get(i - 1), 720720);
                if (counter.get(i) < counter.get(i - 1) && counter.get(i - 1) - counter.get(i) < 360360) {
                    backwards++;
                } else {
                    counted += step;
                }
            }
            check(backwards == 0, "Iris's frame counter went back to nought " + backwards + " times during an export: shader packs that build their picture up over frames would start again (REN-5)");
            check(counted >= drawn - 1, "Iris counted " + counted + " frames between the first and last of " + drawn + " an export drew: shader packs would not see the thrown-away frames (REN-5)");
        }
        test.log("with throwawayFrames at 3, ten pictures took " + drawn + " frames" + (irisCounter != null ? ", all counted by Iris" : ""));
    }

    // --- REN-6: a shader pack in use, when one is set

    private static void shaderPack(SelfTest test, boolean iris) throws Exception {
        String pack = Host.get().setting("testShaderPack", "").trim();
        if (!iris) {
            test.skip("Iris is not installed: no shader pack was tried (REN-6)");
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (pack.isEmpty()) {
            // The pack the owner supplies for this, Complementary Shaders - Reimagined, is found by itself when it is the only one there
            pack = onlyComplementary(minecraft.gameDirectory.toPath().resolve("shaderpacks"));
            if (pack != null) {
                test.log("testShaderPack is empty; using the one Complementary pack in the shaderpacks folder, " + pack);
            }
        }
        if (pack == null || pack.isEmpty()) {
            test.skip("no shader pack was set (testShaderPack is empty, and there is no single Complementary pack in run-modded/shaderpacks): playback and export with a shader pack (REN-6) were not tried");
            return;
        }
        Path file = minecraft.gameDirectory.toPath().resolve("shaderpacks").resolve(pack);
        if (!Files.exists(file)) {
            test.skip("the shader pack " + pack + " named by testShaderPack is not in " + file.getParent() + ": REN-6 was not tried");
            return;
        }
        Object config;
        Class<?> irisClass;
        String oldPack;
        boolean oldEnabled;
        try {
            irisClass = Class.forName(IRIS + "Iris");
            config = irisClass.getMethod("getIrisConfig").invoke(null);
            Object name = config.getClass().getMethod("getShaderPackName").invoke(config);
            oldPack = name instanceof java.util.Optional<?> optional ? (String) optional.orElse(null) : null;
            oldEnabled = (boolean) config.getClass().getMethod("areShadersEnabled").invoke(config);
        } catch (Throwable t) {
            test.skip("Iris's settings could not be reached to choose a shader pack (" + t + "): REN-6 was not tried");
            return;
        }
        int from = RecordingSteps.notes.get(RecordingSteps.notes.size() / 2).tick();
        PlaybackSteps.jump(test, from);
        Path plain = exports().resolve("renderers-without-pack.png");
        Files.deleteIfExists(plain);
        test.tool("export_screenshot", args("output_path", plain.toString(), "width", 640, "height", 360, "overwrite", true));
        test.waitFor(() -> !Export.active(), 60, "the still without the shader pack");
        try {
            choosePack(test, irisClass, config, pack, true);
            boolean inUse = test.onClient(() -> {
                Object api = Class.forName(IRIS + "api.v0.IrisApi").getMethod("getInstance").invoke(null);
                return (boolean) api.getClass().getMethod("isShaderPackInUse").invoke(api);
            });
            check(inUse, "Iris does not report the shader pack " + pack + " in use after choosing it");

            Path packed = exports().resolve("renderers-with-pack.png");
            Files.deleteIfExists(packed);
            test.tool("export_screenshot", args("output_path", packed.toString(), "width", 640, "height", 360, "overwrite", true));
            test.waitFor(() -> !Export.active(), 120, "the still with the shader pack");
            BufferedImage with = ImageIO.read(packed.toFile());
            check(with != null && spread(with) > 12.0, "a still with the shader pack is blank");
            check(!dark(with), String.format(Locale.ROOT, "a still with the shader pack is black or nearly so (mean brightness %.1f of 255, %.0f%% of it near black)", brightness(with), 100.0 * nearBlack(with)));

            // Played, not only jumped to: forty ticks at the usual speed with the pack drawing every frame. The pictures
            // exported below come after the play, so they show whether it left the pack drawing properly
            String packName = pack;
            test.onClientRun(() -> Playback.playTo(from + 40));
            test.waitFor(() -> (Playback.paused() && Playback.position() >= from + 40) || Playback.lastError() != null, 60, "playback to play forty ticks with the shader pack");
            check(Playback.lastError() == null, "playback failed while playing with the shader pack " + packName + ": " + Playback.lastError());
            PlaybackSteps.jump(test, from);
            check(difference(with, ImageIO.read(plain.toFile())) > 2.0, "a still with the shader pack is the same as one without: the pack was not in the picture");

            // Pictures that build up over frames: thrown-away frames first, then the same frame twice comes out the same
            test.tool("config_set", args("key", "throwawayFrames", "value", 4));
            try {
                List<Path> first = pictures(test, "renderers-pack", from, from + 20, 30);
                check(first.size() == 30, "a second at thirty frames a second with the shader pack wrote " + first.size() + " pictures");
                for (Path frame : List.of(first.get(0), first.get(first.size() / 2), first.get(first.size() - 1))) {
                    BufferedImage picture = ImageIO.read(frame.toFile());
                    check(spread(picture) > 12.0 && !dark(picture), "an exported frame with the shader pack is blank or black: " + frame.getFileName());
                }
                List<Path> again = pictures(test, "renderers-pack-again", from, from + 8, 30);
                double drift = difference(ImageIO.read(first.get(5).toFile()), ImageIO.read(again.get(5).toFile()));
                // Shader packs jitter their sampling frame by frame, so a little more is allowed than without one
                check(drift < 6.0, "frame 5 with the shader pack came out differently the second time (difference " + drift + ")");
                test.log("with the shader pack " + pack + ": stills differ from the plain game, 30 frames exported, frame 5 repeats within " + String.format(Locale.ROOT, "%.1f", drift));
            } finally {
                test.tool("config_set", args("key", "throwawayFrames", "value", 0));
            }
        } finally {
            // Off again afterwards, whatever it was before: the rest of the run, and the owner's next game, draw without it
            choosePackBack(test, irisClass, config, oldPack == null ? pack : oldPack, false);
        }
        boolean stillInUse = test.onClient(() -> {
            Object api = Class.forName(IRIS + "api.v0.IrisApi").getMethod("getInstance").invoke(null);
            return (boolean) api.getClass().getMethod("isShaderPackInUse").invoke(api);
        });
        check(!stillInUse, "Iris still reports a shader pack in use after the shader pack steps switched it off");
    }

    /** The one shader pack in the folder whose name starts with Complementary, or null when there is none or more than one */
    private static String onlyComplementary(Path folder) {
        if (!Files.isDirectory(folder)) {
            return null;
        }
        try (Stream<Path> files = Files.list(folder)) {
            List<String> found = files.map(file -> file.getFileName().toString())
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith("complementary") && !name.toLowerCase(Locale.ROOT).endsWith(".txt"))
                .toList();
            return found.size() == 1 ? found.get(0) : null;
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /** Black, or so nearly black that a person would call it broken */
    private static boolean dark(BufferedImage image) {
        return brightness(image) < 12.0 || nearBlack(image) > 0.92;
    }

    private static double brightness(BufferedImage image) {
        double sum = 0;
        int count = 0;
        for (int y = 0; y < image.getHeight(); y += 4) {
            for (int x = 0; x < image.getWidth(); x += 4) {
                int rgb = image.getRGB(x, y);
                sum += ((rgb >> 16 & 0xFF) + (rgb >> 8 & 0xFF) + (rgb & 0xFF)) / 3.0;
                count++;
            }
        }
        return sum / count;
    }

    private static double nearBlack(BufferedImage image) {
        int dark = 0, count = 0;
        for (int y = 0; y < image.getHeight(); y += 4) {
            for (int x = 0; x < image.getWidth(); x += 4) {
                int rgb = image.getRGB(x, y);
                if ((rgb >> 16 & 0xFF) < 10 && (rgb >> 8 & 0xFF) < 10 && (rgb & 0xFF) < 10) {
                    dark++;
                }
                count++;
            }
        }
        return (double) dark / count;
    }

    /** Chooses a shader pack the way Iris's own screen does. Only ever in the test game's own folder */
    private static void choosePack(SelfTest test, Class<?> irisClass, Object config, String pack, boolean enabled) throws Exception {
        test.onClientRun(() -> {
            try {
                config.getClass().getMethod("setShaderPackName", String.class).invoke(config, pack);
                config.getClass().getMethod("setShadersEnabled", boolean.class).invoke(config, enabled);
                config.getClass().getMethod("save").invoke(config);
                irisClass.getMethod("reload").invoke(null);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Iris would not take the shader pack " + pack, e);
            }
        });
        // Iris compiles the pack's programs over the next frames
        test.sleepTicks(60);
    }

    private static void choosePackBack(SelfTest test, Class<?> irisClass, Object config, String pack, boolean enabled) throws Exception {
        choosePack(test, irisClass, config, pack, enabled);
        test.log("the shader pack setting was put back to " + (pack == null ? "none" : pack) + (enabled ? "" : ", shaders off"));
    }

    // --- CMP-1: Distant Horizons keeps the playback world apart and is handed its chunks

    private static void distantHorizons(SelfTest test) throws Exception {
        if (!DistantHorizonsCompat.installed()) {
            test.skip("Distant Horizons is not installed: CMP-1 was not tried (to try it, add DistantHorizons-* to dailies.testMods)");
            return;
        }
        check(DistantHorizonsCompat.working(), "Distant Horizons is installed but Dailies could not use its API (see the log)");
        String recording = Playback.recordingId();
        check(recording != null, "no recording id while a recording is open");
        Path own = DistantHorizonsCompat.folderOf(recording);
        test.waitFor(() -> {
            try {
                return test.onClient(DistantHorizonsCompat::saveFolder) != null;
            } catch (Exception e) {
                return false;
            }
        }, 30, "Distant Horizons to open the playback world");
        Path folder = test.onClient(DistantHorizonsCompat::saveFolder);
        check(folder.toAbsolutePath().normalize().startsWith(own.toAbsolutePath().normalize()),
            "Distant Horizons keeps the playback world in " + folder + ", not in the recording's own folder " + own + " (CMP-1)");
        int toldBefore = DistantHorizonsCompat.chunksTold();
        int far = RecordingSteps.notes.get(RecordingSteps.notes.size() - 1).tick();
        PlaybackSteps.jump(test, 0);
        PlaybackSteps.jump(test, far);
        test.waitFor(() -> DistantHorizonsCompat.chunksTold() > toldBefore, 30, "Distant Horizons to be handed the playback world's chunks");
        test.waitFor(() -> {
            try {
                return test.onClient(DistantHorizonsCompat::settled);
            } catch (Exception e) {
                return false;
            }
        }, 90, "Distant Horizons' far terrain to settle");
        Path still = exports().resolve("renderers-distant-horizons.png");
        Files.deleteIfExists(still);
        test.tool("export_screenshot", args("output_path", still.toString(), "width", 640, "height", 360, "overwrite", true));
        test.waitFor(() -> !Export.active(), 120, "the still with Distant Horizons");
        BufferedImage picture = ImageIO.read(still.toFile());
        check(picture != null && spread(picture) > 12.0, "a still with Distant Horizons is blank");
        test.log("Distant Horizons keeps the playback world in " + folder + ", was handed " + (DistantHorizonsCompat.chunksTold() - toldBefore) + " chunks after two jumps and settled");
    }

    // --- Helpers

    private static Path exports() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("dailies").resolve("exports");
    }

    /** Exports numbered pictures of a range at 640x360 and returns them in order */
    private static List<Path> pictures(SelfTest test, String name, int from, int to, int framesPerSecond) throws Exception {
        Path folder = exports().resolve(name);
        if (Files.isDirectory(folder)) {
            try (Stream<Path> files = Files.list(folder)) {
                for (Path file : files.toList()) {
                    Files.delete(file);
                }
            }
        }
        test.tool("export_start", args("output_path", folder.toString(), "container", "png_sequence", "width", 640, "height", 360, "framerate", framesPerSecond,
            "start_tick", from, "end_tick", to, "no_gui", true));
        test.waitFor(() -> !Export.active(), 300, "the " + name + " pictures to export");
        JsonObject done = test.tool("export_status", new JsonObject());
        check(!done.has("last_error"), "the " + name + " export failed: " + done);
        test.waitFor(() -> !Playback.jumping(), 30, "playback to be put back after the " + name + " export");
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".png")).sorted().toList();
        }
    }

    /** How many pixels differ by more than a little in any colour */
    private static int changedPixels(BufferedImage a, BufferedImage b) {
        int changed = 0;
        for (int y = 0; y < Math.min(a.getHeight(), b.getHeight()); y++) {
            for (int x = 0; x < Math.min(a.getWidth(), b.getWidth()); x++) {
                int p = a.getRGB(x, y);
                int q = b.getRGB(x, y);
                if (Math.abs((p >> 16 & 0xFF) - (q >> 16 & 0xFF)) > 6 || Math.abs((p >> 8 & 0xFF) - (q >> 8 & 0xFF)) > 6 || Math.abs((p & 0xFF) - (q & 0xFF)) > 6) {
                    changed++;
                }
            }
        }
        return changed;
    }

    /** How much the colours of a picture vary: near zero for a blank one */
    private static double spread(BufferedImage image) {
        double sum = 0, squares = 0;
        int count = 0;
        for (int y = 0; y < image.getHeight(); y += 4) {
            for (int x = 0; x < image.getWidth(); x += 4) {
                int rgb = image.getRGB(x, y);
                double light = ((rgb >> 16 & 0xFF) + (rgb >> 8 & 0xFF) + (rgb & 0xFF)) / 3.0;
                sum += light;
                squares += light * light;
                count++;
            }
        }
        double mean = sum / count;
        return Math.sqrt(Math.max(0.0, squares / count - mean * mean));
    }

    /** The average difference between two pictures, per colour, on a scale of 0 to 255 */
    private static double difference(BufferedImage a, BufferedImage b) {
        double sum = 0;
        int count = 0;
        for (int y = 0; y < Math.min(a.getHeight(), b.getHeight()); y += 2) {
            for (int x = 0; x < Math.min(a.getWidth(), b.getWidth()); x += 2) {
                int p = a.getRGB(x, y);
                int q = b.getRGB(x, y);
                sum += Math.abs((p >> 16 & 0xFF) - (q >> 16 & 0xFF)) + Math.abs((p >> 8 & 0xFF) - (q >> 8 & 0xFF)) + Math.abs((p & 0xFF) - (q & 0xFF));
                count += 3;
            }
        }
        return sum / count;
    }

}
