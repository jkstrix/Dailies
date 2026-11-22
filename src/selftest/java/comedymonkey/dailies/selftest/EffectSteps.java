package comedymonkey.dailies.selftest;

import com.google.gson.JsonObject;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.capture.Recorder;
import comedymonkey.dailies.effects.ParticleWatch;
import comedymonkey.dailies.effects.RecorderEyes;
import comedymonkey.dailies.looks.Drawn;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.playback.Viewer;
import comedymonkey.dailies.ui.HudHidden;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.sounds.Musics;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import static comedymonkey.dailies.selftest.SelfTest.args;
import static comedymonkey.dailies.selftest.SelfTest.check;

/**
 * Effects and music in playback (PLAN-EFFECTS.md), in a recording of their own: what the recorder saw of their own state
 * is shown through their eyes in first person with the HUD shown, and never in third person or with the HUD hidden
 * (darkness, nausea and its fading, night vision, frost, fire, water and a block in the face, the carved pumpkin, the
 * spyglass, the portal's wobble and its fading, the sleep fade, a totem going off); potion swirls show around whoever
 * has an effect, in first and third person, and stop when it wears off; invisible entities are as invisible as they were
 * to the recorder, their armour and its trims still drawn, and seen again when it wears off; and no background music
 * plays over a recording. Each stage is checked in the live game first, so that a stage that did not happen is not taken
 * for a fault of playback. Run by itself with -PdailiesOnly=effects.
 */
final class EffectSteps {

    static final String WORLD = "Dailies Effects";
    private static final int Y = -60;
    private static final String TRIM = "[trim={material:\"minecraft:gold\",pattern:\"minecraft:coast\"}]";
    private static final String ENTITY_EFFECT = "minecraft:entity_effect";

    // Where things are built, along the x axis away from the husks
    private static final int SNOW_X = 10, WATER_X = 16, STONE_X = 22, PORTAL_X = 28, BED_X = 40, FIRE_X = 46;

    /** Ticks of the recording at which each stage stands, by name */
    private static final Map<String, Integer> MARKS = new LinkedHashMap<>();
    private static final List<String> problems = new ArrayList<>();
    private static int liveSwirlsNearSwirly = 0;
    private static int liveSwirlsNearRecorder = 0;

    private EffectSteps() {
    }

    static void run(SelfTest test) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        if (Playback.active()) {
            PlaybackSteps.close(test);
        }
        if (minecraft.level != null) {
            WorldSteps.leaveWorld(test);
        }
        MARKS.clear();
        problems.clear();
        Path folder = Host.get().dataDirectory().resolve("effects");
        Files.createDirectories(folder);
        Path recording = folder.resolve("Effects.dailies");
        record(test, minecraft, recording);
        check(problems.isEmpty(), "effects: stages did not happen in the live game: " + problems);
        try {
            watch(test, minecraft, recording);
        } finally {
            test.onClientRun(() -> {
                minecraft.options.setCameraType(CameraType.FIRST_PERSON);
                HudHidden.set(false);
            });
        }
    }

    // ------------------------------------------------------------------ Staging and recording

    private static void c(SelfTest test, String command) throws Exception {
        test.command(command);
    }

    private static int tick() {
        return Recorder.get().recordingTick();
    }

    /** Notes a stage's tick, then lets a few ticks pass, so that what the step does next is not recorded on that same tick */
    private static void mark(SelfTest test, String name) throws Exception {
        MARKS.put(name, tick());
        test.waitTicks(6);
    }

    private static void stageCheck(SelfTest test, boolean ok, String what) {
        if (!ok) {
            problems.add(what);
            test.log("effects: staging problem: " + what);
        }
    }

    private static void record(SelfTest test, Minecraft minecraft, Path recording) throws Exception {
        WorldSteps.createWorld(test, WORLD);
        c(test, "gamerule send_command_feedback false");
        c(test, "gamerule spawn_mobs false");
        c(test, "gamerule advance_time false");
        c(test, "gamerule advance_weather false");
        c(test, "gamerule immediate_respawn true");
        c(test, "time set 1000");
        c(test, "gamemode survival");
        c(test, "effect give @s minecraft:resistance 600 4 true");
        c(test, "effect give @s minecraft:saturation 600 0 true");
        // The stages' places
        c(test, "fill " + SNOW_X + " -63 0 " + SNOW_X + " -61 0 minecraft:powder_snow");
        c(test, "fill " + (WATER_X - 1) + " -63 -1 " + (WATER_X + 1) + " -61 1 minecraft:water");
        // Solid all round, so that the eyes are in it whatever the pose (a block over the head alone makes the player crawl
        // under it) and the game finds no space beside it to push the player out to
        c(test, "fill " + (STONE_X - 1) + " " + Y + " -1 " + (STONE_X + 1) + " " + (Y + 1) + " 1 minecraft:stone");
        c(test, "fill " + PORTAL_X + " " + (Y - 1) + " 0 " + (PORTAL_X + 3) + " " + (Y + 3) + " 0 minecraft:obsidian");
        c(test, "fill " + (PORTAL_X + 1) + " " + Y + " 0 " + (PORTAL_X + 2) + " " + (Y + 2) + " 0 minecraft:nether_portal[axis=x]");
        c(test, "setblock " + BED_X + " " + Y + " 0 minecraft:red_bed[facing=south,part=foot]");
        c(test, "setblock " + BED_X + " " + Y + " 1 minecraft:red_bed[facing=south,part=head]");
        // Two husks in gold-trimmed iron armour: one with swirls that wear off, one invisible until it wears off
        for (String name : new String[]{"Swirly", "Ghost"}) {
            int z = name.equals("Swirly") ? 6 : -6;
            c(test, "summon minecraft:husk 4.5 " + Y + " " + z + ".5 {CustomName:\"" + name + "\",NoAI:1b,Silent:1b,PersistenceRequired:1b}");
            for (String[] piece : new String[][]{{"armor.head", "iron_helmet"}, {"armor.chest", "iron_chestplate"}, {"armor.legs", "iron_leggings"},
                {"armor.feet", "iron_boots"}}) {
                c(test, "item replace entity @e[type=minecraft:husk,name=" + name + ",limit=1] " + piece[0] + " with minecraft:" + piece[1] + TRIM);
            }
        }
        c(test, "tp @s 0.5 " + Y + " 0.5 0 0");
        test.waitTicks(40);

        test.tool("recording_start", new JsonObject());
        test.waitTicks(20);

        // Swirls: the husk's speed and the recorder's regeneration, both wearing off later
        c(test, "effect give @e[type=minecraft:husk,name=Swirly] minecraft:speed 6 1");
        c(test, "effect give @s minecraft:regeneration 6 0");
        c(test, "effect give @e[type=minecraft:husk,name=Ghost] minecraft:invisibility 6 0");
        test.onClientRun(ParticleWatch::start);
        test.waitTicks(30);
        mark(test, "swirls");
        mark(test, "ghost invisible");
        List<ParticleWatch.Seen> live = test.onClient(ParticleWatch::stop);
        Entity swirly = test.onClient(() -> named(minecraft, "Swirly"));
        Entity ghost = test.onClient(() -> named(minecraft, "Ghost"));
        liveSwirlsNearSwirly = swirly == null ? 0 : near(live, swirly.position(), 2.5);
        liveSwirlsNearRecorder = near(live, test.onClient(() -> minecraft.player.position()), 2.5);
        stageCheck(test, liveSwirlsNearSwirly > 0, "no potion swirls around the husk with speed in the live game");
        stageCheck(test, ghost != null && test.onClient(() -> ghost.isInvisibleTo(minecraft.player)), "the husk with invisibility was not invisible in the live game");

        // Darkness, then nausea and its fading, then night vision
        c(test, "effect give @s minecraft:darkness 5 0");
        test.waitTicks(50);
        mark(test, "darkness");
        c(test, "effect clear @s minecraft:darkness");
        // Long enough that it is still blending in when looked at: it blends out from 60 ticks before its end
        c(test, "effect give @s minecraft:nausea 15 0");
        test.waitTicks(90);
        mark(test, "nausea");
        stageCheck(test, test.onClient(() -> minecraft.player.getEffectBlendFactor(net.minecraft.world.effect.MobEffects.NAUSEA, 1.0F)) > 0.1F,
            "nausea did not take hold in the live game");
        c(test, "effect clear @s minecraft:nausea");
        // By now the swirls and the invisibility have worn off too
        test.waitTicks(60);
        mark(test, "nausea gone");
        mark(test, "swirls stopped");
        mark(test, "ghost seen again");
        stageCheck(test, ghost != null && !test.onClient(() -> ghost.isInvisibleTo(minecraft.player)), "the husk's invisibility did not wear off in the live game");
        c(test, "effect give @s minecraft:night_vision 5 0");
        test.waitTicks(20);
        mark(test, "night vision");
        c(test, "effect clear @s minecraft:night_vision");

        // Frost in the powder snow
        c(test, "tp @s " + (SNOW_X + 0.5) + " " + (Y + 1) + " 0.5 0 0");
        test.waitTicks(80);
        mark(test, "frost");
        stageCheck(test, test.onClient(() -> minecraft.player.getTicksFrozen()) > 0, "the recorder did not freeze in the powder snow");
        c(test, "tp @s " + (FIRE_X + 0.5) + " " + Y + " 0.5 0 0");
        test.waitTicks(40);

        // On fire, then into the water, which puts it out
        c(test, "setblock " + FIRE_X + " " + Y + " 0 minecraft:fire");
        test.waitTicks(15);
        mark(test, "on fire");
        stageCheck(test, test.onClient(() -> minecraft.player.isOnFire()), "the recorder did not catch fire");
        test.log("effects: live on fire: flags " + test.onClient(() -> flags(minecraft.player)) + ", fire ticks " + test.onClient(() -> minecraft.player.getRemainingFireTicks())
            + ", server " + test.onClient(() -> minecraft.getSingleplayerServer().getPlayerList().getPlayer(minecraft.player.getUUID()).isOnFire()));
        c(test, "tp @s " + (WATER_X + 0.5) + " " + (Y - 3) + " 0.5 0 0");
        test.waitTicks(25);
        mark(test, "under water");
        stageCheck(test, test.onClient(() -> minecraft.player.isEyeInFluid(net.minecraft.tags.FluidTags.WATER)), "the recorder's eyes were not under water");

        // A block in the face
        c(test, "tp @s " + (STONE_X + 0.5) + " " + Y + " 0.5 0 0");
        test.waitTicks(10);
        stageCheck(test, test.onClient(() -> !minecraft.level.getBlockState(BlockPos.containing(minecraft.player.getEyePosition())).isAir()),
            "the recorder's eyes were not inside the stone");
        mark(test, "block in face");
        c(test, "tp @s " + (STONE_X + 0.5) + " " + Y + " 3.5 0 0");
        test.waitTicks(10);

        // The carved pumpkin, then the spyglass
        c(test, "item replace entity @s armor.head with minecraft:carved_pumpkin");
        test.waitTicks(15);
        mark(test, "pumpkin");
        c(test, "item replace entity @s armor.head with minecraft:air");
        c(test, "item replace entity @s weapon.mainhand with minecraft:spyglass");
        test.waitTicks(5);
        test.onClientRun(() -> {
            minecraft.options.keyUse.setDown(true);
            minecraft.gameMode.useItem(minecraft.player, InteractionHand.MAIN_HAND);
        });
        test.waitTicks(20);
        mark(test, "spyglass");
        stageCheck(test, test.onClient(() -> minecraft.player.isScoping()), "the recorder was not looking through the spyglass");
        test.onClientRun(() -> {
            minecraft.options.keyUse.setDown(false);
            minecraft.player.releaseUsingItem();
        });
        c(test, "item replace entity @s weapon.mainhand with minecraft:air");
        test.waitTicks(10);

        // Into the nether portal, out again before it takes them, and the wobble fading
        c(test, "tp @s " + (PORTAL_X + 2.0) + " " + Y + " 0.5 0 0");
        test.waitTicks(45);
        mark(test, "portal");
        stageCheck(test, test.onClient(() -> minecraft.player.portalEffectIntensity) > 0.1F, "the portal's wobble did not grow in the live game");
        c(test, "tp @s " + (PORTAL_X + 2.0) + " " + Y + " 4.5 0 0");
        test.waitTicks(20);
        mark(test, "portal fading");

        // Asleep at night in the bed
        c(test, "time set 14000");
        c(test, "tp @s " + (BED_X + 0.5) + " " + Y + " -1.5 0 0");
        test.waitTicks(5);
        test.onClientRun(() -> minecraft.gameMode.useItemOn(minecraft.player, InteractionHand.MAIN_HAND,
            new BlockHitResult(Vec3.atCenterOf(new BlockPos(BED_X, Y, 0)), Direction.UP, new BlockPos(BED_X, Y, 0), false)));
        test.waitTicks(40);
        mark(test, "asleep");
        stageCheck(test, test.onClient(() -> minecraft.player.isSleeping()), "the recorder did not fall asleep in the bed");
        test.onClientRun(() -> minecraft.player.connection.send(new ServerboundPlayerCommandPacket(minecraft.player, ServerboundPlayerCommandPacket.Action.STOP_SLEEPING)));
        c(test, "time set 1000");
        test.waitTicks(20);

        // A totem of undying going off
        c(test, "tp @s 0.5 " + Y + " 0.5 0 0");
        c(test, "effect clear @s minecraft:resistance");
        c(test, "item replace entity @s weapon.offhand with minecraft:totem_of_undying");
        test.waitTicks(10);
        mark(test, "totem");
        c(test, "damage @s 1000 minecraft:generic");
        test.waitTicks(10);
        stageCheck(test, test.onClient(() -> minecraft.player.getOffhandItem().isEmpty() && minecraft.player.isAlive()), "the totem did not go off");
        c(test, "effect give @s minecraft:resistance 600 4 true");
        test.waitTicks(10);

        // The recorder invisible in a trimmed chestplate, until it wears off
        c(test, "item replace entity @s armor.chest with minecraft:iron_chestplate" + TRIM);
        c(test, "effect give @s minecraft:invisibility 4 0 true");
        test.waitTicks(20);
        mark(test, "recorder invisible");
        test.waitTicks(80);
        mark(test, "recorder seen again");
        test.waitTicks(10);

        int length = tick();
        JsonObject finished = test.tool("recording_finish", args("name", "Effects"));
        Files.move(Path.of(finished.get("path").getAsString()), recording, StandardCopyOption.REPLACE_EXISTING);
        test.log("effects: recorded " + length + " ticks; stages at " + MARKS + "; live swirls around the husk " + liveSwirlsNearSwirly
            + ", around the recorder " + liveSwirlsNearRecorder);
    }

    /** The entity's shared flags byte (on fire is its first bit), read as the game keeps it, for the log */
    static String flags(Entity entity) {
        try {
            java.lang.reflect.Field field = Entity.class.getDeclaredField("DATA_SHARED_FLAGS_ID");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            net.minecraft.network.syncher.EntityDataAccessor<Byte> id = (net.minecraft.network.syncher.EntityDataAccessor<Byte>) field.get(null);
            return Integer.toBinaryString(entity.getEntityData().get(id) & 0xFF);
        } catch (ReflectiveOperationException e) {
            return "?";
        }
    }

    private static Entity named(Minecraft minecraft, String name) {
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity.getCustomName() != null && entity.getCustomName().getString().equals(name)) {
                return entity;
            }
        }
        return null;
    }

    private static int near(List<ParticleWatch.Seen> seen, Vec3 at, double within) {
        int count = 0;
        for (ParticleWatch.Seen one : seen) {
            if (one.type().equals(ENTITY_EFFECT) && one.at().distanceTo(at) <= within) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------ Playback

    private static void watch(SelfTest test, Minecraft minecraft, Path recording) throws Exception {
        test.tool("replay_open", args("path", recording.toString()));
        test.waitFor(() -> Playback.loaded() && !Playback.jumping(), 120, "the effects recording to open");

        // No background music over a recording
        test.onClientRun(() -> minecraft.getMusicManager().startPlaying(Musics.MENU));
        test.waitTicks(40);
        check(test.onClient(() -> minecraft.getMusicManager().getCurrentMusicTranslationKey() == null && !minecraft.getMusicManager().isPlayingMusic(Musics.MENU)),
            "background music plays over the recording");

        // Through the recorder's eyes, and never in third person or with the HUD hidden
        through(test, minecraft, "darkness", shown -> shown.darkness() > 0.01F, "the darkness");
        through(test, minecraft, "nausea", shown -> shown.nausea() > 0.1F, "the nausea warp");
        RecorderEyes.Shown gone = at(test, minecraft, "nausea gone", CameraType.FIRST_PERSON, false);
        check(gone.nausea() < 0.05F, "the nausea did not fade in playback: " + gone.nausea());
        through(test, minecraft, "night vision", shown -> shown.nightVision() > 0.3F, "night vision");
        through(test, minecraft, "frost", shown -> shown.frost() > 0.0F, "the frost");
        through(test, minecraft, "on fire", RecorderEyes.Shown::onFire, "the fire");
        through(test, minecraft, "under water", RecorderEyes.Shown::eyesInWater, "the water");
        through(test, minecraft, "block in face", RecorderEyes.Shown::blockInFace, "the block in the face");
        through(test, minecraft, "pumpkin", shown -> shown.wornOverlays() > 0, "the carved pumpkin");
        // The scene's Pumpkin overlay look, a checkbox of its own, takes it away
        test.onClientRun(comedymonkey.dailies.ui.Controls::openTimeline);
        test.waitFor(() -> minecraft.gui.screen() instanceof comedymonkey.dailies.ui.EditorScreen, 10, "the editor");
        test.tool("visuals_set", args("values", args("show_pumpkin_overlay", false)));
        RecorderEyes.Shown noPumpkin = at(test, minecraft, "pumpkin", CameraType.FIRST_PERSON, false);
        check(noPumpkin.wornOverlays() == 0, "the carved pumpkin is drawn with the Pumpkin overlay look off: " + noPumpkin);
        test.tool("visuals_set", args("values", args("show_pumpkin_overlay", true)));
        test.onClientRun(() -> minecraft.gui.setScreen(null));
        through(test, minecraft, "spyglass", RecorderEyes.Shown::spyglass, "the spyglass");
        RecorderEyes.Shown portal = through(test, minecraft, "portal", shown -> shown.portal() > 0.1F, "the portal's wobble");
        RecorderEyes.Shown fading = at(test, minecraft, "portal fading", CameraType.FIRST_PERSON, false);
        check(fading.portal() < portal.portal(), "the portal's wobble did not fade in playback: " + portal.portal() + " then " + fading.portal());
        through(test, minecraft, "asleep", shown -> shown.sleep() > 0, "the sleep fade");
        totem(test, minecraft);

        // Potion swirls around whoever has an effect, first and third person, and none once it wears off
        swirls(test, minecraft, CameraType.FIRST_PERSON);
        swirls(test, minecraft, CameraType.THIRD_PERSON_BACK);

        // Invisible as to the recorder, armour and trims still drawn, seen again when it wears off
        invisible(test, minecraft);

        // What drawing the effects costs a frame: with the warp and darkness on, against the same view without
        at(test, minecraft, "night vision", CameraType.FIRST_PERSON, false);
        FrameTimes.Result plain = FrameTimes.measure(test, 40);
        at(test, minecraft, "nausea", CameraType.FIRST_PERSON, false);
        FrameTimes.Result effects = FrameTimes.measure(test, 40);
        test.log("effects: frame times through the recorder's eyes, plain " + plain + "; with the nausea warp " + effects);
        check(effects.average() <= plain.average() * 1.5 + 2.0, "showing the effects slowed the game: " + effects.average() + " ms against " + plain.average() + " ms");

        test.onClientRun(() -> Viewer.ride(null));
        PlaybackSteps.close(test);
    }

    private static Player recorder(Minecraft minecraft) {
        return minecraft.level.getPlayerByUUID(UUID.fromString(Playback.manifest().recorderId));
    }

    /** Plays up to a stage's tick through the recorder's eyes (or in third person, or with the HUD hidden) and says what showed */
    private static RecorderEyes.Shown at(SelfTest test, Minecraft minecraft, String stage, CameraType view, boolean hudHidden) throws Exception {
        int tick = MARKS.get(stage);
        SelfTestSupport.jump(test, Math.max(0, tick - 12), 60);
        test.onClientRun(() -> {
            Viewer.ride(recorder(minecraft));
            minecraft.options.setCameraType(view);
            HudHidden.set(hudHidden);
        });
        test.waitFor(() -> Viewer.riding() != null, 10, "the view to ride with the recorder");
        test.onClientRun(() -> Playback.playTo(tick));
        test.waitFor(() -> Playback.paused() && Playback.position() == tick, 60, "playback to reach " + stage);
        long frames = Drawn.frames();
        test.waitFor(() -> Drawn.frames() >= frames + 4, 30, "frames to be drawn");
        test.log("effects: at '" + stage + "' (" + view + (hudHidden ? ", HUD hidden" : "") + ") the recorder has " + test.onClient(() -> {
            Player recorder = recorder(minecraft);
            StringBuilder effects = new StringBuilder();
            recorder.getActiveEffects().forEach(effect -> effects.append(effect.getEffect().getRegisteredName()).append(' ').append(effect.getDuration())
                .append(" blend ").append(effect.getBlendFactor(recorder, 1.0F)).append("; "));
            return (effects.length() == 0 ? "no effects" : effects.toString()) + " on fire " + recorder.isOnFire() + " flags " + flags(recorder) + " (fire ticks " + recorder.getRemainingFireTicks()
                + "), eyes in water " + recorder.isEyeInFluid(net.minecraft.tags.FluidTags.WATER) + ", frozen " + recorder.getTicksFrozen() + ", at " + recorder.position() + ", eyes at " + recorder.getEyePosition() + " in "
                + minecraft.level.getBlockState(BlockPos.containing(recorder.getEyePosition()));
        }));
        return RecorderEyes.shown();
    }

    private static RecorderEyes.Shown through(SelfTest test, Minecraft minecraft, String stage, Predicate<RecorderEyes.Shown> shows, String what) throws Exception {
        RecorderEyes.Shown first = at(test, minecraft, stage, CameraType.FIRST_PERSON, false);
        check(shows.test(first), what + " is not shown through the recorder's eyes at '" + stage + "': " + first);
        RecorderEyes.Shown third = at(test, minecraft, stage, CameraType.THIRD_PERSON_BACK, false);
        check(!shows.test(third) && !third.through(), what + " is shown in third person at '" + stage + "': " + third);
        RecorderEyes.Shown hidden = at(test, minecraft, stage, CameraType.FIRST_PERSON, true);
        check(!shows.test(hidden), what + " is shown with the HUD hidden at '" + stage + "': " + hidden);
        test.log("effects: " + what + " shown through the recorder's eyes, not in third person or with the HUD hidden: " + first);
        return first;
    }

    private static void totem(SelfTest test, Minecraft minecraft) throws Exception {
        int tick = MARKS.get("totem");
        SelfTestSupport.jump(test, tick, 60);
        test.onClientRun(() -> {
            Viewer.ride(recorder(minecraft));
            minecraft.options.setCameraType(CameraType.FIRST_PERSON);
            HudHidden.set(false);
        });
        test.waitFor(() -> Viewer.riding() != null, 10, "the view to ride with the recorder");
        test.onClientRun(() -> Playback.playTo(tick + 15));
        boolean[] seen = {false};
        test.waitFor(() -> {
            seen[0] |= minecraft.player.itemActivation().isActive();
            return Playback.paused() && Playback.position() == tick + 15;
        }, 60, "playback to play through the totem");
        check(seen[0], "the totem going off was not shown through the recorder's eyes");
        test.log("effects: the totem going off shown through the recorder's eyes");
    }

    private static void swirls(SelfTest test, Minecraft minecraft, CameraType view) throws Exception {
        int tick = MARKS.get("swirls");
        SelfTestSupport.jump(test, Math.max(0, tick - 25), 60);
        test.onClientRun(() -> {
            Viewer.ride(recorder(minecraft));
            minecraft.options.setCameraType(view);
            ParticleWatch.start();
        });
        test.onClientRun(() -> Playback.playTo(tick));
        test.waitFor(() -> Playback.paused() && Playback.position() == tick, 60, "playback through the swirls");
        List<ParticleWatch.Seen> seen = test.onClient(ParticleWatch::stop);
        Entity swirly = test.onClient(() -> named(minecraft, "Swirly"));
        check(swirly != null, "the husk with speed is not in playback");
        int aroundHusk = near(seen, swirly.position(), 2.5);
        int aroundRecorder = near(seen, test.onClient(() -> recorder(minecraft).position()), 2.5);
        check(aroundHusk > 0, "no potion swirls around the husk with speed in playback (" + view + ")");
        check(aroundRecorder > 0 || liveSwirlsNearRecorder == 0, "no potion swirls around the recorder in playback (" + view + ")");

        int after = MARKS.get("swirls stopped");
        SelfTestSupport.jump(test, after - 25, 60);
        test.onClientRun(ParticleWatch::start);
        test.onClientRun(() -> Playback.playTo(after));
        test.waitFor(() -> Playback.paused() && Playback.position() == after, 60, "playback past the swirls");
        List<ParticleWatch.Seen> later = test.onClient(ParticleWatch::stop);
        Entity husk = test.onClient(() -> named(minecraft, "Swirly"));
        check(husk == null || near(later, husk.position(), 2.5) == 0, "potion swirls go on around the husk after its speed wore off (" + view + ")");
        test.log("effects: swirls in " + view + ": around the husk " + aroundHusk + ", around the recorder " + aroundRecorder + "; none after they wore off");
    }

    private static void invisible(SelfTest test, Minecraft minecraft) throws Exception {
        // Seen from a free camera standing back from the husks
        int tick = MARKS.get("ghost invisible");
        SelfTestSupport.jump(test, tick, 60);
        test.onClientRun(() -> Viewer.ride(null));
        test.tool("camera_set", args("x", 4.5, "y", Y + 2.0, "z", 14.5, "yaw", 180.0, "pitch", 15.0));
        long frames = Drawn.frames();
        test.waitFor(() -> Drawn.frames() >= frames + 4, 30, "frames to be drawn");
        Entity ghost = test.onClient(() -> named(minecraft, "Ghost"));
        check(ghost != null, "the invisible husk is not in playback");
        check(test.onClient(() -> ghost.isInvisibleTo(minecraft.player)), "the invisible husk is seen in playback, where the recorder could not see it");
        test.waitFor(() -> Drawn.armourOnInvisibleDrawn() >= 4, 10, "the invisible husk's armour to be drawn (" + Drawn.armourOnInvisibleDrawn() + " pieces)");
        check(Drawn.armourTrimmedDrawn() >= 8, "the husks' trimmed armour is not all drawn with its trims: " + Drawn.armourTrimmedDrawn() + " trimmed pieces of " + Drawn.armourDrawn());

        int back = MARKS.get("ghost seen again");
        SelfTestSupport.jump(test, back, 60);
        test.waitFor(() -> named(minecraft, "Ghost") != null, 10, "the husk after the jump");
        Entity seenAgain = test.onClient(() -> named(minecraft, "Ghost"));
        check(!test.onClient(() -> seenAgain.isInvisibleTo(minecraft.player)), "the husk is still invisible in playback after its invisibility wore off");

        // The recorder invisible in a trimmed chestplate, seen from the camera behind them
        int hidden = MARKS.get("recorder invisible");
        SelfTestSupport.jump(test, hidden, 60);
        test.onClientRun(() -> {
            Viewer.ride(recorder(minecraft));
            minecraft.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        });
        long more = Drawn.frames();
        test.waitFor(() -> Drawn.frames() >= more + 4, 30, "frames to be drawn");
        check(test.onClient(() -> recorder(minecraft).isInvisibleTo(minecraft.player)), "the invisible recorder is seen in playback");
        test.waitFor(() -> Drawn.armourOnInvisibleDrawn() >= 1, 10, "the invisible recorder's chestplate to be drawn");
        int again = MARKS.get("recorder seen again");
        SelfTestSupport.jump(test, again, 60);
        test.waitFor(() -> Viewer.riding() != null, 10, "the view to ride with the recorder again");
        check(!test.onClient(() -> recorder(minecraft).isInvisibleTo(minecraft.player)), "the recorder is still invisible in playback after it wore off");
        test.onClientRun(() -> minecraft.options.setCameraType(CameraType.FIRST_PERSON));
        test.log("effects: invisible husk and recorder as the recorder saw them, armour and trims drawn on them, seen again when it wore off");
    }
}
