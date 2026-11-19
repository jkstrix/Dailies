package comedymonkey.dailies.windows;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.api.compat.Claims;
import comedymonkey.dailies.api.compat.DailiesCompat;
import comedymonkey.dailies.api.compat.DisplayTest;
import comedymonkey.dailies.api.compat.OverlayPart;
import comedymonkey.dailies.api.compat.ScreenPart;
import comedymonkey.dailies.api.compat.ServerHalf;
import comedymonkey.dailies.api.compat.WindowExtras;
import comedymonkey.dailies.capture.SnapshotExtensions;
import comedymonkey.dailies.compat.companion.CompanionCompat;
import comedymonkey.dailies.compat.companion.CompatRoutes;
import comedymonkey.dailies.file.RecordingManifest;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.playback.PlaybackExtensions;
import comedymonkey.dailies.ui.Notices;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The compats registered with the compat API (specification CPT-1 to CPT-5): Dailies' side of
 * {@link comedymonkey.dailies.api.compat.DailiesCompats}. A compat's claims go into {@link CompatClaims}; its records
 * are written by {@link CompatCapture} and drawn by {@link CompatLayer}; the client half of its server half is routed
 * through the companion's plugin API ({@link CompanionCompat}, SRV-6 to SRV-9).
 */
public final class Compats {

    static final CompatClaims CLAIMS = new CompatClaims();
    private static final Set<String> SAID = ConcurrentHashMap.newKeySet();

    private Compats() {
    }

    static void init() {
        Playback.onOpened(Compats::opened);
    }

    /** See {@link comedymonkey.dailies.api.compat.DailiesCompats#register} */
    public static void register(DailiesCompat compat) {
        Objects.requireNonNull(compat, "compat");
        String id = compat.id();
        int version = compat.version();
        CLAIMS.add(id, version, compat);
        compat.claim(new Claims() {
            @Override
            public void screen(String name, Class<? extends Screen> screenClass, ScreenPart part) {
                take(new CompatClaims.Claim(id, version, name, CompatClaims.Kind.SCREEN, screenClass.getName(), Objects.requireNonNull(part, "part")));
            }

            @Override
            public void menu(String name, String menuType, ScreenPart part) {
                take(new CompatClaims.Claim(id, version, name, CompatClaims.Kind.MENU, menuKey(menuType), Objects.requireNonNull(part, "part")));
            }

            @Override
            public void windowExtras(String name, String menuType, WindowExtras extras) {
                take(new CompatClaims.Claim(id, version, name, CompatClaims.Kind.EXTRAS, menuKey(menuType), Objects.requireNonNull(extras, "extras")));
            }

            @Override
            public void overlay(String name, OverlayPart part) {
                take(new CompatClaims.Claim(id, version, name, CompatClaims.Kind.OVERLAY, "", Objects.requireNonNull(part, "part")));
            }

            @Override
            public void serverHalf(String pluginId, ServerHalf half) {
                Objects.requireNonNull(half, "half");
                CompanionCompat.register(pluginId, new CompatRoutes.Handler() {
                    @Override
                    public void received(int pluginVersion, byte[] data) {
                        half.received(pluginVersion, data);
                    }

                    @Override
                    public void played(int pluginVersion, byte[] data, boolean skipping) {
                        half.played(pluginVersion, data, skipping);
                    }

                    @Override
                    public void reset() {
                        half.reset();
                    }
                });
            }
        });
        // Its records are its own extension, written as play goes on and in every snapshot, and played back by its layer
        SnapshotExtensions.register(id, (out, codec) -> CompatCapture.snapshot(id, out));
        PlaybackExtensions.register(id, CompatLayer.forCompat(id));
        Host.LOGGER.info("Dailies: compat {} version {} registered, claiming {}", id, version, CLAIMS.claimsOf(id).stream().map(CompatClaims.Claim::name).toList());
    }

    private static String menuKey(String menuType) {
        ResourceLocation key = menuType == null ? null : ResourceLocation.tryParse(menuType);
        if (key == null) {
            throw new IllegalArgumentException("'" + menuType + "' is not a menu type id such as 'mymod:press'");
        }
        return key.toString();
    }

    private static void take(CompatClaims.Claim claim) {
        String leftOut = CLAIMS.claim(claim);
        if (leftOut != null) {
            Host.LOGGER.warn("Dailies: the claim {} is left out: {}", claim.drawnAs(), leftOut);
        }
    }

    // --- What the rest of Dailies asks ---

    /** The claim that records and draws this screen whole, or null when Dailies' own window recording may take it */
    static @Nullable CompatClaims.Claim screenClaim(Screen screen) {
        List<String> classes = new ArrayList<>();
        for (Class<?> type = screen.getClass(); type != null && type != Screen.class && type != Object.class; type = type.getSuperclass()) {
            classes.add(type.getName());
        }
        return CLAIMS.screenFor(classes, menuType(screen));
    }

    /** Whether a compat claims this screen whole, so Dailies' own window recording leaves it alone (REC-26) */
    static boolean claims(Screen screen) {
        try {
            return screenClaim(screen) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    static @Nullable String menuType(Screen screen) {
        AbstractContainerMenu menu = screen instanceof MenuAccess<?> access ? access.getMenu() : null;
        if (menu == null) {
            return null;
        }
        try {
            ResourceLocation key = BuiltInRegistries.MENU.getKey(menu.getType());
            return key == null ? null : key.toString();
        } catch (UnsupportedOperationException none) {
            return null;
        }
    }

    /** Puts a compat's extras on a window Dailies built for playback, just before it is drawn */
    static void applyExtras(String menuType, Screen screen) {
        CompatClaims.Claim claim = CLAIMS.extrasFor(menuType);
        if (claim == null || !(screen instanceof AbstractContainerScreen<?> container)) {
            return;
        }
        CompatHeld.Held held = CompatLayer.held(claim);
        if (held == null) {
            return;
        }
        try {
            ((WindowExtras) claim.part()).apply(container, held.state(), held.version());
        } catch (Throwable t) {
            failed(claim, "could not put its state on the window in playback", t);
        }
    }

    /** A part failed: said once, and the rest goes on */
    static void failed(CompatClaims.Claim claim, String what, Throwable t) {
        if (SAID.add(claim.drawnAs() + " " + what)) {
            Host.LOGGER.warn("Dailies: the compat claim {} {}; it is left out from here. Said once", claim.drawnAs(), what, t);
        }
    }

    /** A recording has opened: says once in this game which compats it lists that this game is without (PLAY-22) */
    private static void opened() {
        RecordingManifest manifest = Playback.manifest();
        if (manifest == null) {
            return;
        }
        for (String note : CLAIMS.notesFor(manifest.compats)) {
            String[] words = note.split(" ");
            if (words[0].equals("missing")) {
                Host.LOGGER.info("Dailies: this recording has screens or overlays from the compat {}, which this game does not have; it plays on without them. Said once", words[1]);
                Notices.show(Component.translatable("dailies.notice.compatMissing", words[1]));
            } else {
                Host.LOGGER.info("Dailies: this recording was made with version {} of the compat {}, and this game has version {}; its screens are drawn as far as that version can. Said once",
                    words[2], words[1], words[3]);
            }
        }
    }

    // --- For the API ---

    /** See {@link comedymonkey.dailies.api.compat.DailiesCompats#recorder} */
    public static @Nullable Player recorder() {
        Minecraft minecraft = Minecraft.getInstance();
        RecordingManifest manifest = Playback.manifest();
        if (!Playback.active() || manifest == null || minecraft.level == null || manifest.recorderId == null || manifest.recorderId.isEmpty()) {
            return null;
        }
        try {
            return minecraft.level.getPlayerByUUID(UUID.fromString(manifest.recorderId));
        } catch (IllegalArgumentException notAnId) {
            return null;
        }
    }

    /** See {@link comedymonkey.dailies.api.compat.DailiesCompats#overlaysShown} */
    public static boolean overlaysShown() {
        Minecraft minecraft = Minecraft.getInstance();
        return Playback.loaded() && minecraft.level != null && minecraft.player != null && WindowLayer.shown(minecraft, false);
    }

    /** See {@link comedymonkey.dailies.api.compat.DailiesCompats#storeOnce} */
    public static String storeOnce(byte[] data) {
        return CompatBlobs.store(data);
    }

    /** See {@link comedymonkey.dailies.api.compat.DailiesCompats#stored} */
    public static byte @Nullable [] stored(String handle) {
        return CompatBlobs.read(handle);
    }

    public static boolean askServer(String pluginId, int version) {
        return CompanionCompat.ask(pluginId, version);
    }

    public static int serverVersion(String pluginId) {
        return CompanionCompat.serverVersion(pluginId);
    }

    public static int recordedVersion(String pluginId) {
        return CompanionCompat.recordedVersion(pluginId);
    }

    // --- For the scripted test ---

    /** A registered compat's display test, or null when it is not registered or has none */
    public static @Nullable DisplayTest displayTest(String compatId) {
        CompatClaims.Compat compat = CLAIMS.compat(compatId);
        return compat == null ? null : ((DailiesCompat) compat.compat()).displayTest();
    }

    /** The frame ({@link comedymonkey.dailies.looks.Drawn#frames()}) a compat's claim, named {@code <compat id>/<name>}, was last drawn in; -1 when never */
    public static long lastDrawnFrame(String drawnAs) {
        return CompatLayer.lastDrawnFrame(drawnAs);
    }

    /** The frame anything of any compat was last drawn in; -1 when nothing has been */
    public static long lastDrawnFrame() {
        return CompatLayer.lastDrawnFrame();
    }

    /** What compats drew last, as {@code <compat id>/<name>}; null when nothing has been */
    public static @Nullable String lastDrawn() {
        return CompatLayer.lastDrawn();
    }

    /** The compats' claims the recording holds as showing at this moment, as {@code <compat id>/<name>} */
    public static Set<String> held() {
        return CompatLayer.held();
    }

    /** The compats registered, by id, with their versions */
    public static Map<String, Integer> registered() {
        Map<String, Integer> all = new java.util.TreeMap<>();
        CLAIMS.compats().forEach(compat -> all.put(compat.id(), compat.version()));
        return all;
    }

}
