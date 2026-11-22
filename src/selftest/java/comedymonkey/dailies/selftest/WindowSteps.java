package comedymonkey.dailies.selftest;

import com.google.gson.JsonObject;
import comedymonkey.dailies.capture.Recorder;
import comedymonkey.dailies.looks.Drawn;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.playback.Viewer;
import comedymonkey.dailies.windows.WindowLayer;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.npc.Villager;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static comedymonkey.dailies.selftest.SelfTest.args;
import static comedymonkey.dailies.selftest.SelfTest.check;

/**
 * The recorder's windows shown in playback (specification REC-25, PLAY-20), kept small: in a world and a recording of
 * their own, a chest, a furnace with iron smelting, a villager's trades and the player's inventory are each opened for
 * a moment while recording. Then in playback, riding along with the recorder in first person with the HUD on, each
 * must be drawn by the window layer; and switching the camera to third person, or flying free, must stop it being
 * drawn. That the windows land the same after a jump or a trim is the unit tests' (WindowStateTest).
 * <p>
 * Each window is one {@link Display}: how to open it, and what the layer reports when it draws it. A compat that draws
 * a window or overlay of its own adds a step of the same shape, a display check of its own ({@link CompatDisplaySteps},
 * from the compat's own display test). Run by itself with -PdailiesOnly=windows.
 */
final class WindowSteps {

    static final String WORLD = "Dailies Windows";
    private static final int Y = -60;

    private WindowSteps() {
    }

    /** One window: what opens it in the live game, and the name the window layer reports for it when it is drawn */
    record Display(String label, String drawnAs, Opener opener) {
    }

    interface Opener {
        void open(SelfTest test, Minecraft minecraft) throws Exception;
    }

    private static final List<Display> WINDOWS = List.of(
        new Display("a chest", "minecraft:generic_9x3", (test, minecraft) -> onServer(test, player ->
            player.openMenu((MenuProvider) player.serverLevel().getBlockEntity(new BlockPos(2, Y, 0))))),
        new Display("a furnace smelting iron", "minecraft:furnace", (test, minecraft) -> onServer(test, player ->
            player.openMenu((MenuProvider) player.serverLevel().getBlockEntity(new BlockPos(-2, Y, 0))))),
        new Display("a villager's trades", "minecraft:merchant", (test, minecraft) -> onServer(test, player -> {
            Villager villager = player.serverLevel().getEntitiesOfClass(Villager.class, player.getBoundingBox().inflate(6.0)).get(0);
            villager.mobInteract(player, InteractionHand.MAIN_HAND);
        })),
        new Display("the inventory", "inventory", (test, minecraft) -> test.onClientRun(() -> minecraft.gui.setScreen(new InventoryScreen(minecraft.player)))));

    static void run(SelfTest test) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        if (Playback.active()) {
            PlaybackSteps.close(test);
        }
        if (minecraft.level != null) {
            WorldSteps.leaveWorld(test);
        }
        WorldSteps.createWorld(test, WORLD);
        test.onClientRun(() -> minecraft.options.setCameraType(CameraType.FIRST_PERSON));
        stage(test);

        // Recording: each window open for two seconds, the tick in the middle of it noted
        test.tool("recording_start", new JsonObject());
        List<Integer> ticks = new ArrayList<>();
        test.waitTicks(30);
        for (Display window : WINDOWS) {
            window.opener().open(test, minecraft);
            test.waitFor(() -> minecraft.gui.screen() != null, 10, window.label() + " to open in the live game");
            test.waitTicks(20);
            ticks.add(test.onClient(() -> Recorder.get().recordingTick()));
            test.waitTicks(20);
            test.onClientRun(() -> minecraft.player.closeContainer());
            test.waitFor(() -> minecraft.gui.screen() == null, 10, window.label() + " to close in the live game");
            test.waitTicks(20);
        }
        JsonObject finished = test.tool("recording_finish", args("name", "Windows"));
        Path recording = Path.of(finished.get("path").getAsString());
        WorldSteps.leaveWorld(test);

        // Playback, riding along with the recorder in first person, the HUD on (no editor: every look as it starts)
        test.tool("replay_open", args("path", recording.toString()));
        test.waitFor(() -> Playback.loaded() || Playback.lastError() != null, 120, "the windows recording to open");
        check(Playback.lastError() == null, "the windows recording did not open: " + Playback.lastError());
        List<String> problems = new ArrayList<>();
        try {
            for (int i = 0; i < WINDOWS.size(); i++) {
                Display window = WINDOWS.get(i);
                ride(test, minecraft, ticks.get(i));
                String held = WindowLayer.held();
                if (!window.drawnAs().equals(held)) {
                    problems.add(window.label() + ": the recording holds " + (held == null ? "no window" : held) + " at tick " + ticks.get(i) + ", not " + window.drawnAs());
                    continue;
                }
                if (!drawn(test, window.drawnAs())) {
                    problems.add(window.label() + ": the window layer did not draw it riding along in first person (last drew " + WindowLayer.lastDrawn() + ")");
                }
            }

            // Not drawn in third person, nor with the camera flying free: the same playback, the camera switched
            ride(test, minecraft, ticks.get(0));
            check(drawn(test, WINDOWS.get(0).drawnAs()), "the chest was not drawn before the camera was switched");
            test.onClientRun(() -> minecraft.options.setCameraType(CameraType.THIRD_PERSON_BACK));
            if (!notDrawn(test)) {
                problems.add("a window was still drawn in third person");
            }
            test.onClientRun(() -> {
                minecraft.options.setCameraType(CameraType.FIRST_PERSON);
                Viewer.ride(null);
            });
            if (!notDrawn(test)) {
                problems.add("a window was still drawn with the camera flying free");
            }
            if (!WindowLayer.leftOut().isEmpty()) {
                problems.add("windows were left out as unbuildable: " + WindowLayer.leftOut());
            }
        } finally {
            test.onClientRun(() -> minecraft.options.setCameraType(CameraType.FIRST_PERSON));
            PlaybackSteps.close(test);
        }
        check(problems.isEmpty(), "windows: " + String.join("; ", problems));
        test.log("windows: " + WINDOWS.size() + " windows recorded and drawn riding along in first person, none in third person or flying free");
    }

    /** The chest, the furnace with iron smelting and the villager, beside where the player stands */
    private static void stage(SelfTest test) throws Exception {
        test.command("gamerule sendCommandFeedback false");
        // In survival: in creative the inventory key opens the creative inventory, which is not a window a recording keeps
        test.command("gamemode survival");
        test.command("effect give @s minecraft:resistance 600 4 true");
        test.command("item replace entity @s weapon.mainhand with minecraft:air");
        test.command("setblock 2 " + Y + " 0 minecraft:chest{Items:[{Slot:0b,id:\"minecraft:diamond\",count:5},{Slot:13b,id:\"minecraft:oak_log\",count:32}]}");
        test.command("setblock -2 " + Y + " 0 minecraft:furnace{Items:[{Slot:0b,id:\"minecraft:raw_iron\",count:16},{Slot:1b,id:\"minecraft:coal\",count:8}]}");
        test.command("summon minecraft:villager 0.5 " + Y + " 2.5 {NoAI:1b,PersistenceRequired:1b,VillagerData:{profession:\"minecraft:librarian\",level:2,type:\"minecraft:plains\"},"
            + "Offers:{Recipes:[{buy:{id:\"minecraft:emerald\",count:3},sell:{id:\"minecraft:bookshelf\",count:1},maxUses:12}]}}");
        test.command("tp @s 0.5 " + Y + " 0.5 0 0");
        // The furnace lights and starts on the iron
        test.waitTicks(20);
    }

    static void onServer(SelfTest test, java.util.function.Consumer<ServerPlayer> action) throws Exception {
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        check(server != null, "there is no built-in server to open the window on");
        server.submit(() -> action.accept(server.getPlayerList().getPlayers().get(0))).get(30, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** Jumps to the tick and rides along with the recorder */
    static void ride(SelfTest test, Minecraft minecraft, int tick) throws Exception {
        SelfTestSupport.jump(test, tick, 60);
        test.onClientRun(() -> Viewer.ride(minecraft.level.getPlayerByUUID(UUID.fromString(Playback.manifest().recorderId))));
        test.waitFor(() -> Viewer.riding() != null, 10, "the view to ride along with the recorder");
    }

    static void frames(SelfTest test, int count) throws Exception {
        long from = Drawn.frames();
        test.waitFor(() -> Drawn.frames() >= from + count, 30, count + " frames to be drawn");
    }

    /** Whether the window layer drew this window in the frames just drawn */
    private static boolean drawn(SelfTest test, String name) throws Exception {
        long from = Drawn.frames();
        frames(test, 4);
        return name.equals(WindowLayer.lastDrawn()) && WindowLayer.lastDrawnFrame() > from;
    }

    /** Whether the window layer drew nothing in the frames just drawn, once the camera has had a frame to change */
    private static boolean notDrawn(SelfTest test) throws Exception {
        frames(test, 2);
        long from = Drawn.frames();
        frames(test, 4);
        return WindowLayer.lastDrawnFrame() <= from;
    }

}
