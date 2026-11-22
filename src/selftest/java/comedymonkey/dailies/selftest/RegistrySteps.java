package comedymonkey.dailies.selftest;

import com.google.gson.JsonObject;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.playback.RegistryNumbering;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.PlayLevelSoundEvent;
import net.neoforged.neoforge.registries.RegistryManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static comedymonkey.dailies.selftest.SelfTest.args;
import static comedymonkey.dailies.selftest.SelfTest.check;

/**
 * A recording made on a server that numbers a registry differently from the game watching it plays its sounds as
 * themselves (specification REC-37, PLAY-25; found when the owner's wings played the wrong sound, 19 November).
 * <p>
 * There is no second game to be the server, so this game numbers its sounds as such a server would before it opens a
 * world: the numbering is put in force the way NeoForge puts a server's in force on joining (RegistryManager's
 * applySnapshot, from a snapshot read from the bytes a server sends), with every sound moved a third of the way round,
 * so that every number means another sound than in this game's own numbering. The built-in server shares the game's
 * registries, so it numbers its sound packets by it too, and the two agree as a server and a client that has joined it
 * do. The world's server plays two sounds by number, as the wings do with level.playSound(null, ...), while it is
 * recorded. Leaving the world has NeoForge put the game's own numbering back, as leaving a server does. In playback each
 * sound must reach the game as itself, not as the sound this game's own numbering gives that number. A second recording
 * made with the game's own numbering is then opened over the first, which puts the game's own back and changes nothing,
 * and closing playback leaves the game's own numbering in every registry.
 * <p>
 * Which sound the game plays is read from NeoForge's event as the client's world plays it, which does not need a sound
 * device. Run by itself with -PdailiesOnly=registries.
 */
final class RegistrySteps {

    static final String WORLD = "Dailies Registries";
    private static final Identifier SOUNDS = Registries.SOUND_EVENT.location();
    /** Played by number by the world's server: one the game holds as a holder, as the wings' sound is, and a plain one */
    private static final List<Holder<SoundEvent>> PLAYED = List.of(SoundEvents.NOTE_BLOCK_BELL, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.ANVIL_LAND));
    private static final int ROUNDS = 3;

    /** The sounds the client's world played, live and in playback (not while a jump skips), each as "live name" or "play name" */
    private static final List<String> heard = new CopyOnWriteArrayList<>();
    private static boolean listening = false;
    /** Only while this group runs: the listener stays for the rest of the run, and the groups after play many sounds */
    private static volatile boolean collecting = false;

    private RegistrySteps() {
    }

    static void run(SelfTest test) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        if (Playback.active()) {
            PlaybackSteps.close(test);
        }
        if (minecraft.level != null) {
            WorldSteps.leaveWorld(test);
        }
        listen();
        collecting = true;
        try {
            steps(test, minecraft);
        } finally {
            collecting = false;
            heard.clear();
        }
    }

    private static void steps(SelfTest test, Minecraft minecraft) throws Exception {
        Path folder = Host.get().dataDirectory().resolve("registries");
        Files.createDirectories(folder);
        SelfTestSupport.emptyFolder(folder);
        Path shifted = folder.resolve("Shifted.dailies");
        Path plain = folder.resolve("Plain.dailies");

        // This game's own numbering, which must be what it has again once each world and each playback is left
        Map<String, Map<Integer, String>> own = test.onClient(RegistrySteps::numbering);
        Map<Integer, String> sounds = own.get(SOUNDS.toString());
        check(sounds != null && sounds.size() > 100, "the game does not say how it numbers its sounds: " + (sounds == null ? "no numbering" : sounds.size() + " sounds"));
        int count = Collections.max(sounds.keySet()) + 1;
        List<String> right = new ArrayList<>();
        List<Integer> ownNumbers = new ArrayList<>();
        for (Holder<SoundEvent> sound : PLAYED) {
            String name = sound.value().getLocation().toString();
            int ownNumber = -1;
            for (Map.Entry<Integer, String> entry : sounds.entrySet()) {
                if (entry.getValue().equals(name)) {
                    ownNumber = entry.getKey();
                }
            }
            check(ownNumber >= 0, "the game has no number for " + name);
            right.add(name);
            ownNumbers.add(ownNumber);
        }
        // About a third of the way round, and never so far that a sound played lands on the number of another played
        int shift = count / 3;
        List<String> wrong = new ArrayList<>();
        List<Integer> serverNumbers = new ArrayList<>();
        while (true) {
            wrong.clear();
            serverNumbers.clear();
            for (int ownNumber : ownNumbers) {
                serverNumbers.add((ownNumber + shift) % count);
                // What playback would play for the server's number if it read it by this game's own numbering
                wrong.add(sounds.get((ownNumber + shift) % count));
            }
            if (wrong.stream().noneMatch(name -> name == null || right.contains(name))) {
                break;
            }
            shift++;
        }
        int moved = shift;
        SortedMap<Integer, Identifier> serverNumbering = new TreeMap<>();
        sounds.forEach((number, name) -> serverNumbering.put((number + moved) % count, Identifier.parse(name)));
        test.log("registries: the simulated server numbers every sound " + shift + " on, of " + count + ": " + right + " are numbered " + serverNumbers
            + ", which this game's own numbering gives to " + wrong);

        // The recording on the "server": its numbering in force from before the world opens until it is left
        test.onClientRun(() -> {
            Set<ResourceKey<?>> unknown = RegistryManager.applySnapshot(Map.of(SOUNDS, RegistryNumbering.snapshot(serverNumbering,
                RegistryManager.takeSnapshot(RegistryManager.SnapshotType.SYNC_TO_CLIENT).get(SOUNDS).getAliases())), false);
            check(unknown.isEmpty(), "the simulated server's numbering named sounds the game does not have: " + unknown);
        });
        try {
            check(test.onClient(() -> numberOf(PLAYED.get(0))).intValue() == serverNumbers.get(0).intValue(), "the simulated server's numbering of sounds was not put in force");
            record(test, minecraft, "Shifted", shifted);
        } finally {
            if (minecraft.level != null) {
                WorldSteps.leaveWorld(test);
            }
            // NeoForge has put the game's own back on leaving, as it does on leaving a server; again here in case the world never opened
            test.onClientRun(RegistryManager::revertToFrozen);
        }
        check(own.equals(test.onClient(RegistrySteps::numbering)), "leaving the world did not put the game's own numbering back");
        expectHeard("live", right, wrong, "while recording on the simulated server");

        // A recording with the game's own numbering, to open over the first
        record(test, minecraft, "Plain", plain);
        WorldSteps.leaveWorld(test);
        expectHeard("live", right, wrong, "while recording with the game's own numbering");

        // Playback of the recording made on the "server": its numbering in force, its sounds themselves
        open(test, shifted);
        test.onClientRun(() -> {
            check(RegistryNumbering.changed(), "playback of the recording made on the simulated server kept the game's own numbering");
            for (int i = 0; i < PLAYED.size(); i++) {
                check(numberOf(PLAYED.get(i)) == serverNumbers.get(i).intValue(), "in playback " + right.get(i) + " is numbered " + numberOf(PLAYED.get(i))
                    + ", not " + serverNumbers.get(i) + " as on the simulated server");
            }
            Map<String, Map<Integer, String>> now = numbering();
            now.remove(SOUNDS.toString());
            Map<String, Map<Integer, String>> others = new TreeMap<>(own);
            others.remove(SOUNDS.toString());
            check(others.equals(now), "playback changed the numbering of registries the simulated server numbered as this game does");
        });
        playThrough(test);
        expectHeard("play", right, wrong, "in playback of the recording made on the simulated server");

        // Another recording opened over it: the game's own numbering back, and left alone, as the recording's is the same
        open(test, plain);
        test.onClientRun(() -> {
            check(!RegistryNumbering.changed(), "opening a recording made with the game's own numbering over one made on the simulated server left a numbering in force");
            check(own.equals(numbering()), "the game's own numbering was not back when a recording made with it was opened");
        });
        playThrough(test);
        expectHeard("play", right, wrong, "in playback of the recording made with the game's own numbering");

        PlaybackSteps.close(test);
        test.onClientRun(() -> {
            check(!RegistryNumbering.changed(), "closing playback left a recording's numbering in force");
            check(own.equals(numbering()), "the game's own numbering was not back after playback");
        });
        test.log("registries: the sounds played by number on the simulated server were heard as themselves in playback, and the game's own numbering is back");
    }

    /** Listens for the sounds the client's world plays, once in the game */
    private static void listen() {
        if (listening) {
            return;
        }
        listening = true;
        NeoForge.EVENT_BUS.addListener((PlayLevelSoundEvent.AtPosition event) -> heard(event));
        NeoForge.EVENT_BUS.addListener((PlayLevelSoundEvent.AtEntity event) -> heard(event));
    }

    private static void heard(PlayLevelSoundEvent event) {
        if (!collecting || !event.getLevel().isClientSide() || event.getSound() == null) {
            return;
        }
        String name = event.getSound().value().getLocation().toString();
        if (!Host.get().inPlayback()) {
            heard.add("live " + name);
        } else if (Playback.loaded() && !Playback.skipping()) {
            heard.add("play " + name);
        }
    }

    /** Each sound played was heard as itself every time, and never as the sound this game's own numbering gives its number */
    private static void expectHeard(String where, List<String> right, List<String> wrong, String when) {
        List<String> got = heard.stream().filter(line -> line.startsWith(where + " ")).map(line -> line.substring(where.length() + 1)).toList();
        heard.clear();
        for (int i = 0; i < right.size(); i++) {
            String sound = right.get(i);
            long times = got.stream().filter(sound::equals).count();
            long mistaken = got.stream().filter(wrong.get(i)::equals).count();
            check(times >= ROUNDS && mistaken == 0, when + ": " + sound + " was heard " + times + " of " + ROUNDS + " times, and "
                + wrong.get(i) + " in its place " + mistaken + " times; heard: " + got);
        }
    }

    private static void record(SelfTest test, Minecraft minecraft, String name, Path recording) throws Exception {
        WorldSteps.createWorld(test, WORLD);
        heard.clear();
        test.tool("recording_start", new JsonObject());
        test.waitTicks(20);
        for (int round = 0; round < ROUNDS; round++) {
            for (Holder<SoundEvent> sound : PLAYED) {
                serverPlays(minecraft, sound);
                test.waitTicks(10);
            }
        }
        test.waitTicks(20);
        JsonObject finished = test.tool("recording_finish", args("name", name));
        Files.move(Path.of(finished.get("path").getAsString()), recording, StandardCopyOption.REPLACE_EXISTING);
        test.log("registries: recorded " + name);
    }

    /** The world's server plays a sound where the player stands, for everyone near, by its number as the wings' is */
    private static void serverPlays(Minecraft minecraft, Holder<SoundEvent> sound) throws Exception {
        IntegratedServer server = minecraft.getSingleplayerServer();
        check(server != null, "there is no built-in server to play a sound");
        server.submit(() -> {
            ServerPlayer player = server.getPlayerList().getPlayers().get(0);
            player.serverLevel().playSound(null, player.getX(), player.getY(), player.getZ(), sound, SoundSource.BLOCKS, 1.0F, 1.0F);
        }).get(30, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static void open(SelfTest test, Path recording) throws Exception {
        test.tool("replay_open", args("path", recording.toString()));
        test.waitFor(() -> Playback.loaded() || Playback.lastError() != null, 90, "the recording " + recording.getFileName() + " to open");
        check(Playback.lastError() == null, "the recording " + recording.getFileName() + " did not open: " + Playback.lastError());
        test.waitTicks(5);
        heard.clear();
    }

    private static void playThrough(SelfTest test) throws Exception {
        test.tool("replay_set_speed", args("tick_rate", 20));
        int total = Playback.total();
        test.onClientRun(() -> Playback.playTo(total));
        test.waitFor(() -> Playback.paused() && Playback.position() == total, 120, "the play-through to tick " + total);
        test.waitTicks(5);
    }

    private static int numberOf(Holder<SoundEvent> sound) {
        return BuiltInRegistries.SOUND_EVENT.getId(sound.value());
    }

    /** By registry, the number of each entry, for every registry a server sends its numbering for. Client thread */
    private static Map<String, Map<Integer, String>> numbering() {
        Map<String, Map<Integer, String>> all = new TreeMap<>();
        RegistryManager.takeSnapshot(RegistryManager.SnapshotType.SYNC_TO_CLIENT).forEach((registry, snapshot) -> {
            Map<Integer, String> numbers = new TreeMap<>();
            snapshot.getIds().forEach((number, name) -> numbers.put(number, name.toString()));
            all.put(registry.toString(), numbers);
        });
        return all;
    }

}
