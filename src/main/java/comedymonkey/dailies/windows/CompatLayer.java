package comedymonkey.dailies.windows;

import comedymonkey.dailies.Dailies;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.api.compat.OverlayPart;
import comedymonkey.dailies.api.compat.ScreenPart;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.playback.PlaybackExtensions;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The playback half of the compat API (specification PLAY-21, EXP-17). Each compat's records are played into what it
 * holds ({@link CompatHeld}); then, under the owner's rule for the recorder's windows (riding along with the recorder in
 * first person, the HUD look on, and for screens the "open windows" look), its overlays are drawn over the world in a
 * layer under the recorder's windows, and its screen in a layer over them: built by the compat from its state, laid out
 * without telling other mods, drawn as the recorder's game drew it, and the recorded pointer drawn over. Its extras for
 * a window Dailies draws are put on that window by {@link WindowLayer} just before it is drawn.
 */
final class CompatLayer {

    /** Each compat's playback half, by compat id */
    private static final Map<String, Played> PLAYED = new ConcurrentHashMap<>();
    private static final Set<String> SAID = ConcurrentHashMap.newKeySet();

    // What was last drawn, for the scripted test and anyone checking a compat reaches the picture
    private static volatile @Nullable String lastDrawn = null;
    private static volatile long lastDrawnFrame = -1;
    private static final Map<String, Long> drawnFrames = new ConcurrentHashMap<>();

    private CompatLayer() {
    }

    static void init() {
        Playback.onClosing(() -> PLAYED.values().forEach(Played::reset));
    }

    static void register(RegisterGuiLayersEvent event) {
        // Overlays where the HUD is, under the recorder's windows (registered just above the saving indicator before this);
        // screens over the windows, as either is the screen the recorder's game had open
        event.registerAbove(VanillaGuiLayers.SAVING_INDICATOR, ResourceLocation.fromNamespaceAndPath(Dailies.ID, "compat_overlays"), CompatLayer::drawOverlays);
        event.registerAbove(ResourceLocation.fromNamespaceAndPath(Dailies.ID, "recorder_windows"), ResourceLocation.fromNamespaceAndPath(Dailies.ID, "compat_screens"),
            CompatLayer::drawScreens);
    }

    static PlaybackExtensions.Extension forCompat(String compatId) {
        return PLAYED.computeIfAbsent(compatId, Played::new);
    }

    /** The newest state of a claim at the point playback has reached, or null while it is not showing */
    static @Nullable CompatHeld.Held held(CompatClaims.Claim claim) {
        Played played = PLAYED.get(claim.compatId());
        return played == null ? null : played.held.held(claim.name());
    }

    /** One compat's records in playback */
    private static final class Played implements PlaybackExtensions.Extension {
        final String compatId;
        final CompatHeld held = new CompatHeld();
        final PointerMotion pointer = new PointerMotion();
        /** The screen built for its claim showing, and what it was built from */
        @Nullable Screen screen = null;
        @Nullable CompatHeld.Held builtFrom = null;
        int builtWidth;
        int builtHeight;
        boolean readFailed = false;

        Played(String compatId) {
            this.compatId = compatId;
        }

        @Override
        public void play(byte[] data, int from, int length, boolean skipping) {
            CompatHeld.Played played;
            try {
                played = this.held.play(data, from, length);
            } catch (IllegalArgumentException unreadable) {
                if (!this.readFailed) {
                    this.readFailed = true;
                    Host.LOGGER.warn("Dailies: a record of the compat {} in this recording could not be read and is left out. Said once", this.compatId, unreadable);
                }
                return;
            }
            if (played.pointerMoved()) {
                this.pointer.moved(this.held.pointerX, this.held.pointerY, skipping || !this.pointer.known());
            }
            if (this.held.isEmpty()) {
                this.pointer.forget();
            }
        }

        @Override
        public void describe(Consumer<byte[]> out) {
            this.held.describe().forEach(out);
        }

        @Override
        public void reset() {
            this.held.clear();
            this.pointer.forget();
            this.screen = null;
            this.builtFrom = null;
        }
    }

    // --- Drawing ---

    private static void drawOverlays(GuiGraphics graphics, DeltaTracker delta) {
        if (PLAYED.isEmpty() || !Playback.loaded()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || !WindowLayer.shown(minecraft, false)) {
            return;
        }
        for (CompatClaims.Claim claim : Compats.CLAIMS.overlays()) {
            CompatHeld.Held held = held(claim);
            if (held == null || SAID.contains(claim.drawnAs())) {
                continue;
            }
            graphics.pose().pushPose();
            try {
                ((OverlayPart) claim.part()).draw(graphics, held.state(), held.version(), delta.getGameTimeDeltaPartialTick(false));
                drawn(claim);
            } catch (Throwable t) {
                leaveOut(claim, "could not be drawn", t);
            } finally {
                graphics.pose().popPose();
            }
        }
    }

    private static void drawScreens(GuiGraphics graphics, DeltaTracker delta) {
        if (PLAYED.isEmpty() || !Playback.loaded()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || !WindowLayer.shown(minecraft, true)) {
            return;
        }
        for (Played played : PLAYED.values()) {
            for (String name : played.held.showing()) {
                CompatClaims.Claim claim = Compats.CLAIMS.claim(played.compatId, name);
                if (claim != null && (claim.kind() == CompatClaims.Kind.SCREEN || claim.kind() == CompatClaims.Kind.MENU) && !SAID.contains(claim.drawnAs())) {
                    drawScreen(graphics, delta, minecraft, played, claim);
                    // One screen at a time, as on the recorder's game
                    return;
                }
            }
        }
    }

    private static void drawScreen(GuiGraphics graphics, DeltaTracker delta, Minecraft minecraft, Played played, CompatClaims.Claim claim) {
        CompatHeld.Held held = played.held.held(claim.name());
        ScreenPart part = (ScreenPart) claim.part();
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        Screen screen = played.screen;
        try {
            if (screen == null || played.builtFrom != held || played.builtWidth != width || played.builtHeight != height) {
                // Built afresh for a new state or another size: a screen lays itself out once
                played.screen = null;
                screen = part.build(held.state(), held.version());
                if (screen == null) {
                    return;
                }
                WindowLayer.layOut(minecraft, screen, width, height);
                played.screen = screen;
                played.builtFrom = held;
                played.builtWidth = width;
                played.builtHeight = height;
            }
        } catch (Throwable t) {
            leaveOut(claim, "could not be built", t);
            return;
        }
        try {
            double x;
            double y;
            boolean pointer = played.held.hasPointer(claim.name());
            float[] at = played.pointer.at(Playback.frameTime());
            int[] corner = played.held.pointerShare ? null : part.corner(screen);
            if (!pointer) {
                // Never recorded: far off the window, so nothing shows as hovered
                x = -1000.0;
                y = -1000.0;
            } else if (corner == null || corner.length < 2) {
                x = WindowPointer.fromShare(at[0], width);
                y = WindowPointer.fromShare(at[1], height);
            } else {
                x = WindowPointer.toScreen(at[0], corner[0]);
                y = WindowPointer.toScreen(at[1], corner[1]);
            }
            WindowLayer.drawScreen(graphics, minecraft, screen, (int) Math.floor(x), (int) Math.floor(y), delta.getGameTimeDeltaPartialTick(false));
            if (pointer) {
                WindowLayer.drawPointer(graphics, minecraft, x, y);
            }
            drawn(claim);
        } catch (Throwable t) {
            played.screen = null;
            leaveOut(claim, "could not be drawn", t);
        }
    }

    private static void drawn(CompatClaims.Claim claim) {
        long frame = comedymonkey.dailies.looks.Drawn.frames();
        lastDrawn = claim.drawnAs();
        lastDrawnFrame = frame;
        drawnFrames.put(claim.drawnAs(), frame);
    }

    private static void leaveOut(CompatClaims.Claim claim, String what, Throwable t) {
        if (SAID.add(claim.drawnAs())) {
            Host.LOGGER.warn("Dailies: the compat claim {} {} in playback and is left out. Said once", claim.drawnAs(), what, t);
        }
    }

    // --- For the scripted test, and anyone checking a compat reaches the picture ---

    /** What was drawn last, as {@code <compat id>/<claim name>}; null when nothing has been */
    static @Nullable String lastDrawn() {
        return lastDrawn;
    }

    /** The frame anything of a compat was last drawn in; -1 when nothing has been */
    static long lastDrawnFrame() {
        return lastDrawnFrame;
    }

    /** The frame this claim was last drawn in; -1 when it has not been */
    static long lastDrawnFrame(String drawnAs) {
        return drawnFrames.getOrDefault(drawnAs, -1L);
    }

    /** What the recording holds as showing at this moment, each as {@code <compat id>/<claim name>} */
    static Set<String> held() {
        Set<String> all = new java.util.TreeSet<>();
        PLAYED.forEach((id, played) -> played.held.showing().forEach(name -> all.add(id + "/" + name)));
        return all;
    }

}
