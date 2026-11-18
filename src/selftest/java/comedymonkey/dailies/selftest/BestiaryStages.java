package comedymonkey.dailies.selftest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.LargeFireball;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import net.minecraft.world.entity.projectile.SmallFireball;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.entity.projectile.WitherSkull;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.entity.SculkShriekerBlockEntity;
import net.minecraft.world.level.dimension.end.EndDragonFight;
import net.minecraft.world.level.levelgen.feature.SpikeFeature;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.Predicate;

import static comedymonkey.dailies.selftest.BestiarySteps.*;
import static comedymonkey.dailies.selftest.SelfTest.check;

/**
 * What the bestiary puts in front of the recorder, a few seconds each, in a flat creative world and in The End.
 * Where a command can stage a thing it does; where the game only does it for its own reasons (a ghast's fireball, a
 * dragon's phase, a bobber, a respawn) the server is asked directly, by the same calls the game itself makes.
 */
final class BestiaryStages {

    private BestiaryStages() {
    }

    private interface Stage {
        void run() throws Exception;
    }

    static void prepare() throws Exception {
        c("gamerule commandModificationBlockLimit 4000000");
        c("gamerule sendCommandFeedback false");
        c("time set 6000");
        home();
    }

    static void all() throws Exception {
        stage("the Wither", BestiaryStages::wither);
        stage("creepers", BestiaryStages::creepers);
        stage("fireballs", BestiaryStages::fireballs);
        stage("experience orbs", BestiaryStages::experience);
        stage("The End", BestiaryStages::theEnd);
        stage("projectiles and effects", BestiaryStages::projectiles);
        stage("hanging, riding and display entities", BestiaryStages::fixtures);
        stage("mobs with states", BestiaryStages::mobs);
        stage("the recorder", BestiaryStages::recorder);
        stage("blocks", BestiaryStages::blocks);
        stage("light", BestiaryStages::light);
        stage("sky", BestiaryStages::sky);
    }

    private static void stage(String name, Stage stage) throws Exception {
        test.log("bestiary: staging " + name + " at tick " + tick());
        begin();
        try {
            stage.run();
        } catch (Throwable t) {
            problems.add("the stage '" + name + "' stopped early: " + t);
            comedymonkey.dailies.Host.LOGGER.warn("Dailies self test: bestiary stage {} stopped early", name, t);
        }
        end();
        // Whatever a stage left behind, the next starts from the same place
        try {
            minecraft.execute(() -> minecraft.options.keyUse.setDown(false));
            if (minecraft.level != null && !minecraft.level.dimension().location().toString().equals(OVERWORLD)) {
                c("execute in minecraft:overworld run tp @s 0.5 -57 -6.5 0 20");
                test.waitFor(() -> minecraft.level != null && minecraft.level.dimension().location().toString().equals(OVERWORLD), 60, "the way back to the overworld");
                waitTicks(20);
            }
            c("gamemode creative");
            quietly("effect clear @s");
            quietly("clear @s");
            clearField();
            home();
            waitTicks(10);
        } catch (Throwable t) {
            problems.add("tidying up after '" + name + "' failed: " + t);
        }
    }

    private static void home() throws Exception {
        c("tp @s 0.5 -57 -6.5 0 20");
        fly();
    }

    private static void fly() throws Exception {
        server("hovering", (level, player) -> {
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
        });
    }

    private static void clearField() throws Exception {
        quietly("kill @e[type=!minecraft:player]");
        quietly("fill -40 -60 -40 40 -46 40 minecraft:air");
        quietly("fill -40 -61 -40 40 -61 40 minecraft:grass_block");
        quietly("fill -40 -63 -40 40 -62 40 minecraft:dirt");
        quietly("fill -40 -64 -40 40 -64 40 minecraft:bedrock");
        waitTicks(3);
        quietly("kill @e[type=!minecraft:player]");
    }

    private static Entity find(ServerLevel level, Predicate<Entity> which) {
        for (Entity entity : level.getAllEntities()) {
            if (which.test(entity)) {
                return entity;
            }
        }
        throw new IllegalStateException("the entity to work on is not there");
    }

    private static Entity called(ServerLevel level, String name) {
        return find(level, entity -> entity.hasCustomName() && entity.getCustomName().getString().equals(name));
    }

    private static boolean liveHas(Predicate<Entity> which) throws Exception {
        return test.onClient(() -> {
            if (minecraft.level != null) {
                for (Entity entity : minecraft.level.entitiesForRendering()) {
                    if (which.test(entity)) {
                        return true;
                    }
                }
            }
            return false;
        });
    }

    private static boolean waitForLive(Predicate<Entity> which, int ticks, String what) throws Exception {
        int until = tick() + ticks;
        while (tick() < until) {
            if (liveHas(which)) {
                return true;
            }
            Thread.sleep(40);
        }
        problems.add("the live game never showed " + what + " within " + ticks + " ticks");
        return false;
    }

    // --- 1. The Wither ---

    private static void wither() throws Exception {
        item("Wither: summoned, charging, its explosion").owns("Test Wither").need(present("Test Wither", 1)).need(state("Test Wither", "d19=")).need(changed(-12, 14, 12, 34, 30, 100000));
        item("Wither: boss bar (name, progress, colour, darkened sky)").owns("boss bar").need(seen("a bar named Test Wither that darkens the sky", census -> census.bars.contains("Test Wither") && census.bars.contains("darken=true")))
            .need("the bar at eight or more lengths", item -> {
                java.util.TreeSet<String> shown = new java.util.TreeSet<>();
                Census.live.subMap(item.from, item.to).values().forEach(census -> {
                    if (census.bars.contains("Test Wither|")) {
                        shown.add(census.bars.substring(census.bars.indexOf('|') + 1, census.bars.indexOf('|') + 6));
                    }
                });
                return shown.size() >= 8 ? "the bar at " + shown.size() + " different lengths, " + shown.first() + " to " + shown.last() : null;
            })
            .need(seen("no bar", census -> census.bars.isEmpty()));
        item("Wither: skulls, black and blue").owns("minecraft:wither_skull", "skull target").need(present("minecraft:wither_skull", 2)).need(state("minecraft:wither_skull", "d8=true"));
        item("Wither: blocks broken").owns("blocks").need(changed(-40, -40, 40, 40, 60, 1000000));
        lit("around the Wither's destroyed blocks");
        item("Wither: death, nether star and XP").owns("minecraft:item", "minecraft:experience_orb").need(state("Test Wither", "deathTime=")).need(state("minecraft:item", "nether_star"))
            .need(present("minecraft:experience_orb", 1));

        c("fill -3 -60 21 3 -56 27 minecraft:stone_bricks hollow");
        c("fill -8 -60 34 8 -55 34 minecraft:oak_planks");
        c("summon minecraft:pig -5.5 -60 31.5 {NoAI:1b,Health:300f,attributes:[{id:\"minecraft:generic.max_health\",base:300.0}]," + named("skull target") + "}");
        waitTicks(8);
        mark("before the wither");
        waitTicks(4);
        c("summon minecraft:wither 0.5 -59 24.5 {Invul:100," + named("Test Wither") + "}");
        waitTicks(50);
        still("wither-charging", mark("wither charging"), 0.5, -56, 8.5, 0.5, -58, 24.5, false);
        waitTicks(46);
        mark("the wither's explosion");
        waitTicks(4);
        mark("the wither's explosion");
        waitTicks(4);
        mark("the wither's explosion");
        waitTicks(12);
        mark("after the wither's explosion");
        waitTicks(40);
        server("a blue wither skull", (level, player) -> {
            LivingEntity wither = (LivingEntity) called(level, "Test Wither");
            WitherSkull skull = new WitherSkull(level, wither, new Vec3(0.0, -0.1, 1.0).normalize());
            skull.setOwner(wither);
            skull.setDangerous(true);
            skull.setPos(wither.getX(), wither.getY() + 3.0, wither.getZ() + 1.5);
            level.addFreshEntity(skull);
        });
        waitTicks(6);
        still("wither-fight", mark("wither fight, blue skull flying"), 0.5, -54, 4.5, 0.5, -56, 26.5, false);
        waitTicks(40);
        c("damage @e[type=minecraft:wither,limit=1] 60 minecraft:player_attack by @s");
        waitTicks(30);
        mark("wither hurt");
        waitTicks(30);
        c("damage @e[type=minecraft:wither,limit=1] 90 minecraft:player_attack by @s");
        waitTicks(30);
        mark("wither at half health");
        waitTicks(30);
        mark("wither fight");
        c("damage @e[type=minecraft:wither,limit=1] 4000 minecraft:player_attack by @s");
        waitTicks(8);
        still("wither-dying", mark("wither dying"), 0.5, -54, 4.5, 0.5, -56, 26.5, false);
        waitTicks(22);
        mark("wither dead");
        waitTicks(40);
        mark("after the wither");
        waitTicks(10);
    }

    // --- 3. Creepers ---

    private static void creepers() throws Exception {
        item("Creeper: ordinary, swelling then the bang").owns("creeper plain", "blocks", "particles", "sounds").need(present("creeper plain", 1)).need(state("creeper plain", "swell="))
            .need(changed(-27, 4, -14, 17, 10, 100000)).need(heard("minecraft:entity.generic.explode"));
        item("Creeper: charged").owns("creeper charged").need(state("creeper charged", "charged=true")).need(changed(-18, 21, 2, 40, 60, 100000));
        item("Creeper: a chain of three").owns("creeper chain 1", "creeper chain 2", "creeper chain 3").need(present("creeper chain 1", 1)).need(present("creeper chain 3", 1))
            .need(changed(1, 4, 24, 17, 30, 100000));
        item("Creeper: into water (no blocks broken)").owns("creeper wet").need(present("creeper wet", 1)).need("nothing round the pool blown away", item -> {
            // From after the pool was dug to the end: nothing but the pool's own water may have changed there
            int blown = 0;
            for (Census census : Census.live.subMap(item.from + 12, item.to).values()) {
                for (String change : census.changes == null ? java.util.List.<String>of() : census.changes) {
                    String[] at = change.substring(0, change.indexOf(':')).split(" ");
                    int x = Integer.parseInt(at[0]), z = Integer.parseInt(at[2]);
                    blown += x >= -30 && x <= -20 && z >= 30 && z <= 40 && change.endsWith("-> air") ? 1 : 0;
                }
            }
            return blown == 0 ? "nothing round the pool was blown away" : null;
        });
        lit("the creepers' craters");
        item("Creeper: explosion with drops").owns("creeper drops", "minecraft:item").need(present("creeper drops", 1)).need(state("minecraft:item", "diamond"));

        c("fill -27 -61 33 -23 -61 37 minecraft:water");
        c("setblock 31 -60 31 minecraft:chest{Items:[{Slot:0b,id:\"minecraft:diamond\",count:7},{Slot:1b,id:\"minecraft:gold_ingot\",count:3}]}");
        waitTicks(10);
        int before = mark("before the creepers");
        Census.liveGrabs.put(before, minecraft.gameDirectory.toPath().resolve("dailies/bestiary/light-backwards-to-before-the-explosions-live.png"));
        waitTicks(4);
        c("summon minecraft:creeper -20.5 -60 10.5 {NoAI:1b,ignited:1b,Fuse:40," + named("creeper plain") + "}");
        c("summon minecraft:creeper -8.5 -60 30.5 {NoAI:1b,ignited:1b,Fuse:55,powered:1b," + named("creeper charged") + "}");
        c("summon minecraft:creeper 6.5 -60 10.5 {NoAI:1b,ignited:1b,Fuse:70,Invulnerable:1b," + named("creeper chain 1") + "}");
        c("summon minecraft:creeper 12.5 -60 10.5 {NoAI:1b,ignited:1b,Fuse:78,Invulnerable:1b," + named("creeper chain 2") + "}");
        c("summon minecraft:creeper 18.5 -60 10.5 {NoAI:1b,ignited:1b,Fuse:86,Invulnerable:1b," + named("creeper chain 3") + "}");
        c("summon minecraft:creeper -24.5 -61 35.5 {NoAI:1b,ignited:1b,Fuse:62," + named("creeper wet") + "}");
        c("summon minecraft:creeper 30.5 -60 30.5 {NoAI:1b,ignited:1b,Fuse:48," + named("creeper drops") + "}");
        waitTicks(20);
        still("creepers-swelling", mark("creepers swelling"), -16.5, -57, 4.5, -20.5, -59.5, 10.5, false);
        waitTicks(19);
        still("creeper-bang", mark("the plain creeper's bang"), -16.5, -55, 0.5, -20.5, -59.5, 10.5, true);
        waitTicks(3);
        mark("plain creeper gone");
        waitTicks(8);
        mark("drops creeper gone");
        waitTicks(7);
        mark("charged creeper gone");
        waitTicks(8);
        mark("wet creeper gone");
        waitTicks(8);
        mark("chain, one gone");
        waitTicks(8);
        mark("chain, two gone");
        waitTicks(10);
        mark("chain, all gone");
        waitTicks(30);
        int after = mark("after the creepers");
        still("creepers-after", after, 0.5, -45, -6.5, 0.5, -61, 20.5, false);
        // The screen itself, for the light: a jump forwards over the explosions, and one backwards to before them
        Census.liveGrabs.put(after, minecraft.gameDirectory.toPath().resolve("dailies/bestiary/light-forwards-over-the-explosions-live.png"));
        comparisons.add(new Object[]{"forwards-over-the-explosions", before, after});
        comparisons.add(new Object[]{"backwards-to-before-the-explosions", after, before});
        waitTicks(10);
    }

    // --- 4. Ghast and blaze fireballs ---

    private static void fireballs() throws Exception {
        item("Ghast: shooting face").owns("ghast").need(state("ghast", "charging=true"));
        item("Ghast fireball: flight, explosion and fire").owns("minecraft:fireball", "blocks").need(present("minecraft:fireball", 1)).need(blockChange("-> fire")).need(heard("minecraft:entity.generic.explode"));
        item("Ghast fireball: deflected by a hit").owns("minecraft:fireball").need(present("minecraft:fireball", 2));
        item("Fireball in flight across a stored world (slowed down so a jump lands on it)").owns("minecraft:fireball").need(present("minecraft:fireball", 3));
        item("Blaze: burning state").owns("blaze").need(state("blaze", "d16=1"));
        lit("the fire the fireballs leave");
        item("Blaze fireballs: a burst of three and the fire they light").owns("minecraft:small_fireball", "blocks").need(present("minecraft:small_fireball", 3)).need(blockChange("-> fire"));

        c("fill -16 -60 34 -8 -54 34 minecraft:oak_planks");
        c("summon minecraft:ghast -12.5 -55 16.5 {NoAI:1b," + named("ghast") + ",Rotation:[0f,0f]}");
        c("summon minecraft:blaze 12.5 -58 16.5 {NoAI:1b,NoGravity:1b," + named("blaze") + ",Rotation:[0f,0f]}");
        waitTicks(12);
        mark("ghast and blaze waiting");
        server("the ghast's shooting face", (level, player) -> ((Ghast) called(level, "ghast")).setCharging(true));
        waitTicks(10);
        still("ghast-charging", mark("ghast charging"), -12.5, -55, 6.5, -12.5, -53, 16.5, false);
        waitTicks(3);
        server("the ghast's fireball", (level, player) -> {
            Ghast ghast = (Ghast) called(level, "ghast");
            LargeFireball fireball = new LargeFireball(level, ghast, new Vec3(0.0, -0.12, 1.0).normalize(), 1);
            fireball.setPos(ghast.getX(), ghast.getY(0.5) + 0.5, ghast.getZ() + 4.0);
            level.addFreshEntity(fireball);
            ghast.setCharging(false);
        });
        waitTicks(5);
        still("ghast-fireball", mark("ghast fireball flying"), -4.5, -55, 16.5, -12.5, -55, 26.5, false);
        waitTicks(25);
        mark("ghast fireball landed");
        server("a fireball to deflect", (level, player) -> {
            Ghast ghast = (Ghast) called(level, "ghast");
            LargeFireball fireball = new LargeFireball(level, ghast, new Vec3(1.0, 0.0, 0.0), 1);
            fireball.setPos(-8.5, -57.0, 3.5);
            level.addFreshEntity(fireball);
        });
        waitTicks(4);
        mark("fireball before the hit");
        // What a player's punch does to a fireball: it is turned along the way they are looking
        server("deflecting the fireball", (level, player) -> ((net.minecraft.world.entity.projectile.Projectile) find(level, entity -> entity.getType() == EntityType.FIREBALL))
            .deflect(net.minecraft.world.entity.projectile.ProjectileDeflection.AIM_DEFLECT, player, player, true));
        waitTicks(5);
        mark("fireball deflected");
        waitTicks(30);
        mark("deflected fireball landed");
        // One fireball slowed to a crawl, launched fifteen ticks before a stored world is written, and looked at seventy
        // ticks after it: a jump there starts from that stored world, with the fireball already in the air
        ticksBeforeStoredWorld(15);
        c("summon minecraft:fireball -4.5 -55 10.5 {Motion:[0.0,0.0,0.05],acceleration_power:0.004,ExplosionPower:1b}");
        server("the blaze's burning state", (level, player) -> {
            java.lang.reflect.Method charged = Blaze.class.getDeclaredMethod("setCharged", boolean.class);
            charged.setAccessible(true);
            charged.invoke(called(level, "blaze"), true);
        });
        waitTicks(20);
        still("blaze-burning", mark("blaze burning"), 12.5, -57, 9.5, 12.5, -57, 16.5, false);
        for (int shot = 0; shot < 3; shot++) {
            server("a blaze fireball", (level, player) -> {
                Blaze blaze = (Blaze) called(level, "blaze");
                SmallFireball fireball = new SmallFireball(level, blaze, new Vec3(0.0, -0.3, 1.0).normalize());
                fireball.setPos(blaze.getX(), blaze.getY(0.5) + 0.5, blaze.getZ() + 1.0);
                level.addFreshEntity(fireball);
            });
            waitTicks(6);
            mark("blaze fireballs flying");
        }
        waitTicks(25);
        mark("blaze fire lit");
        waitTicks(22);
        mark("slow fireball, seventy ticks after a stored world");
        waitTicks(6);
        mark("slow fireball");
        c("kill @e[type=minecraft:fireball]");
        waitTicks(6);
    }

    // --- 5. Experience orbs ---

    private static void experience() throws Exception {
        item("XP: a burst from a killed mob").owns("xp pig", "minecraft:experience_orb").need(present("xp pig", 1)).need(present("minecraft:experience_orb", 1));
        item("XP: a bottle o' enchanting breaking").owns("minecraft:experience_bottle").need(present("minecraft:experience_bottle", 1));
        item("XP: orbs in quantity, merging").owns("minecraft:experience_orb").need(present("minecraft:experience_orb", 100))
            .need("orbs worth 1 merging", item -> {
                java.util.Set<Integer> ever = new java.util.HashSet<>();
                long most = 0;
                for (Census census : Census.live.subMap(item.from, item.to).values()) {
                    long here = 0;
                    for (java.util.Map.Entry<Integer, Census.Thing> thing : census.things.entrySet()) {
                        if (thing.getValue().type().equals("minecraft:experience_orb") && thing.getValue().state().contains("value=1;")) {
                            ever.add(thing.getKey());
                            here++;
                        }
                    }
                    most = Math.max(most, here);
                }
                java.util.Set<Integer> gone = new java.util.HashSet<>(ever);
                Census last = Census.live.get(item.to - 4);
                if (last != null) {
                    gone.removeAll(last.things.keySet());
                }
                return gone.size() >= 15 ? ever.size() + " orbs worth 1 were seen, and " + gone.size() + " of them went again, out of the recorder's reach: merged into others" : null;
            });
        item("XP: orbs homing on the recorder and collected, the XP bar in the recorded HUD").owns("recorded HUD")
            .need(seen("an experience level in the HUD", census -> !census.hud.contains("level=0 ") && census.hud.contains("level=")));
        item("XP: orbs left lying").owns("minecraft:experience_orb").need("orbs still lying at the end", item -> orbs(item.to - 4, 0) > 10 ? orbs(item.to - 4, 0) + " orbs still lying at the end" : null);

        waitTicks(6);
        mark("before the orbs");
        c("summon minecraft:pig -12.5 -60 12.5 {NoAI:1b," + named("xp pig") + "}");
        waitTicks(6);
        c("damage @e[name=\"xp pig\",limit=1] 100 minecraft:player_attack by @s");
        server("orbs in quantity", (level, player) -> {
            for (int i = 0; i < 80; i++) {
                ExperienceOrb orb = new ExperienceOrb(level, 4.5, -60.0, 12.5, 1);
                // Lying still, so that they are still on one spot when the next lot comes
                orb.setDeltaMovement(Vec3.ZERO);
                level.addFreshEntity(orb);
            }
            for (int i = 0; i < 40; i++) {
                level.addFreshEntity(new ExperienceOrb(level, 12.5 + (i % 7) * 0.9, -59.0, 20.5 + (i % 5) * 0.9, 2 + i % 8));
            }
        });
        c("summon minecraft:experience_bottle 12.5 -55 12.5");
        waitTicks(10);
        // More on the same spot. Orbs of one worth merge when their numbers are forty apart, each looking about once a second:
        // what is left of the first lot takes in what is left of the second
        server("orbs to merge with the first", (level, player) -> {
            for (int i = 0; i < 80; i++) {
                ExperienceOrb orb = new ExperienceOrb(level, 4.5, -60.0, 12.5, 1);
                orb.setDeltaMovement(Vec3.ZERO);
                level.addFreshEntity(orb);
            }
        });
        waitTicks(1);
        still("orbs", mark("orbs spawned"), 6.5, -56, 6.5, 8.5, -60, 16.5, false);
        waitTicks(30);
        mark("orbs settling");
        waitTicks(40);
        mark("orbs merged");
        waitTicks(30);
        // Two blocks in front of the recorder, at the height they hover at: near enough to home in at once and be taken
        // (five blocks off and below, none was ever taken)
        server("orbs to home on the recorder", (level, player) -> {
            for (int i = 0; i < 30; i++) {
                level.addFreshEntity(new ExperienceOrb(level, player.getX() - 0.6 + (i % 5) * 0.3, player.getY() + 0.5, player.getZ() + 2.0, 2));
            }
        });
        waitTicks(5);
        mark("orbs homing");
        waitTicks(6);
        mark("orbs homing");
        waitTicks(50);
        mark("orbs collected");
        server("what the recorder took", (level, player) -> test.log("bestiary: after the homing orbs the recorder has " + player.experienceLevel
            + " levels and " + player.totalExperience + " points, at " + player.blockPosition() + ", " + player.gameMode.getGameModeForPlayer()));
        waitTicks(20);
        mark("orbs lying");
        waitTicks(6);
    }

    private static long orbs(int tick, int worth) {
        Census census = Census.live.get(tick);
        return census == null ? 0 : census.things.values().stream().filter(thing -> thing.type().equals("minecraft:experience_orb") && (worth == 0 || thing.state().contains("value=" + worth + ";"))).count();
    }

    // --- 2. The Ender Dragon ---

    private static int podium = 64;

    /**
     * What the parts of The End share: the trip's own item, the height of the exit portal, the pillar that is taken down
     * and rebuilt, and where the recorder and the camera stand to watch it
     */
    private static final class EndTrip {
        BestiarySteps.Item travel;
        /** The height of the exit portal */
        int y;
        /** The pillar's middle, its top and its radius */
        int sx, sz, sh, sr;
        /** Half way up the pillar, where it is cut */
        int half;
        /** Which way the recorder faces the pillar */
        float yaw;
        /** Where the camera for the pictures of the pillar stands */
        double cx, cz;
    }

    /**
     * The End, in parts that follow one another: each works on what the one before left (the dragon, the pillar). A part
     * that fails stops the stage, and {@link #stage} brings the recorder back to the overworld
     */
    private static void theEnd() throws Exception {
        EndTrip end = new EndTrip();
        end.travel = endItems();
        arriveInTheEnd(end);
        crystalWithABeam(end);
        dragonFireballs(end);
        perchedBreath(end);
        dragonDeath(end);
        pillarHalfRemoved(end);
        respawn(end);
        backToTheOverworld();
    }

    /** What The End is to show, and the item for the trip itself */
    private static BestiarySteps.Item endItems() {
        BestiarySteps.Item travel = item("The End: the trip there and back (a change of dimension inside the recording)").owns("dimension")
            .need(seen("the live game in The End", census -> census.dimension.equals(END)));
        item("Dragon: the one there from the start, its parts and phases").owns("minecraft:ender_dragon").need(present("minecraft:ender_dragon", 1))
            .need(state("minecraft:ender_dragon", "parts=8")).need(state("minecraft:ender_dragon", "phase=HoldingPattern"));
        item("Dragon: boss bar").owns("boss bar").need(seen("a bar named Ender Dragon", census -> census.bars.contains("Ender Dragon")));
        item("End crystals and their beams").owns("minecraft:end_crystal", "test crystal").need(present("minecraft:end_crystal", 5)).need(state("test crystal", "d8=some("));
        item("End crystal destroyed (explosion)").owns("test crystal", "blocks").need(heard("minecraft:entity.generic.explode"));
        item("Dragon fireball in flight, and the cloud where it lands").owns("minecraft:dragon_fireball", "minecraft:area_effect_cloud").need(present("minecraft:dragon_fireball", 1))
            .need(state("minecraft:area_effect_cloud", "dragon_breath"));
        item("Dragon breath while perched on the exit portal, and its lingering cloud").owns("minecraft:area_effect_cloud", "minecraft:ender_dragon", "particles", "sounds")
            .need(state("minecraft:ender_dragon", "phase=SittingFlaming")).need(present("minecraft:area_effect_cloud", 2));
        item("Dragon breath hurting the recorder (hurt animation, health in the recorded HUD)").owns("recorder", "recorded HUD").need(state("recorder", "hurtTime="))
            .need(seen("health lost in the HUD", census -> census.hud.contains("health=") && !census.hud.contains("health=100.0") && !census.hud.contains("health=20.0")));
        item("Dragon breath collected in a bottle").owns("recorded HUD", "minecraft:area_effect_cloud").need(seen("dragon breath in the held slot", census -> census.hud.contains("dragon_breath")));
        item("Dragon: death (the long animation, the XP shower)").owns("minecraft:ender_dragon", "minecraft:experience_orb").need(state("minecraft:ender_dragon", "dragonDeath=150"))
            .need(state("minecraft:ender_dragon", "phase=Dying")).need(present("minecraft:experience_orb", 10));
        item("Dragon: exit portal and egg appearing").owns("blocks").need(blockChange("-> end_portal")).need(blockChange("-> dragon_egg"));
        item("End pillar: half removed").owns("blocks").need(blockChange("obsidian -> air"));
        lit("The End's portal and the pillar taken down and rebuilt");
        item("Dragon respawn: the four crystals, beams to each pillar, crystals reappearing, the explosion").owns("minecraft:end_crystal", "blocks", "sounds")
            .need(where("minecraft:end_crystal", "with a beam to the sky", thing -> thing.state().contains("d8=some(BlockPos{x=0, y=128, z=0})")))
            .need(where("minecraft:end_crystal", "with a beam to a pillar", thing -> thing.state().contains("d8=some(") && !thing.state().contains("y=128")));
        item("End pillar: rebuilt by the respawn").owns("blocks").need(blockChange("air -> obsidian")).need(blockChange("-> bedrock"));
        item("Dragon: the summoned one and its boss bar").owns("minecraft:ender_dragon", "boss bar").need(present("minecraft:ender_dragon", 2));
        return travel;
    }

    /** Into The End, to the dragon there from the start; finds the exit portal and the pillar to take down, and watches both */
    private static void arriveInTheEnd(EndTrip end) throws Exception {
        c("execute in minecraft:the_end run tp @s 0.5 90 18.5 180 30");
        test.waitFor(() -> minecraft.level != null && minecraft.level.dimension().location().toString().equals(END), 120, "the recorder to arrive in The End");
        fly();
        check(waitForLive(entity -> entity instanceof EnderDragon, 1200, "the ender dragon"), "there is no dragon in The End");
        int[] spike = new int[5];
        server("finding the exit portal and a pillar", (level, player) -> {
            EndDragonFight fight = level.getDragonFight();
            BlockPos portal = (BlockPos) secret(fight, EndDragonFight.class, "portalLocation");
            if (portal == null) {
                portal = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, net.minecraft.world.level.levelgen.feature.EndPodiumFeature.getLocation(BlockPos.ZERO)).below(4);
            }
            podium = portal.getY();
            // The second pillar the respawn rebuilds, so the wait for it is short
            SpikeFeature.EndSpike chosen = SpikeFeature.getSpikesForLevel(level).get(1);
            spike[0] = chosen.getCenterX();
            spike[1] = chosen.getCenterZ();
            spike[2] = chosen.getHeight();
            spike[3] = chosen.getRadius();
            spike[4] = chosen.isGuarded() ? 1 : 0;
        });
        end.y = podium;
        end.sx = spike[0];
        end.sz = spike[1];
        end.sh = spike[2];
        end.sr = spike[3];
        int y = end.y;
        test.log("bestiary: exit portal at height " + y + "; pillar at " + end.sx + " " + end.sz + ", top " + end.sh + ", radius " + end.sr + (spike[4] == 1 ? ", caged" : ""));
        c("tp @s 0.5 " + (y + 14) + " 18.5 180 30");
        fly();
        waitTicks(5);
        Census.watch(END, tick() + 2, -9, y - 6, -9, 9, y + 12, 9);
        Census.watch(END, tick() + 2, end.sx - 10, 50, end.sz - 10, end.sx + 10, end.sh + 12, end.sz + 10);
        waitTicks(25);
        still("end-arrival", mark("in The End"), 0.5, y + 14, 22.5, 0.5, y + 6, 0.5, false);
    }

    /** A crystal with a beam of its own, then destroyed */
    private static void crystalWithABeam(EndTrip end) throws Exception {
        int y = end.y;
        server("a crystal with a beam", (level, player) -> {
            EndCrystal crystal = new EndCrystal(level, 8.5, y + 7.0, 8.5);
            crystal.setBeamTarget(new BlockPos(0, y + 30, 0));
            crystal.setShowBottom(true);
            crystal.setCustomName(Component.literal("test crystal"));
            level.addFreshEntity(crystal);
        });
        waitTicks(30);
        still("end-crystal-beam", mark("crystal with a beam"), 2.5, y + 8, 16.5, 8.5, y + 9, 8.5, false);
        waitTicks(6);
        c("damage @e[type=minecraft:end_crystal,name=\"test crystal\",limit=1] 5 minecraft:player_attack by @s");
        waitTicks(3);
        mark("crystal destroyed");
        waitTicks(15);
        mark("after the crystal");
    }

    /** The fireball the dragon shoots, in flight, and the clouds where fireballs land */
    private static void dragonFireballs(EndTrip end) throws Exception {
        int y = end.y;
        c("tp @e[type=minecraft:ender_dragon,limit=1] 0 " + (y + 30) + " -30");
        waitTicks(5);
        server("the dragon strafing the recorder", (level, player) -> {
            EnderDragon dragon = level.getDragons().get(0);
            dragon.getPhaseManager().setPhase(EnderDragonPhase.STRAFE_PLAYER);
            dragon.getPhaseManager().getPhase(EnderDragonPhase.STRAFE_PLAYER).setTarget(player);
        });
        if (!waitForLive(entity -> entity.getType() == EntityType.DRAGON_FIREBALL, 300, "a dragon fireball shot in flight")) {
            c("summon minecraft:dragon_fireball 6.5 " + (y + 20) + " 12.5 {Motion:[0.0,-0.4,0.0]}");
        }
        waitTicks(2);
        still("dragon-fireball", mark("dragon fireball in flight"), 0.5, y + 16, 24.5, 0.5, y + 16, 10.5, false);
        waitTicks(4);
        mark("dragon fireball in flight");
        waitForLive(entity -> entity.getType() == EntityType.AREA_EFFECT_CLOUD, 200, "the cloud a dragon fireball leaves");
        waitTicks(10);
        still("dragon-fireball-cloud", mark("dragon fireball's cloud"), 0.5, y + 18, 28.5, 0.5, y + 14, 18.5, true);
        // And one that lands on the ground, whatever the dragon did
        c("summon minecraft:dragon_fireball 6.5 " + (y + 16) + " 13.5 {Motion:[0.0,-0.4,0.0]}");
        waitTicks(6);
        mark("second dragon fireball falling");
        waitTicks(60);
        still("dragon-cloud-landed", mark("clouds, sixty ticks on (the radius has grown)"), 2.5, y + 12, 24.5, 6.5, y + 1, 13.5, true);
    }

    /** Perched on the portal, breathing: the recorder stands in the breath, is hurt by it and bottles some, then goes back up */
    private static void perchedBreath(EndTrip end) throws Exception {
        int y = end.y;
        c("tp @e[type=minecraft:ender_dragon,limit=1] 0 " + (y + 9) + " 0");
        c("data merge entity @e[type=minecraft:ender_dragon,limit=1] {DragonPhase:3}");
        int until = tick() + 160;
        while (tick() < until && !liveHas(entity -> entity instanceof EnderDragon dragon && dragon.getPhaseManager().getCurrentPhase().isSitting())) {
            Thread.sleep(50);
        }
        mark("dragon landing");
        c("data merge entity @e[type=minecraft:ender_dragon,limit=1] {DragonPhase:5}");
        waitTicks(8);
        still("dragon-breath-perched", mark("perched, breathing"), 6.5, y + 9, 18.5, 0.5, y + 6, 0.5, true);
        waitTicks(22);
        mark("perched breath's cloud");
        double[] cloud = new double[3];
        boolean found = server("finding the perched breath's cloud", (level, player) -> {
            EnderDragon dragon = level.getDragons().get(0);
            // The breath itself, which is the dragon's own: the fireball summoned above leaves a cloud near the portal
            // too, owned by nobody, and a glass bottle only fills from a cloud the dragon made
            Entity nearest = breathCloud(level, dragon);
            if (nearest == null) {
                throw new IllegalStateException("the perched dragon left no cloud of its own");
            }
            cloud[0] = nearest.getX();
            cloud[1] = nearest.getY();
            cloud[2] = nearest.getZ();
        });
        if (found) {
            still("dragon-breath-cloud", mark("perched breath's cloud"), cloud[0] + 4.5, cloud[1] + 6, cloud[2] + 9.5, cloud[0], cloud[1], cloud[2], true);
            // The recorder, mortal for a moment, stands in it
            c("gamemode survival");
            c("attribute @s minecraft:generic.max_health base set 100");
            c("effect give @s minecraft:instant_health 1 20 true");
            waitTicks(4);
            c(String.format(java.util.Locale.ROOT, "tp @s %.2f %.2f %.2f 180 30", cloud[0] + 1.0, cloud[1], cloud[2] + 1.0));
            waitTicks(24);
            mark("recorder in the breath");
            waitTicks(4);
            mark("recorder in the breath");
            c("item replace entity @s weapon.mainhand with minecraft:glass_bottle");
            waitTicks(4);
            mark("bottle in hand");
            server("collecting breath in a bottle", (level, player) -> {
                // A bottle fills from a cloud of the dragon's within two blocks of the player. The cloud shrinks as it
                // lasts, so the recorder steps to its middle first, as a player reaching for it would
                net.minecraft.world.entity.AreaEffectCloud breath = level.getDragons().isEmpty() ? null : breathCloud(level, level.getDragons().get(0));
                if (breath == null) {
                    throw new IllegalStateException("the dragon's breath was gone before the bottle came out");
                }
                player.connection.teleport(breath.getX(), breath.getY(), breath.getZ(), player.getYRot(), player.getXRot());
                ItemStack bottle = player.getMainHandItem();
                player.setItemInHand(InteractionHand.MAIN_HAND, bottle.use(level, player, InteractionHand.MAIN_HAND).getObject());
                if (!player.getMainHandItem().is(Items.DRAGON_BREATH)) {
                    throw new IllegalStateException("the bottle did not fill: the hand holds " + player.getMainHandItem() + ", the breath's radius is " + breath.getRadius()
                        + ", the recorder " + (player.hasInfiniteMaterials() ? "has" : "has not") + " infinite materials");
                }
            });
            waitTicks(6);
            mark("breath bottled");
            c("gamemode creative");
            c("attribute @s minecraft:generic.max_health base set 20");
        }
        c("tp @s 0.5 " + (y + 14) + " 18.5 180 30");
        fly();
        waitTicks(12);
        mark("recorder back in the air");
    }

    /** Death, from flight: it flies to the portal, rises and comes apart, and the exit portal opens */
    private static void dragonDeath(EndTrip end) throws Exception {
        int y = end.y;
        c("data merge entity @e[type=minecraft:ender_dragon,limit=1] {DragonPhase:0}");
        waitTicks(3);
        c("damage @e[type=minecraft:ender_dragon,limit=1] 4000 minecraft:player_attack by @s");
        check(waitForLive(entity -> entity instanceof EnderDragon dragon && dragon.dragonDeathTime > 0, 900, "the dragon starting to die"), "the dragon would not die");
        waitTicks(28);
        mark("dragon dying");
        waitTicks(40);
        mark("dragon dying");
        waitTicks(40);
        still("dragon-death", mark("dragon mid-death"), 0.5, y + 14, 26.5, 0.5, y + 10, 0.5, false);
        waitTicks(35);
        mark("dragon dying, beams of light");
        waitTicks(22);
        still("dragon-death-xp", mark("dragon dying, XP shower"), 0.5, y + 14, 26.5, 0.5, y + 10, 0.5, true);
        waitTicks(28);
        mark("dragon about to go");
        waitTicks(10);
        mark("dragon gone, portal open");
        waitTicks(6);
        still("end-portal-open", mark("dragon gone, portal open"), 4.5, y + 12, 14.5, 0.5, y + 2, 0.5, false);
        waitTicks(40);
        mark("after the dragon");
    }

    /** A pillar with its top half taken away, watched from about thirty blocks */
    private static void pillarHalfRemoved(EndTrip end) throws Exception {
        int sx = end.sx, sz = end.sz, sh = end.sh, sr = end.sr;
        double scale = 1.0 - 30.0 / Math.sqrt(sx * sx + sz * sz);
        double rx = sx * scale + 0.5, rz = sz * scale + 0.5;
        end.yaw = (float) Math.toDegrees(Math.atan2(-(sx - rx), sz - rz));
        end.half = (62 + sh) / 2;
        int half = end.half;
        // The camera for the pictures stands back from the recorder, who would otherwise fill them
        end.cx = sx * (scale - 0.2) + 0.5;
        end.cz = sz * (scale - 0.2) + 0.5;
        double cx = end.cx, cz = end.cz;
        c(String.format(java.util.Locale.ROOT, "tp @s %.2f %d %.2f %.1f 5", rx, half + 6, rz, end.yaw));
        fly();
        test.log(String.format("bestiary: the recorder stands %.0f blocks from the pillar's middle, %.0f from the portal", Math.sqrt((sx - rx) * (sx - rx) + (sz - rz) * (sz - rz)), Math.sqrt(rx * rx + rz * rz)));
        waitTicks(30);
        still("pillar-whole", mark("pillar whole"), cx, half + 12, cz, sx, half + 4, sz, false);
        waitTicks(4);
        c("kill @e[type=minecraft:end_crystal,x=" + (sx - 4) + ",y=" + (sh - 3) + ",z=" + (sz - 4) + ",dx=8,dy=10,dz=8]");
        c("fill " + (sx - sr - 3) + " " + half + " " + (sz - sr - 3) + " " + (sx + sr + 3) + " " + (sh + 6) + " " + (sz + sr + 3) + " minecraft:air");
        waitTicks(12);
        still("pillar-half-removed", mark("pillar half removed"), cx, half + 12, cz, sx, half + 4, sz, false);
        waitTicks(10);
    }

    /** The respawn, started as placing the fourth crystal starts it: the beams, the pillar rebuilt, the summoned dragon */
    private static void respawn(EndTrip end) throws Exception {
        int y = end.y;
        int sx = end.sx, sz = end.sz, sh = end.sh;
        int half = end.half;
        double cx = end.cx, cz = end.cz;
        boolean started = server("starting the respawn", (level, player) -> {
            EndDragonFight fight = level.getDragonFight();
            BlockPos portal = (BlockPos) secret(fight, EndDragonFight.class, "portalLocation");
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos place = portal.above(1).relative(direction, 2);
                EndCrystal crystal = new EndCrystal(level, place.getX() + 0.5, place.getY(), place.getZ() + 0.5);
                crystal.setShowBottom(false);
                level.addFreshEntity(crystal);
            }
            fight.tryRespawn();
            if (secret(fight, EndDragonFight.class, "respawnStage") == null) {
                throw new IllegalStateException("the fight did not start the respawn (dragonKilled " + secret(fight, EndDragonFight.class, "dragonKilled") + ", portal " + portal + ")");
            }
        });
        check(started, "the respawn would not start");
        int start = tick();
        waitTicks(50);
        still("respawn-beams", mark("respawn: beams to the sky"), 6.5, y + 8, 14.5, 0.5, y + 12, 0.5, false);
        waitTicks(60);
        mark("respawn: beam to the first pillar");
        // The chosen pillar is the second: it comes back on the tick its forty are up, about 180 ticks in
        int rebuilt = waitForBlock(sx, sh - 3, sz, start + 400);
        test.log("bestiary: the pillar came back " + (rebuilt - start) + " ticks into the respawn");
        Census.keyTicks.add(rebuilt - 12);
        marks.merge(rebuilt - 12, "pillar still broken, beams on it", (a, b) -> a + ", " + b);
        waitTicks(3);
        still("pillar-rebuilt", mark("pillar rebuilt"), cx, half + 12, cz, sx, half + 4, sz, false);
        stills.add(new BestiarySteps.Still("pillar-beam-before-rebuild", rebuilt - 3, cx, half + 12, cz, end.yaw, 5.0F, false));
        Census.keyTicks.add(rebuilt - 3);
        marks.merge(rebuilt - 3, "pillar about to come back", (a, b) -> a + ", " + b);
        waitTicks(40);
        mark("respawn: third pillar");
        c("tp @s 0.5 " + (y + 14) + " 18.5 180 30");
        fly();
        waitTicks(120);
        mark("respawn: pillars");
        waitTicks(120);
        mark("respawn: last pillars");
        check(waitForLive(entity -> entity instanceof EnderDragon && entity.isAlive(), 400, "the summoned dragon"), "the respawn made no dragon");
        waitTicks(1);
        mark("respawn: the dragon appears");
        Census.keyTicks.add(tick() - 30);
        marks.merge(tick() - 30, "respawn: summoning the dragon", (a, b) -> a + ", " + b);
        stills.add(new BestiarySteps.Still("respawn-summoning", tick() - 30, 6.5, y + 8, 14.5, 195.0F, -30.0F, false));
        waitTicks(12);
        still("dragon-summoned", mark("the summoned dragon"), 0.5, y + 30, 40.5, 0.5, y + 60, 0.5, false);
        waitTicks(50);
        mark("the summoned dragon flying");
        end.travel.note = "pillar " + sx + " " + sz + " watched from about 30 blocks";
    }

    /** Back to the overworld, as the end of the trip there and back */
    private static void backToTheOverworld() throws Exception {
        c("execute in minecraft:overworld run tp @s 0.5 -57 -6.5 0 20");
        test.waitFor(() -> minecraft.level != null && minecraft.level.dimension().location().toString().equals(OVERWORLD), 120, "the recorder to come back to the overworld");
        fly();
        waitTicks(30);
        mark("back in the overworld");
        waitTicks(5);
    }

    /** The lingering cloud of the dragon's own breath nearest to it, within sixteen blocks; null when there is none. Server thread */
    private static net.minecraft.world.entity.AreaEffectCloud breathCloud(ServerLevel level, EnderDragon dragon) {
        net.minecraft.world.entity.AreaEffectCloud nearest = null;
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof net.minecraft.world.entity.AreaEffectCloud cloud && cloud.isAlive() && cloud.getOwner() instanceof EnderDragon
                && cloud.distanceTo(dragon) < 16.0F && (nearest == null || cloud.distanceTo(dragon) < nearest.distanceTo(dragon))) {
                nearest = cloud;
            }
        }
        return nearest;
    }

    /** Waits until the live game shows a block that is not air at a place, and says at which recording tick it was */
    private static int waitForBlock(int x, int y, int z, int giveUpAt) throws Exception {
        while (tick() < giveUpAt) {
            boolean there = test.onClient(() -> minecraft.level != null && !minecraft.level.getBlockState(new BlockPos(x, y, z)).isAir());
            if (there) {
                return tick();
            }
            Thread.sleep(20);
        }
        problems.add("the block at " + x + " " + y + " " + z + " never came back");
        return tick();
    }

    // --- 6. Outliers: projectiles and effects ---

    private static void projectiles() throws Exception {
        item("Falling block: sand, and an anvil landing").owns("minecraft:falling_block", "blocks").need(present("minecraft:falling_block", 2)).need(blockChange("-> sand")).need(blockChange("-> anvil"));
        item("Primed TNT").owns("minecraft:tnt", "blocks").need(present("minecraft:tnt", 1)).need(changed(24, 30, 40, 40, 20, 100000));
        item("Arrow stuck in a block, and in a mob").owns("minecraft:arrow", "arrow target").need(present("minecraft:arrow", 2)).need(state("arrow target", "d12=1"));
        item("Trident: thrown, and returning with Loyalty").owns("minecraft:trident").need(present("minecraft:trident", 2)).need(state("minecraft:trident", "d10=3"));
        item("Firework rocket with a three-star explosion").owns("minecraft:firework_rocket").need(present("minecraft:firework_rocket", 1));
        item("Ender pearl, and an eye of ender").owns("minecraft:ender_pearl", "minecraft:eye_of_ender").need(present("minecraft:ender_pearl", 1)).need(present("minecraft:eye_of_ender", 1));
        item("Thrown potion, and a lingering cloud").owns("minecraft:potion", "minecraft:area_effect_cloud").need(present("minecraft:potion", 2)).need(present("minecraft:area_effect_cloud", 1));
        item("Evoker fangs").owns("minecraft:evoker_fangs").need(present("minecraft:evoker_fangs", 1));
        item("Shulker bullet, and its levitating target").owns("minecraft:shulker_bullet", "bullet target", "bullet shulker").need(present("minecraft:shulker_bullet", 1))
            .need(where("bullet target", "risen off the ground", thing -> thing.y() > -59.0));
        item("Guardian's beam").owns("guardian", "beam target").need(state("guardian", "beam="));
        item("Lightning bolt, and the pig it turns to a zombified piglin").owns("minecraft:lightning_bolt", "pork", "minecraft:zombified_piglin").need(present("minecraft:lightning_bolt", 1))
            .need(seen("a zombified piglin", census -> census.things.values().stream().anyMatch(thing -> thing.type().equals("minecraft:zombified_piglin"))));
        item("Llama spit").owns("minecraft:llama_spit").need(present("minecraft:llama_spit", 1));
        lit("lightning's fire and the TNT's crater");

        c("setblock -4 -60 8 minecraft:target");
        c("setblock -4 -59 8 minecraft:target");
        c("summon minecraft:pig 4.5 -60 8.5 {NoAI:1b,Health:100f,attributes:[{id:\"minecraft:generic.max_health\",base:100.0}]," + named("arrow target") + "}");
        c("summon minecraft:pig 4.5 -60 20.5 {attributes:[{id:\"minecraft:generic.movement_speed\",base:0.0}]," + named("bullet target") + "}");
        c("summon minecraft:shulker 4.5 -60 12.5 {NoAI:1b," + named("bullet shulker") + "}");
        c("summon minecraft:guardian -12.5 -60 20.5 {NoAI:1b," + named("guardian") + "}");
        c("summon minecraft:pig -12.5 -60 26.5 {NoAI:1b," + named("beam target") + "}");
        c("summon minecraft:pig 20.5 -60 20.5 {NoAI:1b," + named("pork") + "}");
        c("item replace entity @s weapon.mainhand with minecraft:fishing_rod");
        waitTicks(10);
        mark("before the projectiles");
        // Slow things first, and fifteen ticks before a stored world, so that one is written with them in the air
        ticksBeforeStoredWorld(15);
        server("a shulker bullet", (level, player) -> level.addFreshEntity(new ShulkerBullet(level, (LivingEntity) called(level, "bullet shulker"), called(level, "bullet target"), Direction.Axis.Y)));
        server("a guardian's beam", (level, player) -> {
            java.lang.reflect.Method target = Guardian.class.getDeclaredMethod("setActiveAttackTarget", int.class);
            target.setAccessible(true);
            target.invoke(called(level, "guardian"), called(level, "beam target").getId());
        });
        c("summon minecraft:tnt 32.5 -60 35.5 {fuse:70}");
        c("summon minecraft:potion -19.5 -56 12.5 {Item:{id:\"minecraft:lingering_potion\",count:1,components:{\"minecraft:potion_contents\":{potion:\"minecraft:poison\"}}}}");
        c("summon minecraft:potion -16.5 -56 12.5 {Item:{id:\"minecraft:splash_potion\",count:1,components:{\"minecraft:potion_contents\":{potion:\"minecraft:healing\"}}}}");
        c("summon minecraft:falling_block -19.5 -50 4.5 {BlockState:{Name:\"minecraft:sand\"},Time:1}");
        c("summon minecraft:falling_block -16.5 -48 4.5 {BlockState:{Name:\"minecraft:anvil\"},Time:1,HurtEntities:1b}");
        c("summon minecraft:arrow -3.5 -59.5 4.5 {Motion:[0.0,0.0,1.2]}");
        c("summon minecraft:arrow 4.5 -59.3 4.5 {Motion:[0.0,0.0,1.2]}");
        c("summon minecraft:firework_rocket 12.5 -59 8.5 {LifeTime:25,FireworksItem:{id:\"minecraft:firework_rocket\",count:1,components:{\"minecraft:fireworks\":{flight_duration:1,explosions:["
            + "{shape:\"large_ball\",colors:[I;16711680],has_trail:1b},{shape:\"star\",colors:[I;65280,255],fade_colors:[I;16777215],has_twinkle:1b},{shape:\"burst\",colors:[I;16776960]}]}}}}");
        c("summon minecraft:ender_pearl 20.5 -57 6.5 {Motion:[0.0,0.35,0.3]}");
        c("summon minecraft:evoker_fangs -3.5 -60 14.5 {Warmup:12}");
        c("summon minecraft:llama_spit -19.5 -58 20.5 {Motion:[0.0,0.15,0.5]}");
        server("an eye of ender", (level, player) -> {
            EyeOfEnder eye = new EyeOfEnder(level, 20.5, -58.0, 12.5);
            eye.setItem(new ItemStack(Items.ENDER_EYE));
            eye.signalTo(new BlockPos(20, -40, 60));
            level.addFreshEntity(eye);
        });
        server("a trident thrown, and one with Loyalty", (level, player) -> {
            ThrownTrident plain = new ThrownTrident(level, player, new ItemStack(Items.TRIDENT));
            plain.shootFromRotation(player, 10.0F, -20.0F, 0.0F, 1.6F, 0.0F);
            level.addFreshEntity(plain);
            ItemStack loyal = new ItemStack(Items.TRIDENT);
            loyal.enchant(level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.LOYALTY), 3);
            ThrownTrident returning = new ThrownTrident(level, player, loyal);
            returning.shootFromRotation(player, 10.0F, 20.0F, 0.0F, 1.6F, 0.0F);
            level.addFreshEntity(returning);
        });
        waitTicks(5);
        still("projectiles", mark("projectiles in the air"), 0.5, -54, -4.5, 0.5, -59, 10.5, false);
        waitTicks(5);
        c("summon minecraft:lightning_bolt 20.5 -60 20.5");
        waitTicks(4);
        mark("lightning");
        waitTicks(6);
        mark("fangs, firework");
        waitTicks(8);
        still("firework", mark("firework bursting"), 12.5, -56, 0.5, 12.5, -52, 8.5, true);
        waitTicks(20);
        mark("trident coming back");
        waitTicks(20);
        still("beam-and-bullet", mark("beam and bullet, seventy ticks after a stored world"), -4.5, -56, 12.5, -6.5, -59, 22.5, false);
        waitTicks(8);
        mark("tnt gone");
        waitTicks(20);
        mark("lingering cloud");
        waitTicks(6);
    }

    // --- 6. Outliers: things that hang, ride and are displayed ---

    private static void fixtures() throws Exception {
        item("Item frame with a rotated item").owns("minecraft:item_frame").need(state("minecraft:item_frame", "diamond_sword")).need(state("minecraft:item_frame", "d9=3"));
        item("Glow item frame with a map").owns("minecraft:glow_item_frame").need(state("minecraft:glow_item_frame", "filled_map")).need(where("minecraft:glow_item_frame", "with the map's picture known",
            thing -> thing.state().contains("map=") && !thing.state().contains("map=not known")));
        item("Painting").owns("minecraft:painting").need(present("minecraft:painting", 1));
        item("Leash knot with a leashed animal").owns("minecraft:leash_knot", "leashed cow").need(present("minecraft:leash_knot", 1)).need(state("leashed cow", "leash="));
        item("Armour stand with a pose and equipment").owns("stand posed").need(state("stand posed", "wears.head=1 minecraft:carved_pumpkin")).need(state("stand posed", "d16="));
        item("Minecart with a passenger, and a chest minecart").owns("minecraft:minecart", "minecraft:chest_minecart", "cart rider").need(state("minecraft:minecart", "riders=")).need(present("minecraft:chest_minecart", 1));
        item("Boat with two riders").owns("minecraft:boat", "boat rider one", "boat rider two").need(state("boat rider one", "vehicle=")).need(state("boat rider two", "vehicle="));
        item("Fishing bobber with a catch").owns("minecraft:fishing_bobber", "hooked pig").need(present("minecraft:fishing_bobber", 1))
            .need(where("minecraft:fishing_bobber", "hooked into something", thing -> thing.state().contains("d8=") && !thing.state().contains("d8=0;")));
        item("Block display, transformation interpolating").owns("minecraft:block_display").need(state("minecraft:block_display", "shownY=1.5"));
        item("Item display, transformation interpolating").owns("minecraft:item_display").need(state("minecraft:item_display", "shownY=1.5"));
        item("Text display, transformation interpolating").owns("minecraft:text_display").need(state("minecraft:text_display", "shownY=1.5"));
        item("Interaction entity").owns("minecraft:interaction").need(present("minecraft:interaction", 1));
        item("Marker (must be ignored)").owns("minecraft:marker").need("the marker was never sent to the client", item -> Census.live.subMap(item.from, item.to).values().stream()
            .noneMatch(census -> census.things.values().stream().anyMatch(thing -> thing.type().equals("minecraft:marker"))) ? "a marker was summoned and the game was never told of it" : null);
        item("Horse with armour and a rider").owns("armoured horse", "horse rider").need(state("armoured horse", "diamond_horse_armor")).need(state("horse rider", "vehicle="));
        item("Allay holding an item").owns("allay").need(state("allay", "wears.mainhand=1 minecraft:amethyst_shard"));

        c("fill -22 -60 29 24 -56 29 minecraft:smooth_stone");
        c("setblock -4 -60 24 minecraft:oak_fence");
        c("setblock 12 -60 24 minecraft:rail");
        c("setblock 14 -60 24 minecraft:rail");
        waitTicks(4);
        c("summon minecraft:item_frame -19 -58 28 {Facing:2b,Fixed:1b,Item:{id:\"minecraft:diamond_sword\",count:1},ItemRotation:3b}");
        c("summon minecraft:painting -11 -58 28 {facing:2b,variant:\"minecraft:kebab\"}");
        c("summon minecraft:cow -3.5 -60 22.5 {NoAI:1b," + named("leashed cow") + "}");
        c("summon minecraft:armor_stand 4.5 -60 24.5 {ShowArms:1b,Rotation:[180f,0f],Pose:{RightArm:[-90f,20f,0f],LeftArm:[30f,0f,-40f],Head:[20f,30f,0f]},"
            + "ArmorItems:[{id:\"minecraft:iron_boots\",count:1},{id:\"minecraft:iron_leggings\",count:1},{id:\"minecraft:diamond_chestplate\",count:1},{id:\"minecraft:carved_pumpkin\",count:1}],"
            + "HandItems:[{id:\"minecraft:diamond_sword\",count:1},{id:\"minecraft:shield\",count:1}]," + named("stand posed") + "}");
        c("summon minecraft:minecart 12.5 -60 24.5 {Passengers:[{id:\"minecraft:villager\",NoAI:1b," + named("cart rider") + "}]}");
        c("summon minecraft:chest_minecart 14.5 -60 24.5");
        c("summon minecraft:boat 20.5 -60 22.5 {Type:\"spruce\",Passengers:[{id:\"minecraft:pig\",NoAI:1b," + named("boat rider one") + "},{id:\"minecraft:chicken\",NoAI:1b," + named("boat rider two") + "}]}");
        c("summon minecraft:pig -19.5 -60 12.5 {NoAI:1b,Health:100f,attributes:[{id:\"minecraft:generic.max_health\",base:100.0}]," + named("hooked pig") + "}");
        c("summon minecraft:interaction -11.5 -60 14.5 {width:2f,height:3f,response:1b}");
        c("summon minecraft:marker -9.5 -60 14.5");
        c("summon minecraft:horse -3.5 -60 14.5 {Tame:1b,NoAI:1b,SaddleItem:{id:\"minecraft:saddle\",count:1},body_armor_item:{id:\"minecraft:diamond_horse_armor\",count:1},"
            + "Passengers:[{id:\"minecraft:villager\",NoAI:1b," + named("horse rider") + "}]," + named("armoured horse") + "}");
        c("summon minecraft:allay 4.5 -59 14.5 {NoAI:1b,NoGravity:1b,HandItems:[{id:\"minecraft:amethyst_shard\",count:1},{}]," + named("allay") + "}");
        c("summon minecraft:block_display 11.5 -59 12.5 {block_state:{Name:\"minecraft:diamond_block\"},Tags:[\"shown\"]}");
        c("summon minecraft:item_display 15.5 -59 12.5 {item:{id:\"minecraft:netherite_sword\",count:1},item_display:\"fixed\",Tags:[\"shown\"]}");
        c("summon minecraft:text_display 19.5 -59 12.5 {text:'{\"text\":\"Dailies bestiary\"}',billboard:\"center\",Tags:[\"shown\"]}");
        c("item replace entity @s weapon.mainhand with minecraft:fishing_rod");
        server("a leash knot", (level, player) -> ((Mob) called(level, "leashed cow")).setLeashedTo(LeashFenceKnotEntity.getOrCreateKnot(level, new BlockPos(-4, -60, 24)), true));
        server("a glow item frame with a map", (level, player) -> {
            ItemStack map = MapItem.create(level, 0, 0, (byte) 0, false, false);
            MapItemSavedData data = MapItem.getSavedData(map, level);
            for (int x = 0; x < 128; x++) {
                for (int z = 0; z < 128; z++) {
                    data.setColor(x, z, (byte) (4 * (1 + (x / 16 + z / 16) % 50) + 2));
                }
            }
            GlowItemFrame frame = new GlowItemFrame(level, new BlockPos(-15, -58, 28), Direction.NORTH);
            frame.setItem(map, false);
            frame.setRotation(1);
            level.addFreshEntity(frame);
        });
        server("a bobber hooked into a pig", (level, player) -> {
            FishingHook hook = new FishingHook(player, level, 0, 0);
            hook.moveTo(-19.5, -58.0, 12.5, 0.0F, 0.0F);
            hook.setDeltaMovement(0.0, -0.2, 0.0);
            level.addFreshEntity(hook);
        });
        waitTicks(12);
        mark("fixtures placed");
        // The displays start a long change fifteen ticks before a stored world, so later jumps land inside it
        ticksBeforeStoredWorld(15);
        c("execute as @e[tag=shown] run data merge entity @s {start_interpolation:0,interpolation_duration:200,transformation:{translation:[0f,3f,0f],scale:[2f,2f,2f],left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0.7071f,0f,0.7071f]}}");
        waitTicks(40);
        still("fixtures", mark("fixtures, displays a fifth of the way"), 0.5, -55, 2.5, 0.5, -59, 20.5, false);
        waitTicks(45);
        still("displays-moving", mark("displays, seventy ticks after a stored world"), 15.5, -57, 4.5, 15.5, -57, 12.5, false);
        waitTicks(15);
        mark("displays half way");
        waitTicks(60);
        mark("displays most of the way");
        waitTicks(50);
        still("frames", mark("displays arrived"), -15.5, -57.5, 23.5, -15.5, -57.5, 28.5, false);
        waitTicks(6);
    }

    // --- 6. Outliers: mobs with states ---

    private static void mobs() throws Exception {
        item("Warden rising from the ground").owns("minecraft:warden").need(state("minecraft:warden", "pose=EMERGING")).need(state("minecraft:warden", "emerge="));
        item("Slime that splits").owns("big slime").need(present("big slime", 3));
        item("Phantom").owns("phantom").need(present("phantom", 1));
        item("Bat").owns("bat").need(present("bat", 1));
        item("Sheep being dyed and sheared").owns("sheep").need(state("sheep", "d17=14")).need(state("sheep", "d17=30"));
        item("Villager changing profession").owns("villager").need(state("villager", "minecraft:plains/minecraft:none/1")).need(state("villager", "minecraft:plains/minecraft:librarian/2"));
        item("Night falling at a word").owns("time of day").need(seen("night", census -> census.dayTime == 18000));

        c("time set 18000");
        c("summon minecraft:slime 20.5 -60 20.5 {Size:3,NoAI:1b," + named("big slime") + "}");
        c("summon minecraft:phantom -19.5 -54 8.5 {NoAI:1b,Size:3," + named("phantom") + "}");
        c("summon minecraft:bat -11.5 -57 8.5 {NoAI:1b," + named("bat") + "}");
        c("summon minecraft:sheep -3.5 -60 8.5 {NoAI:1b,Color:0b," + named("sheep") + "}");
        c("summon minecraft:villager 4.5 -60 8.5 {NoAI:1b,VillagerData:{profession:\"minecraft:none\",level:1,type:\"minecraft:plains\"}," + named("villager") + "}");
        waitTicks(8);
        mark("mobs placed");
        ticksBeforeStoredWorld(30);
        server("a warden rising", (level, player) -> {
            Warden warden = EntityType.WARDEN.create(level);
            warden.moveTo(12.5, -60.0, 20.5, 180.0F, 0.0F);
            warden.finalizeSpawn(level, level.getCurrentDifficultyAt(warden.blockPosition()), MobSpawnType.TRIGGERED, null);
            level.addFreshEntity(warden);
        });
        waitTicks(10);
        c("data merge entity @e[name=\"sheep\",limit=1] {Color:14b}");
        c("data merge entity @e[name=\"villager\",limit=1] {VillagerData:{profession:\"minecraft:librarian\",level:2,type:\"minecraft:plains\"}}");
        waitTicks(10);
        still("mobs", mark("mobs, sheep dyed"), 0.5, -55, 0.5, 0.5, -59, 12.5, false);
        c("data merge entity @e[name=\"sheep\",limit=1] {Sheared:1b}");
        c("damage @e[name=\"big slime\",limit=1] 100 minecraft:player_attack by @s");
        waitTicks(20);
        still("warden-rising-early", mark("warden a third risen"), 12.5, -58, 12.5, 12.5, -59, 20.5, false);
        waitTicks(40);
        mark("slime split");
        waitTicks(22);
        still("warden-rising", mark("warden rising, seventy ticks after a stored world"), 12.5, -58, 12.5, 12.5, -59, 20.5, false);
        waitTicks(30);
        mark("warden nearly up");
        waitTicks(30);
        mark("warden up");
        c("kill @e[type=minecraft:warden]");
        waitTicks(6);
        c("time set 6000");
        waitTicks(4);
    }

    // --- 6. Outliers: the recorder's own body ---

    private static void recorder() throws Exception {
        item("Recorder: sleeping in a bed").owns("recorder", "blocks").need(state("recorder", "asleep=true")).need(blockChange("occupied=true"));
        item("Recorder: sitting in a pig's saddle").owns("recorder", "steed").need(state("recorder", "vehicle=")).need(state("steed", "riders="));
        item("Recorder: a totem of undying popping").owns("recorder", "recorded HUD").need(heard("minecraft:item.totem.use"));
        item("Recorder: a shield block").owns("recorder", "minecraft:arrow").need(state("recorder", "blocking=true")).need(heard("minecraft:item.shield.block"));
        item("Recorder: gliding with an elytra").owns("recorder").need(state("recorder", "gliding=true")).need(state("recorder", "pose=FALL_FLYING"));
        item("Recorder: a riptide trident").owns("recorder").need(state("recorder", "spinning=true"));

        // Asleep, at night
        c("time set 18000");
        c("setblock 2 -60 -9 minecraft:red_bed[part=foot,facing=south]");
        c("setblock 2 -60 -8 minecraft:red_bed[part=head,facing=south]");
        c("summon minecraft:pig 5.5 -60 -8.5 {Saddle:1b,NoAI:1b," + named("steed") + "}");
        c("tp @s 1.5 -60 -6.5 0 20");
        waitTicks(8);
        mark("before bed");
        server("going to bed", (level, player) -> player.startSleepInBed(new BlockPos(2, -60, -8)).ifLeft(problem -> {
            throw new IllegalStateException("the bed refused: " + problem);
        }));
        waitTicks(30);
        still("asleep", mark("asleep"), 6.5, -57, -12.5, 2.5, -59.5, -8.5, false);
        waitTicks(50);
        mark("asleep");
        // Everyone asleep ends the night; if the game has not woken the recorder by now, they get up
        waitTicks(40);
        server("getting up", (level, player) -> {
            if (player.isSleeping()) {
                player.stopSleepInBed(true, true);
            }
        });
        waitTicks(10);
        mark("out of bed");

        // In the saddle
        server("mounting the pig", (level, player) -> player.startRiding(called(level, "steed"), true));
        waitTicks(25);
        still("riding", mark("riding the pig"), 8.5, -58, -4.5, 5.5, -59.5, -8.5, false);
        waitTicks(25);
        server("getting off", (level, player) -> player.stopRiding());
        waitTicks(8);
        mark("off the pig");

        // Mortal for the totem and the shield
        c("time set 6000");
        c("tp @s 0.5 -60 -6.5 0 0");
        c("gamemode survival");
        c("item replace entity @s weapon.offhand with minecraft:totem_of_undying");
        waitTicks(10);
        mark("holding a totem");
        c("damage @s 1000 minecraft:magic");
        waitTicks(4);
        still("totem", mark("totem popping"), 0.5, -58.5, -2.5, 0.5, -59, -6.5, true);
        waitTicks(30);
        mark("after the totem");
        c("effect clear @s");
        c("effect give @s minecraft:instant_health 1 10 true");
        c("item replace entity @s weapon.offhand with minecraft:shield");
        waitTicks(10);
        // The use key held down, as a hand on the mouse holds it: the game raises the shield itself
        minecraft.execute(() -> minecraft.options.keyUse.setDown(true));
        waitTicks(14);
        still("shield-up", mark("shield up"), 3.5, -58.5, -3.5, 0.5, -59, -6.5, false);
        c("summon minecraft:arrow 0.5 -58.6 -3.5 {Motion:[0.0,0.0,-1.5]}");
        waitTicks(12);
        mark("arrow blocked");
        minecraft.execute(() -> minecraft.options.keyUse.setDown(false));
        waitTicks(10);
        mark("shield down");

        // Gliding: mortal still, so nothing holds them in the air
        c("item replace entity @s weapon.offhand with minecraft:air");
        c("item replace entity @s armor.chest with minecraft:elytra");
        c("tp @s 0.5 -20 -38.5 0 15");
        waitTicks(2);
        server("spreading the elytra", (level, player) -> player.startFallFlying());
        waitTicks(14);
        still("gliding", mark("gliding"), 6.5, -24, -18.5, 0.5, -27, -26.5, false);
        waitTicks(14);
        mark("gliding");
        waitTicks(30);
        mark("gliding or landed");
        c("gamemode creative");
        c("item replace entity @s armor.chest with minecraft:air");
        home();
        waitTicks(12);
        mark("home again");

        // Riptide: the spin the trident gives, with a push
        c("item replace entity @s weapon.mainhand with minecraft:trident");
        waitTicks(6);
        server("a riptide spin", (level, player) -> {
            player.startAutoSpinAttack(20, 8.0F, player.getMainHandItem());
            Vec3 look = player.getLookAngle();
            player.push(look.x * 1.5, 0.6, look.z * 1.5);
            player.hurtMarked = true;
        });
        waitTicks(8);
        still("riptide", mark("riptide spin"), 4.5, -56, -6.5, 0.5, -56.5, -2.5, false);
        waitTicks(20);
        mark("after the spin");
        waitTicks(6);
    }

    // --- 6. Outliers: blocks the client is told about by block events and block entity data ---

    private static void blocks() throws Exception {
        item("Piston pushing a row (the moving-piston block entity)").owns("blocks", "block entity minecraft:piston").need(blockChange("-> moving_piston")).need(blockEntity("minecraft:piston", "moved="));
        item("Note block").owns("blocks").need(heard("minecraft:block.note_block.harp"));
        item("Bell").owns("block entity minecraft:bell").need(blockEntity("minecraft:bell", "ringing=1"));
        item("Chest opening").owns("block entity minecraft:chest").need(blockEntity("minecraft:chest", "open=1.00"));
        item("Shulker box opening").owns("block entity minecraft:shulker_box").need(blockEntity("minecraft:shulker_box", "lid=OPENED"));
        item("End gateway's beam").owns("block entity minecraft:end_gateway").need(blockEntity("minecraft:end_gateway", "Age"));
        item("Beacon coming on").owns("block entity minecraft:beacon").need(blockEntity("minecraft:beacon", "beam=1"));
        item("Banner with patterns").owns("block entity minecraft:banner").need(blockEntity("minecraft:banner", "creeper"));
        item("Sign with text front and back").owns("block entity minecraft:sign").need(blockEntity("minecraft:sign", "Front one")).need(blockEntity("minecraft:sign", "Back"));
        item("Player head").owns("block entity minecraft:skull").need(blockEntity("minecraft:skull", "Dailies"));
        item("Jukebox playing").owns("block entity minecraft:jukebox", "blocks").need(blockChange("has_record=true")).need(heard("minecraft:music_disc.cat"));
        item("Campfire cooking").owns("block entity minecraft:campfire", "minecraft:item").need(blockEntity("minecraft:campfire", "minecraft:beef")).need(state("minecraft:item", "cooked_beef"));
        item("Decorated pot").owns("block entity minecraft:decorated_pot").need(blockEntity("minecraft:decorated_pot", "angler_pottery_sherd"));
        item("Sculk sensor and shrieker").owns("block entity minecraft:sculk_sensor", "block entity minecraft:sculk_shrieker", "blocks").need(blockChange("sculk_sensor_phase=active")).need(blockChange("shrieking=true"));
        item("Conduit").owns("block entity minecraft:conduit").need(blockEntity("minecraft:conduit", "active=true"));
        item("Spawner's spinning mob").owns("block entity minecraft:mob_spawner").need(blockEntity("minecraft:mob_spawner", "minecraft:zombie"));
        item("Brewing stand").owns("block entity minecraft:brewing_stand", "blocks").need(blockChange("has_bottle_0=true"));
        item("Lectern").owns("block entity minecraft:lectern", "blocks").need(blockChange("has_book=true")).note("a game is told only that a lectern has a book, not which");
        item("Redstone lamp and repeater changing state").owns("blocks").need(blockChange("redstone_lamp[lit=true]")).need(blockChange("powered=true"));
        item("Door and trapdoor").owns("blocks").need(blockChange("oak_door[facing=north,half=lower,hinge=left,open=true")).need(blockChange("oak_trapdoor[facing=north,half=bottom,open=true"));
        lit("the beacon, the lamps and the campfire");

        c("setblock -20 -60 12 minecraft:sticky_piston[facing=east]");
        c("setblock -19 -60 12 minecraft:stone");
        c("setblock -18 -60 12 minecraft:glass");
        c("setblock -17 -60 12 minecraft:oak_planks");
        c("setblock -12 -60 12 minecraft:note_block[note=5]");
        c("setblock -4 -60 12 minecraft:bell[attachment=floor]");
        c("setblock 4 -60 12 minecraft:chest[facing=north]");
        c("setblock 8 -60 12 minecraft:shulker_box");
        c("setblock 4 -54 20 minecraft:end_gateway{Age:0L}");
        c("fill 11 -61 11 13 -61 13 minecraft:iron_block");
        c("setblock 12 -60 12 minecraft:beacon");
        c("setblock 20 -60 12 minecraft:red_banner[rotation=8]{patterns:[{pattern:\"minecraft:creeper\",color:\"black\"},{pattern:\"minecraft:border\",color:\"yellow\"}]}");
        c("setblock -20 -60 20 minecraft:oak_sign[rotation=8]{front_text:{messages:['{\"text\":\"Front one\"}','{\"text\":\"two\"}','\"\"','\"\"'],color:\"red\",has_glowing_text:1b},"
            + "back_text:{messages:['{\"text\":\"Back\"}','\"\"','\"\"','\"\"']}}");
        c("setblock -12 -60 20 minecraft:player_head[rotation=8]{profile:{id:[I;1,2,3,4],name:\"Dailies\"}}");
        c("setblock -4 -60 20 minecraft:jukebox");
        c("setblock 4 -60 20 minecraft:campfire[lit=true]{Items:[{Slot:0b,id:\"minecraft:beef\",count:1},{Slot:1b,id:\"minecraft:porkchop\",count:1}],CookingTimes:[I;0,0,0,0],CookingTotalTimes:[I;90,600,600,600]}");
        c("setblock 12 -60 20 minecraft:decorated_pot{sherds:[\"minecraft:angler_pottery_sherd\",\"minecraft:brick\",\"minecraft:skull_pottery_sherd\",\"minecraft:brick\"]}");
        c("setblock 20 -60 20 minecraft:sculk_sensor");
        c("setblock 20 -60 23 minecraft:sculk_shrieker[can_summon=false]");
        c("fill -22 -60 26 -18 -56 30 minecraft:glass hollow");
        c("fill -21 -59 27 -19 -57 29 minecraft:water");
        c("fill -22 -58 26 -18 -58 30 minecraft:prismarine");
        c("fill -21 -58 27 -19 -58 29 minecraft:water");
        c("setblock -20 -58 28 minecraft:conduit[waterlogged=true]");
        c("setblock -12 -60 28 minecraft:spawner{SpawnData:{entity:{id:\"minecraft:zombie\"}},Delay:600s,RequiredPlayerRange:1s}");
        c("setblock -4 -60 28 minecraft:brewing_stand{Items:[{Slot:0b,id:\"minecraft:potion\",count:1,components:{\"minecraft:potion_contents\":{potion:\"minecraft:water\"}}},{Slot:3b,id:\"minecraft:nether_wart\",count:1},"
            + "{Slot:4b,id:\"minecraft:blaze_powder\",count:1}]}");
        c("setblock 4 -60 28 minecraft:lectern[facing=north,has_book=true]{Book:{id:\"minecraft:written_book\",count:1,components:{\"minecraft:written_book_content\":{title:\"Dailies\",author:\"Test\",pages:['\"page\"']}}},Page:0}");
        c("setblock 12 -60 28 minecraft:redstone_lamp");
        c("setblock 14 -60 28 minecraft:repeater[facing=north,delay=4]");
        c("setblock 14 -60 27 minecraft:redstone_lamp");
        c("setblock 20 -60 28 minecraft:oak_door[half=lower,facing=north]");
        c("setblock 20 -59 28 minecraft:oak_door[half=upper,facing=north]");
        c("setblock 23 -60 28 minecraft:oak_trapdoor[facing=north]");
        waitTicks(12);
        still("blocks-before", mark("blocks placed"), 0.5, -52, 0.5, 0.5, -60, 20.5, false);
        waitTicks(4);

        // Everything is set going at once, fifteen ticks before a stored world, and left for long enough that later jumps
        // land well after it with only a stored world to go by
        ticksBeforeStoredWorld(15);
        c("setblock -21 -60 12 minecraft:redstone_block");
        c("setblock -12 -60 11 minecraft:redstone_block");
        c("setblock -4 -60 11 minecraft:redstone_block");
        c("setblock 12 -60 29 minecraft:redstone_block");
        c("setblock 14 -60 29 minecraft:redstone_block");
        c("setblock 20 -60 29 minecraft:redstone_block");
        c("setblock 23 -60 29 minecraft:redstone_block");
        c("summon minecraft:item 20.5 -57 18.5 {Item:{id:\"minecraft:stone\",count:1}}");
        server("opening the chest and the shulker box, wobbling the pot, playing the record, the shrieker", (level, player) -> {
            level.blockEvent(new BlockPos(4, -60, 12), Blocks.CHEST, 1, 1);
            level.blockEvent(new BlockPos(8, -60, 12), Blocks.SHULKER_BOX, 1, 1);
            level.blockEvent(new BlockPos(12, -60, 20), Blocks.DECORATED_POT, 1, 0);
            ((JukeboxBlockEntity) level.getBlockEntity(new BlockPos(-4, -60, 20))).setTheItem(new ItemStack(Items.MUSIC_DISC_CAT));
            ((SculkShriekerBlockEntity) level.getBlockEntity(new BlockPos(20, -60, 23))).tryShriek(level, player);
        });
        waitTicks(1);
        mark("piston moving");
        waitTicks(1);
        mark("piston moving");
        waitTicks(6);
        still("bell-ringing", mark("bell ringing, lids opening"), -1.5, -58, 6.5, 1.5, -59.5, 12.5, false);
        waitTicks(30);
        mark("blocks going");
        waitTicks(55);
        still("blocks-open", mark("blocks, seventy ticks after a stored world"), 0.5, -52, 0.5, 0.5, -60, 20.5, false);
        waitTicks(6);
        still("chest-open", mark("chest and shulker box open"), 6.5, -58, 7.5, 6.5, -59.5, 12.5, false);
        waitTicks(40);
        mark("beacon and conduit on");
        server("closing the chest and the shulker box", (level, player) -> {
            level.blockEvent(new BlockPos(4, -60, 12), Blocks.CHEST, 1, 0);
            level.blockEvent(new BlockPos(8, -60, 12), Blocks.SHULKER_BOX, 1, 0);
        });
        c("setblock -21 -60 12 minecraft:air");
        c("setblock 20 -60 29 minecraft:air");
        c("setblock 23 -60 29 minecraft:air");
        c("setblock 12 -60 29 minecraft:air");
        waitTicks(4);
        mark("lids closing, piston pulling back");
        waitTicks(40);
        mark("blocks at rest");
        waitTicks(6);
    }

    // --- Light ---

    private static void lit(String where) {
        item("Light: " + where).owns("light").need(seen("light written down", census -> census.light != null));
    }

    private static void light() throws Exception {
        item("Light: a torch and glowstone placed and broken, lava poured and taken away").owns("light", "blocks").need(blockChange("-> torch")).need(blockChange("-> glowstone")).need(blockChange("-> lava"));
        item("Light: a lamp high in empty air, lit after a stored world (a jump back keeps the world, and must not keep its glow)").owns("light", "blocks")
            .need(blockChange("air -> glowstone")).need(blockChange("glowstone -> air"));

        // Two lamps in sections of the world that hold nothing but air, well above the ground
        Census.watch(OVERWORLD, tick() + 2, -5, -47, 15, 5, -33, 25);
        Census.watch(OVERWORLD, tick() + 2, 1, -15, 15, 11, -1, 25);
        waitTicks(10);
        mark("before the lights");
        c("setblock -10 -60 10 minecraft:torch");
        c("setblock 0 -60 14 minecraft:glowstone");
        c("setblock 10 -60 10 minecraft:lava");
        waitTicks(2);
        mark("lights just placed");
        waitTicks(28);
        still("lights", mark("torch, glowstone and lava"), 0.5, -54, 0.5, 0.5, -60, 12.5, false);
        waitTicks(6);
        c("setblock -10 -60 10 minecraft:air");
        c("setblock 0 -60 14 minecraft:air");
        c("fill 6 -60 6 14 -60 14 minecraft:air");
        waitTicks(2);
        mark("lights just broken");
        waitTicks(20);
        mark("lights gone");
        // Just after a stored world is written, so that it knows nothing of them
        untilPhase(8);
        mark("before the high lamps");
        waitTicks(6);
        c("setblock 0 -40 20 minecraft:glowstone");
        c("setblock 6 -8 20 minecraft:glowstone");
        waitTicks(40);
        mark("high lamps lit");
        waitTicks(75);
        still("high-lamps", mark("high lamps, well after another stored world"), 0.5, -36, 4.5, 3.5, -24, 20.5, false);
        waitTicks(6);
        c("setblock 0 -40 20 minecraft:air");
        c("setblock 6 -8 20 minecraft:air");
        waitTicks(20);
        mark("high lamps out");
        waitTicks(6);
    }

    // --- 6. Outliers: the sky ---

    private static void sky() throws Exception {
        item("Daylight cycle running").owns("time of day").need(seen("the sun moving", census -> census.dayTime > 11000 && census.dayTime < 11990));
        item("Weather change with thunder").owns("weather", "minecraft:lightning_bolt").need(seen("full rain", census -> census.rain > 0.9F)).need(seen("thunder", census -> census.thunder > 0.5F))
            .need(present("minecraft:lightning_bolt", 1)).need(heard("minecraft:entity.lightning_bolt.thunder")).need(seen("clear again", census -> census.rain < 0.3F && census.tick > 0));

        c("gamerule doDaylightCycle true");
        c("time set 11000");
        waitTicks(20);
        mark("clear sky, sun moving");
        c("weather thunder");
        waitTicks(60);
        mark("rain coming on");
        waitTicks(60);
        c("summon minecraft:lightning_bolt 0.5 -60 20.5");
        waitTicks(3);
        still("thunder", mark("thunder"), 0.5, -55, -10.5, 0.5, -52, 20.5, true);
        waitTicks(40);
        mark("storm, seventy ticks or more after a stored world");
        c("weather clear");
        waitTicks(60);
        mark("clearing");
        waitTicks(60);
        mark("clear again");
        c("gamerule doDaylightCycle false");
        waitTicks(6);
    }

}
