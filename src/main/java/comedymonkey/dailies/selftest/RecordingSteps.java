package comedymonkey.dailies.selftest;

import com.google.gson.JsonObject;
import comedymonkey.dailies.capture.Audit;
import comedymonkey.dailies.capture.GameCodec;
import comedymonkey.dailies.capture.Recorder;
import comedymonkey.dailies.file.RecordingFile;
import comedymonkey.dailies.file.RecordingManifest;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.world.InteractionHand;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static comedymonkey.dailies.selftest.SelfTest.args;
import static comedymonkey.dailies.selftest.SelfTest.check;

/**
 * Chapter XIV step 1, "Record": start, do things worth seeing, pause and resume, cross segment boundaries, finish,
 * and then prove what the file holds. Also cancel, and a recording cut short as a crash would leave it.
 */
final class RecordingSteps {

    /** The recording the later steps play back */
    static Path recording;
    /** Where things were in the live game at known ticks of that recording */
    static final List<Note> notes = new java.util.ArrayList<>();

    record Note(int tick, java.util.Map<String, net.minecraft.world.phys.Vec3> positions) {
        /** Call on the client thread, so the tick and the positions belong together */
        static Note take(Minecraft minecraft) {
            return new Note(Recorder.get().recordingTick(), CraftSteps.positions(minecraft, null));
        }
    }

    private RecordingSteps() {
    }

    static void run(SelfTest test) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        JsonObject none = new JsonObject();

        // Refusals that tell the caller what to do
        check(test.toolError("recording_pause", none).contains("No recording"), "pausing with no recording did not say so");
        check(test.toolError("recording_finish", none).contains("No recording"), "finishing with no recording did not say so");

        // Cancel leaves nothing behind (REC-1)
        test.tool("recording_start", none);
        test.sleepTicks(30);
        test.tool("recording_cancel", none);
        test.sleepTicks(20);
        check(Recorder.unfinished().isEmpty(), "a cancelled recording left its file behind");

        // A recording cut short, as a crash leaves it, is found and recovered (REC-6)
        test.tool("recording_start", none);
        test.sleepTicks(80);
        test.onClientRun(() -> Recorder.get().addMarker(0x55FF55, "before the crash", true));
        test.sleepTicks(60);
        test.onClientRun(() -> {
            // What a crash does: the writer stops and the file never gets its ending
            try {
                java.lang.reflect.Method fail = Recorder.class.getDeclaredMethod("fail", Throwable.class);
                fail.setAccessible(true);
                fail.invoke(Recorder.get(), new RuntimeException("Self test: pretending the game crashed"));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
        test.sleepTicks(20);
        List<Path> unfinished = Recorder.unfinished();
        check(unfinished.size() == 1, "expected one unfinished recording after the pretend crash, found " + unfinished.size());
        check(RecordingFile.isUnfinished(unfinished.get(0)), "the crashed recording does not look unfinished");
        Path recovered = Recorder.recover(unfinished.get(0));
        RecordingManifest recoveredManifest = RecordingFile.readManifest(recovered);
        check(recoveredManifest.recovered && recoveredManifest.finished, "the recovered recording is not marked as recovered");
        check(recoveredManifest.totalTicks >= 80, "recovery kept only " + recoveredManifest.totalTicks + " ticks of about 140");
        check(recoveredManifest.markers.size() == 1, "the marker dropped before the crash was lost");
        Files.delete(recovered);
        test.log("crash recovery kept " + recoveredManifest.totalTicks + " ticks");

        // What the game's frames cost with no recording, to compare against
        FrameTimes.Result idle = FrameTimes.measure(test, 60);

        // The real thing
        JsonObject started = test.tool("recording_start", none);
        check(started.get("active").getAsBoolean(), "recording_start did not start a recording");
        check(test.toolError("recording_start", none).contains("already"), "starting twice did not say a recording is already being made");
        String id = started.get("recording_id").getAsString();
        test.sleepTicks(20);

        test.command("summon minecraft:armor_stand 8.5 -60 6.5 {CustomName:'\"Mover\"',NoGravity:1b}");
        test.command("summon minecraft:pig 3 -60 3 {CustomName:'\"Hamlet\"',NoAI:1b}");
        test.command("setblock 2 -60 0 minecraft:gold_block");
        test.sleepTicks(10);
        test.command("setblock 2 -60 0 minecraft:air destroy");
        test.command("summon minecraft:zombie 0 -60 4 {NoAI:1b,PersistenceRequired:1b}");
        test.sleepTicks(10);
        test.command("damage @e[type=minecraft:zombie,limit=1,sort=nearest] 100 minecraft:player_attack by @s");
        test.command("item replace entity @s weapon.mainhand with minecraft:diamond_sword");
        test.sleepTicks(10);
        test.onClientRun(() -> minecraft.player.swing(InteractionHand.MAIN_HAND));
        test.command("tp @s 4.5 -60 0.5 90 10");
        test.sleepTicks(10);

        // A known piece of sound, as if from voice chat, close to the kill so that its clip takes it in (MOM-31)
        OwnerFeatureSteps.speak(test);

        // Chapter XIV step 2: machines, built while the recording runs
        CraftSteps.build(test);

        JsonObject marker = test.tool("recording_add_marker", args("color", "#33AAFF", "description", "The pig"));
        check(marker.get("tick").getAsInt() > 0, "the marker has no tick");

        // Pause: the clock stands still and what happens meanwhile is not captured (REC-3)
        JsonObject paused = test.tool("recording_pause", none);
        check(paused.get("paused").getAsBoolean(), "recording_pause did not pause");
        int tickAtPause = paused.get("tick").getAsInt();
        test.command("setblock 6 -60 0 minecraft:diamond_block");
        test.sleepTicks(40);
        check(test.tool("dailies_status", none).getAsJsonObject("recording").get("tick").getAsInt() == tickAtPause, "the clock ran on while paused");
        test.tool("recording_resume", none);

        // Long enough to cross at least two more segment boundaries with the short test segments (REC-20, REC-24)
        int segmentTicks = Integer.getInteger("dailies.test.segmentTicks", 6000);
        FrameTimes.begin();
        check(segmentTicks <= 200, "the scripted run needs short segments: set dailies.test.segmentTicks");
        for (int i = 0; i < segmentTicks * 2 / 20 + 3; i++) {
            test.command("tp @s " + (4.5 + (i % 5)) + " -60 0.5 " + (i * 20 % 360) + " 0");
            test.command("tp @e[type=minecraft:armor_stand,name=Mover,limit=1] " + (8.5 + (i % 4) * 1.5) + " -60 " + (6.5 + (i % 3)) + " " + (i * 45 % 360) + " 0");
            test.sleepTicks(20);
            notes.add(test.onClient(() -> Note.take(minecraft)));
            // Nothing else may happen on the tick a note belongs to
            test.sleepTicks(4);
        }

        // How far each thing travelled between the first and the last note, so it is plain what the position checks prove
        StringBuilder travel = new StringBuilder();
        java.util.Map<String, double[]> spans = new java.util.TreeMap<>();
        for (Note note : notes) {
            note.positions().forEach((name, at) -> {
                double[] span = spans.computeIfAbsent(name, key -> new double[]{at.y, at.y, at.x, at.x});
                span[0] = Math.min(span[0], at.y);
                span[1] = Math.max(span[1], at.y);
                span[2] = Math.min(span[2], at.x);
                span[3] = Math.max(span[3], at.x);
            });
        }
        spans.forEach((name, span) -> travel.append(String.format(" %s %.1f up-down %.1f across;", name.length() > 20 ? name.substring(0, 20) : name, span[1] - span[0], span[3] - span[2])));
        test.log("range of the noted positions, in blocks:" + travel);
        FrameTimes.Result recordingFrames = FrameTimes.end();
        test.log("frame times idle: " + idle + "; while recording, with teleports and two snapshots: " + recordingFrames);
        check(recordingFrames.average() <= idle.average() * 1.5 + 2.0, "recording slowed the game: frames averaged " + recordingFrames.average() + " ms against " + idle.average() + " ms idle");

        JsonObject finished = test.tool("recording_finish", args("name", "Self test"));
        recording = Path.of(finished.get("path").getAsString());
        check(Files.exists(recording) && recording.getFileName().toString().equals("Self test" + RecordingFile.EXTENSION), "the recording was not saved under its name: " + recording);
        check(!test.tool("dailies_status", none).getAsJsonObject("recording").get("active").getAsBoolean(), "the recording is still active after finishing");
        check(Recorder.unfinished().isEmpty(), "finishing left an unfinished file behind");

        // What the file holds
        ClientPacketListener listener = minecraft.getConnection();
        GameCodec codec = new GameCodec(listener.registryAccess(), listener.getConnectionType());
        int ownId = minecraft.player.getId();
        try (RecordingFile file = RecordingFile.open(recording)) {
            RecordingManifest manifest = file.manifest();
            check(manifest.id.equals(id), "the file's id is not the one recording_start reported");
            check(manifest.name.equals("Self test") && manifest.finished && !manifest.recovered, "the manifest's name or state is wrong");
            check(manifest.recorderName.equals(minecraft.player.getGameProfile().getName()), "the recorder's name is missing");
            check(manifest.worldName.equals(WorldSteps.WORLD) && manifest.singleplayer, "the world name is missing");
            check(manifest.mods.stream().anyMatch(mod -> mod.id.equals("dailies")) && manifest.mods.stream().anyMatch(mod -> mod.id.equals("minecraft")), "the mod list is incomplete");
            check(manifest.markers.size() == 1 && manifest.markers.get(0).description.equals("The pig") && manifest.markers.get(0).colour == 0x33AAFF
                && manifest.markers.get(0).position != null, "the marker was not stored as given");
            check(manifest.segments.size() >= 4, "expected at least 4 segments (start, resume, two boundaries), found " + manifest.segments.size());
            check(manifest.totalTicks >= segmentTicks * 2, "the recording is only " + manifest.totalTicks + " ticks long");

            Audit audit = Audit.of(file, codec, true);
            check(audit.problems.isEmpty(), "the recording has problems: " + audit.problems);
            check(audit.snapshots == manifest.segments.size(), "not every segment starts with a snapshot");
            check(audit.count(ClientboundAnimatePacket.class) >= 1, "the recorder's arm swing was not captured");
            check(audit.count(ClientboundSetEquipmentPacket.class) >= 1, "the recorder's held item was not captured");
            long ownMoves = audit.seen.stream().filter(seen -> seen.packet() instanceof ClientboundTeleportEntityPacket move && move.getId() == ownId).count();
            check(ownMoves >= 10, "the recorder's own movement was captured only " + ownMoves + " times");
            check(audit.packets.containsKey("ClientboundAddEntityPacket") || audit.bundles > 0, "no entity spawn was captured");
            check(audit.packets.containsKey("ClientboundBlockUpdatePacket") || audit.packets.containsKey("ClientboundSectionBlocksUpdatePacket"), "no block change was captured");
            // Snapshots (REC-20, REC-21, FILE-5)
            check(audit.worlds == manifest.segments.size(), "not every snapshot describes its registries");
            long logins = audit.seen.stream().filter(seen -> seen.inSnapshot() && seen.packet() instanceof net.minecraft.network.protocol.game.ClientboundLoginPacket login
                && login.playerId() == comedymonkey.dailies.capture.Snapshots.VIEWER_ENTITY_ID).count();
            check(logins == manifest.segments.size(), "not every snapshot starts a world for the viewer");
            long ownSpawns = audit.seen.stream().filter(seen -> seen.inSnapshot() && seen.packet() instanceof net.minecraft.network.protocol.game.ClientboundAddEntityPacket spawn
                && spawn.getId() == ownId && spawn.getType() == net.minecraft.world.entity.EntityType.PLAYER).count();
            check(ownSpawns == manifest.segments.size(), "the recorder is not in every snapshot");
            long pigs = audit.seen.stream().filter(seen -> seen.inSnapshot() && seen.packet() instanceof net.minecraft.network.protocol.game.ClientboundAddEntityPacket spawn
                && spawn.getType() == net.minecraft.world.entity.EntityType.PIG).count();
            check(pigs >= manifest.segments.size() - 1, "the pig is missing from the later snapshots");
            check(audit.count(net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket.class) >= audit.sharedPackets && audit.sharedPackets >= 9 * manifest.segments.size(),
                "snapshots hold too few chunks: " + audit.sharedPackets);
            check(file.sharedCount() < audit.sharedPackets / 2, "unchanged chunks were stored again: " + file.sharedCount() + " stored for " + audit.sharedPackets + " uses");
            test.log("snapshots: " + audit.sharedPackets + " chunk uses stored as " + file.sharedCount() + " pieces; last snapshot held the game for "
                + String.format("%.1f", comedymonkey.dailies.capture.Snapshots.lastMillis) + " ms; file is " + java.nio.file.Files.size(recording) / 1024 + " KB");
            test.log("recording holds " + manifest.totalTicks + " ticks in " + manifest.segments.size() + " segments, " + audit.packets.values().stream().mapToInt(Integer::intValue).sum()
                + " packets of " + audit.packets.size() + " kinds, " + audit.bundles + " bundles, " + audit.payloads.size() + " kinds of mod message");
        }
    }

}
