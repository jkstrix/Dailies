package comedymonkey.dailies.selftest;

import com.google.gson.JsonObject;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.capture.Recorder;
import comedymonkey.dailies.export.Export;
import comedymonkey.dailies.file.RecordingFile;
import comedymonkey.dailies.file.RecordingManifest;
import comedymonkey.dailies.file.Records;
import comedymonkey.dailies.packs.GamePacks;
import comedymonkey.dailies.packs.PackLog;
import comedymonkey.dailies.playback.PackPlayback;
import comedymonkey.dailies.playback.Playback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundResourcePackPopPacket;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.resources.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static comedymonkey.dailies.selftest.SelfTest.args;
import static comedymonkey.dailies.selftest.SelfTest.check;

/**
 * Chapter XIV step 2b, "Server resource packs" (REC-19, PLAY-17). The scripted run has no dedicated server, so the
 * world's built-in server pushes a small pack, made here and served from this machine, to the one player, and the
 * script answers the game's question as a person would. The recording made meanwhile is then proved to hold the pack,
 * and playback to put it on and take it off at the recorded ticks.
 */
final class ResourcePackSteps {

    /** A file only the test pack has */
    private static final ResourceLocation MARKER = ResourceLocation.fromNamespaceAndPath("minecraft", "dailies_selftest/marker.txt");
    /** A file only the pack that is declined has */
    private static final ResourceLocation DECLINED_MARKER = ResourceLocation.fromNamespaceAndPath("minecraft", "dailies_selftest/declined.txt");
    private static final String LANG_KEY = "dailies.selftest.server_pack";

    private static Path recording;
    private static byte[] pack;
    private static List<String> ownPacks;
    private static List<String> ownSelected;
    private static int popTick = -1;
    private static int pushTick = -1;

    private ResourcePackSteps() {
    }

    // ------------------------------------------------------------------ Live: the server pushes, the player answers

    static void record(SelfTest test) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        JsonObject none = new JsonObject();
        IntegratedServer server = minecraft.getSingleplayerServer();
        check(server != null, "there is no built-in server to push a resource pack from");
        // Packs written out by an earlier run would be counted as this one's
        deleteFolder(PackPlayback.cacheFolder());
        ownPacks = test.onClient(() -> List.copyOf(minecraft.options.resourcePacks));
        ownSelected = test.onClient(() -> List.copyOf(minecraft.getResourcePackRepository().getSelectedIds()));
        check(!test.onClient(() -> on(minecraft, MARKER)), "the test pack is in force before it has been pushed");

        pack = buildPack("marker.txt", true);
        byte[] declinedPack = buildPack("declined.txt", false);
        Map<String, byte[]> served = new HashMap<>();
        served.put("/test-pack.zip", pack);
        served.put("/declined-pack.zip", declinedPack);
        UUID first = UUID.randomUUID();
        UUID declined = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        try (ServerSocket http = new ServerSocket(0, 8, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}))) {
            serve(http, served);
            String base = "http://127.0.0.1:" + http.getLocalPort();

            // Pushed before any recording is running, as a server does while the player is still joining
            send(server, new ClientboundResourcePackPushPacket(first, base + "/test-pack.zip", sha1(pack), false, Optional.of(Component.literal("Dailies self test"))));
            answer(test, minecraft, true);
            test.waitFor(() -> GamePacks.inForce(first), 120, "the pushed pack to be loaded by the game");
            waitForReload(test, minecraft);
            check(test.onClient(() -> on(minecraft, MARKER) && net.minecraft.locale.Language.getInstance().has(LANG_KEY)), "the pushed pack was loaded but its files are not in force");

            test.tool("recording_start", none);
            test.waitFor(() -> Recorder.get().recordingTick() >= 30, 30, "the recording to get going");

            // Taken off by the server (REC-19)
            send(server, new ClientboundResourcePackPopPacket(Optional.of(first)));
            test.waitFor(() -> !GamePacks.inForce(first), 120, "the popped pack to be taken off");
            waitForReload(test, minecraft);
            check(!test.onClient(() -> on(minecraft, MARKER)), "the popped pack is still in force in the live game");
            int offAt = Recorder.get().recordingTick();
            test.waitFor(() -> Recorder.get().recordingTick() >= offAt + 30, 30, "the recording to run on with the pack off");

            // One the player says no to: never applied, live or in playback
            send(server, new ClientboundResourcePackPushPacket(declined, base + "/declined-pack.zip", sha1(declinedPack), false, Optional.empty()));
            answer(test, minecraft, false);
            test.sleepTicks(20);
            check(!test.onClient(() -> on(minecraft, DECLINED_MARKER)) && GamePacks.inForce().isEmpty(), "a declined pack was loaded all the same");

            // The same pack again, under another id: in force again, and its bytes are not stored twice
            send(server, new ClientboundResourcePackPushPacket(second, base + "/test-pack.zip", sha1(pack), false, Optional.empty()));
            answer(test, minecraft, true);
            test.waitFor(() -> GamePacks.inForce(second), 120, "the pack pushed again to be loaded");
            waitForReload(test, minecraft);
            int onAt = Recorder.get().recordingTick();
            test.waitFor(() -> Recorder.get().recordingTick() >= onAt + 40, 30, "the recording to run on with the pack on");

            JsonObject finished = test.tool("recording_finish", args("name", "Pack test"));
            // Out of the recordings folder, so that the steps that count recordings see only their own
            Path saved = Path.of(finished.get("path").getAsString());
            Path folder = Host.get().dataDirectory().resolve("test-packs");
            Files.createDirectories(folder);
            recording = folder.resolve("Pack test" + RecordingFile.EXTENSION);
            Files.move(saved, recording, StandardCopyOption.REPLACE_EXISTING);

            // The live game is left as it was found
            send(server, new ClientboundResourcePackPopPacket(Optional.empty()));
            test.waitFor(() -> GamePacks.inForce().isEmpty(), 120, "the pack to be taken off after recording");
            waitForReload(test, minecraft);

            stress(test, minecraft, server, base);
        }

        // What the file holds (REC-19)
        try (RecordingFile file = RecordingFile.open(recording)) {
            RecordingManifest manifest = file.manifest();
            check(manifest.packs.size() == 1, "the recording holds " + manifest.packs.size() + " packs, not the one pack once");
            RecordingManifest.Pack stored = manifest.packs.get(0);
            check(stored.size == pack.length && stored.sha256.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pack))),
                "the stored pack is not the pack that was pushed");
            Path out = recording.resolveSibling("extracted.zip");
            file.extractFile(stored, out);
            check(java.util.Arrays.equals(Files.readAllBytes(out), pack), "the pack read back out of the recording differs from the one pushed");
            Files.delete(out);

            PackLog log = new PackLog();
            int inSnapshots = 0;
            boolean startsOn = false;
            for (RecordingManifest.Segment segment : manifest.segments) {
                try (Records.Reader reader = new Records.Reader(file.openSegment(segment.number))) {
                    int tick = segment.startTick;
                    boolean snapshot = false;
                    while (reader.next()) {
                        int kind = reader.kind();
                        if (kind == Records.TICK) {
                            tick += reader.number();
                        } else if (kind == Records.SNAPSHOT_BEGIN) {
                            snapshot = true;
                        } else if (kind == Records.SNAPSHOT_END) {
                            snapshot = false;
                        } else if (kind == Records.EXTENSION && reader.name().equals(PackLog.ID)) {
                            List<String> before = log.contents();
                            log.apply(reader.payload(), reader.bodyStart(), reader.length() - reader.bodyStart());
                            List<String> after = log.contents();
                            if (snapshot) {
                                inSnapshots++;
                                check(segment.number == manifest.segments.get(0).number || before.equals(after),
                                    "the snapshot at tick " + tick + " says " + after + " is in force where the records before it say " + before);
                                if (segment.number == manifest.segments.get(0).number) {
                                    startsOn = after.equals(List.of(stored.key));
                                }
                            } else if (!before.isEmpty() && after.isEmpty()) {
                                popTick = tick;
                            } else if (before.isEmpty() && !after.isEmpty()) {
                                pushTick = tick;
                                PackLog.Pack pushed = log.inForce().get(0);
                                check(pushed.id().equals(second) && pushed.url().endsWith("/test-pack.zip") && pushed.hash().equals(sha1(pack)) && !pushed.required()
                                    && pushed.content().equals(stored.key), "the push was not recorded as the server sent it: " + pushed);
                            }
                        }
                    }
                }
            }
            check(inSnapshots == manifest.segments.size(), "not every snapshot says which packs are in force: " + inSnapshots + " of " + manifest.segments.size());
            check(startsOn, "the pack pushed before the recording started is not in its first snapshot");
            check(popTick > 0 && pushTick > popTick, "the pop and the second push were not recorded at their ticks (pop " + popTick + ", push " + pushTick + ")");
            check(log.refusals().size() == 1 && log.refusals().get(0).pack().id().equals(declined) && log.refusals().get(0).reason().equals("DECLINED")
                && log.refusals().get(0).pack().url().endsWith("/declined-pack.zip"), "the declined pack was not recorded as not applied: " + log.refusals());
            check(manifest.segments.size() >= 2, "the pack recording has only " + manifest.segments.size() + " segment");
            test.log("pack recording: " + manifest.totalTicks + " ticks, the pack (" + pack.length + " bytes) stored once, popped at tick " + popTick + ", pushed again at tick "
                + pushTick + ", one push declined");
        }
    }

    // ------------------------------------------------------------------ Playback puts the packs on and takes them off

    static void play(SelfTest test) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        check(recording != null, "the pack recording was not made");
        check(!test.onClient(() -> on(minecraft, MARKER)), "the test pack is in force before its recording is opened");

        int changesBefore = PackPlayback.changes();
        test.tool("replay_open", args("path", recording.toString()));
        test.waitFor(() -> Playback.loaded() || Playback.lastError() != null, 90, "the pack recording to open");
        check(Playback.lastError() == null, "the pack recording did not open: " + Playback.lastError());
        settle(test);
        int total = Playback.total();
        // In force from the opening tick, having been pushed before the recording began
        check(test.onClient(() -> on(minecraft, MARKER) && net.minecraft.locale.Language.getInstance().has(LANG_KEY)), "playback did not apply the recorded pack on opening");
        check(test.tool("dailies_status", new JsonObject()).getAsJsonObject("playback").get("server_packs_applied").getAsInt() == 1, "dailies_status does not report the pack as applied");
        int opening = PackPlayback.changes() - changesBefore;
        check(opening == 1, "opening the recording changed the packs " + opening + " times, not once");
        int changes = PackPlayback.changes();

        // Jumps: the packs change only when the set in force differs where the jump lands. Text is measured on another
        // thread all the while, as other mods do, and after every jump the game's font must be a live one (see stress)
        Thread measuring = measureText(minecraft);
        expect(test, minecraft, popTick - 1, true, changes, "just before the pop");
        expect(test, minecraft, 0, true, changes, "back at the start");
        expect(test, minecraft, popTick, false, ++changes, "at the pop");
        expect(test, minecraft, pushTick - 1, false, changes, "just before the second push");
        expect(test, minecraft, popTick + 1, false, changes, "back to just after the pop");
        expect(test, minecraft, pushTick, true, ++changes, "at the second push");
        expect(test, minecraft, total, true, changes, "at the end");
        expect(test, minecraft, pushTick + 5, true, changes, "back to just after the second push");
        stopMeasuring(measuring);
        check(!test.onClient(() -> on(minecraft, DECLINED_MARKER)), "the declined pack was applied in playback");
        check(test.onClient(PackPlayback::failed).isEmpty(), "playback reports packs it could not apply: " + test.onClient(PackPlayback::failed));

        // Playing through the pop: the pack comes off there, and the recording waits while it does
        expect(test, minecraft, popTick - 15, true, changes, "before playing through the pop");
        test.onClientRun(() -> Playback.playTo(popTick + 10));
        test.waitFor(() -> Playback.paused() && Playback.position() == popTick + 10, 180, "play through the pop");
        settle(test);
        check(!test.onClient(() -> on(minecraft, MARKER)), "playing through the pop left the pack in force");
        check(PackPlayback.changes() == ++changes, "playing through the pop changed the packs " + (PackPlayback.changes() - changes + 1) + " times");

        // The scene's look setting, and exports (PLAY-17)
        expect(test, minecraft, pushTick + 10, true, ++changes, "before the look setting is tried");
        test.tool("camera_set", args("x", 20.5, "y", -57.0, "z", 20.5, "yaw", 0.0, "pitch", 90.0));
        Path folder = minecraft.gameDirectory.toPath().resolve("dailies").resolve("exports");
        BufferedImage with = still(test, folder.resolve("self-test-pack-on.png"));
        test.tool("visuals_set", args("values", args("server_resource_packs", false)));
        Thread.sleep(200);
        settle(test);
        check(!test.onClient(() -> on(minecraft, MARKER)), "the scene's look setting did not turn the server's packs off");
        expect(test, minecraft, total, false, ++changes, "at the end with the look setting off");
        expect(test, minecraft, pushTick + 10, false, changes, "back again with the look setting off");
        BufferedImage without = still(test, folder.resolve("self-test-pack-off.png"));
        double differ = difference(with, without);
        check(differ > 8.0, "a still exported with the server's pack and one without are the same picture (difference " + differ + "): exports do not use the packs as the scene says");
        test.tool("visuals_set", args("values", args("server_resource_packs", true)));
        Thread.sleep(200);
        settle(test);
        check(test.onClient(() -> on(minecraft, MARKER)), "turning the look setting back on did not bring the packs back");
        changes++;
        BufferedImage withAgain = still(test, folder.resolve("self-test-pack-on-again.png"));
        check(difference(with, withAgain) < differ / 4, "the picture with the pack back on is not the picture with the pack on");

        // An export that runs through the pop: pictures before it have the pack, pictures after it do not
        Path frames = folder.resolve("self-test-pack-frames");
        deleteFolder(frames);
        test.tool("export_start", args("output_path", frames.toString(), "container", "png_sequence", "width", 320, "height", 180, "framerate", 20,
            "start_tick", popTick - 4, "end_tick", popTick + 4, "no_gui", true));
        test.waitFor(() -> !Export.active(), 300, "the export through the pop");
        JsonObject done = test.tool("export_status", new JsonObject());
        check(!done.has("last_error"), "the export through the pop failed: " + done);
        settle(test);
        List<Path> written;
        try (Stream<Path> files = Files.list(frames)) {
            written = files.filter(file -> file.getFileName().toString().endsWith(".png")).sorted().toList();
        }
        check(written.size() == 8, "the export through the pop wrote " + written.size() + " pictures, not 8");
        BufferedImage firstFrame = ImageIO.read(written.get(0).toFile());
        BufferedImage lastFrame = ImageIO.read(written.get(written.size() - 1).toFile());
        double across = difference(firstFrame, lastFrame);
        check(across > 8.0, "the pictures before and after the pop are the same (difference " + across + "): the export did not change packs at the recorded tick");
        test.log(String.format("stills with and without the server's pack differ by %.1f; export frames either side of the pop differ by %.1f", differ, across));
        check(test.onClient(() -> on(minecraft, MARKER)), "after the export the packs are not as they were before it");

        // Closing takes the packs off and leaves the person's own exactly as they were
        test.tool("replay_close", new JsonObject());
        test.waitFor(() -> !Playback.active() && minecraft.level == null, 60, "the pack recording to close");
        test.waitFor(() -> GamePacks.inForce().isEmpty(), 120, "the recorded packs to be taken off on closing");
        waitForReload(test, minecraft);
        check(!test.onClient(() -> on(minecraft, MARKER) || net.minecraft.locale.Language.getInstance().has(LANG_KEY)), "the recorded pack is still in force after the recording was closed");
        List<String> packsNow = test.onClient(() -> List.copyOf(minecraft.options.resourcePacks));
        List<String> selectedNow = test.onClient(() -> List.copyOf(minecraft.getResourcePackRepository().getSelectedIds()));
        check(packsNow.equals(ownPacks) && selectedNow.equals(ownSelected), "the person's own packs changed: " + ownSelected + " before, " + selectedNow + " after");
        Path cached;
        try (Stream<Path> files = Files.list(PackPlayback.cacheFolder())) {
            List<Path> listed = files.toList();
            check(listed.size() == 1 && listed.get(0).getFileName().toString().endsWith(".zip"), "the pack cache holds " + listed + ", not the one pack");
            cached = listed.get(0);
        }
        check(java.util.Arrays.equals(Files.readAllBytes(cached), pack), "the pack in the cache folder is not the recorded pack");
        test.log("server resource packs: applied on opening, changed " + (PackPlayback.changes() - changesBefore)
            + " times in all over the jumps, the play-through, the look setting and the exports, and taken off on closing");
    }

    /** Jumps to a tick and checks whether the pack is in force there, and how often the packs have been changed so far */
    private static void expect(SelfTest test, Minecraft minecraft, int tick, boolean wanted, int changes, String where) throws Exception {
        test.onClientRun(() -> Playback.jumpTo(tick, true));
        test.waitFor(() -> !Playback.jumping() && Playback.position() == tick, 180, "the jump to tick " + tick + " (" + where + ")");
        Thread.sleep(60);
        settle(test);
        boolean inForce = test.onClient(() -> on(minecraft, MARKER));
        check(inForce == wanted, where + " (tick " + tick + ") the pack is " + (inForce ? "in force" : "not in force"));
        check(PackPlayback.changes() == changes, where + " (tick " + tick + ") the packs have been changed " + PackPlayback.changes() + " times, expected " + changes);
        fontIsLive(test, minecraft, where);
    }

    // ------------------------------------------------------------------ The game's font across reloads

    /**
     * Packs pushed and popped one after another while a recording runs, with text being measured on another thread
     * throughout. The game keeps the font it last used in a field that it reads and writes without any lock; a thread
     * that measures text while a reload swaps the fonts can put the old font back there after the reload has closed
     * it, and the next new character drawn then crashes the game ("Image is not allocated"). The game's own download
     * notice measures text on its download thread, and Create's Ponder does on the worker that rebuilds the creative
     * search after every reload, so in the modded game this happened about one run in two until FontManagerMixin.
     */
    private static void stress(SelfTest test, Minecraft minecraft, IntegratedServer server, String base) throws Exception {
        test.tool("recording_start", new JsonObject());
        Thread measuring = measureText(minecraft);
        int reloads = 0;
        try {
            for (int round = 0; round < 3; round++) {
                UUID id = UUID.randomUUID();
                send(server, new ClientboundResourcePackPushPacket(id, base + "/test-pack.zip", sha1(pack), false, Optional.empty()));
                answer(test, minecraft, true);
                test.waitFor(() -> GamePacks.inForce(id), 120, "the pack to be loaded in round " + round + " of pushing and popping");
                waitForReload(test, minecraft);
                fontIsLive(test, minecraft, "after push " + round);
                send(server, new ClientboundResourcePackPopPacket(Optional.of(id)));
                test.waitFor(() -> !GamePacks.inForce(id), 120, "the pack to be taken off in round " + round + " of pushing and popping");
                waitForReload(test, minecraft);
                fontIsLive(test, minecraft, "after pop " + round);
                reloads += 2;
            }
            // And without waiting in between: a push, its pop and another push before the first has been loaded
            UUID first = UUID.randomUUID();
            UUID last = UUID.randomUUID();
            send(server, new ClientboundResourcePackPushPacket(first, base + "/test-pack.zip", sha1(pack), false, Optional.empty()));
            answer(test, minecraft, true);
            send(server, new ClientboundResourcePackPopPacket(Optional.of(first)));
            send(server, new ClientboundResourcePackPushPacket(last, base + "/test-pack.zip", sha1(pack), false, Optional.empty()));
            answer(test, minecraft, true);
            test.waitFor(() -> GamePacks.inForce(last) && !GamePacks.inForce(first), 120, "the packs pushed and popped at once to settle");
            waitForReload(test, minecraft);
            fontIsLive(test, minecraft, "after a push, a pop and a push at once");
            send(server, new ClientboundResourcePackPopPacket(Optional.empty()));
            test.waitFor(() -> GamePacks.inForce().isEmpty(), 120, "the last pack to be taken off");
            waitForReload(test, minecraft);
            fontIsLive(test, minecraft, "after the last pop");
        } finally {
            stopMeasuring(measuring);
        }
        test.tool("recording_cancel", new JsonObject());
        test.sleepTicks(10);
        test.log("the game's font was live after each of " + reloads + " pack reloads and a burst, with text measured on another thread throughout");
    }

    private static volatile boolean measuringText = false;
    private static int freshText = 0;

    /** What Ponder's tooltips and the game's download notice do: measure text away from the game's own thread */
    private static Thread measureText(Minecraft minecraft) {
        measuringText = true;
        Thread thread = new Thread(() -> {
            while (measuringText) {
                try {
                    minecraft.font.width("Dailies");
                } catch (Throwable ignored) {
                    // Only the font it leaves behind matters
                }
            }
        }, "Dailies-Self-Test-Text");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void stopMeasuring(Thread thread) throws InterruptedException {
        measuringText = false;
        thread.join(5000);
    }

    /** The font the game draws with is the one its last reload made, and characters not drawn since then can be drawn */
    private static void fontIsLive(SelfTest test, Minecraft minecraft, String when) throws Exception {
        test.onClientRun(() -> {
            comedymonkey.dailies.mixin.access.FontManagerAccess fonts =
                (comedymonkey.dailies.mixin.access.FontManagerAccess) ((comedymonkey.dailies.mixin.access.MinecraftAccess) minecraft).dailies$fontManager();
            minecraft.font.width("Dailies");
            net.minecraft.client.gui.font.FontSet used = fonts.dailies$lastFontSet();
            check(used != null && fonts.dailies$fontSets().get(used.name()) == used,
                when + ", the game draws text with a font that a resource reload has closed: the next new character would crash it");
            StringBuilder fresh = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                fresh.append((char) (0x00C0 + (freshText++ % 0x180)));
            }
            net.minecraft.client.gui.GuiGraphics graphics = new net.minecraft.client.gui.GuiGraphics(minecraft, minecraft.renderBuffers().bufferSource());
            graphics.drawString(minecraft.font, fresh.toString(), 2, 2, 0xFFFFFFFF, false);
            graphics.flush();
        });
    }

    private static void settle(SelfTest test) throws Exception {
        test.waitFor(() -> !Playback.jumping(), 180, "the recording's resource packs to be loaded");
    }

    private static BufferedImage still(SelfTest test, Path file) throws Exception {
        Files.deleteIfExists(file);
        test.tool("export_screenshot", args("output_path", file.toString(), "width", 320, "height", 180, "overwrite", true));
        test.waitFor(() -> !Export.active() && Files.exists(file), 120, "the still " + file.getFileName());
        settle(test);
        BufferedImage picture = ImageIO.read(file.toFile());
        check(picture != null, "the still " + file.getFileName() + " cannot be read");
        return picture;
    }

    // ------------------------------------------------------------------ Helpers

    private static boolean on(Minecraft minecraft, ResourceLocation marker) {
        return minecraft.getResourceManager().getResource(marker).isPresent();
    }

    /** Waits for the game's question about the pack and presses yes or no */
    private static void answer(SelfTest test, Minecraft minecraft, boolean yes) throws Exception {
        test.waitFor(() -> minecraft.screen instanceof ConfirmScreen && minecraft.screen.getClass().getName().contains("PackConfirmScreen"), 30,
            "the game to ask about the server's resource pack");
        test.onClientRun(() -> {
            List<Button> buttons = new ArrayList<>();
            for (GuiEventListener child : minecraft.screen.children()) {
                if (child instanceof Button button) {
                    buttons.add(button);
                }
            }
            check(buttons.size() == 2, "the resource pack question has " + buttons.size() + " buttons");
            buttons.get(yes ? 0 : 1).onPress();
        });
        test.waitFor(() -> !(minecraft.screen instanceof ConfirmScreen), 30, "the resource pack question to close");
    }

    private static void waitForReload(SelfTest test, Minecraft minecraft) throws Exception {
        test.waitFor(() -> minecraft.getOverlay() == null, 120, "the game to finish reloading its resources");
        test.sleepTicks(5);
    }

    private static void send(IntegratedServer server, Packet<?> packet) throws Exception {
        server.submit(() -> server.getPlayerList().getPlayers().get(0).connection.send(packet)).get(30, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * A resource pack with a file of its own to look for and, for the one that is applied, a language entry and a grass
     * top that is plainly not the game's, so that a picture shows whether the pack is on.
     */
    private static byte[] buildPack(String marker, boolean visible) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            int format = net.minecraft.SharedConstants.getCurrentVersion().getPackVersion(net.minecraft.server.packs.PackType.CLIENT_RESOURCES);
            entry(zip, "pack.mcmeta", ("{\"pack\":{\"pack_format\":" + format + ",\"description\":\"Dailies self test\"}}").getBytes(StandardCharsets.UTF_8));
            entry(zip, "assets/minecraft/dailies_selftest/" + marker, "here".getBytes(StandardCharsets.UTF_8));
            if (visible) {
                entry(zip, "assets/minecraft/lang/en_us.json", ("{\"" + LANG_KEY + "\":\"on\"}").getBytes(StandardCharsets.UTF_8));
                BufferedImage top = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < 16; y++) {
                    for (int x = 0; x < 16; x++) {
                        top.setRGB(x, y, (x / 4 + y / 4) % 2 == 0 ? 0xFFFFFFFF : 0xFF101010);
                    }
                }
                ByteArrayOutputStream png = new ByteArrayOutputStream();
                ImageIO.write(top, "png", png);
                entry(zip, "assets/minecraft/textures/block/grass_block_top.png", png.toByteArray());
            }
        }
        return bytes.toByteArray();
    }

    private static void entry(ZipOutputStream zip, String name, byte[] content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    private static String sha1(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The smallest web server that will do: it answers GET for the files it was given, on this machine only */
    private static void serve(ServerSocket http, Map<String, byte[]> files) {
        Thread thread = new Thread(() -> {
            while (!http.isClosed()) {
                try (Socket socket = http.accept()) {
                    socket.setSoTimeout(10_000);
                    InputStream in = socket.getInputStream();
                    StringBuilder head = new StringBuilder();
                    int read;
                    while (!head.toString().endsWith("\r\n\r\n") && (read = in.read()) >= 0) {
                        head.append((char) read);
                    }
                    String[] request = head.toString().split(" ");
                    byte[] body = request.length >= 2 ? files.get(request[1]) : null;
                    OutputStream out = socket.getOutputStream();
                    if (body == null) {
                        out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    } else {
                        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        out.write(body);
                    }
                    out.flush();
                } catch (java.io.IOException e) {
                    // The socket was closed at the end of the step, or a request went wrong: the step's own checks say which
                }
            }
        }, "Dailies-Self-Test-Pack-Server");
        thread.setDaemon(true);
        thread.start();
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

    private static void deleteFolder(Path folder) throws Exception {
        if (Files.isDirectory(folder)) {
            try (Stream<Path> files = Files.list(folder)) {
                for (Path file : files.toList()) {
                    Files.delete(file);
                }
            }
        }
    }

}
