package comedymonkey.dailies.selftest;

import com.google.gson.JsonObject;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.capture.Recorder;
import comedymonkey.dailies.effects.RecorderHands;
import comedymonkey.dailies.looks.Drawn;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.playback.Viewer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;

import static comedymonkey.dailies.selftest.SelfTest.args;
import static comedymonkey.dailies.selftest.SelfTest.check;

/**
 * The recorder's hands in playback, in a recording of its own: through their eyes, in first person with the HUD shown,
 * their arms and what each hand holds are drawn as their game drew them (a sword and a shield, a swing, the shield
 * raised, a hand dropping as the hotbar slot changes, a bow drawn with the other hand put away); and no hands are drawn
 * in third person, with the HUD hidden, with the held items look off, or with the camera flying free. The shield is
 * also raised on their body in third person. Each stage is checked in the live game first, so that a stage that did
 * not happen is not taken for a fault of playback. In third person, what each hand holds is drawn on bodies: the
 * recorder's, a recorded mob's and another player's. Run by itself with -PdailiesOnly=hands.
 */
final class HandSteps {

    static final String WORLD = "Dailies Hands";
    private static final int Y = -60;

    /** Ticks of the recording at which each stage stands, by name */
    private static final Map<String, Integer> MARKS = new LinkedHashMap<>();
    private static final List<String> problems = new ArrayList<>();

    private HandSteps() {
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
        Path folder = Host.get().dataDirectory().resolve("hands");
        Files.createDirectories(folder);
        record(test, minecraft);
        check(problems.isEmpty(), "hands: stages did not happen in the live game: " + problems);
        Path recording = folder.resolve("Hands.dailies");
        try {
            watch(test, minecraft, recording);
        } finally {
            test.onClientRun(() -> {
                minecraft.options.setCameraType(CameraType.FIRST_PERSON);
                minecraft.options.keyUse.setDown(false);
                minecraft.options.hideGui = false;
            });
        }
    }

    // ------------------------------------------------------------------ Staging and recording

    private static int tick() {
        return Recorder.get().recordingTick();
    }

    /** Notes a stage's tick, then lets a few ticks pass, so that what the step does next is not recorded on that same tick */
    private static void mark(SelfTest test, String name) throws Exception {
        MARKS.put(name, test.onClient(HandSteps::tick));
        test.waitTicks(4);
    }

    private static void stageCheck(SelfTest test, boolean ok, String what) {
        if (!ok) {
            problems.add(what);
            test.log("hands: staging problem: " + what);
        }
    }

    /** Holds the use key down, as a player holding the right mouse button does, or lets it go */
    private static void use(SelfTest test, boolean down) throws Exception {
        test.onClientRun(() -> Minecraft.getInstance().options.keyUse.setDown(down));
    }

    private static void record(SelfTest test, Minecraft minecraft) throws Exception {
        WorldSteps.createWorld(test, WORLD);
        test.command("gamerule sendCommandFeedback false");
        test.command("gamerule doMobSpawning false");
        test.command("time set 1000");
        test.command("gamemode survival");
        test.command("effect give @s minecraft:resistance 600 4 true");
        test.command("clear @s");
        test.command("item replace entity @s hotbar.0 with minecraft:iron_sword");
        test.command("item replace entity @s hotbar.1 with minecraft:bow");
        test.command("item replace entity @s hotbar.8 with minecraft:arrow 16");
        test.command("item replace entity @s weapon.offhand with minecraft:shield");
        // A husk (which does not burn by day) holding an axe and a totem, in front of the recorder and off to one side
        test.command("summon minecraft:husk 3.5 " + Y + " 4.5 {CustomName:\"Holder\",NoAI:1b,Silent:1b,PersistenceRequired:1b,Rotation:[180f,0f]}");
        test.command("item replace entity @e[type=minecraft:husk,name=Holder,limit=1] weapon.mainhand with minecraft:iron_axe");
        test.command("item replace entity @e[type=minecraft:husk,name=Holder,limit=1] weapon.offhand with minecraft:totem_of_undying");
        // Looking along the flat ground at nothing in reach, so the use key uses what is held and not a block
        test.command("tp @s 0.5 " + Y + " 0.5 0 0");
        test.onClientRun(() -> minecraft.player.getInventory().selected = 0);
        test.onClientRun(() -> minecraft.options.setCameraType(CameraType.FIRST_PERSON));
        test.waitTicks(40);

        test.tool("recording_start", new JsonObject());
        test.waitTicks(30);

        // A sword in the main hand and a shield in the other, both hands up
        stageCheck(test, test.onClient(() -> minecraft.player.getMainHandItem().is(Items.IRON_SWORD) && minecraft.player.getOffhandItem().is(Items.SHIELD)),
            "the sword and the shield are not in the player's hands");
        stageCheck(test, test.onClient(() -> minecraft.level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, minecraft.player.getBoundingBox().inflate(10.0))
            .stream().anyMatch(husk -> husk.getMainHandItem().is(Items.IRON_AXE) && husk.getOffhandItem().is(Items.TOTEM_OF_UNDYING))),
            "the husk is not holding the axe and the totem");
        mark(test, "held");
        test.waitTicks(10);

        // A swing of the sword
        test.onClientRun(() -> { minecraft.player.swing(InteractionHand.MAIN_HAND); minecraft.player.resetAttackStrengthTicker(); });
        mark(test, "swing");
        stageCheck(test, test.onClient(() -> minecraft.player.swinging), "the swing did not happen");
        test.waitTicks(20);

        // The shield raised: the use key held with a sword in the main hand uses the shield in the other
        use(test, true);
        test.waitTicks(15);
        stageCheck(test, test.onClient(() -> minecraft.player.isUsingItem() && minecraft.player.getUsedItemHand() == InteractionHand.OFF_HAND
            && minecraft.player.isBlocking()), "the shield was not raised");
        mark(test, "blocking");
        use(test, false);
        test.waitTicks(20);
        stageCheck(test, test.onClient(() -> !minecraft.player.isUsingItem()), "the shield was not lowered");

        // A hotbar slot changed: the hand drops as the bow comes up in place of the sword
        test.onClientRun(() -> minecraft.player.getInventory().selected = 1);
        test.waitTicks(2);
        stageCheck(test, test.onClient(() -> RecorderHandsLive.mainHeight(minecraft) < 0.9F), "the main hand did not drop as the slot changed: "
            + test.onClient(() -> RecorderHandsLive.mainHeight(minecraft)));
        mark(test, "swapping");
        test.waitTicks(20);

        // The bow drawn: only the bow's hand shows
        use(test, true);
        test.waitTicks(15);
        stageCheck(test, test.onClient(() -> minecraft.player.isUsingItem() && minecraft.player.getUseItem().is(Items.BOW)), "the bow was not drawn");
        mark(test, "bow drawn");
        use(test, false);
        test.waitTicks(30);

        JsonObject finished = test.tool("recording_finish", args("name", "Hands"));
        Path saved = Path.of(finished.get("path").getAsString());
        Path recording = Host.get().dataDirectory().resolve("hands").resolve("Hands.dailies");
        Files.copy(saved, recording, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        WorldSteps.leaveWorld(test);
    }

    // ------------------------------------------------------------------ Playback

    private static void watch(SelfTest test, Minecraft minecraft, Path recording) throws Exception {
        test.tool("replay_open", args("path", recording.toString()));
        test.waitFor(() -> Playback.loaded() && !Playback.jumping(), 120, "the hands recording to open");
        test.waitTicks(40);

        // A sword and a shield, both hands up, through their eyes; nothing in third person, with the HUD hidden, with the
        // held items look off, or flying free
        RecorderHands.Shown held = drawn(test, minecraft, "held", CameraType.FIRST_PERSON, false);
        check(held != null, "the recorder's hands are not drawn through their eyes");
        check(held.mainHand().equals("minecraft:iron_sword") && held.offHand().equals("minecraft:shield"),
            "the recorder's hands do not hold the sword and the shield through their eyes: " + held);
        check(held.mainHeight() > 0.95F && held.offHeight() > 0.95F, "the recorder's hands are not up through their eyes: " + held);
        check(drawn(test, minecraft, "held", CameraType.THIRD_PERSON_BACK, false) == null, "the recorder's hands are drawn in third person");
        check(drawn(test, minecraft, "held", CameraType.FIRST_PERSON, true) == null, "the recorder's hands are drawn with the HUD hidden");
        test.onClientRun(comedymonkey.dailies.ui.Controls::openTimeline);
        test.waitFor(() -> minecraft.screen instanceof comedymonkey.dailies.ui.EditorScreen, 10, "the editor");
        test.tool("visuals_set", args("values", args("show_held_items", false)));
        check(drawn(test, minecraft, "held", CameraType.FIRST_PERSON, false) == null, "the recorder's hands are drawn with the held items look off");
        test.tool("visuals_set", args("values", args("show_held_items", true)));
        test.onClientRun(() -> minecraft.setScreen(null));
        at(test, minecraft, "held", CameraType.FIRST_PERSON, false);
        test.onClientRun(() -> Viewer.ride(null));
        long before = RecorderHands.drawnCount();
        frames(test, 6);
        check(RecorderHands.drawnCount() == before, "the recorder's hands are drawn with the camera flying free");

        // The swing
        drawn(test, minecraft, "swing", CameraType.FIRST_PERSON, false);
        check(test.onClient(() -> recorder(minecraft).swinging), "the recorder's swing is not played");

        // The shield raised, through their eyes and on their body in third person
        RecorderHands.Shown blocking = drawn(test, minecraft, "blocking", CameraType.FIRST_PERSON, false);
        check(blocking != null && blocking.usingItem() && blocking.usedHand() == InteractionHand.OFF_HAND && blocking.blocking(),
            "the recorder's shield is not raised through their eyes: " + blocking);
        at(test, minecraft, "blocking", CameraType.THIRD_PERSON_BACK, false);
        check(test.onClient(() -> recorder(minecraft).isBlocking()), "the recorder's shield is not raised on their body in third person");

        // The hand dropping as the slot changed
        RecorderHands.Shown swapping = drawn(test, minecraft, "swapping", CameraType.FIRST_PERSON, false);
        check(swapping != null && swapping.mainHeight() < 0.9F, "the recorder's main hand does not drop as the slot changes: " + swapping);

        // The bow drawn
        RecorderHands.Shown bow = drawn(test, minecraft, "bow drawn", CameraType.FIRST_PERSON, false);
        check(bow != null && bow.mainHand().equals("minecraft:bow") && bow.usingItem() && bow.useRemaining() > 0,
            "the recorder's bow is not drawn through their eyes: " + bow);

        thirdPerson(test, minecraft);

        // What drawing the hands costs a frame: through their eyes with the hands, against the same view without them
        at(test, minecraft, "held", CameraType.FIRST_PERSON, false);
        FrameTimes.Result withHands = FrameTimes.measure(test, 40);
        at(test, minecraft, "held", CameraType.FIRST_PERSON, true);
        FrameTimes.Result without = FrameTimes.measure(test, 40);
        test.log("hands: frame times through the recorder's eyes, with their hands " + withHands + "; without " + without);
        check(withHands.average() <= without.average() * 1.5 + 2.0, "drawing the recorder's hands slowed the game: " + withHands.average() + " ms against "
            + without.average() + " ms");

        test.onClientRun(() -> {
            Viewer.ride(null);
            minecraft.options.hideGui = false;
        });
        PlaybackSteps.close(test);
        test.log("hands: the recorder's arms, sword, shield, swing, raised shield, swap and drawn bow shown through their eyes, and not in third person, "
            + "with the HUD hidden, with the held items look off or flying free");
    }

    private static final UUID ADA = UUID.nameUUIDFromBytes("dailies-test-hands-ada".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private static final int ADA_ID = 90101;

    /**
     * Held items on bodies in third person: the recorder's sword and shield, the recorded husk's axe and totem, and the
     * pickaxe and torch of another player put into the playback world, each hand's item drawn; and the recorder's own
     * with the view riding with them in third person
     */
    private static void thirdPerson(SelfTest test, Minecraft minecraft) throws Exception {
        at(test, minecraft, "held", CameraType.THIRD_PERSON_BACK, false);
        java.util.Set<String> riding = Drawn.heldDrawn();
        check(riding.contains("RIGHT minecraft:iron_sword") && riding.contains("LEFT minecraft:shield"),
            "the recorder's sword and shield are not drawn on their body riding with them in third person: " + riding);
        test.onClientRun(() -> {
            minecraft.options.setCameraType(CameraType.FIRST_PERSON);
            Viewer.ride(null);
            net.minecraft.client.multiplayer.ClientPacketListener listener = minecraft.getConnection();
            comedymonkey.dailies.capture.GameCodec codec = new comedymonkey.dailies.capture.GameCodec(listener.registryAccess(), listener.getConnectionType());
            listener.handlePlayerInfoUpdate(comedymonkey.dailies.capture.PlayerInfoPackets.build(codec, comedymonkey.dailies.capture.PlayerInfoPackets.allButChat(),
                List.of(new net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket.Entry(ADA, new com.mojang.authlib.GameProfile(ADA, "Ada"), true, 0,
                    net.minecraft.world.level.GameType.SURVIVAL, null, null))));
            listener.handleAddEntity(new net.minecraft.network.protocol.game.ClientboundAddEntityPacket(ADA_ID, ADA, -2.5, Y, 3.5, 0.0F, 0.0F,
                net.minecraft.world.entity.EntityType.PLAYER, 0, net.minecraft.world.phys.Vec3.ZERO, 0.0));
            listener.handleSetEquipment(new net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket(ADA_ID, List.of(
                com.mojang.datafixers.util.Pair.of(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new net.minecraft.world.item.ItemStack(Items.GOLDEN_PICKAXE)),
                com.mojang.datafixers.util.Pair.of(net.minecraft.world.entity.EquipmentSlot.OFFHAND, new net.minecraft.world.item.ItemStack(Items.TORCH)))));
        });
        // A free camera in front of all three, looking back at them
        test.tool("camera_set", args("x", 0.5, "y", Y + 1.5, "z", 10.0, "yaw", 180.0, "pitch", 8.0));
        frames(test, 8);
        java.util.Set<String> seen = Drawn.heldDrawn();
        test.log("hands: held items drawn on bodies in third person: " + seen);
        for (String expected : new String[]{"RIGHT minecraft:iron_sword", "LEFT minecraft:shield", "RIGHT minecraft:iron_axe", "LEFT minecraft:totem_of_undying",
            "RIGHT minecraft:golden_pickaxe", "LEFT minecraft:torch"}) {
            check(seen.contains(expected), "a held item is not drawn on a body in third person (" + expected + "): the recorder holds a sword and a shield, "
                + "the husk an axe and a totem, the other player a pickaxe and a torch; drawn: " + seen);
        }
        othersTotem(test, minecraft);
        test.onClientRun(() -> minecraft.getConnection().handleRemoveEntities(new net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket(ADA_ID)));
    }

    /**
     * Another player's totem going off: its burst of particles around them whatever the view, and the totem rising over
     * the screen only riding with them through their eyes, never flying free or riding with someone else
     */
    private static void othersTotem(SelfTest test, Minecraft minecraft) throws Exception {
        totem(test, minecraft, "flying free", null, false);
        totem(test, minecraft, "riding with the recorder", recorder(minecraft), false);
        totem(test, minecraft, "riding with the other player", test.onClient(() -> minecraft.level.getEntity(ADA_ID)), true);
        test.onClientRun(() -> Viewer.ride(null));
        test.log("hands: another player's totem bursts around them in every view, and is over the screen only through their eyes");
    }

    private static void totem(SelfTest test, Minecraft minecraft, String view, net.minecraft.world.entity.Entity riding, boolean overScreen) throws Exception {
        test.onClientRun(() -> {
            Viewer.ride(riding);
            minecraft.options.setCameraType(CameraType.FIRST_PERSON);
        });
        frames(test, 4);
        comedymonkey.dailies.effects.RecorderEyes.totemShown(true);
        comedymonkey.dailies.effects.ParticleWatch.start();
        // Playing, as a totem goes off in a recording being watched: a paused recording holds its particles still
        test.tool("replay_play", new JsonObject());
        test.onClientRun(() -> {
            net.minecraft.world.entity.Entity ada = minecraft.level.getEntity(ADA_ID);
            minecraft.getConnection().handleEntityEvent(new net.minecraft.network.protocol.game.ClientboundEntityEventPacket(ada, (byte) 35));
        });
        boolean[] shown = {false};
        long until = System.currentTimeMillis() + 1500;
        test.waitFor(() -> {
            shown[0] |= comedymonkey.dailies.effects.RecorderEyes.totemShown(false);
            return System.currentTimeMillis() > until;
        }, 10, "the totem to go off");
        test.tool("replay_pause", new JsonObject());
        List<comedymonkey.dailies.effects.ParticleWatch.Seen> seen = comedymonkey.dailies.effects.ParticleWatch.stop();
        net.minecraft.world.phys.Vec3 at = new net.minecraft.world.phys.Vec3(-2.5, Y + 1.0, 3.5);
        long burst = seen.stream().filter(one -> one.type().equals("minecraft:totem_of_undying") && one.at().distanceTo(at) < 4.0).count();
        test.log("hands: the other player's totem " + view + ": " + burst + " totem particles around them, " + (shown[0] ? "" : "not ") + "over the screen");
        // The game's own burst is sixteen particles a tick for half a second or more
        check(burst >= 100, "the other player's totem does not burst around them as the game's does " + view + ": " + burst + " particles");
        check(shown[0] == overScreen, "the other player's totem is " + (overScreen ? "not " : "") + "over the screen " + view);
        if (overScreen) {
            // Let it finish before anything else is looked at
            test.waitTicks(45);
        }
    }

    private static Player recorder(Minecraft minecraft) {
        return minecraft.level.getPlayerByUUID(UUID.fromString(Playback.manifest().recorderId));
    }

    private static void frames(SelfTest test, int count) throws Exception {
        long from = Drawn.frames();
        test.waitFor(() -> Drawn.frames() >= from + count, 30, count + " frames to be drawn");
    }

    /** Plays up to a stage's tick riding with the recorder in this view */
    private static void at(SelfTest test, Minecraft minecraft, String stage, CameraType view, boolean hudHidden) throws Exception {
        int tick = MARKS.get(stage);
        SelfTestSupport.jump(test, Math.max(0, tick - 12), 60);
        test.onClientRun(() -> {
            Viewer.ride(recorder(minecraft));
            minecraft.options.setCameraType(view);
            minecraft.options.hideGui = hudHidden;
        });
        test.waitFor(() -> Viewer.riding() != null, 10, "the view to ride with the recorder");
        test.onClientRun(() -> Playback.playTo(tick));
        test.waitFor(() -> Playback.paused() && Playback.position() == tick, 60, "playback to reach " + stage);
        frames(test, 4);
    }

    /** Plays up to a stage and says what the hands were drawn holding there, or null when they were not drawn */
    private static RecorderHands.Shown drawn(SelfTest test, Minecraft minecraft, String stage, CameraType view, boolean hudHidden) throws Exception {
        at(test, minecraft, stage, view, hudHidden);
        long before = RecorderHands.drawnCount();
        frames(test, 4);
        RecorderHands.Shown shown = RecorderHands.drawnCount() > before ? RecorderHands.shown() : null;
        test.log("hands: at '" + stage + "' (" + view + (hudHidden ? ", HUD hidden" : "") + ") " + (shown == null ? "no hands drawn" : "drawn: " + shown));
        return shown;
    }

    /** The live game's own hand heights, while recording */
    private static final class RecorderHandsLive {
        static float mainHeight(Minecraft minecraft) {
            return ((comedymonkey.dailies.mixin.access.ItemInHandRendererAccess) minecraft.gameRenderer.itemInHandRenderer).dailies$mainHandHeight();
        }
    }
}
