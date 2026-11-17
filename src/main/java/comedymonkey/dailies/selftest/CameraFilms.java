package comedymonkey.dailies.selftest;

import com.google.gson.JsonObject;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.edit.Interpolation;
import comedymonkey.dailies.edit.Keyframe;
import comedymonkey.dailies.editor.EditorSession;
import comedymonkey.dailies.export.Codecs;
import comedymonkey.dailies.export.Encoder;
import comedymonkey.dailies.export.Export;
import comedymonkey.dailies.filming.FilmFrame;
import comedymonkey.dailies.filming.FilmMeasures;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.playback.Viewer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static comedymonkey.dailies.selftest.SelfTest.args;
import static comedymonkey.dailies.selftest.SelfTest.check;

/**
 * Films every tracking camera in every place of the camera route and measures each film (specification FILM-3 to FILM-9).
 * Each film is a scene of its own with one camera on the recorder for the place's stretch, exported at 1920x1080 and
 * 60 frames a second to dailies/camera-films/&lt;camera&gt;/&lt;place&gt;.mp4, with its frames written beside it as a CSV and
 * three stills for a person to look at. Only hard faults fail the step, and every one is reported, not just the first.
 */
final class CameraFilms {

    /** Every camera that films a player by itself, by the name its folder has */
    static final List<String> CAMERAS = List.of("chase", "orbit", "tripod", "director", "follow-keyframe", "watch-keyframe", "orbit-keyframe", "panel-follow");
    private static final double FPS = 60.0;
    private static final double EYE_HEIGHT = 1.62;

    private CameraFilms() {
    }

    /**
     * @param quick ten seconds a film instead of sixty, for checking the harness itself
     */
    static void run(SelfTest test, boolean quick) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        int seconds = quick ? 10 : 60;
        if (!Encoder.bundled()) {
            test.skip("this build has no video encoder packed in: the camera films were not made (FILM-3)");
            return;
        }
        CameraRoute.Route route = CameraRoute.obtain(test);
        Path films = Host.get().dataDirectory().resolve("camera-films");
        Files.createDirectories(films);
        // The editor keeps its scenes in the recording it edits: a copy is edited, so the fixture stays as it was recorded
        Path working = films.resolve("route-working" + comedymonkey.dailies.file.RecordingFile.EXTENSION);
        Files.copy(route.file(), working, StandardCopyOption.REPLACE_EXISTING);

        if (Playback.active()) {
            test.tool("replay_close", new JsonObject());
            test.waitFor(() -> !Playback.active() && minecraft.level == null, 60, "the recording before the camera films to close");
        } else if (minecraft.level != null) {
            WorldSteps.leaveWorld(test);
        }
        test.tool("replay_open", args("path", working.toString()));
        test.waitFor(() -> Playback.loaded() && !Playback.jumping() && minecraft.level != null && minecraft.player != null, 300, "the camera route to open");
        test.waitFor(EditorSession::open, 60, "the editor to take the camera route");
        UUID recorder = UUID.fromString(Playback.manifest().recorderId);

        Codecs.Pick pick = Codecs.pick(List.of("h264"), "mp4", Encoder.choices());
        check(pick.codec() != null, "this machine can encode no video for the camera films");
        if (pick.fellBack()) {
            test.log("camera films: this machine has no hardware H.264 encoder; the films are " + pick.codec() + " in " + pick.container());
        }

        List<String> failures = new ArrayList<>();
        List<String> table = new ArrayList<>();
        long began = System.currentTimeMillis();
        for (String camera : CAMERAS) {
            for (CameraRoute.Stretch stretch : route.stretches()) {
                try {
                    film(test, camera, stretch, seconds, recorder, pick, films, failures, table);
                } catch (Throwable t) {
                    Host.LOGGER.error("Dailies self test: the {} film of the {} could not be made", camera, stretch.place(), t);
                    failures.add(camera + " / " + stretch.place() + ": the film could not be made: " + t);
                    tidy(test);
                }
            }
        }
        for (String place : CameraRoute.PLACES) {
            if (route.stretches().stream().noneMatch(stretch -> stretch.place().equals(place))) {
                test.skip("the camera route has no " + place + " stretch: no camera was filmed there");
            }
        }

        test.log(String.format(Locale.ROOT, "camera films: %d films in %d s", table.size(), (System.currentTimeMillis() - began) / 1000));
        test.log("camera films: | " + FilmMeasures.summaryHeader());
        for (String row : table) {
            test.log("camera films: | " + row);
        }
        for (String failure : failures) {
            test.log("camera films: FAULT " + failure);
        }
        test.tool("replay_close", new JsonObject());
        test.waitFor(() -> !Playback.active(), 60, "the camera route to close");
        check(failures.isEmpty(), failures.size() + " hard faults in the camera films; the first: " + (failures.isEmpty() ? "" : failures.get(0)));
    }

    private static void film(SelfTest test, String camera, CameraRoute.Stretch stretch, int seconds, UUID recorder, Codecs.Pick pick, Path films,
                             List<String> failures, List<String> table) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        String place = stretch.place();
        int start = stretch.start();
        int end = Math.min(stretch.end(), Math.min(Playback.total(), start + seconds * 20));
        if (end - start < 40) {
            test.skip("the " + place + " stretch of the camera route is too short to film (" + (end - start) + " ticks)");
            return;
        }
        Path folder = films.resolve(camera);
        Files.createDirectories(folder);
        Path video = folder.resolve(place + "." + pick.container());
        Files.deleteIfExists(video);

        // A scene of its own, at the start of the stretch, with the viewer where a person setting up a shot would stand
        int scene = test.onClient(() -> EditorSession.history().addScene("Camera test: " + camera + " in the " + place));
        test.onClientRun(() -> {
            minecraft.setScreen(null);
            Playback.jumpTo(start, true);
        });
        test.waitFor(() -> !Playback.jumping() && Playback.position() == start, 120, "the jump to the start of the " + place + " stretch");
        test.sleepTicks(10);
        boolean placed = test.onClient(() -> placeViewer(minecraft, recorder));
        if (!placed) {
            test.log("camera films: the recorder is not in the world at the start of the " + place + " stretch; the viewer was left where it was");
        }

        boolean smart = List.of("chase", "orbit", "tripod", "director").contains(camera);
        if (smart) {
            test.tool("smart_camera_add", args("tick", start, "mode", camera));
        } else {
            String refused = test.onClient(() -> addCamera(minecraft, camera, recorder, start));
            if (refused != null) {
                failures.add(camera + " / " + place + ": " + refused);
                tidy(test, scene);
                return;
            }
        }

        CameraMetrics.begin(recorder, smart, FPS);
        long started = System.currentTimeMillis();
        test.tool("export_start", args("output_path", video.toString(), "container", pick.container(), "video_codec", pick.codec(), "width", 1920, "height", 1080,
            "framerate", FPS, "start_tick", start, "end_tick", end, "no_gui", true, "overwrite", true, "bitrate", "20m"));
        int expected = (int) Math.round((end - start) / 20.0 * FPS);
        test.waitFor(() -> !Export.active(), Math.max(600, expected / 4), "the " + camera + " film of the " + place + " to export");
        List<FilmFrame> frames = CameraMetrics.end();
        JsonObject done = test.tool("export_status", new JsonObject());
        test.waitFor(() -> !Playback.jumping(), 60, "playback to be put back after the film");
        long took = System.currentTimeMillis() - started;
        tidy(test, scene);
        if (done.has("last_error")) {
            failures.add(camera + " / " + place + ": the export failed: " + done.get("last_error"));
            return;
        }

        Files.writeString(folder.resolve(place + ".csv"), FilmMeasures.csv(frames), StandardCharsets.UTF_8);
        FilmMeasures.Analysis analysis = FilmMeasures.analyse(frames, FPS, camera.equals("director"));
        test.log(String.format(Locale.ROOT, "camera films: %s in the %s, ticks %d to %d, %d frames noted of %d, exported in %.0f s: %s",
            camera, place, start, end, frames.size(), expected, took / 1000.0, FilmMeasures.describe(analysis)));
        table.add(FilmMeasures.summaryRow(camera, place, analysis));
        if (frames.size() < expected * 0.95) {
            failures.add(camera + " / " + place + ": only " + frames.size() + " of " + expected + " frames were noted, so the measures are incomplete");
        }
        for (String fault : analysis.faults) {
            failures.add(camera + " / " + place + ": " + fault);
        }
        if (camera.equals("orbit-keyframe") || camera.equals("panel-follow")) {
            double moved = cameraTravel(frames);
            test.log(String.format(Locale.ROOT, "camera films: the %s camera moved %.1f blocks in all through the %s%s", camera, moved, place,
                moved < 0.5 ? ": it did not track the moving player in this film" : ""));
        }
        stills(test, video, (end - start) / 20.0, place);
    }

    /** Takes the scene the film was made in away again, and anything the film left on the viewer */
    private static void tidy(SelfTest test, int scene) throws Exception {
        test.onClientRun(() -> {
            Viewer.follow(null);
            var history = EditorSession.history();
            if (scene >= 0 && scene < history.state().scenes().size() && history.state().scenes().size() > 1) {
                history.removeScene(scene);
            }
        });
    }

    private static void tidy(SelfTest test) {
        try {
            if (Export.active()) {
                test.onClientRun(Export::cancel);
                test.waitFor(() -> !Export.active(), 120, "the failed film's export to stop");
            }
            CameraMetrics.end();
            test.onClientRun(() -> Viewer.follow(null));
        } catch (Throwable ignored) {
            // The next film starts afresh from its own scene
        }
    }

    /**
     * Puts the viewer a little behind the player and above, looking at them, or the first place round them that is open
     * and from which they can be seen: where a tripod or a watching camera stands when it is made, as the editor makes
     * them from the view. Client thread
     */
    private static boolean placeViewer(Minecraft minecraft, UUID recorder) {
        Entity subject = minecraft.level.getEntities().get(recorder);
        if (subject == null || subject == minecraft.player) {
            return false;
        }
        Vec3 eyes = subject.getEyePosition();
        float facing = subject.getYRot();
        for (double distance : new double[]{6.0, 4.5, 3.0}) {
            for (float around : new float[]{180, 135, -135, 90, -90, 45, -45, 0}) {
                Vec3 back = Vec3.directionFromRotation(0.0F, facing + around).scale(distance);
                Vec3 camera = eyes.add(back.x, 2.0, back.z);
                BlockPos at = BlockPos.containing(camera);
                if (!minecraft.level.getBlockState(at).getCollisionShape(minecraft.level, at).isEmpty()) {
                    continue;
                }
                HitResult hit = minecraft.level.clip(new ClipContext(camera, eyes, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()));
                if (hit.getType() != HitResult.Type.MISS && hit.getLocation().distanceTo(eyes) > 0.3) {
                    continue;
                }
                Vec3 look = eyes.subtract(camera);
                float yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
                float pitch = (float) Math.toDegrees(-Math.atan2(look.y, Math.hypot(look.x, look.z)));
                Viewer.set(camera.x, camera.y - EYE_HEIGHT, camera.z, yaw, pitch);
                return true;
            }
        }
        Vec3 back = Vec3.directionFromRotation(0.0F, facing + 180.0F).scale(6.0);
        Viewer.set(eyes.x + back.x, eyes.y + 2.0 - EYE_HEIGHT, eyes.z + back.z, facing, 15.0F);
        return true;
    }

    /**
     * The keyframe cameras and the players panel's follow, each with what the editor gives it by default when made
     * from the view. Client thread
     *
     * @return why it could not be made, or null
     */
    private static String addCamera(Minecraft minecraft, String camera, UUID recorder, int tick) {
        Entity subject = minecraft.level.getEntities().get(recorder);
        if (subject == null) {
            return "the recorder is not in the world at tick " + tick + ", so no " + camera + " camera could be set on them";
        }
        var history = EditorSession.history();
        Vec3 eyes = subject.position().add(0.0, subject.getEyeHeight(), 0.0);
        switch (camera) {
            case "follow-keyframe" -> {
                // Keeps its place: the offset from the eyes to where the viewer stands, as the editor takes it
                Vec3 offset = Viewer.position().subtract(eyes);
                history.setKeyframe(new Keyframe.FollowEntity(tick, Interpolation.LINEAR, recorder, subject.getId(), Keyframe.BodyPart.EYES,
                    round(offset.x), round(offset.y), round(offset.z), Keyframe.FollowMode.KEEP_RELATIVE));
            }
            case "watch-keyframe" -> history.setKeyframe(new Keyframe.FollowEntity(tick, Interpolation.LINEAR, recorder, subject.getId(), Keyframe.BodyPart.EYES,
                0.0, 0.0, 0.0, Keyframe.FollowMode.WATCH_FROM_FIXED));
            case "orbit-keyframe" -> {
                // Centred on the player, who it follows as they move: the centre numbers are then an offset from their middle
                Vec3 centre = subject.position().add(0.0, subject.getBbHeight() / 2.0, 0.0);
                Vec3 feet = Viewer.position();
                double dx = feet.x - centre.x;
                double dz = feet.z - centre.z;
                double distance = Math.min(1000.0, Math.sqrt(dx * dx + dz * dz));
                double angle = distance < 1.0e-6 ? Viewer.yaw() : Math.toDegrees(Math.atan2(dx, -dz));
                history.setKeyframe(new Keyframe.Orbit(tick, Interpolation.LINEAR, 0.0, 0.0, 0.0, round(distance),
                    round(Math.max(-1000.0, Math.min(1000.0, feet.y - centre.y))), round(angle), recorder, subject.getId()));
            }
            case "panel-follow" -> Viewer.follow(recorder);
            default -> {
                return "no such camera as " + camera;
            }
        }
        EditorSession.setFollowCamera(true);
        return null;
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static double cameraTravel(List<FilmFrame> frames) {
        double moved = 0.0;
        for (int i = 1; i < frames.size(); i++) {
            FilmFrame a = frames.get(i - 1);
            FilmFrame b = frames.get(i);
            moved += Math.sqrt((b.camX - a.camX) * (b.camX - a.camX) + (b.camY - a.camY) * (b.camY - a.camY) + (b.camZ - a.camZ) * (b.camZ - a.camZ));
        }
        return moved;
    }

    /** Three pictures out of the film, a sixth, a half and five sixths of the way through: at 10, 30 and 50 seconds of a minute */
    private static void stills(SelfTest test, Path video, double length, String place) throws Exception {
        Path program = Encoder.programPath();
        for (double share : new double[]{1.0 / 6.0, 0.5, 5.0 / 6.0}) {
            double at = length * share;
            Path still = video.resolveSibling(String.format(Locale.ROOT, "%s-%02ds.jpg", place, Math.round(at)));
            Files.deleteIfExists(still);
            Process process = new ProcessBuilder(program.toString(), "-v", "error", "-y", "-ss", String.format(Locale.ROOT, "%.2f", at), "-i", video.toString(),
                "-frames:v", "1", "-q:v", "3", still.toString()).redirectErrorStream(true).start();
            String said = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(60, TimeUnit.SECONDS) || !Files.exists(still)) {
                process.destroyForcibly();
                test.log("camera films: no still could be taken from " + video + " at " + at + " s: " + said.trim());
            }
        }
    }

}
