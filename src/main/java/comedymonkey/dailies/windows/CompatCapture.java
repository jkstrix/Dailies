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
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
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
 * has moved, in the window's own units. Every snapshot holds every state showing then, whole.
 */
final class CompatCapture {

    /** How far, in GUI units, the pointer must move to be written again, as for the recorder's windows */
    private static final float POINTER_STEP = 0.5F;

    /** What the recording holds of each compat, by compat id */
    private static final Map<String, Written> WRITTEN = new HashMap<>();
    private static final Set<String> SAID = ConcurrentHashMap.newKeySet();
    private static String recordingId = null;
    private static int ticks = 0;

    private CompatCapture() {
    }

    static void init() {
        Recorder.everyRecordedTick(CompatCapture::tick);
    }

    /** What the recording holds of one compat: each claim's last state written, and the pointer */
    private static final class Written {
        final Map<String, byte[]> states = new LinkedHashMap<>();
        final Map<String, Integer> writtenAt = new HashMap<>();
        String pointerOf = null;
        boolean share;
        float x;
        float y;
        int pointerAt;
    }

    /** A claim showing now, with its state and, for a screen, where the pointer is */
    private record Showing(CompatClaims.Claim claim, byte[] state, float[] pointer, boolean share) {
    }

    /** Every claim showing on the recorder's screen now, by compat id */
    private static Map<String, List<Showing>> showing(Minecraft minecraft) {
        Map<String, List<Showing>> now = new LinkedHashMap<>();
        Screen screen = minecraft.screen;
        CompatClaims.Claim screenClaim = screen == null ? null : Compats.screenClaim(screen);
        if (screenClaim != null) {
            byte[] state = capture(screenClaim, () -> ((ScreenPart) screenClaim.part()).capture(screen));
            if (state != null) {
                int[] corner = corner(screenClaim, screen);
                now.computeIfAbsent(screenClaim.compatId(), id -> new ArrayList<>()).add(new Showing(screenClaim, state, pointer(minecraft, corner), corner == null));
            }
        } else if (screen instanceof AbstractContainerScreen<?> container && container.getMenu() == minecraft.player.containerMenu && container.getMenu().containerId > 0) {
            // Extras only for a window the server opened for the player, the kind Dailies records itself (REC-25)
            CompatClaims.Claim extras = Compats.CLAIMS.extrasFor(Compats.menuType(screen));
            if (extras != null) {
                byte[] state = capture(extras, () -> ((WindowExtras) extras.part()).capture(container));
                if (state != null) {
                    now.computeIfAbsent(extras.compatId(), id -> new ArrayList<>()).add(new Showing(extras, state, null, false));
                }
            }
        }
        for (CompatClaims.Claim overlay : Compats.CLAIMS.overlays()) {
            byte[] state = capture(overlay, () -> ((OverlayPart) overlay.part()).capture());
            if (state != null) {
                now.computeIfAbsent(overlay.compatId(), id -> new ArrayList<>()).add(new Showing(overlay, state, null, false));
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

    private static void tick() {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null) {
                return;
            }
            String id = Recorder.get().recordingId();
            if (id != null && !id.equals(recordingId)) {
                // A new recording holds nothing yet: its first snapshot says what shows
                recordingId = id;
                WRITTEN.clear();
            }
            ticks++;
            Map<String, List<Showing>> now = showing(minecraft);
            Set<String> compats = new java.util.TreeSet<>(WRITTEN.keySet());
            compats.addAll(now.keySet());
            for (String compatId : compats) {
                CompatClaims.Compat compat = Compats.CLAIMS.compat(compatId);
                if (compat == null) {
                    continue;
                }
                Written written = WRITTEN.computeIfAbsent(compatId, key -> new Written());
                List<CompatRecord.Op> ops = changes(written, now.getOrDefault(compatId, List.of()));
                if (!ops.isEmpty()) {
                    Recorder.get().writeExtension(compatId, CompatRecord.write(compat.version(), ops));
                    Recorder.get().noteCompat(compatId, compat.version());
                }
            }
        } catch (Throwable t) {
            if (SAID.add("failed")) {
                Host.LOGGER.warn("Dailies: a compat's screens could not be recorded. The rest of the recording is unharmed. Said once", t);
            }
        }
    }

    /** What to write for one compat this tick: claims gone, states new or changed (when due), and the pointer */
    private static List<CompatRecord.Op> changes(Written written, List<Showing> now) {
        List<CompatRecord.Op> ops = new ArrayList<>();
        Set<String> names = new java.util.HashSet<>();
        for (Showing showing : now) {
            names.add(showing.claim().name());
        }
        for (Iterator<String> it = written.states.keySet().iterator(); it.hasNext(); ) {
            String name = it.next();
            if (!names.contains(name)) {
                ops.add(CompatRecord.Op.gone(name));
                it.remove();
                written.writtenAt.remove(name);
                if (name.equals(written.pointerOf)) {
                    written.pointerOf = null;
                }
            }
        }
        for (Showing showing : now) {
            String name = showing.claim().name();
            byte[] before = written.states.get(name);
            boolean fresh = before == null;
            if (fresh || !Arrays.equals(before, showing.state()) && ticks - written.writtenAt.getOrDefault(name, 0) >= ticksBetween(showing.claim())) {
                ops.add(CompatRecord.Op.state(name, showing.state()));
                written.states.put(name, showing.state());
                written.writtenAt.put(name, ticks);
            }
            if (showing.pointer() != null) {
                float x = showing.pointer()[0];
                float y = showing.pointer()[1];
                boolean moved = !name.equals(written.pointerOf) || showing.share() != written.share
                    || Math.abs(x - written.x) * (showing.share() ? 400 : 1) >= POINTER_STEP || Math.abs(y - written.y) * (showing.share() ? 300 : 1) >= POINTER_STEP;
                if (fresh || !name.equals(written.pointerOf) || moved && ticks - written.pointerAt >= WindowCapture.POINTER_TICKS) {
                    ops.add(CompatRecord.Op.pointer(name, showing.share(), x, y));
                    written.pointerOf = name;
                    written.share = showing.share();
                    written.x = x;
                    written.y = y;
                    written.pointerAt = ticks;
                }
            }
        }
        return ops;
    }

    /** One compat's part of a snapshot: every claim of its showing now, whole, with the pointer. Client thread */
    static void snapshot(String compatId, Recorder.Out out) {
        Minecraft minecraft = Minecraft.getInstance();
        CompatClaims.Compat compat = Compats.CLAIMS.compat(compatId);
        if (minecraft.player == null || compat == null) {
            return;
        }
        String id = Recorder.get().recordingId();
        if (id != null && !id.equals(recordingId)) {
            recordingId = id;
            WRITTEN.clear();
        }
        List<Showing> now = showing(minecraft).getOrDefault(compatId, List.of());
        // Playback forgets everything before a snapshot, so what was written before counts for nothing after it
        Written written = new Written();
        WRITTEN.put(compatId, written);
        if (now.isEmpty()) {
            return;
        }
        List<CompatRecord.Op> ops = changes(written, now);
        out.extension(compatId, CompatRecord.write(compat.version(), ops));
        Recorder.get().noteCompat(compatId, compat.version());
    }

}
