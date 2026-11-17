package comedymonkey.dailies.selftest;

import com.google.gson.JsonObject;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.capture.Recorder;
import comedymonkey.dailies.export.Export;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.playback.Viewer;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSource;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;

import static comedymonkey.dailies.selftest.SelfTest.args;
import static comedymonkey.dailies.selftest.SelfTest.check;

/**
 * The awkward parts of the game, put through a recording of their own: the Wither, the Ender Dragon in The End,
 * creepers, ghast and blaze fireballs, experience orbs, and a long list of entities and blocks that do not behave like
 * a walking mob ({@link BestiaryStages} sets them up). While it records, the live game is written down at the end of
 * every tick ({@link Census}). The recording is then played through and compared with that tick by tick, jumped
 * through forwards, and jumped through backwards, where every landing starts from a stored world. Everything that
 * differs is gathered, so one run reports all of it, item by item, and the test fails at the end with the list.
 * <p>
 * Tolerances, which are this step's own and looser than nothing that existed before: an entity may be a third of a
 * block from its live place plus one tick of its own travel as the live game saw it (the measure PlaybackSteps uses);
 * the recorder may be a third of a block plus three ticks of their own travel, because playback shows them as other
 * players are shown, eased over three ticks; the recorder's state may be what the live game had up to five ticks
 * earlier, because a game changes its own player at once and hears the same from the server afterwards; the HUD may
 * be three ticks old; rain and thunder may differ by a hundredth. State, blocks, block entities and boss bars must
 * be equal. A few animation counters that are fed by drawing rather than by ticks have slack, listed in Census.
 */
final class BestiarySteps {

    /** Besides the ticks the stages mark, every so many ticks is jumped to as well */
    static final int EVERY = 100;
    static final String WORLD = "Dailies Bestiary";
    static final String OVERWORLD = "minecraft:overworld";
    static final String END = "minecraft:the_end";

    static SelfTest test;
    static Minecraft minecraft;
    /** What could not be set up as meant: these are faults of the test's staging, not of playback, and are reported apart */
    static final List<String> problems = new ArrayList<>();
    static final List<Item> items = new ArrayList<>();
    static final TreeMap<Integer, String> marks = new TreeMap<>();
    static final List<Still> stills = new ArrayList<>();
    private static final List<Item> open = new ArrayList<>();
    private static int openFrom = 0;

    record Still(String name, int tick, double x, double y, double z, float yaw, float pitch, boolean byPlay) {
    }

    /** One thing the owner asked to see tested */
    static final class Item {
        final String name;
        final List<String> labels = new ArrayList<>();
        final List<Function<Item, String>> needs = new ArrayList<>();
        int from, to;
        String note = "";

        Item(String name) {
            this.name = name;
        }

        /** The names or kinds of entity, or "blocks", "boss bar" and the like, whose differences count against this item */
        Item owns(String... labels) {
            this.labels.addAll(List.of(labels));
            return this;
        }

        /** Something the live game must have shown for this item to have been staged at all */
        Item need(Function<Item, String> need) {
            this.needs.add(need);
            return this;
        }

        Item note(String note) {
            this.note = note;
            return this;
        }
    }

    private BestiarySteps() {
    }

    // --- Helpers for the stages ---

    static Item item(String name) {
        Item item = new Item(name);
        items.add(item);
        open.add(item);
        return item;
    }

    static void begin() {
        openFrom = tick();
        open.clear();
    }

    static void end() {
        for (Item item : open) {
            item.from = openFrom;
            item.to = tick() + 2;
        }
        open.clear();
    }

    static int tick() {
        return Recorder.get().recordingTick();
    }

    static void waitTicks(int ticks) throws Exception {
        int target = tick() + ticks;
        test.waitFor(() -> tick() >= target, ticks / 10 + 60, "recording tick " + target);
    }

    /** Notes a tick, two on from now, to jump to later. Two on, so that it has not been counted yet */
    static int mark(String label) {
        int tick = tick() + 2;
        marks.merge(tick, label, (a, b) -> a + ", " + b);
        Census.keyTicks.add(tick);
        return tick;
    }

    /** A picture to export at the tick just marked, from a camera at one place looking at another */
    static void still(String name, int tick, double x, double y, double z, double atX, double atY, double atZ, boolean byPlay) {
        double dx = atX - x, dy = atY - y, dz = atZ - z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        stills.add(new Still(name, tick, x, y, z, yaw, pitch, byPlay));
    }

    /** Runs a command that may well have nothing to do: tidying up */
    static void quietly(String command) throws Exception {
        int before = problems.size();
        c(command);
        while (problems.size() > before) {
            problems.remove(problems.size() - 1);
        }
    }

    /** Runs a command as the player and notes it when the game refuses it */
    static boolean c(String command) throws Exception {
        net.minecraft.client.server.IntegratedServer server = minecraft.getSingleplayerServer();
        check(server != null, "there is no built-in server to run '" + command + "' on");
        List<String> said = new ArrayList<>();
        server.submit(() -> {
            ServerPlayer player = server.getPlayerList().getPlayers().get(0);
            CommandSource sink = new CommandSource() {
                @Override
                public void sendSystemMessage(Component message) {
                    said.add(message.getString());
                }

                @Override
                public boolean acceptsSuccess() {
                    return false;
                }

                @Override
                public boolean acceptsFailure() {
                    return true;
                }

                @Override
                public boolean shouldInformAdmins() {
                    return false;
                }
            };
            server.getCommands().performPrefixedCommand(player.createCommandSourceStack().withPermission(4).withSource(sink), command);
        }).get(30, java.util.concurrent.TimeUnit.SECONDS);
        if (!said.isEmpty()) {
            problems.add("command refused: " + command + " -> " + said);
        }
        return said.isEmpty();
    }

    interface ServerWork {
        void run(ServerLevel level, ServerPlayer player) throws Exception;
    }

    /** Does something on the server's own thread, as the game itself would: for what no command can stage */
    static boolean server(String what, ServerWork work) throws Exception {
        net.minecraft.client.server.IntegratedServer server = minecraft.getSingleplayerServer();
        check(server != null, "there is no built-in server for: " + what);
        Throwable[] failed = new Throwable[1];
        server.submit(() -> {
            try {
                ServerPlayer player = server.getPlayerList().getPlayers().get(0);
                work.run(player.serverLevel(), player);
            } catch (Throwable t) {
                failed[0] = t;
            }
        }).get(30, java.util.concurrent.TimeUnit.SECONDS);
        if (failed[0] != null) {
            problems.add("could not stage " + what + ": " + failed[0]);
            Host.LOGGER.warn("Dailies self test: bestiary could not stage {}", what, failed[0]);
        }
        return failed[0] == null;
    }

    static Object secret(Object owner, Class<?> type, String name) throws ReflectiveOperationException {
        java.lang.reflect.Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    /** How many ticks into its segment the recording is: a stored world is written at nought */
    static int phase() {
        try {
            return tick() - (Integer) secret(Recorder.get(), Recorder.class, "segmentStartTick");
        } catch (ReflectiveOperationException e) {
            return 0;
        }
    }

    /** Waits until the recording is so many ticks into a segment, so that what follows is caught in flight by the next stored world */
    static void untilPhase(int wanted) throws Exception {
        test.waitFor(() -> phase() == wanted || phase() == wanted + 1, 30, "the recording to be " + wanted + " ticks into a segment");
    }

    static String named(String name) {
        return "CustomName:'\"" + name + "\"'";
    }

    // --- What the live game showed, for proving a thing was staged ---

    static Function<Item, String> present(String label, int atLeast) {
        return item -> {
            int ticks = 0, most = 0;
            TreeSet<Integer> ids = new TreeSet<>();
            for (Census census : Census.live.subMap(item.from, true, item.to, true).values()) {
                int here = 0;
                for (Map.Entry<Integer, Census.Thing> thing : census.things.entrySet()) {
                    if (thing.getValue().label().equals(label)) {
                        here++;
                        ids.add(thing.getKey());
                    }
                }
                ticks += here > 0 ? 1 : 0;
                most = Math.max(most, here);
            }
            return ids.size() < atLeast ? null : ids.size() + " " + label + " over " + ticks + " ticks (" + most + " at once)";
        };
    }

    static Function<Item, String> state(String label, String fragment) {
        return where(label, fragment, thing -> thing.state().contains(fragment) || thing.clocks().contains(fragment));
    }

    static Function<Item, String> where(String label, String what, Predicate<Census.Thing> test) {
        return item -> {
            int ticks = 0, first = -1;
            for (Census census : Census.live.subMap(item.from, true, item.to, true).values()) {
                for (Census.Thing thing : census.things.values()) {
                    if (thing.label().equals(label) && test.test(thing)) {
                        ticks++;
                        first = first < 0 ? census.tick : first;
                        break;
                    }
                }
            }
            return ticks == 0 ? null : label + " " + what + " on " + ticks + " ticks from " + first;
        };
    }

    /** Blocks changed inside a box of the staging ground while the item was staged: at least and at most so many */
    static Function<Item, String> changed(int x1, int z1, int x2, int z2, int atLeast, int atMost) {
        return item -> {
            int changed = 0;
            for (Census census : Census.live.subMap(item.from, true, item.to, true).values()) {
                if (census.changes == null) {
                    continue;
                }
                for (String change : census.changes) {
                    String[] parts = change.substring(0, change.indexOf(':')).split(" ");
                    int x = Integer.parseInt(parts[0]), z = Integer.parseInt(parts[2]);
                    if (x >= x1 && x <= x2 && z >= z1 && z <= z2) {
                        changed++;
                    }
                }
            }
            return changed < atLeast || changed > atMost ? null : changed + " block changes";
        };
    }

    static Function<Item, String> blockChange(String fragment) {
        return item -> {
            int changed = 0, first = -1;
            for (Census census : Census.live.subMap(item.from, true, item.to, true).values()) {
                if (census.changes != null) {
                    for (String change : census.changes) {
                        if (change.contains(fragment)) {
                            changed++;
                            first = first < 0 ? census.tick : first;
                        }
                    }
                }
            }
            return changed == 0 ? null : changed + " blocks '" + fragment + "' from tick " + first;
        };
    }

    static Function<Item, String> blockEntity(String kind, String fragment) {
        return item -> {
            int ticks = 0;
            for (Census census : Census.live.subMap(item.from, true, item.to, true).values()) {
                for (String entity : census.blockEntities.values()) {
                    if (entity.startsWith(kind) && entity.contains(fragment)) {
                        ticks++;
                        break;
                    }
                }
            }
            return ticks == 0 ? null : kind + " with " + fragment + " on " + ticks + " ticks";
        };
    }

    static Function<Item, String> seen(String what, Predicate<Census> test) {
        return item -> {
            int ticks = 0, first = -1;
            for (Census census : Census.live.subMap(item.from, true, item.to, true).values()) {
                if (test.test(census)) {
                    ticks++;
                    first = first < 0 ? census.tick : first;
                }
            }
            return ticks == 0 ? null : what + " on " + ticks + " ticks from " + first;
        };
    }

    static Function<Item, String> heard(String sound) {
        return item -> {
            long times = Census.liveSounds.stream().filter(line -> line.endsWith(" " + sound) && Integer.parseInt(line.substring(0, line.indexOf(' '))) >= item.from
                && Integer.parseInt(line.substring(0, line.indexOf(' '))) <= item.to).count();
            return times == 0 ? null : sound + " heard " + times + " times";
        };
    }

    // --- The run ---

    static void run(SelfTest self) throws Exception {
        test = self;
        minecraft = Minecraft.getInstance();
        JsonObject none = new JsonObject();
        if (Playback.active()) {
            PlaybackSteps.close(test);
        }
        if (minecraft.level != null) {
            WorldSteps.leaveWorld(test);
        }
        WorldSteps.createWorld(test, WORLD);
        Path folder = Host.get().dataDirectory().resolve("bestiary");
        Files.createDirectories(folder);
        try (java.util.stream.Stream<Path> old = Files.list(folder)) {
            for (Path file : old.toList()) {
                Files.deleteIfExists(file);
            }
        }

        // --- Record ---
        Census.watch(OVERWORLD, 0, -40, -64, -40, 40, -46, 40);
        test.tool("config_set", args("key", "recordHud", "value", true));
        BestiaryStages.prepare();
        test.tool("recording_start", none);
        Census.start(Census.Mode.LIVE);
        test.sleepTicks(20);
        BestiaryStages.all();
        test.sleepTicks(10);
        Census.stop();
        test.tool("config_set", args("key", "recordHud", "value", false));
        JsonObject finished = test.tool("recording_finish", args("name", "Bestiary"));
        // Out of the recordings folder, where other steps count what is there
        Path recording = folder.resolve("Bestiary.dailies");
        Files.move(Path.of(finished.get("path").getAsString()), recording, StandardCopyOption.REPLACE_EXISTING);
        int total = Census.live.lastKey();
        test.log("bestiary: recorded " + total + " ticks with " + items.size() + " items staged, " + marks.size() + " ticks marked; " + problems.size() + " staging problems");

        // --- Play it through, comparing every tick ---
        Census.Tally tally = new Census.Tally();
        Census.tally = tally;
        test.tool("replay_open", args("path", recording.toString()));
        test.waitFor(() -> Playback.loaded() || Playback.lastError() != null, 120, "the bestiary recording to open");
        check(Playback.lastError() == null, "the bestiary recording did not open: " + Playback.lastError());
        int length = Playback.total();
        test.onClientRun(() -> Census.aim(1));
        Census.start(Census.Mode.PLAY);
        test.tool("replay_set_speed", args("tick_rate", 60));
        test.onClientRun(() -> Playback.playTo(length));
        test.waitFor(() -> (Playback.paused() && Playback.position() >= length) || Playback.lastError() != null, 600, "the bestiary recording to play through");
        check(Playback.lastError() == null, "playback failed while the bestiary played: " + Playback.lastError());
        Census.stop();
        test.tool("replay_set_speed", args("tick_rate", 20));
        int played = tally.compared;
        test.log("bestiary: played through " + length + " ticks, " + played + " compared with the live game");

        // --- Jump through it forwards, then backwards: every landing going back starts from a stored world ---
        TreeSet<Integer> stops = new TreeSet<>(marks.keySet());
        for (int tick = EVERY; tick < total; tick += EVERY) {
            stops.add(tick);
        }
        stops.removeIf(tick -> !Census.live.containsKey(tick) || Census.live.get(tick).blocks == null || tick > length);
        Census.skippedSounds.clear();

        // First with the three light fixes of playback switched off, looking at light alone, so the report can say what they are worth
        System.setProperty("dailies.playback.noSettleLight", "true");
        System.setProperty("dailies.playback.leaveStaleLight", "true");
        System.setProperty("dailies.playback.lightOutOfOrder", "true");
        try {
            for (int stop : stops) {
                landForLight(stop, "without the light fixes, jump", tally);
            }
            for (int stop : stops.descendingSet()) {
                landForLight(stop, "without the light fixes, jump back", tally);
            }
        } finally {
            System.clearProperty("dailies.playback.noSettleLight");
            System.clearProperty("dailies.playback.leaveStaleLight");
            System.clearProperty("dailies.playback.lightOutOfOrder");
        }
        PlaybackSteps.jump(test, 0);
        pictures(folder);
        for (int stop : stops) {
            land(stop, "jump", tally);
        }
        for (int stop : stops.descendingSet()) {
            land(stop, "jump back", tally);
        }
        test.log("bestiary: jumped to " + stops.size() + " ticks forwards and backwards; longest jump " + PlaybackSteps.longestJumpMillis + " ms");
        if (!Census.skippedSounds.isEmpty()) {
            tally.add("jump", "sounds", "were played from time that was skipped over", 0, Census.skippedSounds.size() + " sounds, first " + Census.skippedSounds.get(0), false);
        }

        // --- Pictures for what only an eye can judge ---
        for (Still still : stills) {
            try {
                if (still.byPlay()) {
                    PlaybackSteps.jump(test, Math.max(0, still.tick() - 30));
                    test.onClientRun(() -> Viewer.set(still.x(), still.y(), still.z(), still.yaw(), still.pitch()));
                    test.onClientRun(() -> Playback.playTo(still.tick()));
                    test.waitFor(() -> Playback.paused() && Playback.position() == still.tick(), 60, "play to reach the still at tick " + still.tick());
                } else {
                    PlaybackSteps.jump(test, still.tick());
                }
                test.onClientRun(() -> Viewer.set(still.x(), still.y(), still.z(), still.yaw(), still.pitch()));
                Thread.sleep(150);
                Path picture = folder.resolve(still.name() + ".png");
                test.tool("export_screenshot", args("output_path", picture.toString(), "width", 1280, "height", 720, "no_gui", false, "overwrite", true));
                test.waitFor(() -> !Export.active(), 60, "the still " + still.name());
                test.waitFor(() -> !Playback.jumping(), 30, "playback to be put back after a still");
                if (!Files.exists(picture)) {
                    problems.add("the still " + still.name() + " was not written");
                }
            } catch (Throwable t) {
                problems.add("the still " + still.name() + " at tick " + still.tick() + " could not be taken: " + t.getMessage());
            }
        }
        test.log("bestiary: " + stills.size() + " stills written to " + folder);
        PlaybackSteps.close(test);

        // --- The result, item by item ---
        report(folder, tally, total, played, stops.size());
    }

    /** Jumps, and returns the light as it stood on the very frame the jump arrived, before anything was drawn */
    private static byte[] arrive(int stop) throws Exception {
        byte[][] arrival = new byte[1][];
        boolean[] there = new boolean[1];
        long started = System.nanoTime();
        test.onClientRun(() -> {
            Playback.jumpTo(stop, true);
            Playback.whenLoaded(() -> {
                arrival[0] = Census.lightNow(minecraft, stop);
                there[0] = true;
            });
        });
        test.waitFor(() -> there[0] && !Playback.jumping() && Playback.position() == stop, 60, "the jump to tick " + stop);
        PlaybackSteps.longestJumpMillis = Math.max(PlaybackSteps.longestJumpMillis, (System.nanoTime() - started) / 1_000_000);
        return arrival[0];
    }

    private static void frames(int count) throws Exception {
        long until = Census.frames + count;
        test.waitFor(() -> Census.frames >= until, 30, count + " frames to be drawn");
    }

    private static void landForLight(int stop, String mode, Census.Tally tally) throws Exception {
        try {
            byte[] arrival = arrive(stop);
            Census then = Census.live.get(stop);
            Census.compareLight(then, arrival, mode + ", on the frame it arrives", tally, true);
            frames(20);
            Census.compareLight(then, test.onClient(() -> Census.lightNow(minecraft, stop)), mode + ", twenty frames on", tally, true);
        } catch (Throwable t) {
            tally.add(mode, "the jump", "failed", stop, String.valueOf(t.getMessage()), false);
            check(Playback.loaded(), "playback closed during the bestiary's jump to tick " + stop + ": " + Playback.lastError());
        }
    }

    /** Stills that are to be set against the live game's own screen: {name, tick to jump from, tick to land on} */
    static final List<Object[]> comparisons = new ArrayList<>();

    /**
     * For each comparison: the screen on the frame a jump arrives and forty frames later, seen through the recorder's eyes,
     * beside the live game's screen at that tick. How far apart the pictures are is logged; a person looks at them too
     */
    private static void pictures(Path folder) throws Exception {
        for (Object[] comparison : comparisons) {
            String name = (String) comparison[0];
            int from = (Integer) comparison[1], to = (Integer) comparison[2];
            try {
                PlaybackSteps.jump(test, from);
                test.onClientRun(() -> {
                    Viewer.ride(minecraft.level.getPlayerByUUID(java.util.UUID.fromString(Playback.manifest().recorderId)));
                    Playback.jumpTo(to, true);
                    Playback.whenLoaded(() -> {
                        Viewer.ride(minecraft.level.getPlayerByUUID(java.util.UUID.fromString(Playback.manifest().recorderId)));
                        Census.grab(folder.resolve("light-" + name + "-arrival.png"), 0);
                        Census.grab(folder.resolve("light-" + name + "-forty-frames-on.png"), 40);
                    });
                });
                test.waitFor(() -> !Playback.jumping() && Playback.position() == to, 60, "the jump for the picture " + name);
                frames(50);
                test.onClientRun(() -> Viewer.ride(null));
                double settling = apart(folder.resolve("light-" + name + "-arrival.png"), folder.resolve("light-" + name + "-forty-frames-on.png"));
                double fromLive = apart(folder.resolve("light-" + name + "-live.png"), folder.resolve("light-" + name + "-forty-frames-on.png"));
                double arrivalFromLive = apart(folder.resolve("light-" + name + "-live.png"), folder.resolve("light-" + name + "-arrival.png"));
                pictureNotes.add(String.format("%s (jump from tick %d to %d): arrival frame against forty frames on %.2f; arrival against live %.2f; forty frames on against live %.2f (mean difference of a pixel, of 255)",
                    name, from, to, settling, arrivalFromLive, fromLive));
            } catch (Throwable t) {
                problems.add("the pictures for " + name + " could not be taken: " + t);
            }
        }
    }

    static final List<String> pictureNotes = new ArrayList<>();

    private static double apart(Path one, Path two) throws Exception {
        java.awt.image.BufferedImage a = javax.imageio.ImageIO.read(one.toFile()), b = javax.imageio.ImageIO.read(two.toFile());
        if (a == null || b == null || a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            return -1.0;
        }
        long sum = 0;
        int count = 0;
        // The world, not the bars along the top and bottom of the screen
        for (int y = a.getHeight() / 8; y < a.getHeight() * 3 / 4; y += 2) {
            for (int x = 0; x < a.getWidth(); x += 2) {
                int p = a.getRGB(x, y), q = b.getRGB(x, y);
                sum += Math.abs((p >> 16 & 255) - (q >> 16 & 255)) + Math.abs((p >> 8 & 255) - (q >> 8 & 255)) + Math.abs((p & 255) - (q & 255));
                count += 3;
            }
        }
        return (double) sum / count;
    }

    private static void land(int stop, String mode, Census.Tally tally) throws Exception {
        try {
            byte[] arrival = arrive(stop);
            Census then = Census.live.get(stop);
            Census.compareLight(then, arrival, mode + ", on the frame it arrives", tally, false);
            test.onClientRun(() -> Census.aim(stop));
            // Twenty drawn frames from where the recorder stood: for the counters that drawing feeds, and for light to have settled if it was going to
            frames(20);
            Census now = test.onClient(() -> Census.ofPlayback(minecraft));
            Census.compare(then, now, mode, tally);
            java.util.List<String> moved = arrival == null || now.light == null || arrival.length != now.light.length ? null : java.util.stream.IntStream.range(0, arrival.length)
                .filter(i -> arrival[i] != now.light[i]).mapToObj(String::valueOf).toList();
            if (moved != null && !moved.isEmpty()) {
                tally.add(mode, "light", "went on changing after the jump arrived", stop, moved.size() + " places differ between the arrival frame and twenty frames on", false);
            }
        } catch (Throwable t) {
            tally.add(mode, "the jump", "failed", stop, String.valueOf(t.getMessage()), false);
            check(Playback.loaded(), "playback closed during the bestiary's jump to tick " + stop + ": " + Playback.lastError());
        }
    }

    private static void report(Path folder, Census.Tally tally, int total, int played, int stops) throws Exception {
        List<String> lines = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        java.util.Set<Census.Entry> claimed = new java.util.HashSet<>();
        int fine = 0;
        for (Item item : items) {
            List<String> evidence = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            for (Function<Item, String> need : item.needs) {
                String seen;
                try {
                    seen = need.apply(item);
                } catch (Throwable t) {
                    seen = null;
                }
                if (seen == null) {
                    missing.add("need " + (item.needs.indexOf(need) + 1));
                } else {
                    evidence.add(seen);
                }
            }
            List<String> wrong = new ArrayList<>();
            List<String> noted = new ArrayList<>();
            for (Census.Entry entry : tally.entries.values()) {
                if (!item.labels.contains(entry.label) || entry.ticks.subSet(item.from, true, item.to, true).isEmpty()) {
                    continue;
                }
                claimed.add(entry);
                (entry.warning ? noted : wrong).add(entry.toString());
            }
            String status = !missing.isEmpty() ? "NOT STAGED" : wrong.isEmpty() ? "OK" : "FAIL";
            String line = "BESTIARY " + status + " | " + item.name + " | ticks " + item.from + "-" + item.to + " | live: " + (evidence.isEmpty() ? "-" : String.join("; ", evidence))
                + (missing.isEmpty() ? "" : " | the live game never showed: " + missing) + (item.note.isEmpty() ? "" : " | " + item.note);
            lines.add(line);
            wrong.forEach(one -> lines.add("    wrong: " + one));
            noted.forEach(one -> lines.add("    " + one));
            if (status.equals("OK")) {
                fine++;
            } else {
                failures.add(item.name + (missing.isEmpty() ? " (" + wrong.size() + " differences)" : " (not staged)"));
            }
        }
        List<String> other = new ArrayList<>();
        for (Census.Entry entry : tally.entries.values()) {
            if (!claimed.contains(entry)) {
                other.add(entry.toString());
                if (!entry.warning) {
                    failures.add("unclaimed: " + entry.mode + " " + entry.label + " " + entry.what);
                }
            }
        }
        lines.add("BESTIARY differences that belong to no item: " + other.size());
        other.forEach(one -> lines.add("    " + one));
        lines.add("BESTIARY staging problems: " + problems.size());
        problems.forEach(one -> lines.add("    " + one));
        StringBuilder far = new StringBuilder();
        tally.furthest.forEach((label, off) -> {
            if (off > 0.05) {
                far.append(String.format(" %s %.2f;", label, off));
            }
        });
        lines.add("BESTIARY furthest from the live place, in blocks (only those over 0.05):" + far);
        lines.add("BESTIARY places with the wrong light, summed over the ticks looked at: " + tally.lightPlaces);
        pictureNotes.forEach(note -> lines.add("BESTIARY pictures: " + note));
        lines.add("BESTIARY marks: " + marks);
        lines.add("BESTIARY sounds: " + Census.liveSounds.size() + " live, " + Census.playSounds.size() + " in play and landings, " + Census.skippedSounds.size() + " from skipped time");
        String summary = "BESTIARY SUMMARY: " + items.size() + " items, " + fine + " OK, " + (items.size() - fine) + " not; " + total + " ticks recorded, " + played + " compared in play, "
            + stops + " ticks jumped to each way, " + tally.thingsCompared + " entity comparisons; " + tally.entries.size() + " kinds of difference; " + problems.size() + " staging problems";
        lines.add(summary);
        Files.write(folder.resolve("report.txt"), lines, StandardCharsets.UTF_8);
        lines.forEach(line -> Host.LOGGER.info("{}", line));
        check(failures.isEmpty() && problems.isEmpty(), "bestiary: " + failures.size() + " items or differences are wrong and " + problems.size() + " things could not be staged (see the BESTIARY lines): "
            + String.join("; ", failures.subList(0, Math.min(12, failures.size()))));
    }

}
