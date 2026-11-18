package comedymonkey.dailies.selftest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.edit.Keyframe;
import comedymonkey.dailies.edit.KeyframeKind;
import comedymonkey.dailies.edit.Scene;
import comedymonkey.dailies.edit.Track;
import comedymonkey.dailies.editor.EditorSession;
import comedymonkey.dailies.file.RecordingFile;
import comedymonkey.dailies.file.RecordingManifest;
import comedymonkey.dailies.moments.MomentsTimeline;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.ui.MomentsLane;

import java.util.ArrayList;
import java.util.List;

import static comedymonkey.dailies.selftest.SelfTest.args;
import static comedymonkey.dailies.selftest.SelfTest.check;
import static comedymonkey.dailies.selftest.SelfTestSupport.array;

/**
 * Moments on the editor's timeline (EDIT-9) and scenes made from clips (plan 2.2): the scripted recording's kill,
 * its clip and its voice are on the timeline at their ticks, the switch hides them, a click on a band sets the export
 * range, moments_clip_to_scene makes a scene that one undo removes, and in a trimmed recording everything lands at
 * its new tick while what was cut is gone. The trimmed parts are called by the library steps, which make the trims.
 */
final class TimelineSteps {

    private TimelineSteps() {
    }

    /** With the scripted recording as it was made, before anything is trimmed */
    static void original(SelfTest test) throws Exception {
        JsonObject none = new JsonObject();
        RecordingManifest manifest = RecordingFile.readManifest(RecordingSteps.recording);
        String recordingId = manifest.id;

        JsonObject listed = test.tool("moments_events_query", args("session", "latest", "limit", 500));
        String session = listed.get("session").getAsString();
        JsonObject kill = null;
        int stamped = 0;
        for (JsonElement element : listed.getAsJsonArray("events")) {
            JsonObject event = element.getAsJsonObject();
            if (has(event, "replay_tick") && recordingId.equals(event.get("replay_uuid").getAsString())) {
                stamped++;
                if (event.get("type").getAsString().equals("mob_kill") && kill == null) {
                    kill = event;
                }
            }
        }
        check(kill != null, "there is no kill with a recording tick to look for on the timeline");
        int killTick = kill.get("replay_tick").getAsInt();
        String killId = kill.get("id").getAsString();
        JsonObject clip = killClip(test, session);
        String clipId = clip.get("id").getAsString();
        int clipStart = clip.get("start_replay_tick").getAsInt();
        int clipEnd = Math.min(manifest.totalTicks, clip.get("end_replay_tick").getAsInt());

        test.tool("replay_open", args("path", RecordingSteps.recording.toString()));
        test.waitFor(() -> Playback.lastError() != null || Playback.loaded() && !Playback.jumping() && recordingId.equals(Playback.recordingId()), 120, "the scripted recording to open");
        check(Playback.lastError() == null, "playback failed: " + Playback.lastError());

        // --- What the timeline holds ---
        MomentsTimeline.Timeline timeline = MomentsLane.reloadNow();
        MomentsTimeline.Mark killMark = mark(timeline, killId);
        check(killMark != null && killMark.tick() == killTick && killMark.kind().equals("mob_kill"), "the kill is not on the timeline at tick " + killTick + ": " + killMark);
        check(!killMark.description().isBlank() && killMark.score() > 0.0, "the kill's mark has no description or score: " + killMark);
        check(timeline.marks().size() == stamped, "the timeline holds " + timeline.marks().size() + " marks, the recording " + stamped + " moments");
        for (int i = 1; i < timeline.marks().size(); i++) {
            check(timeline.marks().get(i).tick() >= timeline.marks().get(i - 1).tick(), "the marks are not in the order of their ticks");
        }
        MomentsTimeline.Band band = band(timeline, clipId);
        check(band != null && band.start() == clipStart && band.end() == clipEnd && !band.cut(), "the kill's clip is not a band from tick " + clipStart + " to " + clipEnd + ": " + band);
        check(band.title().equals(clip.get("title").getAsString()), "the band is not named after its clip: " + band.title());
        // The scripted voice was fed in as the recorder's own
        String speaker = String.valueOf(manifest.recorderId);
        MomentsTimeline.Strip strip = null;
        for (MomentsTimeline.Strip candidate : timeline.strips()) {
            if (candidate.speakerId().equals(speaker)) {
                strip = candidate;
            }
        }
        check(strip != null && !strip.talks().isEmpty(), "there is no voice strip for the speaker " + speaker + ": " + timeline.strips());
        MomentsTimeline.Talk talk = strip.talks().get(0);
        check(talk.end() > talk.start() && talk.end() - talk.start() <= 200 && talk.start() >= 0 && talk.end() <= manifest.totalTicks, "the voice strip's stretch is not about a second inside the recording: " + talk);

        // Marks too close to tell apart are drawn as one, with a count; none is lost on the way
        List<Integer> squeezed = test.onClient(() -> MomentsLane.clusterSizes(30));
        List<Integer> roomy = test.onClient(() -> MomentsLane.clusterSizes(40_000));
        check(squeezed.stream().mapToInt(Integer::intValue).sum() == timeline.marks().size() && squeezed.size() < timeline.marks().size(),
            "on a timeline 30 pixels wide the " + timeline.marks().size() + " marks were not gathered: " + squeezed);
        check(roomy.size() >= squeezed.size() && roomy.stream().mapToInt(Integer::intValue).sum() == timeline.marks().size(), "zoomed far in, marks went missing: " + roomy);

        // --- The ignore list ---
        test.tool("moments_ignored_events", args("action", "add", "entries", array("mob_kill")));
        try {
            MomentsTimeline.Timeline without = MomentsLane.reloadNow();
            check(mark(without, killId) == null, "an ignored kind of event is still on the timeline");
            check(band(without, clipId) == null || without.bands().size() <= timeline.bands().size(), "ignoring a kind of event added bands");
        } finally {
            test.tool("moments_ignored_events", args("action", "remove", "entries", array("mob_kill")));
        }
        timeline = MomentsLane.reloadNow();
        check(mark(timeline, killId) != null, "the kill did not come back to the timeline when its kind was taken off the ignore list");

        // --- The switch ---
        check(test.onClient(MomentsLane::shown) && test.onClient(MomentsLane::drawnHeight) > 0, "the moments are not on the timeline to begin with");
        test.onClientRun(() -> MomentsLane.setShown(false));
        check(!test.onClient(MomentsLane::shown) && test.onClient(MomentsLane::drawnHeight) == 0, "switching the moments off did not take them off the timeline");
        test.waitFor(() -> !Host.get().setting("showMomentsOnTimeline", true), 10, "the switch to reach the settings");
        test.onClientRun(() -> MomentsLane.setShown(true));
        check(test.onClient(MomentsLane::drawnHeight) > 0, "switching the moments back on did not bring them back");
        test.waitFor(() -> Host.get().setting("showMomentsOnTimeline", false), 10, "the switch to reach the settings again");

        // --- A click on a band: the export range becomes the clip, as one step, and the recording goes to its start ---
        MomentsTimeline.Band chosen = band(timeline, clipId);
        boolean hadRange = test.onClient(() -> EditorSession.scene().hasExportRange());
        int oldStart = test.onClient(() -> EditorSession.scene().hasExportRange() ? EditorSession.scene().exportStart() : -1);
        test.onClientRun(() -> MomentsLane.chooseBand(chosen));
        check(test.onClient(() -> EditorSession.scene().hasExportRange() && EditorSession.scene().exportStart() == clipStart && EditorSession.scene().exportEnd() == clipEnd),
            "clicking the band did not set the export range to ticks " + clipStart + " to " + clipEnd);
        test.waitFor(() -> !Playback.jumping() && Playback.position() == clipStart, 60, "the jump to the start of the clip");
        test.tool("editor_undo", none);
        check(test.onClient(() -> EditorSession.scene().hasExportRange() == hadRange && (!hadRange || EditorSession.scene().exportStart() == oldStart)),
            "one undo did not put the export range back");

        // --- moments_clip_to_scene: a scene with the range and a Smart Camera, and one undo takes all of it away ---
        int scenesBefore = test.onClient(() -> EditorSession.history().state().scenes().size());
        String sceneBefore = test.onClient(() -> EditorSession.scene().name());
        String refused = test.toolError("moments_clip_to_scene", args("session", session, "clip_id", clipId, "colour", "red"));
        check(refused.contains("colour"), "moments_clip_to_scene did not refuse an unknown argument by name: " + refused);
        JsonObject made = test.tool("moments_clip_to_scene", args("session", session, "clip_id", clipId));
        check(made.get("start_tick").getAsInt() == clipStart && made.get("end_tick").getAsInt() == clipEnd && made.get("camera_added").getAsBoolean(),
            "moments_clip_to_scene did not say what it made: " + made);
        String title = clip.get("title").getAsString();
        String wantedName = title.length() > 60 ? title.substring(0, 60).trim() : title;
        test.onClientRun(() -> {
            Scene scene = EditorSession.scene();
            check(EditorSession.history().state().scenes().size() == scenesBefore + 1, "no scene was added");
            check(scene.name().equals(wantedName), "the new scene is called '" + scene.name() + "', not after its clip '" + wantedName + "'");
            check(scene.hasExportRange() && scene.exportStart() == clipStart && scene.exportEnd() == clipEnd, "the new scene's export range is not the clip");
            check(smartCameraAt(scene, clipStart) != null, "the new scene has no Smart Camera keyframe at tick " + clipStart);
        });
        test.waitFor(() -> !Playback.jumping() && Playback.position() == clipStart && Playback.paused(), 60, "the jump to the new scene's start");
        test.tool("editor_undo", none);
        test.onClientRun(() -> {
            check(EditorSession.history().state().scenes().size() == scenesBefore, "one undo did not take the scene made from a clip away");
            check(EditorSession.scene().name().equals(sceneBefore), "after the undo the current scene is '" + EditorSession.scene().name() + "', not '" + sceneBefore + "'");
        });

        test.log("timeline: " + timeline.marks().size() + " marks (the kill at tick " + killTick + "), " + timeline.bands().size() + " band(s), " + timeline.strips().size()
            + " voice strip(s); gathered into " + squeezed.size() + " at 30 pixels; switch, band click and scene from a clip each undone in one step");
        PlaybackSteps.close(test);
    }

    /**
     * With the recording trimmed to ticks keepFrom to keepTo of the original open: what was kept is on the timeline
     * at its new tick, and what was cut is not there.
     */
    static void trimmed(SelfTest test, String killId, int killTick, String cutEventId, String clipId, int clipStart, int keepFrom, int keepTo) throws Exception {
        MomentsTimeline.Timeline timeline = MomentsLane.reloadNow();
        MomentsTimeline.Mark kill = mark(timeline, killId);
        check(kill != null && kill.tick() == killTick - keepFrom, "in the trimmed recording the kill of tick " + killTick + " is not on the timeline at tick " + (killTick - keepFrom) + ": " + kill);
        check(mark(timeline, cutEventId) == null, "a moment that was cut from the recording is still on its timeline");
        for (MomentsTimeline.Mark mark : timeline.marks()) {
            check(mark.tick() >= 0 && mark.tick() <= keepTo - keepFrom, "a mark is outside the trimmed recording: " + mark);
        }
        MomentsTimeline.Band band = band(timeline, clipId);
        check(band != null && band.start() == clipStart - keepFrom && !band.cut(), "in the trimmed recording the clip's band does not start at tick " + (clipStart - keepFrom) + ": " + band);
        for (MomentsTimeline.Strip strip : timeline.strips()) {
            for (MomentsTimeline.Talk talk : strip.talks()) {
                check(talk.start() >= 0 && talk.end() <= keepTo - keepFrom, "a voice stretch is outside the trimmed recording: " + talk);
            }
        }
        test.log("timeline of the trimmed recording: the kill at tick " + kill.tick() + ", " + timeline.marks().size() + " marks, the cut moment absent");
    }

    /** With the recording that has a cut through the middle of the clip open: the band runs over what is left, and says so */
    static void cutThrough(SelfTest test, String clipId, int clipStart, int piecesEnd) throws Exception {
        MomentsTimeline.Timeline timeline = MomentsLane.reloadNow();
        MomentsTimeline.Band band = band(timeline, clipId);
        check(band != null && band.cut() && band.start() == clipStart && band.end() == piecesEnd, "a clip with a cut through it is not a band from tick " + clipStart + " to " + piecesEnd
            + " marked as cut: " + band);
    }

    private static Keyframe smartCameraAt(Scene scene, int tick) {
        for (Track track : scene.tracks()) {
            if (track.kind() == KeyframeKind.SMART_CAMERA) {
                for (Keyframe keyframe : track.keyframes()) {
                    if (keyframe.tick() == tick) {
                        return keyframe;
                    }
                }
            }
        }
        return null;
    }

    private static MomentsTimeline.Mark mark(MomentsTimeline.Timeline timeline, String eventId) {
        List<MomentsTimeline.Mark> found = new ArrayList<>();
        for (MomentsTimeline.Mark mark : timeline.marks()) {
            if (mark.eventId().equals(eventId)) {
                found.add(mark);
            }
        }
        // Event ids are only unique inside a session; the scripted run has one session per world, and one world here
        return found.isEmpty() ? null : found.get(0);
    }

    private static MomentsTimeline.Band band(MomentsTimeline.Timeline timeline, String clipId) {
        for (MomentsTimeline.Band band : timeline.bands()) {
            if (band.clipId().equals(clipId)) {
                return band;
            }
        }
        return null;
    }

    private static boolean has(JsonObject json, String field) {
        return json.has(field) && !json.get(field).isJsonNull();
    }

    private static JsonObject killClip(SelfTest test, String session) throws Exception {
        JsonObject listed = test.tool("moments_clips_list", args("session", session));
        for (JsonElement element : listed.getAsJsonArray("clips")) {
            if (element.toString().contains("mob_kill") && has(element.getAsJsonObject(), "replay_uuid")) {
                return element.getAsJsonObject();
            }
        }
        throw new IllegalStateException("no clip holds the kill");
    }

}
