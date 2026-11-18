package comedymonkey.dailies.selftest;

import com.google.gson.JsonObject;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.playback.Playback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static comedymonkey.dailies.selftest.BestiarySteps.begin;
import static comedymonkey.dailies.selftest.BestiarySteps.c;
import static comedymonkey.dailies.selftest.BestiarySteps.end;
import static comedymonkey.dailies.selftest.BestiarySteps.heard;
import static comedymonkey.dailies.selftest.BestiarySteps.item;
import static comedymonkey.dailies.selftest.BestiarySteps.mark;
import static comedymonkey.dailies.selftest.BestiarySteps.present;
import static comedymonkey.dailies.selftest.BestiarySteps.problems;
import static comedymonkey.dailies.selftest.BestiarySteps.still;
import static comedymonkey.dailies.selftest.BestiarySteps.tick;
import static comedymonkey.dailies.selftest.BestiarySteps.waitTicks;
import static comedymonkey.dailies.selftest.BestiarySteps.where;
import static comedymonkey.dailies.selftest.SelfTest.args;

/**
 * Three things the other recordings never did (the owner, 18 November: "we haven't tested powdered snow and falling into
 * the void ... also throwing snow balls"), in a recording of their own: the recorder sinks into powder snow and freezes
 * through, then climbs out and thaws; throws snowballs at a blaze while a snow golem pelts a zombie; and falls out of the
 * bottom of the world, dies in the void and respawns. Playback is compared with the live game by play, by jump and by
 * jump back ({@link CensusPlayback}), the recorder's frost included, and the differences are reported under EDGES.
 * Run by itself with -PdailiesFlags=dailies.test.edgesOnly.
 */
final class EdgeSteps {

    static final String WORLD = "Dailies Edges";
    private static final String OVERWORLD = BestiarySteps.OVERWORLD;
    private static final int Y = -60;
    /** The powder snow pit, three deep, its top level with the ground */
    private static final int PIT_X = 8, PIT_Z = 8;

    private EdgeSteps() {
    }

    static void run(SelfTest test) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        if (Playback.active()) {
            PlaybackSteps.close(test);
        }
        if (minecraft.level != null) {
            WorldSteps.leaveWorld(test);
        }
        Path folder = Host.get().dataDirectory().resolve("edges");
        Files.createDirectories(folder);
        try (java.util.stream.Stream<Path> old = Files.list(folder)) {
            for (Path file : old.toList()) {
                Files.deleteIfExists(file);
            }
        }
        CensusPlayback.begin(test);
        try {
            // How frozen each player is, which the game draws as frost on the screen and on the player: counts up a tick at a
            // time in powder snow and down two at a time out of it, on each game's own clock
            Census.EXTRAS.add(EdgeSteps::describe);
            Census.EXTRA_SLACK.put("frozen", 12.0);
            Path recording = folder.resolve("Edges.dailies");
            record(test, minecraft, recording);
            CensusPlayback.compare(test, recording, folder, "EDGES", "edges");
        } finally {
            CensusPlayback.end();
        }
    }

    private static void describe(Entity entity, StringBuilder state, StringBuilder clocks) {
        if (entity instanceof Player player && player.getTicksFrozen() > 0) {
            clocks.append("frozen=").append(player.getTicksFrozen()).append(';');
        }
    }

    // --- Staging ---

    private static void record(SelfTest test, Minecraft minecraft, Path recording) throws Exception {
        WorldSteps.createWorld(test, WORLD);
        Census.watch(OVERWORLD, 0, -12, -64, -12, 30, -55, 30);
        c("gamerule sendCommandFeedback false");
        c("gamerule doMobSpawning false");
        c("gamerule doImmediateRespawn false");
        c("time set 1000");
        c("fill " + PIT_X + " -63 " + PIT_Z + " " + PIT_X + " -61 " + PIT_Z + " minecraft:powder_snow");
        c("tp @s 0.5 " + Y + " 0.5 0 0");
        test.sleepTicks(40);

        test.tool("recording_start", new JsonObject());
        Census.start(Census.Mode.LIVE);
        try {
            test.sleepTicks(20);
            stage(test, "powder snow", () -> powderSnow(test, minecraft));
            stage(test, "snowballs", () -> snowballs(test, minecraft));
            stage(test, "the void", () -> theVoid(test, minecraft));
            test.sleepTicks(10);
        } finally {
            Census.stop();
        }
        JsonObject finished = test.tool("recording_finish", args("name", "Edges"));
        Files.move(Path.of(finished.get("path").getAsString()), recording, StandardCopyOption.REPLACE_EXISTING);
        for (BestiarySteps.Item item : BestiarySteps.items) {
            for (java.util.function.Function<BestiarySteps.Item, String> need : item.needs) {
                String seen;
                try {
                    seen = need.apply(item);
                } catch (Throwable t) {
                    seen = null;
                }
                (seen == null ? item.missing : item.evidence).add(seen == null ? "need " + (item.needs.indexOf(need) + 1) : seen);
            }
        }
        CensusPlayback.explain(test, "edges");
        test.log("edges: recorded " + Census.live.lastKey() + " ticks; " + problems.size() + " staging problems");
    }

    private interface Stage {
        void run() throws Exception;
    }

    private static void stage(SelfTest test, String name, Stage stage) throws Exception {
        test.log("edges: staging " + name + " at tick " + tick());
        begin();
        try {
            stage.run();
        } catch (Throwable t) {
            problems.add("the stage '" + name + "' stopped early: " + t);
            Host.LOGGER.warn("Dailies self test: edges stage {} stopped early", name, t);
        }
        end();
    }

    private static int frozen(Minecraft minecraft) throws Exception {
        return BestiarySteps.test.onClient(() -> minecraft.player == null ? 0 : minecraft.player.getTicksFrozen());
    }

    /** Into the pit of powder snow in survival, without leather boots: the recorder sinks, freezes through and is hurt by it */
    private static void powderSnow(SelfTest test, Minecraft minecraft) throws Exception {
        item("Sinking into powder snow, freezing through and thawing").owns("recorder", "blocks")
            .need(where("recorder", "down in the powder snow", thing -> thing.y() < Y - 0.5 && Math.abs(thing.x() - (PIT_X + 0.5)) < 0.8))
            .need(where("recorder", "frozen through", thing -> frozenOf(thing) >= 140))
            .need(where("recorder", "thawing on the ground beside it", thing -> frozenOf(thing) > 0 && thing.y() >= Y - 0.1))
            .need(heard("minecraft:entity.player.hurt_freeze"))
            .note("the frost is drawn from how frozen the recorder is, which the census compares as frozen=, a few ticks apart allowed");
        c("gamemode survival");
        c("effect give @s minecraft:regeneration 60 3 true");
        c("tp @s " + (PIT_X + 0.5) + " " + (Y + 1) + " " + (PIT_Z + 0.5) + " 0 20");
        int at = mark("the recorder drops into the powder snow");
        still("edges-snow-sinking", at + 30, PIT_X + 4.5, Y + 2.0, PIT_Z + 4.5, PIT_X + 0.5, Y - 1.0, PIT_Z + 0.5, false);
        // Fully frozen after seven seconds in it, and hurt every two seconds after that
        for (int part = 0; part < 12 && frozen(minecraft) < 140; part++) {
            waitTicks(20);
        }
        waitTicks(60);
        int most = frozen(minecraft);
        at = mark("the recorder frozen through");
        still("edges-snow-frozen", at, PIT_X + 4.5, Y + 2.0, PIT_Z + 4.5, PIT_X + 0.5, Y - 1.0, PIT_Z + 0.5, true);
        if (most < 140) {
            problems.add("the recorder never froze through in the powder snow (frozen " + most + " of 140 ticks)");
        }
        // Out onto the ground beside the pit, to thaw
        c("tp @s " + (PIT_X + 2.5) + " " + Y + " " + (PIT_Z + 0.5) + " 90 10");
        waitTicks(20);
        mark("the recorder thawing");
        waitTicks(60);
        c("gamemode creative");
    }

    private static double frozenOf(Census.Thing thing) {
        java.util.regex.Matcher frozen = java.util.regex.Pattern.compile("frozen=(\\d+)").matcher(thing.clocks());
        return frozen.find() ? Double.parseDouble(frozen.group(1)) : 0;
    }

    /** The recorder throws snowballs at a blaze, and a snow golem pelts a zombie that cannot move */
    private static void snowballs(SelfTest test, Minecraft minecraft) throws Exception {
        item("Snowballs thrown at a blaze, and a snow golem pelting a zombie").owns("recorder", "Target", "Snowman", "Walker", "minecraft:snowball", "blocks")
            .need(present("minecraft:snowball", 1))
            .need(heard("minecraft:entity.snowball.throw"))
            .need(where("Target", "hit by a snowball", thing -> thing.clocks().contains("hurtTime=")))
            .need(where("Walker", "pelted by the snow golem", thing -> thing.clocks().contains("hurtTime=")))
            .note("snowballs fly as entities the server sends; a blaze is hurt by them, a zombie only knocked back, which shows as hurt");
        c("summon minecraft:blaze 4.5 " + Y + " 12.5 {CustomName:'\"Target\"',NoAI:1b,PersistenceRequired:1b,Silent:1b}");
        c("summon minecraft:snow_golem 18.5 " + Y + " 0.5 {CustomName:'\"Snowman\"',PersistenceRequired:1b}");
        c("summon minecraft:zombie 18.5 " + Y + " 8.5 {CustomName:'\"Walker\"',NoAI:1b,PersistenceRequired:1b,Silent:1b}");
        c("item replace entity @s weapon.mainhand with minecraft:snowball 16");
        // Looking at the blaze from six blocks, a little above it: snowballs drop as they fly
        c("tp @s 4.5 " + Y + " 6.5 0 -6");
        waitTicks(10);
        int at = mark("the recorder throws snowballs");
        still("edges-snowballs", at + 6, 9.5, Y + 3.0, 9.5, 4.5, Y + 1.0, 9.5, false);
        for (int thrown = 0; thrown < 6; thrown++) {
            test.onClientRun(() -> minecraft.gameMode.useItem(minecraft.player, InteractionHand.MAIN_HAND));
            waitTicks(8);
        }
        waitTicks(20);
        at = mark("the snow golem pelting the zombie");
        still("edges-snow-golem", at, 24.5, Y + 3.0, 4.5, 18.5, Y + 1.0, 4.5, true);
        waitTicks(80);
        c("kill @e[type=minecraft:snow_golem]");
        c("item replace entity @s weapon.mainhand with minecraft:air");
    }

    /** Out of the bottom of the world in survival: the void hurts until the recorder dies, and the recorder respawns */
    private static void theVoid(SelfTest test, Minecraft minecraft) throws Exception {
        item("Falling into the void, dying there and respawning").owns("recorder", "blocks")
            .need(where("recorder", "below the bottom of the world", thing -> thing.y() < -100))
            .need(where("recorder", "dying", thing -> thing.state().contains("pose=DYING") || thing.clocks().contains("deathTime=")))
            .need(where("recorder", "standing again after respawning", thing -> thing.y() > Y - 1 && thing.y() < Y + 2 && !thing.state().contains("pose=DYING")))
            .note("the recorder falls through chunks with nothing below them; death and the respawn come to the recorder's game as packets of their own");
        c("effect clear @s");
        c("gamemode survival");
        c("tp @s 2.5 -90 2.5 0 60");
        int at = mark("the recorder falls out of the world");
        still("edges-void-falling", at + 10, 8.5, -80.0, 8.5, 2.5, -100.0, 2.5, false);
        try {
            test.waitFor(() -> minecraft.screen instanceof DeathScreen, 30, "the recorder to die in the void");
        } catch (Throwable never) {
            problems.add("the recorder did not die in the void within thirty seconds");
            c("gamemode creative");
            return;
        }
        mark("the recorder dead in the void");
        waitTicks(30);
        test.onClientRun(() -> {
            minecraft.player.respawn();
            minecraft.setScreen(null);
        });
        test.waitFor(() -> minecraft.player != null && minecraft.player.isAlive() && minecraft.screen == null, 30, "the respawn");
        waitTicks(10);
        at = mark("the recorder respawned");
        still("edges-respawned", at + 10, 6.5, Y + 3.0, 6.5, 0.5, Y + 1.0, 0.5, false);
        waitTicks(60);
        c("gamemode creative");
    }

}
