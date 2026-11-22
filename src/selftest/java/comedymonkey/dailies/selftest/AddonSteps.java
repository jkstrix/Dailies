package comedymonkey.dailies.selftest;

import com.google.gson.JsonObject;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.compat.sable.SableCompat;
import comedymonkey.dailies.playback.Playback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;

import static comedymonkey.dailies.selftest.BestiarySteps.begin;
import static comedymonkey.dailies.selftest.BestiarySteps.c;
import static comedymonkey.dailies.selftest.BestiarySteps.end;
import static comedymonkey.dailies.selftest.BestiarySteps.item;
import static comedymonkey.dailies.selftest.BestiarySteps.mark;
import static comedymonkey.dailies.selftest.BestiarySteps.present;
import static comedymonkey.dailies.selftest.BestiarySteps.problems;
import static comedymonkey.dailies.selftest.BestiarySteps.quietly;
import static comedymonkey.dailies.selftest.BestiarySteps.still;
import static comedymonkey.dailies.selftest.BestiarySteps.tick;
import static comedymonkey.dailies.selftest.BestiarySteps.waitTicks;
import static comedymonkey.dailies.selftest.SelfTest.args;

/**
 * The owner's own mod, ComedyMonkey's Create Addons, in a recording of its own (the owner's request of 17 November:
 * "mainly block states, and the trade bot"). A row of its machines is built and set working before the recording starts,
 * so that the first stored world has to carry them: cobblestone generators, a Fluid Wellspring making water, an Auto
 * Harvester, an Animal Pen with cows, the Pressure Crucible, String Tangler and Stone Infuser loaded, a lit Incinerator,
 * a Magma Engine burning, a Lava Generator running an Electric Motor, a Clockwork Dynamo, a Fish Tank and a Monster Cage
 * with food in their troughs, and a formed Geothermal Turbine, each turned by a creative motor where it needs a shaft.
 * While recording, the Cuckoo Clock strikes eight, Trade Bot makes a delivery run (an upgraded chest of wheat, a farmer
 * sitting on a Create seat, a chest for his emeralds), a Fluid Wellspring rides a bearing's arm, and, with Sable, a Magma
 * Engine turns a Compact Gyroscope on a physics object. Every tick the live game's blocks, block entities (with what each
 * was told: troughs, the cuckoo's show, the bot's station) and entities are written down, Trade Bot with how far into his
 * animation he is and where it puts him; then playback is compared with that by play, by jump and by jump back
 * ({@link CensusPlayback}), and the differences are reported item by item under ADDON.
 * <p>
 * Run only when the addon is installed (the modded test game copies it: comedymonkeysaddons-* in dailies.testMods).
 */
final class AddonSteps {

    /**
     * Goes up whenever the staging changes, so that a fixture staged before is staged and recorded again by itself.
     * 2: the notes keep the light the live game drew after each tick written down (Census.lightDrawn)
     */
    private static final int VERSION = 2;


    static final String WORLD = "Dailies Addon";
    private static final String OVERWORLD = BestiarySteps.OVERWORLD;
    private static final String MOD = "comedymonkeysaddons:";
    /** The machines' row faces south, each with its back (and a creative motor) to the north */
    private static final int ROW_Z = 6;
    private static final int Y = -60;
    private static final BlockPos STATION = new BlockPos(6, Y, 16);
    private static final BlockPos INPUT = new BlockPos(3, Y, 16);
    private static final BlockPos OUTPUT = new BlockPos(9, Y, 16);
    private static final BlockPos SEAT = new BlockPos(6, Y, 22);

    private AddonSteps() {
    }

    static void run(SelfTest test) throws Exception {
        if (!Host.get().isModLoaded("comedymonkeysaddons")) {
            test.skip("ComedyMonkey's Create Addons is not installed: the owner's addon (its machines, the Cuckoo Clock and Trade Bot) was not tried");
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (Playback.active()) {
            PlaybackSteps.close(test);
        }
        if (minecraft.level != null) {
            WorldSteps.leaveWorld(test);
        }
        Path folder = Host.get().dataDirectory().resolve("addon");
        Files.createDirectories(folder);
        try (java.util.stream.Stream<Path> old = Files.list(folder)) {
            for (Path file : old.toList()) {
                Files.deleteIfExists(file);
            }
        }
        CensusPlayback.begin(test);
        try {
            Census.EXTRAS.add(AddonSteps::describe);
            // Trade Bot's place in his animation, in seconds, and where it puts him: each game keeps its own clock for it
            Census.EXTRA_SLACK.put("clip", 0.25);
            Census.EXTRA_SLACK.put("rootX", 0.4);
            Census.EXTRA_SLACK.put("rootY", 0.4);
            Census.EXTRA_SLACK.put("rootZ", 0.4);
            // Out of the recordings folder, where other steps count what is there
            Path recording = folder.resolve("Addon.dailies");
            // Played back from the recording kept by an earlier run when it still stands for this code (StagedFixtures)
            StagedFixtures.recordOrReuse(test, "addon", VERSION, folder, recording, () -> {
                record(test, minecraft, recording);
                return true;
            }, out -> {
            }, in -> {
            });
            CensusPlayback.compare(test, recording, folder, "ADDON", "addon");
        } finally {
            CensusPlayback.end();
        }
    }

    // --- Staging ---

    private static void record(SelfTest test, Minecraft minecraft, Path recording) throws Exception {
        WorldSteps.createWorld(test, WORLD);
        Census.watch(OVERWORLD, 0, -3, -61, 2, 66, -55, 25);
        c("gamerule max_block_modifications 4000000");
        c("gamerule send_command_feedback false");
        // The Cuckoo Clock strikes as the day passes the hour, and Trade Bot only works by day
        c("gamerule advance_time true");
        c("time set 1000");
        machines();
        boolean bot = tradeBot(test, minecraft);
        c("tp @s 22.5 -59 14.5 150 20");
        test.waitTicks(60);

        test.tool("recording_start", new JsonObject());
        Census.start(Census.Mode.LIVE);
        try {
            test.waitTicks(20);
            stage(test, "machines", () -> running(test));
            stage(test, "the Cuckoo Clock", () -> cuckoo(test));
            if (bot) {
                stage(test, "Trade Bot", () -> delivery(test, minecraft));
            }
            stage(test, "a contraption", () -> contraption(test, minecraft));
            if (Host.get().isModLoaded("sable") && SableCompat.available()) {
                stage(test, "a physics object", () -> craft(test, minecraft));
            } else {
                test.skip("Sable is not installed: the addon on a physics object was not tried");
            }
            test.waitTicks(10);
        } finally {
            Census.stop();
        }
        JsonObject finished = test.tool("recording_finish", args("name", "Addon"));
        Files.move(Path.of(finished.get("path").getAsString()), recording, StandardCopyOption.REPLACE_EXISTING);
        // The machines were running all through: their items cover the whole recording
        for (BestiarySteps.Item item : BestiarySteps.items) {
            if (item.note.startsWith("machine")) {
                item.from = 0;
                item.to = Census.live.lastKey();
            }
        }
        weigh();
        CensusPlayback.explain(test, "addon");
        test.log("addon: recorded " + Census.live.lastKey() + " ticks with " + BestiarySteps.items.size() + " items staged; " + problems.size() + " staging problems");
    }

    private interface Stage {
        void run() throws Exception;
    }

    private static void stage(SelfTest test, String name, Stage stage) throws Exception {
        test.log("addon: staging " + name + " at tick " + tick());
        begin();
        try {
            stage.run();
        } catch (Throwable t) {
            problems.add("the stage '" + name + "' stopped early: " + t);
            Host.LOGGER.warn("Dailies self test: addon stage {} stopped early", name, t);
        }
        end();
    }

    /** What the live game showed of each item's needs, as the bestiary weighs its own */
    private static void weigh() {
        BestiarySteps.weigh();
    }

    /** A creative motor turning the block in front of it, from the north, at 64 rpm */
    private static void motor(int x, int y, int z) throws Exception {
        c("setblock " + x + " " + y + " " + (z - 1) + " create:creative_motor[facing=south]");
        quietly("data merge block " + x + " " + y + " " + (z - 1) + " {ScrollValue:64}");
    }

    private static String bottled(String item, String entity) {
        return "{id:\"" + MOD + item + "\",count:1,components:{\"minecraft:entity_data\":{id:\"" + entity + "\"}}}";
    }

    /** The row of machines, set working before the recording starts */
    private static void machines() throws Exception {
        int z = ROW_Z;
        c("fill -2 -61 3 66 -61 24 minecraft:smooth_stone");
        c("setblock 0 " + Y + " " + z + " " + MOD + "andesite_cobble_generator[facing=south]");
        motor(0, Y, z);
        c("setblock 3 " + Y + " " + z + " " + MOD + "brass_cobble_generator[facing=south]");
        motor(3, Y, z);
        c("setblock 6 " + Y + " " + z + " " + MOD + "wellspring{UpgradeSlots:{Size:6,Items:[{Slot:0,id:\"" + MOD + "water_upgrade\",count:1}]}}");
        c("setblock 9 " + Y + " " + z + " " + MOD + "auto_harvester[facing=south,plant=crop]{Plant:{id:\"minecraft:wheat_seeds\",count:1}}");
        motor(9, Y, z);
        c("setblock 12 " + Y + " " + z + " " + MOD + "animal_pen[facing=south]{Captive:" + bottled("bottled_animal", "minecraft:cow") + ",Adults:3}");
        motor(12, Y, z);
        // The crucible takes its shaft on the side it faces, so it faces the motor
        c("setblock 15 " + Y + " " + z + " " + MOD + "pressure_crucible[facing=north]{Inventory:{Size:2,Items:[{Slot:0,id:\"minecraft:gravel\",count:64}]}}");
        motor(15, Y, z);
        c("setblock 18 " + Y + " " + z + " " + MOD + "string_tangler[facing=south]{Inventory:{Size:3,Items:[{Slot:0,id:\"minecraft:string\",count:64},{Slot:1,id:\"minecraft:slime_ball\",count:16}]}}");
        motor(18, Y, z);
        c("setblock 21 " + Y + " " + z + " " + MOD + "stone_infuser[facing=south]{Inventory:{Size:3,Items:[{Slot:0,id:\"minecraft:cobblestone\",count:64},{Slot:1,id:\"minecraft:iron_nugget\",count:64}]}}");
        motor(21, Y, z);
        c("setblock 24 " + Y + " " + z + " " + MOD + "incinerator[facing=south]{Heat:90}");
        // The engine gives rotation out of its front: a shaft there shows it turning
        c("setblock 27 " + Y + " " + z + " " + MOD + "magma_engine[facing=south]{Tank:{Fluid:{id:\"minecraft:lava\",amount:4000}}}");
        c("setblock 27 " + Y + " " + (z + 1) + " create:shaft[axis=z]");
        // The generator gives its power out of its sides; the motor takes it in at its base, opposite its shaft
        c("setblock 30 " + Y + " " + z + " " + MOD + "lava_generator[facing=south]{Tank:{Fluid:{id:\"minecraft:lava\",amount:4000}}}");
        c("setblock 31 " + Y + " " + z + " " + MOD + "electric_motor[facing=east]{StressSetting:256,Energy:100000}");
        // The dynamo takes rotation in at the side it faces
        c("setblock 34 " + Y + " " + z + " " + MOD + "clockwork_dynamo[facing=north]");
        motor(34, Y, z);
        // Two blocks tall, its weights hanging into the block below, its shaft into the middle of its back
        c("setblock 37 " + (Y + 1) + " " + z + " " + MOD + "cuckoo_clock[facing=south]");
        motor(37, Y + 1, z);
        c("setblock 40 " + Y + " " + z + " " + MOD + "fish_tank[facing=south]{Captive:" + bottled("bottled_animal", "minecraft:cod")
            + ",Adults:4,Water:{Fluid:{id:\"minecraft:water\",amount:8000}},Food:{Size:2,Items:[{Slot:0,id:\"" + MOD + "fish_food\",count:32}]}}");
        motor(40, Y, z);
        c("setblock 43 " + Y + " " + z + " " + MOD + "monster_cage[facing=south]{Captive:" + bottled("iron_jar", "minecraft:zombie")
            + ",Count:6,Chow:{Size:2,Items:[{Slot:0,id:\"" + MOD + "monster_chow\",count:32}]}}");
        motor(43, Y, z);
        // A three by three by three of casings with the core in the middle and the controller in the middle of the front
        c("fill 46 " + Y + " " + (z - 1) + " 48 " + (Y + 2) + " " + (z + 1) + " " + MOD + "geothermal_casing");
        c("setblock 47 " + (Y + 1) + " " + z + " " + MOD + "geothermal_core");
        c("setblock 47 " + (Y + 1) + " " + (z + 1) + " " + MOD + "geothermal_controller[facing=south]");
        // Trade Bot's input chest is upgraded too: he opens its lid
        c("setblock " + INPUT.getX() + " " + Y + " " + INPUT.getZ() + " " + MOD + "upgraded_chest[facing=south,tier=iron]{Items:[{Slot:0b,Count:200,Item:{id:\"minecraft:wheat\",count:1}}]}");
        c("setblock " + OUTPUT.getX() + " " + Y + " " + OUTPUT.getZ() + " minecraft:chest[facing=south]");
        // Fluid tanks are saved as {Fluid:{id,amount}}; an upgraded chest keeps each stack as {Slot,Count,Item}, since its stacks outgrow a byte
        // The turbine forms within a second; its tanks are filled once it has
        BestiarySteps.test.waitTicks(50);
        c("data merge block 47 " + (Y + 1) + " " + (z + 1) + " {Lava:{Fluid:{id:\"minecraft:lava\",amount:16000}},Water:{Fluid:{id:\"minecraft:water\",amount:16000}}}");

        String kind = MOD;
        machine("Cobblestone Generators", "machine: turned from behind, making cobblestone", "cobble_generator", "Speed:-?[1-9]", "Stored:[1-9]");
        machine("Fluid Wellspring", "machine: generating water", "wellspring", "amount:[1-9]");
        machine("Auto Harvester", "machine: growing wheat", "auto_harvester", "Speed:-?[1-9]", "wheat_seeds");
        machine("Animal Pen", "machine: three cows", "animal_pen", "Adults:[1-9]", "Speed:-?[1-9]");
        machine("Pressure Crucible", "machine: pressing gravel into stone", "pressure_crucible", "Speed:-?[1-9]", "gravel");
        machine("String Tangler and Stone Infuser", "machine: loaded and turning", "string_tangler", "Speed:-?[1-9]");
        machine("Incinerator", "machine: lit", "incinerator", "Heat:[1-9][0-9]");
        machine("Magma Engine", "machine: burning lava", "magma_engine", "Running:1b");
        machine("Lava Generator and Electric Motor", "machine: the generator running the motor", "lava_generator", "Running:1b");
        BestiarySteps.items.get(BestiarySteps.items.size() - 1).owns("block entity " + kind + "electric_motor").need(blockEntity("electric_motor", "Running:1b"));
        machine("Clockwork Dynamo", "machine: working", "clockwork_dynamo", "Working:1b");
        machine("Fish Tank", "machine: cod, with food in the trough", "fish_tank", "Adults:[1-9]", "FoodCount:[1-9]");
        machine("Monster Cage", "machine: zombies, with chow in the bowl", "monster_cage", "Count:[1-9]", "ChowCount:[1-9]");
        machine("Geothermal Turbine", "machine: formed and running", "geothermal_controller", "Formed:1b", "Running:1b");
        BestiarySteps.items.get(BestiarySteps.items.size() - 1).owns("block entity " + kind + "geothermal_casing");
    }

    /** An item for a machine that runs through the whole recording, with what its block entity must have shown */
    private static void machine(String name, String note, String type, String... shown) {
        BestiarySteps.Item item = new BestiarySteps.Item(name);
        BestiarySteps.items.add(item);
        item.note(note).owns("block entity " + MOD + type, "blocks");
        for (String fragment : shown) {
            item.need(blockEntity(type, fragment));
        }
        if (type.equals("string_tangler")) {
            item.owns("block entity " + MOD + "stone_infuser");
        }
        if (type.equals("cobble_generator")) {
            // The motors that turn the row are Create's, and count against the first machine they are listed with
            item.owns("block entity create:creative_motor", "block entity create:motor");
        }
    }

    /** Whether the live game had a block entity of the addon's kind whose description matched, somewhere in the watched ground */
    private static Function<BestiarySteps.Item, String> blockEntity(String type, String regex) {
        Pattern pattern = Pattern.compile(regex);
        String kind = MOD + type;
        return item -> {
            int ticks = 0;
            for (Census census : Census.live.subMap(item.from, true, Math.max(item.to, Census.live.isEmpty() ? 0 : Census.live.lastKey()), true).values()) {
                for (String entity : census.blockEntities.values()) {
                    if (entity.startsWith(kind) && pattern.matcher(entity).find()) {
                        ticks++;
                        break;
                    }
                }
            }
            return ticks == 0 ? null : kind + " showing " + regex + " on " + ticks + " ticks";
        };
    }

    private static void running(SelfTest test) throws Exception {
        int at = mark("the machines running");
        still("addon-machines-west", at, 10.5, -56.0, 15.5, 10.5, -60.0, 6.5, false);
        waitTicks(60);
        at = mark("the machines running, a while on");
        still("addon-machines-east", at, 36.5, -56.0, 15.5, 38.5, -60.0, 6.5, true);
        waitTicks(60);
    }

    // --- The Cuckoo Clock ---

    private static void cuckoo(SelfTest test) throws Exception {
        item("Cuckoo Clock strikes eight").owns("block entity " + MOD + "cuckoo_clock", "blocks")
            .need(blockEntity("cuckoo_clock", "ShowStart:")).need(BestiarySteps.heard("minecraft:block.note_block.bell"))
            .note("the hands, the doors, the carved villager and the cuckoo are drawn from the show's start time it was told");
        // Twenty ticks before eight: the clock marks the hour as it passes it, and a jump in time past the hour gives no show
        c("time set 1980");
        waitTicks(40);
        int at = mark("the cuckoo clock's show begun");
        still("addon-cuckoo-doors", at + 30, 37.5, -58.5, 9.5, 37.5, -58.5, 6.5, false);
        waitTicks(100);
        at = mark("the cuckoo calling");
        still("addon-cuckoo-calling", at, 37.5, -58.5, 9.5, 37.5, -58.5, 6.5, true);
        waitTicks(170);
        mark("the cuckoo clock's show over");
    }

    // --- Trade Bot ---

    /** His station, placed as a player places it, his containers linked, and a farmer sitting on a seat with an offer for wheat */
    private static boolean tradeBot(SelfTest test, Minecraft minecraft) throws Exception {
        c("setblock " + SEAT.getX() + " " + Y + " " + SEAT.getZ() + " create:white_seat");
        c("setblock " + (SEAT.getX() + 1) + " " + Y + " " + SEAT.getZ() + " minecraft:composter");
        c("summon minecraft:villager " + (SEAT.getX() + 0.5) + " " + (Y + 0.6) + " " + (SEAT.getZ() + 0.5) + " {CustomName:\"Buyer\",PersistenceRequired:1b,"
            + "VillagerData:{profession:\"minecraft:farmer\",level:1,type:\"minecraft:plains\"},"
            + "Offers:{Recipes:[{buy:{id:\"minecraft:wheat\",count:20},sell:{id:\"minecraft:emerald\",count:1},maxUses:16,uses:0,rewardExp:1b,xp:2}]}}");
        // A full charge, so that he sets off at once instead of winding up first
        c("tp @s " + (STATION.getX() + 0.5) + " " + Y + " " + (STATION.getZ() + 3.5) + " 180 40");
        c("item replace entity @s weapon.mainhand with " + MOD + "trade_bot[" + MOD + "trade_bot={charge:100000},custom_name='\"Trader\"']");
        test.waitTicks(10);
        BlockPos ground = STATION.below();
        test.onClientRun(() -> minecraft.gameMode.useItemOn(minecraft.player, InteractionHand.MAIN_HAND,
            new BlockHitResult(new Vec3(ground.getX() + 0.5, ground.getY() + 1.0, ground.getZ() + 0.5), Direction.UP, ground, false)));
        test.waitTicks(20);
        boolean placed = test.onClient(() -> {
            BlockEntity station = minecraft.level.getBlockEntity(STATION);
            return station != null && BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(station.getType()).toString().equals(MOD + "trade_bot");
        });
        if (!placed) {
            problems.add("Trade Bot's station was not placed at " + STATION.toShortString() + " by using his item on the ground");
            return false;
        }
        BlockPos in = INPUT.subtract(STATION), out = OUTPUT.subtract(STATION);
        c("data merge block " + STATION.getX() + " " + STATION.getY() + " " + STATION.getZ() + " {Energy:100000,Inputs:[[I;" + in.getX() + "," + in.getY() + "," + in.getZ()
            + "]],Output:[I;" + out.getX() + "," + out.getY() + "," + out.getZ() + "]}");
        test.waitTicks(25);
        boolean seated = test.onClient(() -> {
            for (Entity entity : minecraft.level.entitiesForRendering()) {
                if (entity.hasCustomName() && entity.getCustomName().getString().equals("Buyer")) {
                    return entity.isPassenger();
                }
            }
            return false;
        });
        if (!seated) {
            problems.add("the farmer did not sit down on the Create seat: Trade Bot only sells to seated villagers");
        }
        wheatOffer();
        c("item replace entity @s weapon.mainhand with minecraft:air");
        return true;
    }

    /**
     * The farmer's one offer, wheat for an emerald, set on the server. The offers given at the summoning do not last, and
     * nor does this for long: a farmer takes up his trade again by the composter beside him and gets a farmer's own offers
     * (potatoes for an emerald, an emerald for bread), none of which buys wheat. So it is set again before each run
     */
    private static void wheatOffer() throws Exception {
        BestiarySteps.server("the farmer's wheat offer", (level, player) -> {
            for (net.minecraft.world.entity.npc.villager.Villager villager : level.getEntitiesOfClass(net.minecraft.world.entity.npc.villager.Villager.class, new net.minecraft.world.phys.AABB(SEAT).inflate(4))) {
                net.minecraft.world.item.trading.MerchantOffers offers = new net.minecraft.world.item.trading.MerchantOffers();
                offers.add(new net.minecraft.world.item.trading.MerchantOffer(new net.minecraft.world.item.trading.ItemCost(net.minecraft.world.item.Items.WHEAT, 20),
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.EMERALD), 16, 2, 0.05F));
                villager.setOffers(offers);
            }
        });
    }

    private static void delivery(SelfTest test, Minecraft minecraft) throws Exception {
        String bot = "Trader";
        Set<String> clips = new HashSet<>();
        item("Trade Bot's delivery run").owns(bot, "Buyer", "block entity " + MOD + "trade_bot", "block entity " + MOD + "upgraded_chest", "block entity minecraft:chest", "blocks")
            .need(present(bot, 1))
            .need(BestiarySteps.where(bot, "away from his station", thing -> Math.abs(thing.x() - (STATION.getX() + 0.5)) + Math.abs(thing.z() - (STATION.getZ() + 0.5)) > 2.5))
            .need(BestiarySteps.where(bot, "in three or more animations", thing -> {
                java.util.regex.Matcher clip = Pattern.compile("anim=([^;]+);").matcher(thing.state());
                if (clip.find()) {
                    clips.add(clip.group(1));
                }
                return clips.size() >= 3;
            }))
            .need(BestiarySteps.where("Buyer", "seated", thing -> thing.state().contains("vehicle=")))
            .need(blockEntity("upgraded_chest", "lid=true"))
            .need(BestiarySteps.where(bot, "in his trading animation", thing -> thing.state().contains("anim=trade_villager")))
            .need(blockEntity("trade_bot", "TradesToday:[1-9]"))
            .need(BestiarySteps.where(bot, "cheering after his trading", thing -> thing.state().contains("anim=expr_happy")))
            .note("his trading animation (the toss, the trade beat, the emerald) is all in one clip whose place, compared tick by tick, is clip=; he is drawn where his animation puts him, from data the server sends; what he carries is not sent, so there is no held item to compare");
        test.waitTicks(5);
        // His own route logging, so that the log says what he did if he does not get there
        quietly("tradebot debug");
        boolean sent = false;
        String refusal = "";
        for (int attempt = 0; attempt < 5 && !sent; attempt++) {
            wheatOffer();
            sent = c("tradebot trade");
            if (!sent) {
                // Refused: he may still be winding up. The refusal is not a fault of playback, but it is logged
                refusal = problems.remove(problems.size() - 1);
                test.log("addon: Trade Bot refused to set off (" + refusal + "); " + whatHeSees(minecraft));
                waitTicks(40);
            }
        }
        if (!sent) {
            problems.add("Trade Bot would not set off (/tradebot trade was refused five times; the last time: " + refusal + "; " + whatHeSees(minecraft) + ")");
        }
        int at = mark("Trade Bot sets off");
        still("addon-bot-leaving", at + 20, 6.5, -57.0, 25.5, 6.5, -60.0, 17.5, false);
        // The recorder watches the farmer's seat from one place and keeps still, with the HUD hidden, so that the live
        // game's screen and playback's stills at the same tick are taken from the same eyes
        float yaw = (float) Math.toDegrees(Math.atan2(-(SEAT.getX() + 0.5 - VANTAGE.x), SEAT.getZ() + 0.5 - VANTAGE.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(Y + 1.0 - (VANTAGE.y + 1.62),
            Math.hypot(SEAT.getX() + 0.5 - VANTAGE.x, SEAT.getZ() + 0.5 - VANTAGE.z)));
        c(String.format(java.util.Locale.ROOT, "tp @s %.2f %.2f %.2f %.1f %.1f", VANTAGE.x, VANTAGE.y, VANTAGE.z, yaw, pitch));
        test.onClientRun(() -> comedymonkey.dailies.ui.HudHidden.set(true));
        Path folder = Host.get().dataDirectory().resolve("addon");
        int tradeStart = -1, cheered = -1;
        int began = tick(), nextMark = began + 100;
        try {
            // Up to eighty seconds: he fetches the wheat, rolls to the farmer, trades, cheers, takes his emeralds to the
            // output and goes home
            while (tick() - began < 1600) {
                waitTicks(5);
                Object[] now = test.onClient(() -> animation(minecraft, bot));
                String animation = now == null ? null : (String) now[0];
                if (tradeStart < 0 && animation != null && animation.startsWith("trade_villager")) {
                    // When the trade began, from how far into it he is now
                    tradeStart = tick() - Math.round((Float) now[1] * 20.0F);
                    trading(folder, tradeStart, yaw, pitch);
                }
                if (cheered < 0 && "expr_happy".equals(animation)) {
                    cheered = mark("Trade Bot cheers after his trading");
                    BestiarySteps.stills.add(new BestiarySteps.Still("addon-bot-cheering", cheered + 10, VANTAGE.x, VANTAGE.y, VANTAGE.z, yaw, pitch, false));
                }
                if (tick() >= nextMark) {
                    mark("Trade Bot on his round, " + (tick() - began) / 20 + " seconds in");
                    nextMark += 100;
                }
                boolean home = tick() - began > 300 && test.onClient(() -> {
                    BlockEntity station = minecraft.level.getBlockEntity(STATION);
                    return station != null && told(station, minecraft).contains("BotDocked:1b");
                });
                if (home) {
                    break;
                }
            }
        } finally {
            test.onClientRun(() -> comedymonkey.dailies.ui.HudHidden.set(false));
        }
        if (tradeStart >= 0) {
            // The trade beat once more, jumped back to from later in the recording (the stills are taken in this order)
            BestiarySteps.stills.add(new BestiarySteps.Still("addon-trade-beat-after-a-jump-back", tradeStart + 80, VANTAGE.x, VANTAGE.y, VANTAGE.z, yaw, pitch, false));
        } else {
            problems.add("Trade Bot was never seen in his trading animation (trade_villager)");
        }
        mark("Trade Bot's round over");
    }

    /** Where the recorder stands to watch the trade: south-east of the farmer, out of Trade Bot's way */
    private static final Vec3 VANTAGE = new Vec3(11.5, Y, 26.5);

    /**
     * The moments of his trading animation (seven seconds and a fifth), marked to be jumped to, with the live game's
     * screen kept at each and a playback still asked for at the same tick from the same eyes: his greeting, the haggling,
     * the toss, the beat where the package lands in the farmer's arms and the trades are made, his catching the emerald,
     * and the drawer shut. The beat is also reached by play
     */
    private static void trading(Path folder, int start, float yaw, float pitch) {
        int[] at = {12, 45, 70, 80, 100, 110};
        String[] what = {"greets the farmer", "haggles", "tosses the goods", "the package lands: the trade", "catches his emerald", "shuts his drawer"};
        for (int i = 0; i < at.length; i++) {
            int tick = start + at[i];
            if (tick <= tick() + 2) {
                continue;
            }
            BestiarySteps.marks.merge(tick, "Trade Bot " + what[i], (a, b) -> a + ", " + b);
            Census.keyTicks.add(tick);
            String name = String.format(java.util.Locale.ROOT, "addon-trade-%.2fs", at[i] / 20.0);
            Census.liveGrabs.put(tick, folder.resolve(name + "-live.png"));
            BestiarySteps.stills.add(new BestiarySteps.Still(name + "-playback", tick, VANTAGE.x, VANTAGE.y, VANTAGE.z, yaw, pitch, at[i] == 80));
        }
    }

    /** The animation Trade Bot is in and how far into it, in seconds, as this game shows him; null when he is not here */
    private static Object[] animation(Minecraft minecraft, String name) {
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity.hasCustomName() && entity.getCustomName().getString().equals(name)) {
                try {
                    Method[] methods = BOT_METHODS.computeIfAbsent(entity.getClass(), AddonSteps::botMethods);
                    Object clip = methods[0].invoke(entity);
                    return new Object[]{String.valueOf(clip.getClass().getMethod("name").invoke(clip)), (Float) methods[1].invoke(entity, 1.0F)};
                } catch (Throwable t) {
                    return null;
                }
            }
        }
        return null;
    }

    /** What the server holds of everything a trading run needs, for the log when he will not go */
    private static String whatHeSees(Minecraft minecraft) {
        try {
            return minecraft.getSingleplayerServer().submit(() -> {
                net.minecraft.server.level.ServerLevel level = minecraft.getSingleplayerServer().overworld();
                StringBuilder seen = new StringBuilder("day=" + level.isBrightOutside() + " time=" + level.getOverworldClockTime());
                BlockEntity station = level.getBlockEntity(STATION);
                seen.append(" station=").append(station == null ? "missing" : station.saveWithoutMetadata(level.registryAccess()));
                net.neoforged.neoforge.transfer.ResourceHandler<net.neoforged.neoforge.transfer.item.ItemResource> input = level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.Item.BLOCK, INPUT, null);
                int wheat = 0;
                if (input != null) {
                    for (int slot = 0; slot < input.size(); slot++) {
                        wheat += input.getResource(slot).is(net.minecraft.world.item.Items.WHEAT) ? input.getAmountAsInt(slot) : 0;
                    }
                }
                seen.append(" input=").append(input == null ? "no item handler" : wheat + " wheat");
                seen.append(" output=").append(level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.Item.BLOCK, OUTPUT, null) == null ? "no item handler" : "a container");
                for (net.minecraft.world.entity.npc.villager.Villager villager : level.getEntitiesOfClass(net.minecraft.world.entity.npc.villager.Villager.class, new net.minecraft.world.phys.AABB(SEAT).inflate(4))) {
                    seen.append(" villager riding ").append(villager.getVehicle() == null ? "nothing" : BuiltInRegistries.ENTITY_TYPE.getKey(villager.getVehicle().getType()))
                        .append(" offers ").append(villager.getOffers().size());
                    for (net.minecraft.world.item.trading.MerchantOffer offer : villager.getOffers()) {
                        seen.append(" [").append(offer.getCostA()).append(" -> ").append(offer.getResult()).append(offer.isOutOfStock() ? " out of stock" : "").append(']');
                    }
                }
                for (Entity bot : level.getEntitiesOfClass(Entity.class, new net.minecraft.world.phys.AABB(STATION).inflate(40), entity -> entity.hasCustomName() && entity.getCustomName().getString().equals("Trader"))) {
                    seen.append(" bot at ").append(bot.blockPosition().toShortString());
                }
                return seen.toString();
            }).get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            return "the server could not be asked (" + e + ")";
        }
    }

    private static String told(BlockEntity entity, Minecraft minecraft) {
        return String.valueOf(comedymonkey.dailies.capture.Snapshots.updateTag(entity, minecraft.level.registryAccess()));
    }

    // --- On a contraption and on a physics object ---

    private static void contraption(SelfTest test, Minecraft minecraft) throws Exception {
        // A bearing's arm is one of Create's stationary contraptions, not a free one
        item("A Fluid Wellspring on a bearing's arm").owns("create:stationary_contraption", "blocks", "block entity create:mechanical_bearing")
            .need(present("create:stationary_contraption", 1))
            .need(BestiarySteps.where("create:stationary_contraption", "carrying the Wellspring", thing -> thing.state().contains("wellspring")))
            .note("the addon says everything works on Create contraptions; the Wellspring is the machine that keeps working there");
        c("fill 58 -58 6 61 -58 6 minecraft:oak_planks");
        c("setblock 60 -57 6 " + MOD + "wellspring{UpgradeSlots:{Size:6,Items:[{Slot:0,id:\"" + MOD + "water_upgrade\",count:1}]}}");
        c("create glue 58 -58 6 61 -57 6");
        c("setblock 58 -60 6 create:creative_motor[facing=up]");
        boolean assembled = false;
        int before = CraftSteps.contraptions(minecraft);
        for (int attempt = 0; attempt < 4 && !assembled; attempt++) {
            c("setblock 58 -59 6 create:mechanical_bearing[facing=up]");
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline && CraftSteps.contraptions(minecraft) <= before) {
                Thread.sleep(100);
            }
            assembled = CraftSteps.contraptions(minecraft) > before;
            if (!assembled) {
                c("setblock 58 -59 6 minecraft:air");
                waitTicks(8);
            }
        }
        if (!assembled) {
            problems.add("the bearing did not assemble the arm with the Wellspring in four tries");
            return;
        }
        test.log("addon: the arm as this game holds it: " + test.onClient(() -> {
            StringBuilder seen = new StringBuilder();
            for (Entity entity : minecraft.level.entitiesForRendering()) {
                if (String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())).contains("contraption")) {
                    StringBuilder state = new StringBuilder();
                    describe(entity, state, new StringBuilder());
                    seen.append(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())).append(' ').append(state).append("; ");
                }
            }
            return seen.length() == 0 ? "no contraption" : seen.toString();
        }));
        int at = mark("the Wellspring turning on the arm");
        still("addon-contraption", at + 20, 56.5, -56.0, 11.5, 59.5, -58.0, 6.5, false);
        waitTicks(80);
        mark("the Wellspring a while on the arm");
    }

    private static void craft(SelfTest test, Minecraft minecraft) throws Exception {
        item("A Magma Engine turning a Compact Gyroscope on a physics object").owns("block entity " + MOD + "magma_engine", "block entity " + MOD + "compact_gyroscope",
                "block entity " + MOD + "flight_instruments", "blocks")
            .need(blockEntity("compact_gyroscope", "Speed:-?[1-9]"))
            .note("blocks on a craft stay real blocks in a plot far away, which is watched once the craft is made");
        c("fill 52 -50 12 55 -50 15 minecraft:stone");
        c("setblock 53 -49 13 " + MOD + "magma_engine[facing=up]{Tank:{Fluid:{id:\"minecraft:lava\",amount:4000}}}");
        c("setblock 53 -48 13 " + MOD + "compact_gyroscope");
        c("setblock 54 -49 14 " + MOD + "flight_instruments[facing=south]");
        c("create glue 52 -50 12 55 -48 15");
        waitTicks(10);
        int before = CraftSteps.crafts(minecraft);
        c("sable assemble area 52 -50 12 55 -48 15");
        test.waitFor(() -> CraftSteps.crafts(minecraft) > before, 15, "the slab with the engine to become a physics object");
        waitTicks(5);
        // Where the craft's blocks really are: watch around them from now on
        BlockPos plot = test.onClient(() -> {
            for (BlockEntity entity : allBlockEntities(minecraft)) {
                String type = String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()));
                if (type.equals(MOD + "compact_gyroscope")) {
                    return entity.getBlockPos();
                }
            }
            return null;
        });
        if (plot == null) {
            problems.add("the Compact Gyroscope was not found on the physics object after it was made");
        } else {
            Census.watch(OVERWORLD, tick(), plot.getX() - 3, plot.getY() - 3, plot.getZ() - 3, plot.getX() + 3, plot.getY() + 3, plot.getZ() + 3);
        }
        int at = mark("the craft falling with its engine running");
        still("addon-craft", at + 10, 49.5, -52.0, 19.5, 53.5, -56.0, 13.5, false);
        waitTicks(100);
        mark("the craft landed");
    }

    /** Every block entity that ticks in this game, wherever it is: a craft's are in a plot far from the chunks round the player */
    private static java.util.List<BlockEntity> allBlockEntities(Minecraft minecraft) {
        java.util.List<BlockEntity> all = new java.util.ArrayList<>();
        try {
            java.lang.reflect.Field field = net.minecraft.world.level.Level.class.getDeclaredField("blockEntityTickers");
            field.setAccessible(true);
            for (Object ticker : new java.util.ArrayList<>((java.util.List<?>) field.get(minecraft.level))) {
                BlockEntity entity = minecraft.level.getBlockEntity(((net.minecraft.world.level.block.entity.TickingBlockEntity) ticker).getPos());
                if (entity != null) {
                    all.add(entity);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            problems.add("the block entities that tick could not be listed: " + e);
        }
        return all;
    }

    // --- What the census writes down of the addon's entities ---

    private static final Map<Class<?>, Method[]> BOT_METHODS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<Class<?>, Method> CONTRAPTION = new java.util.concurrent.ConcurrentHashMap<>();

    private static void describe(Entity entity, StringBuilder state, StringBuilder clocks) {
        String type = String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
        if (type.equals(MOD + "trade_bot") || type.equals(MOD + "trade_master")) {
            try {
                Method[] methods = BOT_METHODS.computeIfAbsent(entity.getClass(), AddonSteps::botMethods);
                Object clip = methods[0].invoke(entity);
                state.append("anim=").append(clip.getClass().getMethod("name").invoke(clip)).append(';');
                float seconds = (float) methods[1].invoke(entity, 1.0F);
                Vec3 root = (Vec3) methods[2].invoke(entity, clip, seconds);
                clocks.append(String.format(java.util.Locale.ROOT, "clip=%.3f;rootX=%.3f;rootY=%.3f;rootZ=%.3f;", seconds, root.x, root.y, root.z));
            } catch (Throwable t) {
                state.append("clip=unreadable ").append(t.getClass().getSimpleName()).append(';');
            }
        } else if (type.equals("create:contraption") || type.equals("create:carriage_contraption") || type.equals("create:stationary_contraption")) {
            // Which of the addon's blocks ride on it, as this game holds them
            try {
                Method contraptionOf = CONTRAPTION.computeIfAbsent(entity.getClass(), owner -> {
                    try {
                        return owner.getMethod("getContraption");
                    } catch (NoSuchMethodException e) {
                        throw new IllegalStateException(e);
                    }
                });
                Object contraption = contraptionOf.invoke(entity);
                if (contraption != null) {
                    Map<?, ?> blocks = (Map<?, ?>) contraption.getClass().getMethod("getBlocks").invoke(contraption);
                    TreeSet<String> addon = new TreeSet<>();
                    for (Object info : blocks.values()) {
                        Object blockState = info.getClass().getMethod("state").invoke(info);
                        String name = blockState.toString();
                        if (name.contains(MOD)) {
                            addon.add(name.replace("Block{", "").replace("}", "").replace(';', ','));
                        }
                    }
                    state.append("blocks=").append(blocks.size()).append(';');
                    if (!addon.isEmpty()) {
                        state.append("addon=").append(addon).append(';');
                    }
                }
            } catch (Throwable t) {
                state.append("contraption=unreadable ").append(t.getClass().getSimpleName()).append(';');
            }
        }
    }

    private static Method[] botMethods(Class<?> type) {
        try {
            Method clip = type.getMethod("clip");
            Method elapsed = type.getMethod("clipElapsed", float.class);
            Method root = type.getMethod("rootPosition", clip.getReturnType(), float.class);
            return new Method[]{clip, elapsed, root};
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

}
