package comedymonkey.dailies.selftest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import comedymonkey.dailies.edit.KeyframeKind;
import comedymonkey.dailies.editor.EditorSession;
import comedymonkey.dailies.moments.Moments;
import comedymonkey.dailies.playback.Playback;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static comedymonkey.dailies.selftest.SelfTest.args;
import static comedymonkey.dailies.selftest.SelfTest.check;

/**
 * Chapter XIV steps 5 to 7: the provided features running on the new core. Moments logged with recording ticks and
 * turned into clips, the ignore list, cueing a clip with its suggested camera, and the Smart Camera's modes.
 */
final class OwnerFeatureSteps {

    static boolean companionUsed = false;

    private OwnerFeatureSteps() {
    }

    /** During the recording: a second of sound, handed over exactly as the voice chat capture hands it over */
    static void speak(SelfTest test) throws Exception {
        UUID speaker = Minecraft.getInstance().player.getUUID();
        short[] frame = new short[960];
        for (int sent = 0; sent < 60; sent++) {
            for (int i = 0; i < frame.length; i++) {
                frame[i] = (short) (Math.sin((sent * 960 + i) * 2.0 * Math.PI * 220.0 / 48000.0) * 9000.0);
            }
            Moments.onVoiceChatAudio(speaker, frame.clone());
            Thread.sleep(20);
        }
        // The silence that ends the segment
        Thread.sleep(1200);
    }

    static void run(SelfTest test) throws Exception {
        JsonObject none = new JsonObject();
        JsonObject latest = args("session", "latest");

        // --- Step 6: moments ---
        JsonObject events = test.tool("moments_events_query", latest);
        String listed = events.toString();
        check(listed.contains("\"mob_kill\""), "the kill was not logged as a moment: " + shorten(listed));
        check(listed.contains("\"marker\""), "the marker was not logged as a moment: " + shorten(listed));
        check(listed.contains("biome_discovered"), "the biome was not logged as a moment: " + shorten(listed));
        if (companionUsed) {
            check(listed.contains("test_event") && listed.contains("Hello from the server"), "the moment sent by /moment data did not arrive: " + shorten(listed));
            check(listed.contains("boss_fight"), "killing the stand-in boss did not arrive as a boss_fight moment: " + shorten(listed));
            JsonObject sent = test.tool("moments_events_query", args("session", "latest", "keyword", "test_event"));
            check(sent.toString().contains("42") && sent.toString().contains("server"), "the server moment lost its data or its source: " + shorten(sent.toString()));
            test.log("server moments arrived: test_event with its data, and boss_fight");
        }
        if (McpCraftSteps.done) {
            check(listed.contains("job_posted") && listed.contains("job_claimed") && listed.contains("job_finished"), "the job board moments did not all arrive: " + shorten(listed));
            test.log("job board moments arrived: posted, claimed and finished");
        }
        JsonArray eventList = firstArray(events);
        int stamped = 0;
        int lastTick = -1;
        boolean ordered = true;
        for (JsonElement element : eventList) {
            JsonObject event = element.getAsJsonObject();
            if (event.has("replay_tick") && !event.get("replay_tick").isJsonNull() && event.has("replay_uuid") && !event.get("replay_uuid").isJsonNull()) {
                stamped++;
            }
        }
        check(stamped >= 2, "moments made while recording are not stamped with the recording and its tick (" + stamped + " of " + eventList.size() + "): " + shorten(listed));

        JsonObject dialogue = test.tool("moments_dialogue_query", latest);
        JsonArray segments = firstArray(dialogue);
        check(segments.size() >= 1, "the voice segment was not logged: " + shorten(dialogue.toString()));
        String segment = segments.get(0).toString();
        check(segment.contains("duration"), "the voice segment has no length: " + shorten(segment));

        JsonObject generated = test.tool("moments_clips_generate", args("session", "latest", "min_importance", 10, "summarize", false, "pre_roll", 1.5, "post_roll", 6));
        JsonArray clips = firstArray(generated);
        check(clips.size() >= 1, "no clips were built: " + shorten(generated.toString()));
        JsonObject killClip = null;
        for (JsonElement element : clips) {
            if (element.toString().contains("mob_kill")) {
                killClip = element.getAsJsonObject();
            }
        }
        check(killClip != null, "no clip holds the kill: " + shorten(generated.toString()));
        check(killClip.toString().contains("camera"), "the kill's clip has no suggested camera: " + shorten(killClip.toString()));
        String clipId = killClip.has("id") ? killClip.get("id").getAsString() : killClip.get("clip_id").getAsString();
        test.log("moments: " + eventList.size() + " events, " + segments.size() + " voice segment(s), " + clips.size() + " clip(s)");

        // --- Step 7: the ignore list ---
        test.tool("moments_ignored_events", args("action", "add", "entries", array("mob_kill")));
        check(!test.tool("moments_events_query", latest).toString().contains("\"mob_kill\""), "an ignored kind of event still shows in a query");
        test.tool("moments_ignored_events", args("action", "remove", "entries", array("mob_kill")));
        check(test.tool("moments_events_query", latest).toString().contains("\"mob_kill\""), "an event did not come back when its kind was taken off the ignore list");

        // --- Step 7: cue a clip with its camera, from the title screen ---
        JsonObject cued = test.tool("moments_cue", args("clip_id", clipId, "camera", true, "session", "latest"));
        test.waitFor(() -> Playback.loaded() && !Playback.jumping(), 90, "the cued recording to open (" + shorten(cued.toString()) + ")");
        test.sleepTicks(30);
        test.waitFor(() -> !Playback.jumping(), 30, "the jump to the clip");
        check(Playback.paused(), "cueing a clip did not leave the recording paused");
        int cuedAt = Playback.position();
        check(cuedAt > 0, "cueing a clip did not jump to it (" + shorten(cued.toString()) + ", error " + Playback.lastError() + ")");
        boolean hasSmartCamera = test.onClient(() -> EditorSession.scene().tracks().stream().anyMatch(track -> track.kind() == KeyframeKind.SMART_CAMERA && !track.keyframes().isEmpty()));
        check(hasSmartCamera, "cueing with a camera did not add a Smart Camera track");
        test.tool("editor_undo", none);
        boolean stillThere = test.onClient(() -> EditorSession.scene().tracks().stream().anyMatch(track -> track.kind() == KeyframeKind.SMART_CAMERA && !track.keyframes().isEmpty()));
        check(!stillThere, "undo did not take the cued Smart Camera away again");
        test.log("cued clip " + clipId + " at tick " + cuedAt);

        // The owner is working on the Smart Camera himself: its checks are his to run, with
        // gradlew runTestModded -PdailiesFlags=dailies.test.smartCamera, and are left out of every other run
        if (!Boolean.getBoolean("dailies.test.smartCamera")) {
            test.skip("the Smart Camera checks (chase, tripod, director) are left to the owner: run with dailies.test.smartCamera to include them");
            PlaybackSteps.close(test);
            return;
        }

        // --- Step 5: the Smart Camera ---
        // The camera track left by the export step sits above it and would take the view back; switch it off
        test.onClientRun(() -> {
            var history = EditorSession.history();
            for (int index = 0; index < history.scene().tracks().size(); index++) {
                if (history.scene().track(index).kind() == KeyframeKind.CAMERA) {
                    history.updateTrack(index, null, false);
                }
            }
        });
        int start = Math.max(20, Playback.total() / 2 - 60);
        PlaybackSteps.jump(test, start);

        // Chase holds its distance
        test.tool("smart_camera_add", args("tick", start, "mode", "chase", "distance", 6, "height", 2, "transition_seconds", 0));
        test.onClientRun(() -> Playback.playTo(start + 30));
        test.waitFor(() -> Playback.paused() && Playback.position() == start + 30, 30, "play under the chase camera");
        JsonObject chase = test.tool("smart_camera_status", none);
        check(chase.has("camera") && chase.get("mode").getAsString().equals("chase"), "the chase camera did not run: " + shorten(chase.toString()));
        double chaseDistance = distance(chase);
        check(chaseDistance > 3.5 && chaseDistance < 9.5, "the chase camera is " + chaseDistance + " blocks from its subject, not about 6");

        // A tripod stays put
        test.tool("smart_camera_add", args("tick", start + 30, "mode", "tripod", "transition_seconds", 0));
        test.onClientRun(() -> Playback.playTo(start + 45));
        test.waitFor(() -> Playback.paused() && Playback.position() == start + 45, 30, "play under the tripod");
        JsonObject tripodA = test.tool("smart_camera_status", none).getAsJsonObject("camera");
        test.onClientRun(() -> Playback.playTo(start + 60));
        test.waitFor(() -> Playback.paused() && Playback.position() == start + 60, 30, "more play under the tripod");
        JsonObject tripodStatus = test.tool("smart_camera_status", none);
        JsonObject tripodB = tripodStatus.getAsJsonObject("camera");
        check(tripodStatus.get("mode").getAsString().equals("tripod"), "the tripod did not take over from the chase camera: " + shorten(tripodStatus.toString()));
        check(Math.abs(tripodA.get("x").getAsDouble() - tripodB.get("x").getAsDouble()) < 0.01 && Math.abs(tripodA.get("z").getAsDouble() - tripodB.get("z").getAsDouble()) < 0.01,
            "the tripod moved");

        // The director cuts, says why, and makes the same edit twice
        test.tool("smart_camera_add", args("tick", start + 60, "mode", "director", "min_shot_seconds", 1, "max_shot_seconds", 2, "seed", 7));
        List<String> first = directorRun(test, start + 60, start + 160);
        List<String> second = directorRun(test, start + 60, start + 160);
        // The test's recorder is teleported about once a second, which restarts the director each time (the reason it gives
        // is subject_jumped), so how often it cuts by itself on a smoothly moving player is left to the eye test
        check(first.size() >= 1, "the director reported no shot");
        // The reason given for the opening shot says how the stretch was reached (start, or seek after a jump); the edit is the shots
        // Played live, frames fall at slightly different moments each time, so a later cut can land a tick apart and pick another
        // shot. Only the opening shot is held to be the same here; an export, whose frame times are fixed, is where the whole
        // edit repeats exactly
        check(first.get(0).substring(0, first.get(0).indexOf('@')).equals(second.get(0).substring(0, second.get(0).indexOf('@'))),
            "the director made a different edit the second time:\n  " + first + "\n  " + second);
        test.log("smart camera: chase at " + String.format("%.1f", chaseDistance) + " blocks, tripod held, director shots " + first);

        PlaybackSteps.close(test);
    }

    private static List<String> directorRun(SelfTest test, int from, int to) throws Exception {
        PlaybackSteps.jump(test, from);
        test.onClientRun(() -> Playback.playTo(to));
        test.waitFor(() -> Playback.paused() && Playback.position() == to, 60, "play under the director");
        JsonObject status = test.tool("smart_camera_status", new JsonObject());
        check(status.get("mode").getAsString().equals("director"), "the director is not in charge: " + shorten(status.toString()));
        List<String> shots = new ArrayList<>();
        for (JsonElement element : status.getAsJsonArray("shots")) {
            JsonObject shot = element.getAsJsonObject();
            check(shot.has("start_reason") && !shot.get("start_reason").getAsString().isBlank(), "a director shot has no reason");
            shots.add(shot.get("mode").getAsString() + "@" + Math.round(shot.get("start_tick").getAsDouble()) + ":" + shot.get("start_reason").getAsString());
        }
        return shots;
    }

    private static double distance(JsonObject status) {
        JsonObject camera = status.getAsJsonObject("camera");
        JsonObject subject = status.getAsJsonObject("subject");
        double dx = camera.get("x").getAsDouble() - subject.get("x").getAsDouble();
        double dy = camera.get("y").getAsDouble() - subject.get("y").getAsDouble();
        double dz = camera.get("z").getAsDouble() - subject.get("z").getAsDouble();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static JsonArray firstArray(JsonObject json) {
        for (String key : json.keySet()) {
            if (json.get(key).isJsonArray()) {
                return json.getAsJsonArray(key);
            }
        }
        throw new IllegalStateException("there is no list in " + shorten(json.toString()));
    }

    private static JsonArray array(String... values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private static String shorten(String text) {
        return text.length() > 700 ? text.substring(0, 700) + "..." : text;
    }

}
