package comedymonkey.dailiescompat.supplementaries.client;

import comedymonkey.dailies.api.compat.Claims;
import comedymonkey.dailies.api.compat.DailiesCompat;
import comedymonkey.dailies.api.compat.DailiesCompats;
import comedymonkey.dailies.api.compat.DisplayTest;
import comedymonkey.dailies.api.compat.OverlayPart;
import comedymonkey.dailies.api.compat.ScreenPart;
import comedymonkey.dailies.api.compat.WindowExtras;
import comedymonkey.dailiescompat.supplementaries.SupplementariesCompat;
import net.mehvahdjukaar.supplementaries.client.screens.BlackBoardScreen;
import net.mehvahdjukaar.supplementaries.client.screens.DoormatScreen;
import net.mehvahdjukaar.supplementaries.client.screens.SignPostScreen;
import net.mehvahdjukaar.supplementaries.client.screens.SpeakerBlockScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The Supplementaries compat itself (specification CPT-10): the class that registers with Dailies, loaded only when
 * Dailies is, and Supplementaries or Amendments (see {@link SupplementariesCompat}). What the mods draw during play, from
 * a sweep of every screen, HUD layer, drawing into the world and line above the hotbar in their jars:
 * <ul>
 * <li>Supplementaries' screens only the player's game knows: a doormat, a sign post, a blackboard and a speaker block
 * ({@link SuppScreens});</li>
 * <li>extras for its windows that the server opens and Dailies draws itself: a present's recipient and message, a trapped
 * present's priming, a cannon's aim and power, and the red merchant's offers, which come in a message of its own
 * ({@link SuppWindows}). Its notice board, pulley, sack, lunch basket and safe show only their slots, and need nothing;</li>
 * <li>its overlays: the slime over the screen, a quiver's or lunch basket's row and the cannon's controls
 * ({@link SuppHud}); a cannon's trajectory and a Stasis slingshot's far outline, drawn into the world ({@link InWorld});</li>
 * <li>the line both mods put above the hotbar on the player's own game ({@link StatusLine});</li>
 * <li>Amendments' book and quill written in on a lectern, a window Dailies cannot draw, claimed whole
 * ({@link LecternBook}).</li>
 * </ul>
 * What needs nothing here: what Amendments changes in the game's own screens (its sign and hanging sign editing, drawn with
 * the game's screen that Dailies' own compat records, and its trade buttons on the villager's window), and its moving
 * hotbar selection, which the game's own hotbar draws in playback; every item tooltip either mod adds (sherds, paintings,
 * banners, blackboards, a quiver's contents), which playback's windows ask for themselves; the mark on a slingshot or
 * quiver in the hotbar, drawn from the item; and lines the server sends, in the game's message or in one of
 * Supplementaries' own, which the recording keeps. Left out: configuration screens and Supplementaries' notices on the
 * title screen (incompatible mods, OptiFine), which are not play; how the world looks (ropes, globes, jars, flags, signs,
 * the rope's camera sway, Lumisene's light), which playback draws from its own world; the camera a cannon is aimed from
 * and the hand and hotbar it hides, which are not interface; and the line "I turned myself into a pickle!", said only to
 * the mod author's own player. Not carried: the tattered page of a written book in antique ink, which Dailies' own compat
 * for books draws from its pages alone, on the plain page.
 * <p>
 * Each state is the least that draws its part again, and the whole of it; Dailies writes it when it changes and keeps it
 * for snapshots, jumps and trims.
 */
public final class SuppDailies implements DailiesCompat {

    public static final String ID = SupplementariesCompat.ID + ":supplementaries";
    /** Raise when a state in SuppStates changes shape; the parts must still read every version before it */
    static final int VERSION = 1;
    /** Typing, and a value dragged on a screen, are written at most five times a second */
    static final int TYPING_TICKS = 4;
    /** A board drawn on, the cannon's charge: at most ten times a second */
    static final int DRAWING_TICKS = 2;

    private final boolean supplementaries;
    private final boolean amendments;

    private SuppDailies(boolean supplementaries, boolean amendments) {
        this.supplementaries = supplementaries;
        this.amendments = amendments;
    }

    /** Registers the compat and its listeners, with the parts of the mods that are loaded. Only call it when Dailies is loaded */
    public static void register(boolean supplementaries, boolean amendments) {
        DailiesCompats.register(new SuppDailies(supplementaries, amendments));
        if (supplementaries) {
            NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, RenderFrameEvent.Pre.class, InWorld::frameStarts);
            NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, RenderLevelStageEvent.class, InWorld::draw);
        }
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int version() {
        return VERSION;
    }

    @Override
    public void claim(Claims claims) {
        // The line above the hotbar, which both mods put up
        claims.overlay(StatusLine.NAME, timedOverlay(StatusLine::capture, StatusLine::draw, 1));
        if (this.supplementaries) {
            claims.screen(SuppScreens.DOORMAT, DoormatScreen.class, screen(SuppScreens::captureTextHolder, SuppScreens::buildDoormat, TYPING_TICKS));
            claims.screen(SuppScreens.SIGN_POST, SignPostScreen.class, screen(SuppScreens::captureTextHolder, SuppScreens::buildSignPost, TYPING_TICKS));
            claims.screen(SuppScreens.BLACKBOARD, BlackBoardScreen.class, screen(SuppScreens::captureBlackboard, SuppScreens::buildBlackboard, DRAWING_TICKS));
            claims.screen(SuppScreens.SPEAKER, SpeakerBlockScreen.class, screen(SuppScreens::captureSpeaker, SuppScreens::buildSpeaker, TYPING_TICKS));
            claims.windowExtras(SuppWindows.PRESENT, SuppWindows.PRESENT_MENU, extras(SuppWindows::capturePresent, SuppWindows::applyPresent, TYPING_TICKS));
            claims.windowExtras(SuppWindows.TRAPPED_PRESENT, SuppWindows.TRAPPED_PRESENT_MENU,
                extras(SuppWindows::captureTrappedPresent, SuppWindows::applyTrappedPresent, 1));
            claims.windowExtras(SuppWindows.CANNON, SuppWindows.CANNON_MENU, extras(SuppWindows::captureCannon, SuppWindows::applyCannon, TYPING_TICKS));
            claims.windowExtras(SuppWindows.MERCHANT, SuppWindows.MERCHANT_MENU, extras(SuppWindows::captureMerchant, SuppWindows::applyMerchant, 1));
            claims.overlay(SuppHud.SLIMED, timedOverlay(SuppHud::captureSlimed, SuppHud::drawSlimed, 1));
            claims.overlay(SuppHud.SELECTABLE, overlay(SuppHud::captureSelectable, SuppHud::drawSelectable, 1));
            claims.overlay(SuppHud.CANNON, timedOverlay(SuppHud::captureCannon, SuppHud::drawCannon, DRAWING_TICKS));
            claims.overlay(InWorld.TRAJECTORY, overlay(InWorld::captureTrajectory, (graphics, state) -> InWorld.noteTrajectory(state), DRAWING_TICKS));
            claims.overlay(InWorld.OUTLINE, overlay(InWorld::captureOutline, (graphics, state) -> InWorld.noteOutline(state), 1));
        }
        if (this.amendments) {
            claims.menu(LecternBook.NAME, LecternBook.MENU, new ScreenPart() {
                @Override
                public byte @Nullable [] capture(Screen screen) {
                    return LecternBook.capture(screen);
                }

                @Override
                public @Nullable Screen build(byte[] state, int version) {
                    return LecternBook.build(state);
                }

                @Override
                public int @Nullable [] corner(Screen screen) {
                    // As the book's screen lays out its page
                    return new int[]{(screen.width - 192) / 2, 2};
                }

                @Override
                public int ticksBetweenWrites() {
                    return TYPING_TICKS;
                }
            });
        }
    }

    @Override
    public DisplayTest displayTest() {
        return new SuppDisplayTest(this.supplementaries, this.amendments);
    }

    // --- The parts, made from what each class gives ---

    /** A screen of Supplementaries' own: each is laid out from the screen's middle, so the pointer is kept as a share */
    private static ScreenPart screen(Function<Screen, byte[]> capture, Function<byte[], @Nullable Screen> build, int ticks) {
        return new ScreenPart() {
            @Override
            public byte @Nullable [] capture(Screen screen) {
                return capture.apply(screen);
            }

            @Override
            public @Nullable Screen build(byte[] state, int version) {
                return build.apply(state);
            }

            @Override
            public int @Nullable [] corner(Screen screen) {
                return null;
            }

            @Override
            public int ticksBetweenWrites() {
                return ticks;
            }
        };
    }

    private static OverlayPart overlay(Supplier<byte @Nullable []> capture, BiConsumer<GuiGraphics, byte[]> draw, int ticks) {
        return new OverlayPart() {
            @Override
            public byte @Nullable [] capture() {
                return capture.get();
            }

            @Override
            public void draw(GuiGraphics graphics, byte[] state, int version, float partialTick) {
                draw.accept(graphics, state);
            }

            @Override
            public int ticksBetweenWrites() {
                return ticks;
            }
        };
    }

    /** An overlay drawn with the moment between ticks, for one that fades or fills */
    private interface TimedDraw {
        void draw(GuiGraphics graphics, byte[] state, float partialTick);
    }

    private static OverlayPart timedOverlay(Supplier<byte @Nullable []> capture, TimedDraw draw, int ticks) {
        return new OverlayPart() {
            @Override
            public byte @Nullable [] capture() {
                return capture.get();
            }

            @Override
            public void draw(GuiGraphics graphics, byte[] state, int version, float partialTick) {
                draw.draw(graphics, state, partialTick);
            }

            @Override
            public int ticksBetweenWrites() {
                return ticks;
            }
        };
    }

    private static WindowExtras extras(Function<AbstractContainerScreen<?>, byte[]> capture, BiConsumer<AbstractContainerScreen<?>, byte[]> apply, int ticks) {
        return new WindowExtras() {
            @Override
            public byte @Nullable [] capture(AbstractContainerScreen<?> screen) {
                return capture.apply(screen);
            }

            @Override
            public void apply(AbstractContainerScreen<?> screen, byte[] state, int version) {
                apply.accept(screen, state);
            }

            @Override
            public int ticksBetweenWrites() {
                return ticks;
            }
        };
    }

}
