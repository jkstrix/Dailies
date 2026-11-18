package comedymonkey.dailies.selftest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.file.Assembler;
import comedymonkey.dailies.file.RecordingFile;
import comedymonkey.dailies.file.RecordingManifest;
import comedymonkey.dailies.file.RecordingWriter;
import comedymonkey.dailies.file.Records;
import comedymonkey.dailies.file.TimeIndex;
import comedymonkey.dailies.library.Library;
import comedymonkey.dailies.library.LibraryIndex;
import comedymonkey.dailies.moments.Moments;
import comedymonkey.dailies.moments.storage.MomentsSession;
import comedymonkey.dailies.playback.Playback;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static comedymonkey.dailies.selftest.SelfTest.args;
import static comedymonkey.dailies.selftest.SelfTest.check;

/**
 * The plan's tests for combining and trimming recordings and for keeping moments working across them (FILE-9 to
 * FILE-14 and FILE-16): trim around the kill and cue it, cue a moment in the stretch that was cut, cut through the
 * middle of a clip, combine with a recording from another world, delete the original, lose the index and rename a
 * file by hand, and find a moment that has lost its recording's id by its time.
 */
final class LibrarySteps {

    private LibrarySteps() {
    }

    static void run(SelfTest test) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        JsonObject none = new JsonObject();
        Path original = RecordingSteps.recording;
        RecordingManifest originalManifest = RecordingFile.readManifest(original);
        String originalId = originalManifest.id;
        int total = originalManifest.totalTicks;

        // --- What the moments say, before anything is edited ---
        JsonObject listed = test.tool("moments_events_query", args("session", "latest", "limit", 500));
        String session = listed.get("session").getAsString();
        JsonObject kill = null;
        for (JsonElement element : listed.getAsJsonArray("events")) {
            JsonObject event = element.getAsJsonObject();
            if (event.get("type").getAsString().equals("mob_kill") && has(event, "replay_tick")) {
                kill = event;
                break;
            }
        }
        check(kill != null, "there is no kill with a recording tick to test with");
        String killId = kill.get("id").getAsString();
        int killTick = kill.get("replay_tick").getAsInt();
        check(originalId.equals(kill.get("replay_uuid").getAsString()), "the kill is not stamped with the scripted recording's id");

        JsonObject clip = killClip(test, session);
        String clipId = clip.get("id").getAsString();
        int clipStart = clip.get("start_replay_tick").getAsInt();
        int clipEnd = Math.min(total, clip.get("end_replay_tick").getAsInt());
        check(clipEnd - clipStart >= 60, "the kill's clip is too short to cut through: ticks " + clipStart + " to " + clipEnd);
        JsonArray before = clip.getAsJsonArray("locations");
        check(before != null && before.size() == 1 && before.get(0).getAsJsonObject().get("original").getAsBoolean() && !before.get(0).getAsJsonObject().get("cut").getAsBoolean(),
            "before any edit a clip should be in its original recording and nowhere else: " + before);

        // --- FILE-16: every tick has its real time and game time, read without playing ---
        try (RecordingFile file = RecordingFile.open(original)) {
            check(file.hasTimes(), "the recording holds no time index");
            check(file.manifest().times.size() == file.segments().size(), "the description does not give every segment's first times: " + file.manifest().times.size() + " for "
                + file.segments().size() + " segments");
            TimeIndex.Sample atKill = file.timeAt(killTick);
            check(atKill != null, "there are no times for the tick of the kill");
            long killReal = kill.get("real_time").getAsLong();
            // A tick on which the recording stood still (a pause, say) has two times, and a moment logged meanwhile is
            // stamped with that tick: its time lies between them. For any other tick the two are one
            java.util.List<TimeIndex.Sample> killTimes = file.timesAt(killTick);
            long earliest = killTimes.stream().mapToLong(TimeIndex.Sample::realTime).min().orElse(0);
            long latest = killTimes.stream().mapToLong(TimeIndex.Sample::realTime).max().orElse(0);
            check(!killTimes.isEmpty() && killReal >= earliest - 250 && killReal <= latest + 250, "the time index says tick " + killTick + " was at " + earliest
                + (latest > earliest ? " and went on at " + latest : "") + ", the moment logged on that tick says " + killReal);
            long killGame = kill.get("game_time").getAsLong();
            check(killTimes.stream().anyMatch(sample -> Math.abs(sample.gameTime() - killGame) <= 3) || killTimes.size() > 1
                && killGame >= killTimes.stream().mapToLong(TimeIndex.Sample::gameTime).min().getAsLong() - 3 && killGame <= killTimes.stream().mapToLong(TimeIndex.Sample::gameTime).max().getAsLong() + 3,
                "the time index says game time " + atKill.gameTime() + " at the kill, the moment says " + killGame);
            TimeIndex.Nearest nearest = file.tickNearest(killReal);
            long allowed = latest - earliest > 250 ? latest - earliest : 100;
            check(nearest != null && Math.abs(nearest.tick() - killTick) <= 3 && nearest.errorMillis() <= allowed, "the tick nearest the kill's real time is given as " + nearest + ", not about " + killTick);

            // The index against the game's own clocks, read by this test on the client thread at noted ticks all through
            // the recording: within segments, either side of segment boundaries, and across the pause
            int segmentsCrossed = 0;
            int boundaryNotes = 0;
            long worst = 0;
            RecordingSteps.Note firstNote = RecordingSteps.notes.get(0);
            RecordingSteps.Note lastNote = RecordingSteps.notes.get(RecordingSteps.notes.size() - 1);
            for (RecordingManifest.Segment segment : file.segments()) {
                if (segment.startTick > firstNote.tick() && segment.startTick <= lastNote.tick()) {
                    segmentsCrossed++;
                }
            }
            for (RecordingSteps.Note note : RecordingSteps.notes) {
                java.util.List<TimeIndex.Sample> times = file.timesAt(note.tick());
                check(!times.isEmpty(), "the time index has nothing for tick " + note.tick() + ", which the live game was noted on");
                // The note was taken between two ticks: after the tick it names was recorded, before the next one was
                long off = Long.MAX_VALUE;
                for (TimeIndex.Sample sample : times) {
                    long late = note.realTime() - sample.realTime();
                    if (Math.abs(late) < Math.abs(off)) {
                        off = late;
                    }
                    check(Math.abs(note.gameTime() - sample.gameTime()) <= 2, "the time index says game time " + sample.gameTime() + " at tick " + note.tick() + ", the game said " + note.gameTime());
                }
                worst = Math.max(worst, Math.abs(off));
                check(off >= -30 && off <= 300, "the time index says tick " + note.tick() + " was at " + times.get(0).realTime() + ", the game's clock read " + note.realTime()
                    + " while that tick was the newest (" + off + " ms later)");
                for (RecordingManifest.Segment segment : file.segments()) {
                    if (segment.startTick > 0 && Math.abs(note.tick() - segment.startTick) <= 30) {
                        boundaryNotes++;
                        break;
                    }
                }
            }
            check(segmentsCrossed >= 2 && boundaryNotes >= 2, "the noted ticks cross " + segmentsCrossed + " segment boundaries with " + boundaryNotes
                + " of them near one: too few to say anything about the time index at a boundary");
            // And tick by tick: time never runs backwards, and within a segment a tick is about a twentieth of a second
            for (RecordingManifest.Segment segment : file.segments()) {
                TimeIndex.Sample earlier = null;
                for (int tick = segment.startTick; tick <= segment.endTick; tick++) {
                    java.util.List<TimeIndex.Sample> both = file.timesAt(tick);
                    check(!both.isEmpty(), "tick " + tick + " has no times");
                    // Where two segments meet, this segment's own time for the tick: the later at its start, the earlier at its end
                    TimeIndex.Sample now = tick == segment.startTick ? both.get(both.size() - 1) : both.get(0);
                    if (earlier != null) {
                        long step = now.realTime() - earlier.realTime();
                        check(step >= 0 && step <= 2000, "from tick " + (tick - 1) + " to tick " + tick + " inside a segment the time index moves " + step + " ms");
                        check(now.gameTime() - earlier.gameTime() >= 0 && now.gameTime() - earlier.gameTime() <= 3, "from tick " + (tick - 1) + " to tick " + tick
                            + " the game time in the index moves " + (now.gameTime() - earlier.gameTime()));
                    }
                    earlier = now;
                }
            }
            test.log("time index against the game's clock at " + RecordingSteps.notes.size() + " noted ticks across " + segmentsCrossed + " segment boundaries (" + boundaryNotes
                + " within 30 ticks of one): at most " + worst + " ms apart");
            TimeIndex.Sample first = file.timeAt(0);
            TimeIndex.Sample last = file.timeAt(total);
            check(first != null && last != null && last.realTime() - first.realTime() >= total * 45L, "the times do not run from the first tick to the last: " + first + " to " + last);
            check(file.manifest().realStart == first.realTime() && file.manifest().realEnd >= last.realTime() - 100, "the description's first and last real time are not the time index's");
            test.log("time index: tick " + killTick + " at real " + atKill.realTime() + " game " + atKill.gameTime() + "; " + file.manifest().times.size() + " segment start times in the header");
        }
        LibraryIndex index = Library.index();
        check(Files.isRegularFile(index.indexFile()), "there is no index.json in the recordings folder");
        check(Files.readString(index.indexFile(), StandardCharsets.UTF_8).contains(originalId), "index.json does not list the scripted recording");

        // --- The ranges ---
        // Around the kill: from a little before its clip to a little after, neither end on the start of a segment
        int firstKept = Math.max(3, clipStart - 13);
        int keepTo = Math.min(total - 5, clipEnd + 9);
        // Through the middle of the clip: its middle third goes
        int cutFrom = clipStart + (clipEnd - clipStart) / 3;
        int cutTo = clipStart + 2 * (clipEnd - clipStart) / 3;
        try (RecordingFile file = RecordingFile.open(original)) {
            while (!Assembler.needsSnapshot(file, firstKept)) {
                firstKept++;
            }
            while (!Assembler.needsSnapshot(file, cutTo)) {
                cutTo++;
            }
        }
        int keepFrom = firstKept;
        check(killTick >= keepFrom && killTick < keepTo, "the kill at tick " + killTick + " is not inside the range kept, " + keepFrom + " to " + keepTo);
        JsonObject outside = null;
        for (JsonElement element : listed.getAsJsonArray("events")) {
            JsonObject event = element.getAsJsonObject();
            if (has(event, "replay_tick") && originalId.equals(event.get("replay_uuid").getAsString()) && event.get("replay_tick").getAsInt() > keepTo + 20) {
                outside = event;
            }
        }
        check(outside != null, "no moment was logged after tick " + (keepTo + 20) + " to stand for one in a removed stretch");
        int outsideTick = outside.get("replay_tick").getAsInt();
        int afterCut = keepFrom + 40;
        int afterMiddleCut = Math.min(clipEnd - 2, cutTo + 20);

        // The world as the original has it at two ticks, each just after where a cut will fall
        test.tool("replay_open", args("path", original.toString()));
        waitOpen(test, originalId, 0);
        PlaybackSteps.jump(test, afterCut);
        String originalAfterCut = test.onClient(() -> PlaybackSteps.digest(minecraft));
        PlaybackSteps.jump(test, afterMiddleCut);
        String originalAfterMiddleCut = test.onClient(() -> PlaybackSteps.digest(minecraft));

        // --- Trim around the kill (FILE-10), with the recording open: it is played to the cut where it stands ---
        String refused = test.toolError("replay_trim", args("source", originalId, "keep", ranges(keepFrom, keepTo), "name", "Around the kill", "colour", "red"));
        check(refused.contains("colour"), "replay_trim did not refuse an unknown argument by name: " + refused);
        check(test.toolError("replay_trim", args("source", originalId, "name", "Nothing")).contains("keep"), "replay_trim without ranges did not say what it needs");
        check(test.toolError("replay_trim", args("source", originalId, "keep", ranges(0, total), "name", "Everything", "wait_seconds", 30)).contains("Nothing would be cut"),
            "a trim that keeps everything was not refused");
        JsonObject around = finish(test, test.tool("replay_trim", args("source", originalId, "keep", ranges(keepFrom, keepTo), "name", "Around the kill")));
        check(around.get("through_game").getAsBoolean(), "a trim whose cut is inside a segment did not say that it goes through the game");
        String aroundId = around.get("recording_id").getAsString();
        Path aroundFile = Path.of(around.get("path").getAsString());
        int aroundTicks = keepTo - keepFrom;
        check(around.get("total_ticks").getAsInt() == aroundTicks, "the trimmed recording is " + around.get("total_ticks") + " ticks long, not the " + aroundTicks + " that were kept");
        test.waitFor(() -> Playback.loaded() && !Playback.jumping(), 60, "playback to settle after the trim");
        check(originalId.equals(Playback.recordingId()) && Playback.position() == afterMiddleCut, "the recording that was open is not open where it was after the trim (tick "
            + Playback.position() + " of " + Playback.recordingId() + ")");
        check(RecordingFile.readManifest(original).totalTicks == total && Files.size(original) > 0, "the source of the trim was changed");
        RecordingManifest aroundManifest = RecordingFile.readManifest(aroundFile);
        check(aroundManifest.spans.size() == 1 && aroundManifest.spans.get(0).source.equals(originalId) && aroundManifest.spans.get(0).sourceStart == keepFrom
            && aroundManifest.spans.get(0).sourceEnd == keepTo && aroundManifest.spans.get(0).here == 0, "the trimmed recording's source map is not the range kept of the original");
        check(aroundManifest.sources.size() == 1 && aroundManifest.sources.get(0).id.equals(originalId) && aroundManifest.sources.get(0).name.equals("Self test"),
            "the trimmed recording does not say which original it came from");
        check(aroundManifest.edited > 0 && aroundManifest.finished && !aroundManifest.id.equals(originalId), "the trimmed recording's description is not that of a new, edited recording");
        for (RecordingManifest.Marker marker : aroundManifest.markers) {
            check(marker.tick >= 0 && marker.tick <= aroundTicks, "a marker was carried to tick " + marker.tick + " of a recording of " + aroundTicks);
        }
        try (RecordingFile file = RecordingFile.open(aroundFile)) {
            TimeIndex.Sample start = file.timeAt(0);
            try (RecordingFile source = RecordingFile.open(original)) {
                check((file.preview() == null) == (source.preview() == null), "the preview picture was not carried across");
                check((file.editState() == null) == (source.editState() == null), "the scenes were not carried across");
                TimeIndex.Sample same = source.timeAt(keepFrom);
                check(start != null && same != null && start.realTime() == same.realTime() && start.gameTime() == same.gameTime(), "the trimmed recording's first tick does not have the times of tick "
                    + keepFrom + " of its source: " + start + " against " + same);
                check(file.timeAt(aroundTicks - 1).realTime() == source.timeAt(keepTo - 1).realTime(), "the trimmed recording's last tick does not have its source's times");
            }
            Set<String> carried = extensionsInFirstSnapshot(file);
            check(carried.contains("dailies:skins"), "the players' skins were not carried into the snapshot written at the cut: " + carried);
            check(carried.contains("dailies:hud"), "the recorder's HUD was not carried into the snapshot written at the cut: " + carried);
        }
        check(Files.readString(index.indexFile(), StandardCharsets.UTF_8).contains(aroundId), "index.json does not list the trimmed recording");
        test.log("trimmed ticks " + keepFrom + " to " + keepTo + " of " + total + " into '" + aroundFile.getFileName() + "' through the game");

        // --- Test 1: cue the kill in the trimmed recording ---
        JsonObject cued = test.tool("moments_cue", args("session", session, "event_id", killId, "lead_seconds", 0, "recording_id", aroundId));
        check(cued.get("recording_id").getAsString().equals(aroundId) && cued.get("target_tick").getAsInt() == killTick - keepFrom, "the kill at tick " + killTick
            + " should be at tick " + (killTick - keepFrom) + " of the trimmed recording: " + shorten(cued.toString()));
        check(cued.getAsJsonArray("locations").size() == 2, "the kill should be in the original and the trimmed recording: " + cued.get("locations"));
        waitOpen(test, aroundId, killTick - keepFrom);
        TimelineSteps.trimmed(test, killId, killTick, outside.get("id").getAsString(), clipId, clipStart, keepFrom, keepTo);
        // The world just after the cut is the world the original has there: the snapshot written from playback is a true one
        PlaybackSteps.jump(test, afterCut - keepFrom);
        String trimmedAfterCut = test.onClient(() -> PlaybackSteps.digest(minecraft));
        String differs = PlaybackSteps.difference(originalAfterCut, trimmedAfterCut);
        check(differs.isEmpty(), "the world " + (afterCut - keepFrom) + " ticks into the trimmed recording is not the world at tick " + afterCut + " of the original:\n" + differs);
        check(originalAfterCut.lines().count() >= 3, "too little was compared: " + originalAfterCut);
        test.onClientRun(() -> {
            int players = 0;
            for (Entity entity : minecraft.level.entitiesForRendering()) {
                if (entity instanceof net.minecraft.world.entity.player.Player && entity != minecraft.player) {
                    players++;
                }
            }
            check(players == 1, "the trimmed recording shows " + players + " players besides the viewer, not the recorder alone");
            check(minecraft.getConnection().getOnlinePlayers().size() == 2, "the trimmed recording's tab list has " + minecraft.getConnection().getOnlinePlayers().size()
                + " entries, not the recorder and the viewer");
        });
        // And the same at its very first tick, which is the snapshot itself, and by playing on from there
        PlaybackSteps.jump(test, 0);
        test.onClientRun(() -> Playback.playTo(afterCut - keepFrom));
        test.waitFor(() -> Playback.paused() && Playback.position() == afterCut - keepFrom, 60, "play from the start of the trimmed recording");
        test.sleepTicks(5);
        differs = PlaybackSteps.difference(originalAfterCut, test.onClient(() -> PlaybackSteps.digest(minecraft)));
        check(differs.isEmpty(), "played from its first tick, the trimmed recording does not reach the world of tick " + afterCut + " of the original:\n" + differs);
        test.log("the kill cues at tick " + (killTick - keepFrom) + " of the trimmed recording; the world after the cut matches the original (" + originalAfterCut.lines().count() + " lines compared)");

        // --- Test 2: a moment in the removed stretch says it was cut, and lists the original ---
        String outsideId = outside.get("id").getAsString();
        String cutMessage = test.toolError("moments_cue", args("session", session, "event_id", outsideId, "lead_seconds", 0, "recording_id", aroundId));
        check(cutMessage.contains("CUT") && cutMessage.contains("cut from this recording") && cutMessage.contains("Self test") && cutMessage.contains(originalId),
            "cueing a moment that was cut did not say so and name the original: " + cutMessage);
        check((int) Host.get().openRecordingContains(originalId, outsideTick) == -1 && Host.get().openRecordingContains(originalId, killTick) == killTick - keepFrom
            && Host.get().openRecordingMadeFrom(originalId), "the open recording does not answer through its spans");
        // Left to choose for itself, it goes to the original, which still holds it (FILE-13)
        JsonObject elsewhere = test.tool("moments_cue", args("session", session, "event_id", outsideId, "lead_seconds", 0));
        check(elsewhere.has("cut_from_open") && elsewhere.get("cut_from_open").getAsBoolean() && elsewhere.get("recording_id").getAsString().equals(originalId)
            && elsewhere.getAsJsonArray("locations").size() == 1, "a cue for a moment cut from the open recording did not say so and turn to the original: " + shorten(elsewhere.toString()));
        waitOpen(test, originalId, outsideTick);
        test.log("a moment at tick " + outsideTick + " reports: " + cutMessage);

        machines(test, minecraft, original, originalId, total);

        // --- Test 3: a cut through the middle of a clip, made from the title screen ---
        PlaybackSteps.close(test);
        JsonObject through = finish(test, test.tool("replay_trim", args("source", "Self test", "cut", ranges(cutFrom, cutTo), "name", "Cut through the clip")));
        check(through.get("through_game").getAsBoolean(), "the second trim should have gone through the game too");
        String throughId = through.get("recording_id").getAsString();
        int removed = cutTo - cutFrom;
        check(through.get("total_ticks").getAsInt() == total - removed, "cutting " + removed + " ticks left " + through.get("total_ticks") + " of " + total);
        test.waitFor(() -> !Playback.active() && minecraft.level == null, 60, "the game to be put back at the title screen after the trim");
        RecordingManifest throughManifest = RecordingFile.readManifest(Path.of(through.get("path").getAsString()));
        check(throughManifest.spans.size() == 2 && throughManifest.spans.get(1).sourceStart == cutTo && throughManifest.spans.get(1).here == cutFrom, "the source map of the cut recording is wrong");
        check(throughManifest.markers.stream().anyMatch(marker -> RecordingManifest.Marker.CUT.equals(marker.kind) && marker.tick == cutFrom), "the cut is not marked on the timeline");
        // The marker dropped while recording comes along at its new tick, unless the cut took the tick it was on
        for (RecordingManifest.Marker marker : originalManifest.markers) {
            int wanted = marker.tick < cutFrom ? marker.tick : marker.tick >= cutTo ? marker.tick - removed : -1;
            boolean carried = throughManifest.markers.stream().anyMatch(kept -> kept.description.equals(marker.description) && kept.tick == wanted && kept.kind == null);
            boolean present = throughManifest.markers.stream().anyMatch(kept -> kept.description.equals(marker.description) && kept.kind == null);
            check(wanted >= 0 ? carried : !present, "the marker '" + marker.description + "' at tick " + marker.tick + " should " + (wanted >= 0 ? "be at tick " + wanted : "have gone with the cut")
                + " in the trimmed recording");
        }

        clip = killClip(test, session);
        JsonObject inAround = location(clip, aroundId);
        JsonObject inThrough = location(clip, throughId);
        check(clip.getAsJsonArray("locations").size() == 3 && clip.getAsJsonArray("locations").get(0).getAsJsonObject().get("original").getAsBoolean(),
            "the clip should be in three recordings, the original first: " + clip.get("locations"));
        check(!inAround.get("cut").getAsBoolean() && inAround.getAsJsonArray("stretches").size() == 1
            && inAround.getAsJsonArray("stretches").get(0).getAsJsonObject().get("tick").getAsInt() == clipStart - keepFrom, "the clip should be whole in the first trim: " + inAround);
        JsonArray pieces = inThrough.getAsJsonArray("stretches");
        check(inThrough.get("cut").getAsBoolean() && inThrough.get("removed_ticks").getAsInt() == Math.min(cutTo, clipEnd) - cutFrom && pieces.size() == 2,
            "a clip with a cut through it should be flagged, with the length removed, in two pieces: " + inThrough);
        JsonObject firstPiece = pieces.get(0).getAsJsonObject();
        JsonObject secondPiece = pieces.get(1).getAsJsonObject();
        check(firstPiece.get("tick").getAsInt() == clipStart && firstPiece.get("end_tick").getAsInt() == cutFrom && secondPiece.get("tick").getAsInt() == cutFrom
            && secondPiece.get("source_tick").getAsInt() == cutTo, "the two pieces of the clip are not back to back at the cut: " + pieces);

        test.tool("moments_cue", args("session", session, "clip_id", clipId, "recording_id", throughId));
        waitOpen(test, throughId, clipStart);
        JsonObject gone = test.tool("moments_clip_goto", args("session", session, "clip_id", clipId));
        int piecesEnd = secondPiece.get("end_tick").getAsInt();
        TimelineSteps.cutThrough(test, clipId, clipStart, piecesEnd);
        check(gone.get("cut").getAsBoolean() && gone.getAsJsonArray("stretches").size() == 2 && gone.get("target_tick").getAsInt() == clipStart && gone.get("end_tick").getAsInt() == piecesEnd,
            "moments_clip_goto did not give the clip as its two pieces: " + shorten(gone.toString()));
        // It plays as the edit has it: straight across the cut, into the world the original has after it
        test.waitFor(() -> !Playback.jumping(), 60, "the jump to the clip");
        test.tool("replay_set_speed", args("tick_rate", 60));
        test.onClientRun(() -> Playback.playTo(piecesEnd));
        test.waitFor(() -> Playback.paused() && Playback.position() == piecesEnd, 90, "the clip to play across its cut");
        test.tool("replay_set_speed", args("tick_rate", 20));
        PlaybackSteps.jump(test, afterMiddleCut - removed);
        differs = PlaybackSteps.difference(originalAfterMiddleCut, test.onClient(() -> PlaybackSteps.digest(minecraft)));
        check(differs.isEmpty(), "the world after the cut through the clip is not the world at tick " + afterMiddleCut + " of the original:\n" + differs);
        test.log("a clip cut from tick " + cutFrom + " to " + cutTo + " is flagged, with " + inThrough.get("removed_ticks") + " ticks removed, and plays as two pieces");

        // --- A second recording, from another world ---
        PlaybackSteps.close(test);
        WorldSteps.createWorld(test, "Dailies Test Two");
        test.tool("recording_start", none);
        test.sleepTicks(20);
        test.command("summon minecraft:cow 3 -60 3 {CustomName:'\"Second\"',NoAI:1b}");
        test.sleepTicks(30);
        JsonObject added = test.tool("moments_event_add", args("label", "Second world moment", "type", "library_test"));
        test.sleepTicks(50);
        JsonObject second = test.tool("recording_finish", args("name", "Second world"));
        Path secondFile = Path.of(second.get("path").getAsString());
        String secondSession = added.get("session").getAsString();
        JsonObject secondEvent = added.getAsJsonObject("event");
        String secondEventId = secondEvent.get("id").getAsString();
        int secondTick = secondEvent.get("replay_tick").getAsInt();
        String secondId = secondEvent.get("replay_uuid").getAsString();
        check(!secondSession.equals(session), "the second world did not get a moments session of its own");
        WorldSteps.leaveWorld(test);
        RecordingManifest secondManifest = RecordingFile.readManifest(secondFile);
        check(secondManifest.id.equals(secondId) && secondManifest.worldName.equals("Dailies Test Two"), "the second recording is not of the second world");

        // --- Test 4: combine (FILE-9) ---
        Path foreign = Library.index().folder().resolve("Another version" + RecordingFile.EXTENSION);
        RecordingManifest foreignManifest = RecordingFile.readManifest(secondFile);
        foreignManifest.id = UUID.randomUUID().toString();
        foreignManifest.name = "Another version";
        foreignManifest.gameVersion = "1.20.4";
        foreignManifest.dataVersion = 3700;
        tenTicks(foreign, foreignManifest);
        String versions = test.toolError("replay_combine", args("sources", array(aroundId, "Another version"), "name", "Mixed versions", "wait_seconds", 60));
        check(versions.contains("different game versions") && versions.contains("1.20.4"), "recordings from different game versions were not refused: " + versions);
        Library.delete(foreign);
        check(test.toolError("replay_combine", args("sources", array(aroundId), "name", "One")).contains("at least two"), "combining one recording was not refused");

        // Different mods are allowed. The differences are told to whoever asked, and stored with the spans they apply to (PLAY-5)
        Path modded = Library.index().folder().resolve("Other mods" + RecordingFile.EXTENSION);
        RecordingManifest moddedManifest = RecordingFile.readManifest(secondFile);
        moddedManifest.id = UUID.randomUUID().toString();
        moddedManifest.name = "Other mods";
        moddedManifest.mods.removeIf(mod -> mod.id.equals("dailies"));
        moddedManifest.mods.add(new RecordingManifest.ModEntry("dailies", "0.0.0-other"));
        moddedManifest.mods.add(new RecordingManifest.ModEntry("phantom_mod", "1.0"));
        tenTicks(modded, moddedManifest);
        JsonObject mixed = finish(test, test.tool("replay_combine", args("sources", array(aroundId, "Other mods"), "name", "Mixed mods")));
        check(mixed.getAsJsonArray("notes").size() == 2 && mixed.get("notes").toString().contains("phantom_mod") && mixed.get("notes").toString().contains("0.0.0-other"),
            "the mods that differ between the sources were not listed: " + mixed.get("notes"));
        Path mixedFile = Path.of(mixed.get("path").getAsString());
        RecordingManifest mixedManifest = RecordingFile.readManifest(mixedFile);
        check(mixedManifest.sources.size() == 2, "the recording combined from different mods does not name its two originals");
        for (RecordingManifest.SourceEntry source : mixedManifest.sources) {
            String modsDiffer = source.modDifferences.stream().map(difference -> difference.id + " " + difference.version + " (header " + difference.header + ")").toList().toString();
            if (source.id.equals(originalId)) {
                check(source.modDifferences.size() == 1 && source.modDifferences.get(0).id.equals("phantom_mod") && source.modDifferences.get(0).version == null
                    && "1.0".equals(source.modDifferences.get(0).header), "the first source should differ from the header only by lacking phantom_mod: " + modsDiffer);
            } else {
                check(source.id.equals(moddedManifest.id) && source.modDifferences.size() == 1 && source.modDifferences.get(0).id.equals("dailies")
                    && "0.0.0-other".equals(source.modDifferences.get(0).version), "the second source should differ from the header only in its version of dailies: " + modsDiffer);
            }
        }
        JsonObject inList = null;
        for (JsonElement element : test.tool("replay_list", none).getAsJsonArray("recordings")) {
            if (element.getAsJsonObject().get("name").getAsString().equals("Mixed mods")) {
                inList = element.getAsJsonObject();
            }
        }
        check(inList != null && inList.getAsJsonArray("spans").size() == 2, "replay_list does not show the combined recording with its two spans: " + inList);
        String firstSpan = inList.getAsJsonArray("spans").get(0).getAsJsonObject().get("mods_that_differ").toString();
        String secondSpan = inList.getAsJsonArray("spans").get(1).getAsJsonObject().get("mods_that_differ").toString();
        check(firstSpan.contains("phantom_mod") && !firstSpan.contains("0.0.0-other") && secondSpan.contains("0.0.0-other") && !secondSpan.contains("phantom_mod"),
            "replay_list does not show each span's own mod differences: " + firstSpan + " and " + secondSpan);
        test.log("combined from different mods: " + mixed.get("notes") + "; stored per span as " + firstSpan + " and " + secondSpan);
        // The steps that follow count the recordings a moment is in, so these go again
        Library.delete(mixedFile);
        Library.delete(modded);

        JsonObject combined = finish(test, test.tool("replay_combine", args("sources", array(aroundId, secondId), "name", "Episode")));
        check(!combined.get("through_game").getAsBoolean(), "a combine should never need the game");
        String episodeId = combined.get("recording_id").getAsString();
        Path episodeFile = Path.of(combined.get("path").getAsString());
        check(combined.get("total_ticks").getAsInt() == aroundTicks + secondManifest.totalTicks, "combining added or lost time: " + combined.get("total_ticks") + " ticks from " + aroundTicks
            + " and " + secondManifest.totalTicks);
        check(combined.getAsJsonArray("notes").size() == 0, "two recordings made with the same mods were said to differ: " + combined.get("notes"));
        RecordingManifest episode = RecordingFile.readManifest(episodeFile);
        check(episode.spans.size() == 2 && episode.spans.get(0).source.equals(originalId) && episode.spans.get(0).sourceStart == keepFrom && episode.spans.get(1).source.equals(secondId)
            && episode.spans.get(1).here == aroundTicks, "the combined recording's spans do not point at the two originals: one step deep, whatever was edited before");
        check(episode.sources.size() == 2, "the combined recording does not name its two originals");
        check(episode.markers.stream().anyMatch(marker -> RecordingManifest.Marker.BOUNDARY.equals(marker.kind) && marker.tick == aroundTicks), "the boundary is not a marked point on the timeline");
        check(episode.worldName.contains("Dailies Test") && episode.worldName.contains("Dailies Test Two"), "the combined recording does not name both worlds: " + episode.worldName);
        check(RecordingFile.readManifest(aroundFile).totalTicks == aroundTicks && RecordingFile.readManifest(secondFile).totalTicks == secondManifest.totalTicks, "a source of the combine was changed");

        // Earlier moments still cue correctly, and those of the second recording land after the boundary
        JsonObject early = test.tool("moments_cue", args("session", session, "event_id", killId, "lead_seconds", 0, "recording_id", episodeId));
        check(early.get("target_tick").getAsInt() == killTick - keepFrom, "the kill moved in the combined recording: " + shorten(early.toString()));
        waitOpen(test, episodeId, killTick - keepFrom);
        check(named(test, minecraft, "Second") == 0, "the second world's cow is in the first world");
        JsonObject late = test.tool("moments_cue", args("session", secondSession, "event_id", secondEventId, "lead_seconds", 0, "recording_id", episodeId));
        check(late.get("target_tick").getAsInt() == aroundTicks + secondTick, "a moment of the second recording should be at tick " + (aroundTicks + secondTick) + ": " + shorten(late.toString()));
        waitOpen(test, episodeId, aroundTicks + secondTick);
        check(named(test, minecraft, "Second") == 1 && named(test, minecraft, "Hamlet") == 0, "after the boundary the world is not the second recording's: " + named(test, minecraft, "Second")
            + " cow(s) called Second, " + named(test, minecraft, "Hamlet") + " pig(s) called Hamlet");
        // Play runs across the boundary by itself and the world switches there
        PlaybackSteps.jump(test, aroundTicks - 15);
        check(named(test, minecraft, "Hamlet") == 1 && named(test, minecraft, "Second") == 0, "before the boundary the world is not the first recording's");
        test.onClientRun(() -> Playback.playTo(aroundTicks + 25));
        test.waitFor(() -> Playback.paused() && Playback.position() == aroundTicks + 25, 90, "play across the boundary between the two recordings");
        test.sleepTicks(5);
        check(named(test, minecraft, "Second") == 1 && named(test, minecraft, "Hamlet") == 0, "playing across the boundary did not switch worlds");
        try (RecordingFile file = RecordingFile.open(episodeFile); RecordingFile source = RecordingFile.open(secondFile)) {
            check(file.timeAt(aroundTicks + secondTick).realTime() == source.timeAt(secondTick).realTime(), "the combined recording does not carry the second recording's times");
        }
        test.log("combined into '" + episodeFile.getFileName() + "': " + combined.get("total_ticks") + " ticks, boundary at " + aroundTicks + ", worlds switch there");

        // --- Test 5: delete the original and cue again ---
        PlaybackSteps.close(test);
        Library.delete(original);
        check(!Files.exists(original), "the original was not deleted");
        JsonObject orphan = test.tool("moments_cue", args("session", session, "event_id", killId, "lead_seconds", 0));
        check(orphan.get("recording_id").getAsString().equals(episodeId), "with the original gone the newest recording that holds the moment should answer, not " + shorten(orphan.toString()));
        int holders = killTick < cutFrom || killTick >= cutTo ? 3 : 2;
        check(orphan.getAsJsonArray("locations").size() == holders, "the kill should still be in " + holders + " recordings: " + orphan.get("locations"));
        waitOpen(test, episodeId, killTick - keepFrom);
        clip = killClip(test, session);
        check(clip.getAsJsonArray("locations").size() == 3 && !clip.toString().contains("\"original\":true"), "the clip still lists its deleted original: " + clip.get("locations"));
        // The moment the first trim cut is still in the recording with the cut through the clip, which now answers for it
        JsonObject survivor = test.tool("moments_cue", args("session", session, "event_id", outsideId, "lead_seconds", 0));
        check(survivor.get("recording_id").getAsString().equals(throughId) && survivor.get("target_tick").getAsInt() == outsideTick - removed && survivor.get("cut_from_open").getAsBoolean(),
            "a moment cut from the open recording should be found in the one other recording that still holds it: " + shorten(survivor.toString()));
        waitOpen(test, throughId, outsideTick - removed);
        test.log("with the original deleted, the kill cues in '" + episodeFile.getFileName() + "'");

        // --- Test 6: the index is lost, and a recording is renamed outside the game ---
        PlaybackSteps.close(test);
        Files.delete(index.indexFile());
        Path renamed = aroundFile.resolveSibling("Renamed by hand" + RecordingFile.EXTENSION);
        Files.move(aroundFile, renamed);
        JsonObject found = test.tool("moments_cue", args("session", session, "event_id", killId, "lead_seconds", 0, "recording_id", aroundId));
        check(found.get("file").getAsString().equals(renamed.getFileName().toString()), "the renamed recording was not found by its id: " + shorten(found.toString()));
        waitOpen(test, aroundId, killTick - keepFrom);
        check(Playback.file().file().getFileName().equals(renamed.getFileName()), "the cue did not open the renamed file");
        String rebuilt = Files.readString(index.indexFile(), StandardCharsets.UTF_8);
        check(rebuilt.contains("Renamed by hand") && rebuilt.contains(episodeId) && rebuilt.contains(throughId) && rebuilt.contains(secondId) && !rebuilt.contains("Around the kill.dailies"),
            "the index did not repair itself: " + shorten(rebuilt));
        JsonObject other = test.tool("moments_cue", args("session", secondSession, "event_id", secondEventId, "lead_seconds", 0));
        check(other.get("recording_id").getAsString().equals(secondId), "a moment of the second recording should open its original: " + shorten(other.toString()));
        waitOpen(test, secondId, secondTick);
        clip = killClip(test, session);
        check(clip.getAsJsonArray("locations").size() == 3 && location(clip, aroundId).get("file").getAsString().equals(renamed.getFileName().toString()), "the clip's locations do not follow the rename");
        test.log("index.json was rebuilt and the recording renamed by hand is still found by its id");

        // --- Test 7: a moment that has lost its recording's id is found by its time ---
        PlaybackSteps.close(test);
        MomentsSession stored = Moments.storage().getSession(secondSession);
        check(stored != null && stored.update(MomentsSession.Kind.EVENTS, secondEventId, event -> {
            event.remove("replay_uuid");
            event.remove("replay_tick");
        }) != null, "the moment could not be edited by hand");
        JsonObject byTime = test.tool("moments_cue", args("session", secondSession, "event_id", secondEventId, "lead_seconds", 0));
        check(byTime.get("found_by").getAsString().equals("time") && byTime.get("recording_id").getAsString().equals(secondId) && Math.abs(byTime.get("target_tick").getAsInt() - secondTick) <= 3,
            "a moment with no recording id was not found by its time at about tick " + secondTick + " of its recording: " + shorten(byTime.toString()));
        test.waitFor(() -> Playback.loaded() && !Playback.jumping() && secondId.equals(Playback.recordingId()) && Math.abs(Playback.position() - secondTick) <= 3, 120,
            "the recording found by time to open");
        // The same for an id that no recording has: the kill, in the world of the first recording, whose original is gone
        MomentsSession firstStored = Moments.storage().getSession(session);
        check(firstStored != null && firstStored.update(MomentsSession.Kind.EVENTS, killId, event -> event.addProperty("replay_uuid", UUID.randomUUID().toString())) != null,
            "the kill could not be edited by hand");
        JsonObject strayed = test.tool("moments_cue", args("session", session, "event_id", killId, "lead_seconds", 0));
        check(strayed.get("found_by").getAsString().equals("time") && strayed.get("recording_id").getAsString().equals(episodeId)
            && Math.abs(strayed.get("target_tick").getAsInt() - (killTick - keepFrom)) <= 3, "a moment whose recording id is unknown was not found by its time in the newest recording of its world: "
            + shorten(strayed.toString()));
        test.waitFor(() -> Playback.loaded() && !Playback.jumping() && episodeId.equals(Playback.recordingId()) && Math.abs(Playback.position() - (killTick - keepFrom)) <= 3, 120,
            "the edited recording found by time to open");
        test.log("moments without a usable recording id were found by their time: tick " + byTime.get("target_tick") + " of the second recording and tick " + strayed.get("target_tick")
            + " of the combined one");

        JsonObject jobs = test.tool("replay_job_status", none);
        check(jobs.getAsJsonArray("jobs").size() >= 3, "replay_job_status does not list the jobs that ran: " + jobs);
        PlaybackSteps.close(test);

        packs(test, minecraft, secondFile);
    }

    /**
     * A snapshot written from playback while machines are in the world and moving (FILE-10 with chapter VII): the
     * recording is trimmed with one cut while the first physics platform falls and the bearing turns, and another later,
     * with every craft built. In the trimmed recording everything must be there once, where the original has it at the
     * same moment and where the live game had it. Comes in with the original open and leaves it open.
     */
    private static void machines(SelfTest test, Minecraft minecraft, Path original, String originalId, int total) throws Exception {
        if (!CraftSteps.builtContraption && !CraftSteps.builtPlatform) {
            test.skip("no machines were built: a trim that cuts inside a segment with craft and contraptions in the world was not tried (FILE-10 with MOD-1 to MOD-5)");
            return;
        }
        java.util.List<RecordingSteps.Note> notes = RecordingSteps.notes;
        int early = CraftSteps.platformNotesFrom >= 0 ? CraftSteps.platformNotesFrom + 1 : CraftSteps.contraptionNotesFrom + 5;
        int later = RecordingSteps.laterNotesFrom;
        check(early >= 0 && early < notes.size() && later > early && later < notes.size(), "the notes do not say when the machines were moving");
        int firstCut = notes.get(early).tick() - 3;
        int secondCut = notes.get(later).tick() - 7;
        try (RecordingFile file = RecordingFile.open(original)) {
            while (!Assembler.needsSnapshot(file, firstCut)) {
                firstCut--;
            }
            while (!Assembler.needsSnapshot(file, secondCut)) {
                secondCut--;
            }
        }
        int cutA = firstCut;
        int cutB = secondCut;
        int endA = Math.min(cutA + 90, cutB - 5);
        int endB = Math.min(total - 3, cutB + 170);
        check(endA > cutA + 20 && endB > cutB + 40, "the stretches to keep are too short: " + cutA + " to " + endA + " and " + cutB + " to " + endB);

        // The ticks to look at: each cut itself, the tick after it, and every tick the live game was noted on in what is kept
        java.util.TreeMap<Integer, RecordingSteps.Note> ticks = new java.util.TreeMap<>();
        for (int tick : new int[]{cutA, cutA + 1, cutB, cutB + 1}) {
            ticks.put(tick, null);
        }
        for (RecordingSteps.Note note : notes) {
            if (note.tick() > cutA && note.tick() < endA - 1 || note.tick() > cutB && note.tick() < endB - 1) {
                ticks.put(note.tick(), note);
            }
        }
        String recorderId = Playback.manifest().recorderId;
        java.util.Map<Integer, CraftSteps.Machines> wanted = new java.util.TreeMap<>();
        for (int tick : ticks.keySet()) {
            PlaybackSteps.jump(test, tick);
            wanted.put(tick, test.onClient(() -> CraftSteps.Machines.take(minecraft, recorderId)));
        }
        CraftSteps.Machines atFirstCut = wanted.get(cutA);
        CraftSteps.Machines atSecondCut = wanted.get(cutB);
        check(!CraftSteps.builtContraption || atFirstCut.contraptions() >= 1, "no contraption is in the world at the first cut, tick " + cutA);
        check(!CraftSteps.builtPlatform || atFirstCut.crafts() >= 1, "no physics craft is in the world at the first cut, tick " + cutA);
        check(!CraftSteps.builtBalloon || atSecondCut.crafts() >= 3, "the balloon and both platforms are not all in the world at the second cut, tick " + cutB + ": " + atSecondCut.crafts() + " craft");

        JsonArray keep = ranges(cutA, endA);
        keep.addAll(ranges(cutB, endB));
        JsonObject trimmed = finish(test, test.tool("replay_trim", args("source", originalId, "keep", keep, "name", "Machines")));
        check(trimmed.get("through_game").getAsBoolean(), "the trim with machines in it should have gone through the game");
        Path file = Path.of(trimmed.get("path").getAsString());
        String id = trimmed.get("recording_id").getAsString();
        test.waitFor(() -> Playback.loaded() && !Playback.jumping(), 60, "playback to settle after the trim");
        test.tool("replay_open", args("path", file.toString()));
        waitOpen(test, id, 0);

        double worstCraft = 0, worstContraption = 0, worstOther = 0, worstAngle = 0, worstAtCut = 0;
        int compared = 0;
        int live = 0;
        for (java.util.Map.Entry<Integer, RecordingSteps.Note> entry : ticks.entrySet()) {
            int tick = entry.getKey();
            int here = tick < endA ? tick - cutA : (endA - cutA) + (tick - cutB);
            PlaybackSteps.jump(test, here);
            CraftSteps.Machines now = test.onClient(() -> CraftSteps.Machines.take(minecraft, recorderId));
            CraftSteps.Machines then = wanted.get(tick);
            String where = "at tick " + here + " of the trimmed recording (tick " + tick + " of the original)";
            // Everything there once: nothing missing, nothing twice
            check(now.entities() == then.entities(), "there are " + now.entities() + " entities " + where + ", the original has " + then.entities());
            check(now.crafts() == then.crafts(), "there are " + now.crafts() + " physics craft " + where + ", the original has " + then.crafts());
            check(now.contraptions() == then.contraptions(), "there are " + now.contraptions() + " contraptions " + where + ", the original has " + then.contraptions());
            check(now.positions().keySet().equals(then.positions().keySet()), "what is in the world " + where + " is not what the original has:\n  " + now.positions().keySet() + "\n  "
                + then.positions().keySet());
            for (java.util.Map.Entry<String, net.minecraft.world.phys.Vec3> one : then.positions().entrySet()) {
                net.minecraft.world.phys.Vec3 at = now.positions().get(one.getKey());
                if (one.getKey().endsWith(CraftSteps.SEATED)) {
                    check(at.x == one.getValue().x, one.getKey().replace(CraftSteps.SEATED, "") + " is " + (at.x > 0 ? "riding" : "loose") + " " + where + " and was "
                        + (one.getValue().x > 0 ? "riding" : "loose") + " in the original");
                    continue;
                }
                double off = at.distanceTo(one.getValue());
                if (tick == cutA || tick == cutB) {
                    worstAtCut = Math.max(worstAtCut, off);
                }
                if (one.getKey().startsWith("craft ")) {
                    worstCraft = Math.max(worstCraft, off);
                } else if (one.getKey().startsWith("contraption ")) {
                    worstContraption = Math.max(worstContraption, off);
                } else {
                    worstOther = Math.max(worstOther, off);
                }
                // A third of a block, as for a jump (MOD-2); the person on a craft's deck is held to a quarter (MOD-4)
                double allowed = one.getKey().equals(CraftSteps.RECORDER_ON_CRAFT) ? 0.25 : 0.34;
                check(off <= allowed, one.getKey() + " is " + String.format("%.3f", off) + " blocks from where the original has it " + where + " (original " + one.getValue() + ", trimmed " + at + ")");
            }
            check(now.angles().keySet().equals(then.angles().keySet()), "the turning contraptions " + where + " are not the original's: " + now.angles().keySet() + " against " + then.angles().keySet());
            for (java.util.Map.Entry<Integer, Float> angle : then.angles().entrySet()) {
                double apart = Math.abs(net.minecraft.util.Mth.wrapDegrees(now.angles().get(angle.getKey()) - angle.getValue()));
                worstAngle = Math.max(worstAngle, apart);
                check(apart <= 1.0, "contraption " + angle.getKey() + " is at " + now.angles().get(angle.getKey()) + " degrees " + where + ", the original has it at " + angle.getValue());
            }
            compared++;
            if (entry.getValue() != null) {
                // And where the live game had it, by the same measure as a jump in an untrimmed recording
                PlaybackSteps.comparePositions(test, minecraft, entry.getValue(), "in the recording trimmed with machines in it, at its tick " + here);
                live++;
            }
        }
        test.log(String.format(java.util.Locale.ROOT, "machines across a trim: cuts at ticks %d and %d of the original, with %d contraption(s) and %d craft at the first and %d and %d at the second; "
                + "%d ticks compared with the original (%d of them with the live game too); furthest from the original: craft %.3f blocks, contraptions %.3f, mobs and the recorder %.3f, "
                + "on the ticks of the cuts themselves %.3f; contraption angle at most %.3f degrees out",
            cutA, cutB, atFirstCut.contraptions(), atFirstCut.crafts(), atSecondCut.contraptions(), atSecondCut.crafts(), compared, live, worstCraft, worstContraption, worstOther, worstAtCut, worstAngle));

        // The steps that follow count the recordings a moment is in, so this one goes again
        test.tool("replay_open", args("path", original.toString()));
        waitOpen(test, originalId, 0);
        Library.delete(file);
    }

    /**
     * Server resource packs through a trim and a combine (REC-19 and PLAY-17 with FILE-9 and FILE-10), on the recording
     * the pack steps made: trimmed across its pop and across its second push, each cut inside a segment, and combined
     * with a recording that has no pack. The right packs must be in force at every tick, and the pack stored once.
     * Comes in at the title screen and leaves there, with no recorded pack in force.
     */
    private static void packs(SelfTest test, Minecraft minecraft, Path bare) throws Exception {
        Path source = ResourcePackSteps.recording();
        if (source == null && Boolean.getBoolean("dailies.test.skipPacks")) {
            test.skip("the resource pack steps were switched off, so packs through a trim and a combine were not tried (REC-19 with FILE-9 and FILE-10)");
            return;
        }
        check(source != null && Files.isRegularFile(source), "the pack recording is not where the pack steps left it");
        int pop = ResourcePackSteps.popTick();
        int push = ResourcePackSteps.pushTick();
        RecordingManifest sourceManifest = RecordingFile.readManifest(source);
        int total = sourceManifest.totalTicks;
        String sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(ResourcePackSteps.pack()));
        int firstFrom = pop - 12;
        int secondFrom = push - 10;
        try (RecordingFile file = RecordingFile.open(source)) {
            while (!Assembler.needsSnapshot(file, firstFrom)) {
                firstFrom--;
            }
            while (!Assembler.needsSnapshot(file, secondFrom)) {
                secondFrom--;
            }
        }
        int aFrom = firstFrom;
        int aTo = pop + 12;
        int bFrom = secondFrom;
        int bTo = Math.min(total, push + 25);
        check(aFrom > 0 && aTo < bFrom - 6 && bTo > push + 5, "the pop at tick " + pop + " and the push at tick " + push + " are too close together, or to the ends, to cut around");
        check(!test.onClient(() -> ResourcePackSteps.packOn(minecraft)), "the test pack is in force before any recording with it is open");

        // --- A trim across the pop and across the second push ---
        JsonArray keep = ranges(aFrom, aTo);
        keep.addAll(ranges(bFrom, bTo));
        JsonObject trimmed = finish(test, test.tool("replay_trim", args("source", source.toString(), "keep", keep, "name", "Pack trim")));
        check(trimmed.get("through_game").getAsBoolean(), "the trim of the pack recording should have gone through the game");
        Path trimmedFile = Path.of(trimmed.get("path").getAsString());
        String trimmedId = trimmed.get("recording_id").getAsString();
        int firstLength = aTo - aFrom;
        check(trimmed.get("total_ticks").getAsInt() == firstLength + (bTo - bFrom), "the trimmed pack recording has the wrong length: " + trimmed.get("total_ticks"));
        test.waitFor(() -> !Playback.active() && minecraft.level == null, 60, "the game to be put back at the title screen after the trim");
        test.waitFor(() -> comedymonkey.dailies.packs.GamePacks.inForce().isEmpty(), 120, "the recorded pack to be taken off after the trim");
        ResourcePackSteps.waitForReload(test, minecraft);
        try (RecordingFile file = RecordingFile.open(trimmedFile)) {
            RecordingManifest manifest = file.manifest();
            check(manifest.packs.size() == 1 && manifest.packs.get(0).sha256.equals(sha) && manifest.packs.get(0).key.equals(sourceManifest.packs.get(0).key),
                "the trimmed recording should hold the one pack once, under its key: " + manifest.packs.size() + " packs");
            Path out = trimmedFile.resolveSibling("pack-out.zip.tmp");
            file.extractFile(manifest.packs.get(0), out);
            boolean same = java.util.Arrays.equals(Files.readAllBytes(out), ResourcePackSteps.pack());
            Files.delete(out);
            check(same, "the pack read back out of the trimmed recording is not the pack that was pushed");
            check(!manifest.packs.get(0).pieces.isEmpty(), "the pack has no pieces");
            for (String piece : manifest.packs.get(0).pieces) {
                check(file.hasShared(piece), "a piece of the pack is missing from the trimmed recording");
            }
            // What the snapshots written from playback say: the pack at the first cut, none at the second
            java.util.List<String> states = new java.util.ArrayList<>();
            for (RecordingManifest.Segment segment : manifest.segments) {
                comedymonkey.dailies.packs.PackLog log = new comedymonkey.dailies.packs.PackLog();
                boolean said = false;
                try (Records.Reader reader = new Records.Reader(file.openSegment(segment.number))) {
                    while (reader.next() && reader.kind() != Records.SNAPSHOT_END) {
                        if (reader.kind() == Records.EXTENSION && reader.name().equals(comedymonkey.dailies.packs.PackLog.ID)) {
                            said |= log.apply(reader.payload(), reader.bodyStart(), reader.length() - reader.bodyStart());
                        }
                    }
                }
                check(said, "the snapshot at tick " + segment.startTick + " of the trimmed recording does not say which packs are in force");
                states.add(segment.startTick + ":" + log.contents().size());
            }
            check(states.contains("0:1") && states.contains(firstLength + ":0"), "the snapshots at the cuts should say one pack in force at the first and none at the second: " + states);
        }

        int changesBefore = comedymonkey.dailies.playback.PackPlayback.changes();
        test.tool("replay_open", args("path", trimmedFile.toString()));
        test.waitFor(() -> Playback.lastError() != null || Playback.loaded() && !Playback.jumping() && trimmedId.equals(Playback.recordingId()), 180, "the trimmed pack recording to open");
        check(Playback.lastError() == null, "the trimmed pack recording did not open: " + Playback.lastError());
        int popHere = pop - aFrom;
        int pushHere = firstLength + (push - bFrom);
        packAt(test, minecraft, 0, true, "at the first tick of the trim, where the fresh snapshot says the pack is on");
        packAt(test, minecraft, popHere - 1, true, "just before the pop");
        packAt(test, minecraft, popHere, false, "at the pop");
        packAt(test, minecraft, firstLength, false, "at the cut, where the fresh snapshot says no pack is on");
        packAt(test, minecraft, pushHere - 1, false, "just before the second push");
        packAt(test, minecraft, pushHere, true, "at the second push");
        packAt(test, minecraft, firstLength + (bTo - bFrom), true, "at the end");
        packAt(test, minecraft, firstLength + 2, false, "back to just after the cut");
        packAt(test, minecraft, 2, true, "back at the start");
        test.onClientRun(() -> Playback.playTo(popHere + 6));
        test.waitFor(() -> Playback.paused() && Playback.position() == popHere + 6, 180, "play through the pop in the trimmed recording");
        test.waitFor(() -> !Playback.jumping(), 180, "the packs to settle after playing through the pop");
        check(!test.onClient(() -> ResourcePackSteps.packOn(minecraft)), "playing through the pop in the trimmed recording left the pack in force");
        check(test.onClient(comedymonkey.dailies.playback.PackPlayback::failed).isEmpty(), "the trimmed recording has packs that could not be applied: "
            + test.onClient(comedymonkey.dailies.playback.PackPlayback::failed));
        int trimChanges = comedymonkey.dailies.playback.PackPlayback.changes() - changesBefore;
        PlaybackSteps.close(test);

        // --- A trim that keeps only the stretch with no pack in force holds no pack ---
        JsonObject gap = finish(test, test.tool("replay_trim", args("source", source.toString(), "keep", ranges(pop + 3, push - 3), "name", "Pack gap")));
        Path gapFile = Path.of(gap.get("path").getAsString());
        test.waitFor(() -> !Playback.active() && minecraft.level == null, 60, "the game to be put back at the title screen after the second pack trim");
        RecordingManifest gapManifest = RecordingFile.readManifest(gapFile);
        check(gapManifest.packs.isEmpty(), "a trim that kept only ticks with no pack in force still holds " + gapManifest.packs.size() + " pack(s)");
        check(Files.size(gapFile) < Files.size(trimmedFile), "the trim without the pack is not smaller than the trim with it");

        // --- Combined with a recording that has no pack, on either side of it ---
        RecordingManifest bareManifest = RecordingFile.readManifest(bare);
        check(bareManifest.packs.isEmpty(), "the second world's recording was expected to have no pack");
        JsonObject combined = finish(test, test.tool("replay_combine", args("sources", array(source.toString(), bare.toString(), source.toString()), "name", "Pack episode")));
        Path episodeFile = Path.of(combined.get("path").getAsString());
        String episodeId = combined.get("recording_id").getAsString();
        int bareTicks = bareManifest.totalTicks;
        check(combined.get("total_ticks").getAsInt() == total + bareTicks + total, "the combined pack recording has the wrong length: " + combined.get("total_ticks"));
        try (RecordingFile file = RecordingFile.open(episodeFile); RecordingFile once = RecordingFile.open(source)) {
            RecordingManifest manifest = file.manifest();
            check(manifest.packs.size() == 1 && manifest.packs.get(0).sha256.equals(sha), "the pack should be stored once in a recording that uses it twice: " + manifest.packs.size() + " packs");
            for (String piece : manifest.packs.get(0).pieces) {
                check(file.hasShared(piece), "a piece of the pack is missing from the combined recording");
            }
            check(once.manifest().packs.size() == 1, "the source lost its pack");
        }
        test.tool("replay_open", args("path", episodeFile.toString()));
        test.waitFor(() -> Playback.lastError() != null || Playback.loaded() && !Playback.jumping() && episodeId.equals(Playback.recordingId()), 180, "the combined pack recording to open");
        check(Playback.lastError() == null, "the combined pack recording did not open: " + Playback.lastError());
        packAt(test, minecraft, 3, true, "at the start of the combined recording");
        packAt(test, minecraft, total - 3, true, "just before the first boundary");
        packAt(test, minecraft, total + 5, false, "after the boundary, in the recording that has no pack");
        packAt(test, minecraft, total + bareTicks - 2, false, "just before the second boundary");
        check(named(test, minecraft, "Second") == 1, "between the boundaries the world is not the second recording's");
        packAt(test, minecraft, total + bareTicks + 5, true, "after the second boundary, back in the recording with the pack");
        packAt(test, minecraft, total + bareTicks + pop + 2, false, "after the pop of the second copy");
        // Play runs across a boundary by itself, and the packs switch with the world
        packAt(test, minecraft, total - 8, true, "before playing across the boundary");
        test.onClientRun(() -> Playback.playTo(total + 8));
        test.waitFor(() -> Playback.paused() && Playback.position() == total + 8, 180, "play across the boundary into the recording with no pack");
        test.waitFor(() -> !Playback.jumping(), 180, "the packs to settle after the boundary");
        check(!test.onClient(() -> ResourcePackSteps.packOn(minecraft)), "playing across the boundary left the first recording's pack in force");
        check(test.onClient(comedymonkey.dailies.playback.PackPlayback::failed).isEmpty(), "the combined recording has packs that could not be applied");
        PlaybackSteps.close(test);
        test.waitFor(() -> comedymonkey.dailies.packs.GamePacks.inForce().isEmpty(), 120, "the recorded pack to be taken off on closing");
        ResourcePackSteps.waitForReload(test, minecraft);
        check(!test.onClient(() -> ResourcePackSteps.packOn(minecraft)), "the test pack is still in force after the combined recording was closed");
        test.log("server resource packs across edits: trimmed ticks " + aFrom + " to " + aTo + " and " + bFrom + " to " + bTo + " of " + total + " (pop at " + pop + ", push at " + push
            + "), the pack on and off at the right ticks with " + trimChanges + " changes of packs; a trim of the stretch between holds no pack; combined with a recording without one, "
            + combined.get("total_ticks") + " ticks, the pack stored once (" + Files.size(episodeFile) / 1024 + " KB against " + Files.size(source) / 1024 + " KB for the source) and switched at both boundaries");
        Library.delete(trimmedFile);
        Library.delete(gapFile);
        Library.delete(episodeFile);
    }

    /** Jumps to a tick and checks whether the test pack is in force there */
    private static void packAt(SelfTest test, Minecraft minecraft, int tick, boolean wanted, String where) throws Exception {
        test.onClientRun(() -> Playback.jumpTo(tick, true));
        test.waitFor(() -> !Playback.jumping() && Playback.position() == tick, 180, "the jump to tick " + tick + " (" + where + ")");
        Thread.sleep(60);
        test.waitFor(() -> !Playback.jumping(), 180, "the recording's resource packs to be loaded");
        boolean inForce = test.onClient(() -> ResourcePackSteps.packOn(minecraft));
        check(inForce == wanted, where + " (tick " + tick + ") the pack is " + (inForce ? "in force" : "not in force"));
    }

    // --- Helpers ---

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
        throw new IllegalStateException("no clip holds the kill: " + shorten(listed.toString()));
    }

    private static JsonObject location(JsonObject clip, String recordingId) {
        for (JsonElement element : clip.getAsJsonArray("locations")) {
            if (element.getAsJsonObject().get("recording_id").getAsString().equals(recordingId)) {
                return element.getAsJsonObject();
            }
        }
        throw new IllegalStateException("the clip is not listed in recording " + recordingId + ": " + clip.get("locations"));
    }

    private static JsonArray ranges(int from, int to) {
        JsonArray pair = new JsonArray();
        pair.add(from);
        pair.add(to);
        JsonArray list = new JsonArray();
        list.add(pair);
        return list;
    }

    private static JsonArray array(String... values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    /** A recording of ten ticks with nothing in it, under the description given: for what combining says about descriptions */
    private static void tenTicks(Path file, RecordingManifest manifest) throws Exception {
        RecordingWriter writer = RecordingWriter.create(file, manifest);
        Records.Writer few = new Records.Writer();
        few.number(Records.SNAPSHOT_BEGIN, 0);
        few.record(Records.DISCONTINUITY);
        few.record(Records.SNAPSHOT_END);
        few.number(Records.TICK, 10);
        byte[] bytes = few.take();
        writer.beginSegment(0);
        writer.appendToSegment(bytes, 0, bytes.length, 10);
        writer.finish();
    }

    /** Polls a combine or trim until it is over, as a caller of the tools would, and returns what it says once done */
    private static JsonObject finish(SelfTest test, JsonObject started) throws Exception {
        String id = started.get("job_id").getAsString();
        long deadline = System.currentTimeMillis() + 240_000;
        double last = -1.0;
        while (true) {
            JsonObject status = test.tool("replay_job_status", args("job_id", id));
            String state = status.get("state").getAsString();
            double progress = status.get("progress").getAsDouble();
            check(progress >= last - 1e-9 && progress >= 0.0 && progress <= 1.0, "the progress of job " + id + " went from " + last + " to " + progress);
            last = progress;
            if (state.equals("done")) {
                check(progress == 1.0 && status.has("path") && Files.isRegularFile(Path.of(status.get("path").getAsString())), "a finished job has no file: " + status);
                return status;
            }
            check(state.equals("running"), "the " + status.get("kind").getAsString() + " did not finish: " + status);
            check(System.currentTimeMillis() < deadline, "the " + status.get("kind").getAsString() + " is still running after four minutes: " + status);
            Thread.sleep(100);
        }
    }

    private static void waitOpen(SelfTest test, String recordingId, int tick) throws Exception {
        test.waitFor(() -> Playback.lastError() != null || Playback.loaded() && !Playback.jumping() && recordingId.equals(Playback.recordingId()) && Playback.position() == tick, 120,
            "recording " + recordingId + " to be open at tick " + tick + " (open: " + Playback.recordingId() + " at " + Playback.position() + ")");
        check(Playback.lastError() == null, "playback failed: " + Playback.lastError());
        // A couple of drawn frames, so what is checked is what a person would see
        Thread.sleep(80);
    }

    private static int named(SelfTest test, Minecraft minecraft, String name) throws Exception {
        return test.onClient(() -> {
            int count = 0;
            for (Entity entity : minecraft.level.entitiesForRendering()) {
                if (entity.hasCustomName() && entity.getCustomName().getString().equals(name)) {
                    count++;
                }
            }
            return count;
        });
    }

    /** The ids of the extension records in a recording's opening snapshot, up to its first tick */
    private static Set<String> extensionsInFirstSnapshot(RecordingFile file) throws Exception {
        Set<String> ids = new HashSet<>();
        try (Records.Reader reader = new Records.Reader(file.openSegment(file.segments().get(0).number))) {
            while (reader.next() && reader.kind() != Records.TICK) {
                if (reader.kind() == Records.EXTENSION) {
                    ids.add(reader.name());
                }
            }
        }
        return ids;
    }

    private static String shorten(String text) {
        return text.length() > 900 ? text.substring(0, 900) + "..." : text;
    }

}
