package comedymonkey.dailies.ui;

import com.mojang.blaze3d.platform.InputConstants;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.playback.Playback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.settings.IKeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;

import java.util.ArrayList;
import java.util.List;

/**
 * Dailies' key bindings, under their own heading in the game's controls screen.
 *
 * The recording keys (start or finish, pause or resume, four markers) are unbound until the player chooses keys for
 * them. The playback keys have defaults, and belong to a context of their own that is only live while a recording is
 * being watched with no screen open, so in normal play they never see a key press.
 */
public final class Keys {

    private static final String CATEGORY = "key.categories.dailies";

    /** Live only while a recording is being watched and no screen is open */
    private static final IKeyConflictContext WATCHING = new IKeyConflictContext() {
        @Override
        public boolean isActive() {
            return Playback.loaded() && Minecraft.getInstance().screen == null;
        }

        @Override
        public boolean conflicts(IKeyConflictContext other) {
            return other == this;
        }
    };

    /** A key and what it does */
    record Bound(KeyMapping key, Runnable action) {
    }

    private static final List<Bound> RECORDING = new ArrayList<>();
    private static final List<Bound> PLAYBACK = new ArrayList<>();
    private static volatile boolean registered = false;

    private Keys() {
    }

    /** Mod bus. The bindings are made here, on the thread the game reads its options on, and not before */
    public static void register(RegisterKeyMappingsEvent event) {
        if (registered) {
            return;
        }
        int unbound = InputConstants.UNKNOWN.getValue();
        RECORDING.add(new Bound(new KeyMapping("dailies.key.record", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, unbound, CATEGORY), Controls::toggleRecording));
        RECORDING.add(new Bound(new KeyMapping("dailies.key.pause_recording", KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, unbound, CATEGORY), Controls::togglePauseRecording));
        for (int marker = 1; marker <= 4; marker++) {
            int number = marker;
            RECORDING.add(new Bound(new KeyMapping("dailies.key.marker" + number, KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, unbound, CATEGORY), () -> Controls.dropMarker(number)));
        }

        PLAYBACK.add(new Bound(watching("dailies.key.play", InputConstants.KEY_P), Controls::togglePlay));
        PLAYBACK.add(new Bound(watching("dailies.key.step", InputConstants.KEY_PERIOD), Controls::stepOneTick));
        PLAYBACK.add(new Bound(watching("dailies.key.back", InputConstants.KEY_LEFT), () -> Controls.skip(-5)));
        PLAYBACK.add(new Bound(watching("dailies.key.forward", InputConstants.KEY_RIGHT), () -> Controls.skip(5)));
        PLAYBACK.add(new Bound(watching("dailies.key.slower", InputConstants.KEY_MINUS), Controls::slower));
        PLAYBACK.add(new Bound(watching("dailies.key.faster", InputConstants.KEY_EQUALS), Controls::faster));
        PLAYBACK.add(new Bound(watching("dailies.key.follow_camera", InputConstants.KEY_C), Controls::toggleFollowCamera));
        PLAYBACK.add(new Bound(watching("dailies.key.undo", unbound), Controls::undo));
        PLAYBACK.add(new Bound(watching("dailies.key.redo", unbound), Controls::redo));
        PLAYBACK.add(new Bound(watching("dailies.key.timeline", InputConstants.KEY_T), Controls::openTimeline));

        RECORDING.forEach(bound -> event.register(bound.key()));
        PLAYBACK.forEach(bound -> event.register(bound.key()));
        registered = true;
    }

    private static KeyMapping watching(String name, int key) {
        return new KeyMapping(name, WATCHING, InputConstants.Type.KEYSYM, key, CATEGORY);
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> recordingKeys());
        // Once a frame and not once a tick: a slowed-down recording slows the game's ticks down with it, and the
        // keys that would speed it up again must not become sluggish too
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Pre event) -> playbackKeys());
        // The editor, the key for which is one of these, has hooks of its own to set up
        EditorScreen.hook();
    }

    private static void recordingKeys() {
        if (!registered) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        boolean usable = minecraft.level != null && minecraft.screen == null;
        for (Bound bound : RECORDING) {
            if (pressed(bound.key()) && usable) {
                run(bound);
            }
        }
    }

    private static void playbackKeys() {
        if (!registered) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        for (Bound bound : PLAYBACK) {
            // Read even when nothing may be done with it, so a press is never kept for later
            if (pressed(bound.key()) && Playback.loaded() && minecraft.screen == null) {
                silenceOthers(minecraft, bound.key());
                run(bound);
            }
        }
    }

    /**
     * While a recording is watched its keys come first: whatever else the game has on the same key (the chat, the
     * social screen) does not also happen.
     */
    private static void silenceOthers(Minecraft minecraft, KeyMapping ours) {
        if (ours.isUnbound()) {
            return;
        }
        for (KeyMapping other : minecraft.options.keyMappings) {
            if (other != ours && other.getKeyConflictContext() != WATCHING && other.getKey().equals(ours.getKey())) {
                pressed(other);
            }
        }
    }

    private static boolean pressed(KeyMapping key) {
        boolean any = false;
        while (key.consumeClick()) {
            any = true;
        }
        return any;
    }

    private static void run(Bound bound) {
        try {
            bound.action().run();
        } catch (RuntimeException | LinkageError e) {
            Host.LOGGER.error("Dailies: the key for {} failed", bound.key().getName(), e);
        }
    }

    /**
     * For a screen that keeps the playback keys working while it is open.
     *
     * @return whether the key was one of them
     */
    static boolean playbackKeyPressed(int keyCode, int scanCode) {
        for (Bound bound : PLAYBACK) {
            if (!bound.key().isUnbound() && bound.key().matches(keyCode, scanCode)) {
                run(bound);
                return true;
            }
        }
        return false;
    }

    /** Whether this key is the one that opens the timeline */
    static boolean isTimelineKey(int keyCode, int scanCode) {
        for (Bound bound : PLAYBACK) {
            if (bound.key().getName().equals("dailies.key.timeline")) {
                return !bound.key().isUnbound() && bound.key().matches(keyCode, scanCode);
            }
        }
        return false;
    }

}
