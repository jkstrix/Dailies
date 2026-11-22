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
 * Run by itself with -PdailiesOnly=edges.
 */
final class EdgeSteps {

    /** Goes up whenever the staging changes, so that a fixture staged before is staged and recorded again by itself */
    private static final int VERSION = 1;


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
            // Played back from the recording kept by an earlier run when it still stands for this code (StagedFixtures). The
            // ticks the recorder respawned on go with it: playback reaches the rebuilt world a tick later than the live game did
            StagedFixtures.recordOrReuse(test, "edges", VERSION, folder, recording, () -> {
                record(test, minecraft, recording);
                return true;
            }, out -> {
                out.name("respawns");
                out.json.beginArray();
                for (int tick : new java.util.TreeSet<>(Census.respawnTicks)) {
                    out.json.value(tick);
                }
                out.json.endArray();
            }, in -> {
                Census.respawnTicks.clear();
                in.name("respawns");
                in.json.beginArray();
                while (in.more()) {
                    Census.respawnTicks.add(in.integer());
                }
                in.json.endArray();
            });
            Census.voidSpilled = 0;
            try {
                CensusPlayback.compare(test, recording, folder, "EDGES", "edges");
            } finally {
                // What the recorder dropped dying in the void is left to scatter and fall in playback, for the fun of it
                test.log("edges: items seen falling out of the world in playback, counted a tick each: " + Census.voidSpilled);
            }
            // With the Corpse mod nothing falls loose in the void: the corpse holds it all, and there is nothing to spill
            if (Census.voidSpilled == 0 && !Host.get().isModLoaded("corpse")) {
                throw new IllegalStateException("edges: what the recorder dropped dying in the void was never seen scattering and falling in playback");
            }
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
        Census.watch(OVERWORLD, 0, -12, -64, -12, 46, -55, 30);
        c("gamerule sendCommandFeedback false");
        c("gamerule doMobSpawning false");
        c("gamerule doImmediateRespawn false");
        c("time set 1000");
        // Respawning at a known place, well away from where the recorder dies: respawned on the spot, they picked up all
        // they had dropped at once (the owner, watching, 18 November: "you keep dying on the spawn block")
        c("setworldspawn 0 " + Y + " 0");
        c("spawnpoint @s 0 " + Y + " 0");
        c("fill " + PIT_X + " -63 " + PIT_Z + " " + PIT_X + " -61 " + PIT_Z + " minecraft:powder_snow");
        c("tp @s 0.5 " + Y + " 0.5 0 0");
        test.waitTicks(40);

        test.tool("recording_start", new JsonObject());
        Census.start(Census.Mode.LIVE);
        try {
            test.waitTicks(20);
            stage(test, "powder snow", () -> powderSnow(test, minecraft));
            stage(test, "snowballs", () -> snowballs(test, minecraft));
            stage(test, "the void", () -> theVoid(test, minecraft));
            test.waitTicks(10);
        } finally {
            Census.stop();
        }
        JsonObject finished = test.tool("recording_finish", args("name", "Edges"));
        Files.move(Path.of(finished.get("path").getAsString()), recording, StandardCopyOption.REPLACE_EXISTING);
        BestiarySteps.weigh();
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
        item("Falling into the void with a full inventory, dying there and respawning").owns("recorder", "blocks")
            .need(where("recorder", "below the bottom of the world", thing -> thing.y() < -100))
            .need(where("recorder", "dying", thing -> thing.state().contains("pose=DYING") || thing.clocks().contains("deathTime=")))
            .need(where("recorder", "standing again after respawning", thing -> thing.y() > Y - 1 && thing.y() < Y + 2 && !thing.state().contains("pose=DYING")))
            .note("what is carried spills out where the recorder dies, three hundred blocks below the world, and is gone on the next tick, in the live"
                + " game as in playback: nothing to see (the ground fall after this shows the spill); the dead recorder goes in a puff of smoke a second"
                + " after dying, as other players see it; the respawn is shown a tick after the live game rebuilt its world");
        c("effect clear @s");
        c("gamemode survival");
        c("gamerule keepInventory false");
        fillInventory();
        waitTicks(5);
        sayRules(test, "the void");
        c("tp @s 40.5 -90 2.5 0 60");
        int at = mark("the recorder falls out of the world");
        still("edges-void-falling", at + 10, 46.5, -80.0, 8.5, 40.5, -100.0, 2.5, false);
        try {
            test.waitFor(() -> minecraft.gui.screen() instanceof DeathScreen, 30, "the recorder to die in the void");
        } catch (Throwable never) {
            problems.add("the recorder did not die in the void within thirty seconds");
            c("gamemode creative");
            return;
        }
        mark("the recorder dead in the void");
        waitTicks(30);
        test.onClientRun(() -> {
            minecraft.player.respawn();
            minecraft.gui.setScreen(null);
        });
        test.waitFor(() -> minecraft.player != null && minecraft.player.isAlive() && minecraft.gui.screen() == null, 30, "the respawn");
        waitTicks(10);
        checkEmptied(test, minecraft, "dying in the void");
        at = mark("the recorder respawned");
        still("edges-respawned", at + 10, 6.5, Y + 3.0, 6.5, 0.5, Y + 1.0, 0.5, false);
        waitTicks(40);
        groundFall(test, minecraft);
    }

    /**
     * After a respawn with keepInventory off, nothing carried comes back: the bag, the hotbar, the off hand and armour are
     * all empty (the owner, 18 November: "check the inventory after respawn"). What the recorder's hotbar showed is in the
     * recorded HUD, which playback is compared with at every tick
     */
    private static void checkEmptied(SelfTest test, Minecraft minecraft, String after) throws Exception {
        String left = test.onClient(() -> {
            StringBuilder kept = new StringBuilder();
            net.minecraft.world.entity.player.Inventory inventory = minecraft.player.getInventory();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                net.minecraft.world.item.ItemStack stack = inventory.getItem(slot);
                if (!stack.isEmpty()) {
                    kept.append(kept.isEmpty() ? "" : ", ").append(stack.getCount()).append(' ').append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()));
                }
            }
            return kept.toString();
        });
        if (left.isEmpty()) {
            test.log("edges: the inventory was empty after " + after);
        } else {
            problems.add("the inventory was not empty after " + after + ": " + left);
        }
    }

    /** What the server holds of the rule and the game mode that decide whether a death spills what is carried, for the log */
    private static void sayRules(SelfTest test, String before) throws Exception {
        BestiarySteps.server("the rules before " + before, (level, player) -> test.log("edges: before " + before + " keepInventory is "
            + level.getGameRules().getBoolean(net.minecraft.world.level.gamerules.GameRules.RULE_KEEPINVENTORY) + ", the recorder is in "
            + ((net.minecraft.server.level.ServerPlayer) player).gameMode.getGameModeForPlayer() + " carrying " + (36 - countEmpty(((net.minecraft.server.level.ServerPlayer) player).getInventory())) + " stacks"));
    }

    private static int countEmpty(net.minecraft.world.entity.player.Inventory inventory) {
        int empty = 0;
        for (int slot = 0; slot < 36; slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                empty++;
            }
        }
        return empty;
    }

    /** A full inventory to spill: the bag, the hotbar and armour */
    private static void fillInventory() throws Exception {
        String[] carried = {"diamond_sword", "bow", "arrow 64", "cooked_beef 32", "torch 64", "oak_planks 64", "iron_pickaxe", "water_bucket", "shield",
            "cobblestone 64", "gold_ingot 20", "redstone 48", "ender_pearl 16", "golden_apple 3", "spyglass", "compass", "clock", "bucket"};
        for (String thing : carried) {
            c("give @s minecraft:" + thing);
        }
        c("item replace entity @s armor.head with minecraft:iron_helmet");
        c("item replace entity @s armor.chest with minecraft:iron_chestplate");
        c("item replace entity @s armor.legs with minecraft:iron_leggings");
        c("item replace entity @s armor.feet with minecraft:iron_boots");
    }

    /** Sixty blocks onto the ground with a full inventory: everything carried scatters where the recorder lands, for all to see */
    private static void groundFall(SelfTest test, Minecraft minecraft) throws Exception {
        double landX = 40.5, landZ = 20.5;
        // With the Corpse mod a death leaves a corpse holding what was carried, where the items would otherwise scatter
        boolean corpse = Host.get().isModLoaded("corpse");
        BestiarySteps.Item fall = item(corpse ? "Falling sixty blocks with a full inventory: a corpse holding it all lies where the recorder lands"
                : "Falling sixty blocks with a full inventory: everything carried scatters where the recorder lands")
            .owns("recorder", "minecraft:item", "corpse:corpse", "blocks")
            .need(where("recorder", "dying on the ground", thing -> (thing.state().contains("pose=DYING") || thing.clocks().contains("deathTime="))
                && Math.abs(thing.x() - landX) < 2 && Math.abs(thing.z() - landZ) < 2));
        if (corpse) {
            fall.need(present("corpse:corpse", 1))
                .need(where("corpse:corpse", "lying where the recorder landed", thing -> Math.abs(thing.x() - landX) < 3 && Math.abs(thing.z() - landZ) < 3))
                .note("the Corpse mod's corpse is an entity of its own the server sends, wearing the recorder's armour and holding the rest");
        } else {
            fall.need(present("minecraft:item", 6))
                .need(where("minecraft:item", "lying around where the recorder landed", thing -> Math.abs(thing.x() - landX) < 5 && Math.abs(thing.z() - landZ) < 5 && thing.y() < Y + 1))
                .note("the items are the server's, sent to every player; the recorder's body goes in a puff of smoke as other players see it, and the items stay");
        }
        c("gamemode survival");
        fillInventory();
        waitTicks(5);
        sayRules(test, "the sixty block fall");
        c(String.format(java.util.Locale.ROOT, "tp @s %.1f %d %.1f 0 60", landX, Y + 60, landZ));
        int at = mark("the recorder falls sixty blocks");
        try {
            test.waitFor(() -> minecraft.gui.screen() instanceof DeathScreen, 20, "the recorder to die from the fall");
        } catch (Throwable never) {
            problems.add("the recorder did not die from a sixty block fall");
            c("gamemode creative");
            return;
        }
        at = mark("the recorder dead on the ground, everything scattered");
        still("edges-spilled", at + 10, landX + 5.0, Y + 3.0, landZ + 5.0, landX, Y + 0.3, landZ, true);
        waitTicks(40);
        test.onClientRun(() -> {
            minecraft.player.respawn();
            minecraft.gui.setScreen(null);
        });
        test.waitFor(() -> minecraft.player != null && minecraft.player.isAlive() && minecraft.gui.screen() == null, 30, "the respawn after the fall");
        waitTicks(10);
        checkEmptied(test, minecraft, "the sixty block fall");
        waitTicks(30);
        c("gamemode creative");
    }

}
