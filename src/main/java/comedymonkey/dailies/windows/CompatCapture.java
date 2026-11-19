package comedymonkey.dailies.windows;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.api.compat.OverlayPart;
import comedymonkey.dailies.api.compat.ScreenPart;
import comedymonkey.dailies.api.compat.WindowExtras;
import comedymonkey.dailies.capture.Recorder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The recording half of the compat API (specification REC-26, REC-27). At the end of each recorded tick every claim
 * that is showing on the recorder's screen is asked for its state: the screen a compat claims, the extras for a window
 * Dailies is recording, and each overlay. A state is written, under the compat's id, when it first shows and then
 * whenever it differs from the one written last, at most as often as the claim asks; a claim that stops showing is
 * written gone. Over a claimed screen the pointer is written as for the recorder's windows: a few times a second when it
 * has moved, in the window's own units. Every snapshot holds every state showing then, whole, and counts for nothing
 * as written: play running into a segment steps over its snapshot, so the ticks' own records carry every change
 * ({@link CompatWrites}).
 */
final class CompatCapture {

    /** What the recording holds of each compat, and what the snapshot taken this tick described */
    private static final CompatWrites WRITES = new CompatWrites();
    private static final Set<String> SAID = ConcurrentHashMap.newKeySet();
    private static String recordingId = null;
    /** How many snapshots the recorder had taken at the last recorded tick */
    private static int snapshotsSeen = -1;

    /**
     * The screen whose claim was last asked for its state, and whether it gave one: held weakly, so a screen closed is let
     * go. Client thread
     */
    private static java.lang.ref.WeakReference<Screen> asked = new java.lang.ref.WeakReference<>(null);
    private static boolean askedGave = false;

    private CompatCapture() {
    }

    static void init() {
        Recorder.everyRecordedTick(CompatCapture::tick);
    }

    private static void remember(Screen screen, boolean gave) {
        if (asked.get() != screen) {
            asked = new java.lang.ref.WeakReference<>(screen);
        }
        askedGave = gave;
    }

    /**
     * Whether a compat records this screen whole: a claim matches it and gives a state for it, so a mod's screen built on
     * a claimed one, which the claim turns down, is not counted. While the game stands paused under such a screen the
     * recording keeps time (REC-36), and every tick it keeps asks the claim again, so the answer is known from the last
     * one. A screen not yet asked about, one that opened while the game already stood paused, is asked here once.
     * Client thread
     */
    static boolean records(Screen screen) {
        if (asked.get() == screen) {
            return askedGave;
        }
        CompatClaims.Claim claim;
        try {
            claim = Compats.screenClaim(screen);
        } catch (Throwable t) {
            claim = null;
        }
        CompatClaims.Claim found = claim;
        boolean gave = found != null && capture(found, () -> ((ScreenPart) found.part()).capture(screen)) != null;
        remember(screen, gave);
        return gave;
    }

    /** Every claim showing on the recorder's screen now, by compat id */
    private static Map<String, List<CompatWrites.Showing>> showing(Minecraft minecraft) {
        Map<String, List<CompatWrites.Showing>> now = new LinkedHashMap<>();
        Screen screen = minecraft.screen;
        CompatClaims.Claim screenClaim = screen == null ? null : Compats.screenClaim(screen);
        if (screen != null) {
            remember(screen, false);
        }
        if (screenClaim != null) {
            byte[] state = capture(screenClaim, () -> ((ScreenPart) screenClaim.part()).capture(screen));
            remember(screen, state != null);
            if (state != null) {
                int[] corner = corner(screenClaim, screen);
                now.computeIfAbsent(screenClaim.compatId(), id -> new ArrayList<>()).add(new CompatWrites.Showing(screenClaim.name(), state, pointer(minecraft, corner), corner == null, ticksBetween(screenClaim)));
            }
        } else if (screen instanceof AbstractContainerScreen<?> container && container.getMenu() == minecraft.player.containerMenu && container.getMenu().containerId > 0) {
            // Extras only for a window the server opened for the player, the kind Dailies records itself (REC-25)
            CompatClaims.Claim extras = Compats.CLAIMS.extrasFor(Compats.menuType(screen));
            if (extras != null) {
                byte[] state = capture(extras, () -> ((WindowExtras) extras.part()).capture(container));
                if (state != null) {
                    now.computeIfAbsent(extras.compatId(), id -> new ArrayList<>()).add(new CompatWrites.Showing(extras.name(), state, null, false, ticksBetween(extras)));
                }
            }
        }
        for (CompatClaims.Claim overlay : Compats.CLAIMS.overlays()) {
            byte[] state = capture(overlay, () -> ((OverlayPart) overlay.part()).capture());
            if (state != null) {
                now.computeIfAbsent(overlay.compatId(), id -> new ArrayList<>()).add(new CompatWrites.Showing(overlay.name(), state, null, false, ticksBetween(overlay)));
            }
        }
        return now;
    }

    private interface Capture {
        byte[] get() throws Exception;
    }

    /** A claim's state, or null when it gives none or fails (said once) */
    private static byte[] capture(CompatClaims.Claim claim, Capture capture) {
        try {
            return capture.get();
        } catch (Throwable t) {
            if (SAID.add(claim.drawnAs())) {
                Host.LOGGER.warn("Dailies: the compat claim {} could not say what it shows, and is not recorded while it fails. Said once", claim.drawnAs(), t);
            }
            return null;
        }
    }

    private static int @Nullable [] corner(CompatClaims.Claim claim, Screen screen) {
        try {
            int[] corner = ((ScreenPart) claim.part()).corner(screen);
            return corner != null && corner.length >= 2 ? corner : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** The pointer in GUI units from the window's corner, or as shares of the screen when it has none */
    private static float[] pointer(Minecraft minecraft, int @Nullable [] corner) {
        com.mojang.blaze3d.platform.Window window = minecraft.getWindow();
        double rawX = minecraft.mouseHandler.xpos();
        double rawY = minecraft.mouseHandler.ypos();
        if (corner == null) {
            return new float[]{(float) WindowPointer.toShare(rawX, window.getScreenWidth()), (float) WindowPointer.toShare(rawY, window.getScreenHeight())};
        }
        return new float[]{(float) WindowPointer.toWindow(rawX, window.getScreenWidth(), window.getGuiScaledWidth(), corner[0]),
            (float) WindowPointer.toWindow(rawY, window.getScreenHeight(), window.getGuiScaledHeight(), corner[1])};
    }

    private static int ticksBetween(CompatClaims.Claim claim) {
        try {
            Object part = claim.part();
            int between = part instanceof ScreenPart screen ? screen.ticksBetweenWrites()
                : part instanceof WindowExtras extras ? extras.ticksBetweenWrites() : ((OverlayPart) part).ticksBetweenWrites();
            return Math.max(1, between);
        } catch (Throwable t) {
            return 1;
        }
    }

    /** A recording other than the one written for so far holds nothing yet: its first snapshot says what shows */
    private static void sameRecording() {
        String id = Recorder.get().recordingId();
        if (id != null && !id.equals(recordingId)) {
            recordingId = id;
            WRITES.clear();
        }
    }

    private static void tick() {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null) {
                return;
            }
            sameRecording();
            // A snapshot taken since the last tick, known from the recorder rather than from this compat's part of it, so
            // that a part that was never written counts as describing nothing
            int snapshots = Recorder.get().snapshotsTaken();
            WRITES.tickBegins(snapshots != snapshotsSeen);
            snapshotsSeen = snapshots;
            Map<String, List<CompatWrites.Showing>> now = showing(minecraft);
            Set<String> compats = new java.util.TreeSet<>(WRITES.compats());
            compats.addAll(now.keySet());
            for (String compatId : compats) {
                CompatClaims.Compat compat = Compats.CLAIMS.compat(compatId);
                if (compat == null) {
                    continue;
                }
                List<CompatRecord.Op> ops = WRITES.tick(compatId, now.getOrDefault(compatId, List.of()));
                if (!ops.isEmpty()) {
                    Recorder.get().writeExtension(compatId, CompatRecord.write(compat.version(), ops));
                    Recorder.get().noteCompat(compatId, compat.version());
                }
            }
        } catch (Throwable t) {
            if (SAID.add("failed")) {
                Host.LOGGER.warn("Dailies: a compat's screens could not be recorded. The rest of the recording is unharmed. Said once", t);
            }
        } finally {
            WRITES.tickEnds();
        }
    }

    /**
     * One compat's part of a snapshot: every claim of its showing now, whole, with the pointer. Client thread. Nothing
     * counts as written by it: play that runs into its segment steps over it, so the tick that follows writes whatever
     * the records of the ticks do not hold yet ({@link CompatWrites})
     */
    static void snapshot(String compatId, Recorder.Out out) {
        Minecraft minecraft = Minecraft.getInstance();
        CompatClaims.Compat compat = Compats.CLAIMS.compat(compatId);
        if (minecraft.player == null || compat == null) {
            return;
        }
        sameRecording();
        List<CompatRecord.Op> ops = WRITES.snapshot(compatId, showing(minecraft).getOrDefault(compatId, List.of()));
        if (!ops.isEmpty()) {
            out.extension(compatId, CompatRecord.write(compat.version(), ops));
            Recorder.get().noteCompat(compatId, compat.version());
        }
    }

}
