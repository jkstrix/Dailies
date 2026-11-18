package comedymonkey.dailies.selftest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.editor.EditorSession;
import comedymonkey.dailies.editor.PointOfView;
import comedymonkey.dailies.file.RecordingFile;
import comedymonkey.dailies.library.Library;
import comedymonkey.dailies.library.SharePack;
import comedymonkey.dailies.moments.Moments;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.playback.Viewer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import static comedymonkey.dailies.selftest.SelfTest.args;
import static comedymonkey.dailies.selftest.SelfTest.check;
import static comedymonkey.dailies.selftest.SelfTestSupport.deleteTree;
import static comedymonkey.dailies.selftest.SelfTestSupport.shorten;

/**
 * Craft moments (plan 2.1) and several points of view (plan 2.6: FILE-15, PLAY-18, PLAY-19, EDIT-11).
 * <p>
 * The test has one computer and one player, so the second player is made: the first recording is shared, the pack
 * opened, its recording given another id and recorder ("Alex") and its moments session made Alex's, and the pack
 * imported again as a friend's. The two then are recordings of the same world at the same time, which is all a second
 * player's would be.
 */
final class PointOfViewSteps {

    private static final String WORLD = "Dailies Points Of View";
    private static final String FRIEND = "Alex";

    private PointOfViewSteps() {
    }

    // ------------------------------------------------------------------ Craft moments (plan 2.1)

    /**
     * Before the balloon flies: the scripted world is the same folder on every run, and with it its personal bests, so
     * the last run's altitude record would stand in the way of this one's.
     */
    static void beforeCraft(SelfTest test) throws Exception {
        test.onClientRun(() -> {
            if (Moments.gameplay() != null) {
                Moments.gameplay().crafts().forgetRecordsForSelfTest();
            }
        });
    }

    /**
     * A command the player types, sent from their own game as typing it would send it. The balloon is assembled this way,
     * so that the game sees /sable assemble as it would in play (CraftRules: a craft that appears within five seconds
     * of it, within sixteen blocks, is the player's)
     */
    static void typed(SelfTest test, String command) throws Exception {
        test.onClientRun(() -> {
            Minecraft minecraft = Minecraft.getInstance();
            check(minecraft.player != null, "there is no player to type '" + command + "'");
            minecraft.player.connection.sendCommand(command);
        });
    }

    /** After the balloon flight in the recording steps: the game logged it, merged with the companion's report when there is one */
    static void craftMoments(SelfTest test) throws Exception {
        if (!Host.get().isModLoaded("sable") || !CraftSteps.builtBalloon) {
            test.skip("craft moments: no balloon was flown in this run (Sable is not installed, or the balloon was not built)");
            return;
        }
        String balloon = CraftSteps.balloon == null ? null : CraftSteps.balloon.toString();
        JsonArray assembled = events(test, "craft_assembled");
        JsonObject ours = null;
        int forBalloon = 0;
        for (JsonElement element : assembled) {
            JsonObject metadata = element.getAsJsonObject().getAsJsonObject("metadata");
            if (metadata != null && metadata.has("craft_id") && metadata.get("craft_id").getAsString().equals(balloon)) {
                ours = element.getAsJsonObject();
                forBalloon++;
            }
        }
        check(ours != null, "the balloon's assembly was not logged as craft_assembled: " + shorten(assembled.toString()));
        check(forBalloon == 1, "the balloon's assembly was logged " + forBalloon + " times, where both sources should make one event");
        String sources = ours.getAsJsonObject("metadata").get("sources").toString();
        check(sources.contains("game"), "the game did not see the balloon being assembled: sources " + sources);
        if (Host.get().isModLoaded("dailies_companion")) {
            check(sources.contains("server"), "the companion's report of the balloon was not merged into the game's: sources " + sources);
        } else {
            test.skip("craft moments: the companion is not installed in this game, so craft_assembled came from the game alone (both sources merged is unit tested)");
        }
        check(hasFor(events(test, "craft_first_flight"), balloon), "the balloon's first flight was not logged as craft_first_flight");
        check(events(test, "altitude_record").size() > 0, "climbing in the balloon did not log an altitude record");
        check(hasFor(events(test, "craft_boarded"), balloon), "standing in the balloon's basket was not logged as craft_boarded");
        test.log("craft moments: the balloon was assembled (sources " + sources + "), boarded, flew for the first time and set an altitude record");
    }

    private static JsonArray events(SelfTest test, String type) throws Exception {
        return test.tool("moments_events_query", args("type", type, "limit", 50)).getAsJsonArray("events");
    }

    private static boolean hasFor(JsonArray events, String craft) {
        for (JsonElement element : events) {
            JsonObject metadata = element.getAsJsonObject().getAsJsonObject("metadata");
            if (metadata != null && metadata.has("craft_id") && metadata.get("craft_id").getAsString().equals(craft)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ Several points of view (plan 2.6)

    static void run(SelfTest test) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        JsonObject none = new JsonObject();
        if (Playback.active()) {
            PlaybackSteps.close(test);
        } else if (minecraft.level != null) {
            WorldSteps.leaveWorld(test);
        }

        // --- My recording, with a moment in it ---
        WorldSteps.createWorld(test, WORLD);
        test.tool("recording_start", none);
        test.waitTicks(20);
        test.command("summon minecraft:cow 3 -60 3 {CustomName:'\"Witness\"',NoAI:1b}");
        test.waitTicks(40);
        JsonObject added = test.tool("moments_event_add", args("label", "Seen by two", "type", "point_of_view_test"));
        test.waitTicks(80);
        JsonObject finished = test.tool("recording_finish", args("name", "My view"));
        Path mine = Path.of(finished.get("path").getAsString());
        String session = added.get("session").getAsString();
        String mineId = added.getAsJsonObject("event").get("replay_uuid").getAsString();
        String myUuid = minecraft.player.getUUID().toString();
        String myName = minecraft.player.getGameProfile().getName();
        WorldSteps.leaveWorld(test);
        test.tool("moments_clips_generate", args("session", session, "min_importance", 0));

        // --- FILE-15: share it ---
        JsonObject shared = test.tool("replay_share", args("recording", mineId));
        Path pack = Path.of(shared.get("path").getAsString());
        check(Files.isRegularFile(pack) && pack.getFileName().toString().endsWith(SharePack.EXTENSION), "replay_share did not write a .dailiesshare file: " + shared);
        JsonObject info = shared.getAsJsonObject("info");
        check(info.get("recording_id").getAsString().equals(mineId), "the pack is not of the recording shared: " + info);
        check(info.getAsJsonArray("moments_sessions").toString().contains(session), "the pack does not hold the moments session the recording was made in: " + info);

        // --- The friend: the same footage under another id and recorder, with their own moments session ---
        Path work = Host.get().dataDirectory().resolve("point-of-view-test");
        deleteTree(work);
        SharePack.unpack(pack, work.resolve("unpacked"));
        String friendId = UUID.randomUUID().toString();
        String friendUuid = UUID.nameUUIDFromBytes(FRIEND.getBytes(StandardCharsets.UTF_8)).toString();
        try (RecordingFile copy = RecordingFile.openForEditing(work.resolve("unpacked").resolve(SharePack.RECORDING))) {
            copy.restamp(friendId, FRIEND, friendUuid);
        }
        Path sessions = work.resolve("unpacked").resolve("moments");
        List<SharePack.Item> items = new ArrayList<>();
        items.add(SharePack.Item.of(SharePack.RECORDING, work.resolve("unpacked").resolve(SharePack.RECORDING)));
        try (Stream<Path> walk = Files.walk(sessions)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                String name = "moments/" + sessions.relativize(file).toString().replace('\\', '/');
                if (name.endsWith(".json") || name.endsWith(".jsonl")) {
                    // Alex's moments name Alex and Alex's recording
                    String text = Files.readString(file, StandardCharsets.UTF_8).replace(mineId, friendId).replace(myUuid, friendUuid)
                        .replace("\"name\": \"" + myName + "\"", "\"name\": \"" + FRIEND + "\"").replace("\"name\":\"" + myName + "\"", "\"name\":\"" + FRIEND + "\"");
                    items.add(SharePack.Item.of(name, text.getBytes(StandardCharsets.UTF_8)));
                } else {
                    items.add(SharePack.Item.of(name, file));
                }
            }
        }
        JsonObject friendInfo = info.deepCopy();
        friendInfo.addProperty("recording_id", friendId);
        friendInfo.addProperty("recorder_name", FRIEND);
        friendInfo.addProperty("recorder_id", friendUuid);
        Path friendPack = work.resolve("Alex's evening" + SharePack.EXTENSION);
        SharePack.write(friendPack, friendInfo, items);

        // --- FILE-15: import it ---
        JsonObject imported = test.tool("replay_import", args("path", friendPack.toString()));
        Path theirs = Path.of(imported.get("path").getAsString());
        check(!imported.get("already_here").getAsBoolean() && imported.get("recording_id").getAsString().equals(friendId), "the friend's recording was not taken in: " + imported);
        check(theirs.startsWith(Library.index().folder().resolve("shared").resolve(FRIEND)), "the friend's recording is not under shared/" + FRIEND + ": " + theirs);
        check(imported.getAsJsonArray("moments_sessions").size() == 1, "the friend's moments session was not taken in: " + imported);
        String theirSession = imported.getAsJsonArray("moments_sessions").get(0).getAsString();
        check(!theirSession.equals(session), "the friend's session took the id of mine");
        JsonObject listed = null;
        for (JsonElement element : test.tool("moments_sessions_list", args("limit", 100)).getAsJsonArray("sessions")) {
            if (element.getAsJsonObject().get("id").getAsString().equals(theirSession)) {
                listed = element.getAsJsonObject();
            }
        }
        check(listed != null && listed.get("theirs").getAsBoolean() && listed.getAsJsonObject("shared_by").get("name").getAsString().equals(FRIEND),
            "the friend's session is not on the page marked as theirs: " + listed);

        // --- PLAY-18: linked by game time ---
        JsonObject linked = test.tool("replay_linked", args("recording", mineId));
        JsonObject link = null;
        for (JsonElement element : linked.getAsJsonArray("linked")) {
            if (element.getAsJsonObject().get("recording_id").getAsString().equals(friendId)) {
                link = element.getAsJsonObject();
            }
        }
        check(link != null, "the friend's recording of the same world at the same time is not linked to mine: " + shorten(linked.toString()));
        check(link.get("shared_ticks").getAsInt() > 100 && link.get("first_shared_tick").getAsInt() == link.get("first_shared_tick_there").getAsInt(),
            "the two recordings are not lined up tick for tick: " + link);

        // On the page: the clip lists the friend's recording with its own cue, and the strip has a row per person
        JsonObject clip = null;
        for (JsonElement element : test.tool("moments_clips_list", args("session", session)).getAsJsonArray("clips")) {
            if (element.getAsJsonObject().getAsJsonArray("points_of_view").toString().contains(friendId)) {
                clip = element.getAsJsonObject();
            }
        }
        check(clip != null, "no clip of my session lists the friend's recording as a point of view");
        JsonArray rows = test.tool("moments_everyone", args("session", session)).getAsJsonArray("rows");
        boolean friendRow = false;
        for (JsonElement row : rows) {
            JsonObject person = row.getAsJsonObject();
            friendRow |= person.get("theirs").getAsBoolean() && FRIEND.equals(person.getAsJsonObject("player").get("name").getAsString());
        }
        check(rows.size() >= 2 && friendRow, "the session strip does not have a row for the friend: " + shorten(rows.toString()));
        // A cue of the friend's point of view opens their recording at the clip
        JsonObject cued = test.tool("moments_cue", args("session", session, "clip_id", clip.get("id").getAsString(), "recording_id", friendId));
        check(cued.get("recording_id").getAsString().equals(friendId), "cueing the friend's point of view did not choose their recording: " + cued);
        test.waitFor(() -> Playback.loaded() && !Playback.jumping() && friendId.equals(Playback.recordingId()), 120, "the friend's recording to open at the clip");

        // --- Export: the same stretch from every point of view, as one batch ---
        test.tool("replay_open", args("path", mine.toString()));
        test.waitFor(() -> Playback.loaded() && !Playback.jumping() && mineId.equals(Playback.recordingId()), 120, "my recording to open");
        JsonObject batch = test.tool("export_points_of_view", args("start_tick", 20, "end_tick", 80));
        JsonArray jobs = batch.getAsJsonArray("jobs");
        check(jobs.size() == 2 && jobs.toString().contains(mineId) && jobs.toString().contains(friendId), "the batch is not the stretch from both points of view: " + jobs
            + "; " + whatIsLinked(test, mineId, friendId));
        for (JsonElement job : jobs) {
            check(job.getAsJsonObject().get("start_tick").getAsInt() == 20 && job.getAsJsonObject().get("end_tick").getAsInt() == 80,
                "a point of view's stretch is not lined up with mine: " + job);
        }
        // The batch is on the export queue, one point of view job each, in the recording's own ticks; it is not run here,
        // the owner having asked for one export of each kind (QueueSteps runs one), and is taken off again at once so the
        // steps after this one find the queue as it was
        check(batch.get("queued").getAsBoolean(), "export_points_of_view did not put the batch on the export queue: " + batch);
        List<String> queuedIds = new ArrayList<>();
        for (JsonElement job : jobs) {
            check(job.getAsJsonObject().has("job_id"), "a point of view in the batch has no job on the queue: " + job);
            queuedIds.add(job.getAsJsonObject().get("job_id").getAsString());
        }
        JsonArray onQueue = test.tool("export_queue_list", new JsonObject()).getAsJsonArray("jobs");
        for (String id : queuedIds) {
            JsonObject found = null;
            for (JsonElement entry : onQueue) {
                if (entry.getAsJsonObject().get("job_id").getAsString().equals(id)) {
                    found = entry.getAsJsonObject();
                }
            }
            check(found != null, "the job " + id + " of the batch is not on the export queue: " + shorten(onQueue.toString()));
            check(found.get("kind").getAsString().equals("point_of_view") && found.get("state").getAsString().equals("waiting")
                && found.get("start_tick").getAsInt() == 20 && found.get("end_tick").getAsInt() == 80,
                "a job of the batch is not a waiting point of view of ticks 20 to 80: " + found);
            test.tool("export_queue_remove", args("job_id", id));
        }
        JsonArray left = test.tool("export_queue_list", new JsonObject()).getAsJsonArray("jobs");
        for (String id : queuedIds) {
            check(!left.toString().contains(id), "the batch's job " + id + " is still on the export queue after it was removed");
        }

        // --- PLAY-19, EDIT-11: a scene that switches point of view by a cut ---
        JsonObject scene = test.tool("scene_link_recording", none);
        check(scene.getAsJsonArray("linked").toString().contains(friendId), "the friend's recording was not linked to the scene: " + scene);
        JsonObject toFriend = new JsonObject();
        toFriend.addProperty("recording_id", friendId);
        toFriend.addProperty("label", FRIEND);
        test.tool("keyframe_set", args("type", "point_of_view", "tick", 40, "keyframe", toFriend));
        JsonObject back = new JsonObject();
        back.addProperty("recording_id", mineId);
        test.tool("keyframe_set", args("type", "point_of_view", "tick", 110, "keyframe", back));
        test.tool("replay_seek", args("tick", 0));
        test.waitFor(() -> !Playback.jumping() && Playback.position() == 0, 60, "the jump to the start");
        test.tool("camera_set", args("x", 0.5, "y", -50.0, "z", 0.5, "yaw", 45.0, "pitch", 20.0));
        Vec3 camera = test.onClient(Viewer::position);
        test.tool("replay_play", none);
        test.waitFor(() -> friendId.equals(PointOfView.shownId()) && Playback.loaded() && !Playback.jumping(), 60, "the cut to the friend's point of view at tick 40");
        check(Objects.equals(Playback.recordingId(), friendId), "playback is not showing the friend's recording after the cut");
        int sceneTick = test.onClient(PointOfView::position);
        check(sceneTick >= 40 && sceneTick < 110, "after the cut the scene is at tick " + sceneTick + ", not just after the point of view keyframe at 40");
        Vec3 after = test.onClient(Viewer::position);
        check(after.distanceTo(camera) < 0.5, "the camera did not carry across the cut: it was at " + camera + " and is at " + after);
        check(test.onClient(() -> EditorSession.open() && EditorSession.scene().linked().size() == 1 && Objects.equals(PointOfView.homeId(), mineId)),
            "the scene was lost across the cut: the editor should still be editing my recording, with the friend's linked; " + whatIsLinked(test, mineId, friendId));
        test.waitFor(() -> mineId.equals(PointOfView.shownId()) && Playback.loaded() && !Playback.jumping(), 60, "the cut back to my recording at tick 110");
        test.tool("replay_pause", none);
        test.log("points of view: shared, imported as " + FRIEND + "'s, linked by game time, listed on the page, queued as a batch of points of view, and cut to and back in a scene");

        // Tidied away, so that steps after this one count the recordings they made
        PlaybackSteps.close(test);
        Library.delete(theirs);
        Library.delete(mine);
        deleteTree(work);
    }

    /** For a failure: what the library, the links, playback and the editor say of the two recordings at this moment */
    private static String whatIsLinked(SelfTest test, String mineId, String friendId) {
        StringBuilder said = new StringBuilder();
        try {
            Path friendFile = Library.index().findById(friendId);
            said.append("friend's file in the index ").append(friendFile);
            said.append(", described ").append(friendFile == null ? "-" : comedymonkey.dailies.library.Links.describe(friendFile) != null);
            said.append(", friend ").append(friendId);
            comedymonkey.dailies.library.Links.Link own = comedymonkey.dailies.library.Links.find(mineId);
            said.append(", linked to mine ").append(own == null ? "(mine not found)" : comedymonkey.dailies.library.Links.linked(own).stream()
                .map(link -> link.id() + " by " + link.manifest().recorderName + " sharing " + own.alignment().shared(link.alignment()).stream().map(java.util.Arrays::toString).toList()).toList());
            if (own != null && friendFile != null && comedymonkey.dailies.library.Links.describe(friendFile) != null) {
                comedymonkey.dailies.library.Links.Link friend = comedymonkey.dailies.library.Links.describe(friendFile);
                said.append(", mine and the friend's share ").append(own.alignment().shared(friend.alignment()).stream().map(java.util.Arrays::toString).toList())
                    .append(" (same stretch ").append(own.alignment().sameStretch(friend.alignment())).append(", recorders ").append(own.manifest().recorderId)
                    .append(" and ").append(friend.manifest().recorderId).append(", real ").append(own.manifest().realStart).append('-').append(own.manifest().realEnd)
                    .append(" and ").append(friend.manifest().realStart).append('-').append(friend.manifest().realEnd).append(')');
            }
            said.append(", points of view of 20-80 ").append(comedymonkey.dailies.library.Links.pointsOfView(mineId, 20, 80).stream().map(view -> view.recordingId() + " " + view.startTick() + "-" + view.endTick()).toList());
            said.append(", ").append(test.onClient(() -> "playback " + Playback.loaded() + " showing " + Playback.recordingId() + ", home " + PointOfView.homeId() + ", shown " + PointOfView.shownId()
                + ", editor " + EditorSession.open() + (EditorSession.open() ? " linked " + EditorSession.scene().linked() : "")));
        } catch (Throwable t) {
            said.append(" (and then ").append(t).append(')');
        }
        return said.toString();
    }

}
