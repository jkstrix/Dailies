package comedymonkey.dailiescompat.aeronautics.client;

import comedymonkey.dailies.api.compat.Claims;
import comedymonkey.dailies.api.compat.DailiesCompat;
import comedymonkey.dailies.api.compat.DailiesCompats;
import comedymonkey.dailies.api.compat.DisplayTest;
import comedymonkey.dailies.api.compat.OverlayPart;
import comedymonkey.dailies.api.compat.ScreenPart;
import comedymonkey.dailies.api.compat.WindowExtras;
import comedymonkey.dailiescompat.aeronautics.AeronauticsCompat;
import dev.simulated_team.simulated.content.blocks.altitude_sensor.AltitudeSensorScreen;
import dev.simulated_team.simulated.content.blocks.nameplate.NameplateScreen;
import dev.simulated_team.simulated.content.blocks.redstone.modulating_receiver.ModulatingLinkedReceiverScreen;
import dev.simulated_team.simulated.content.entities.diagram.screen.DiagramScreen;
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
 * The Create Aeronautics compat itself (specification CPT-9): the class that registers with Dailies, loaded only when
 * Dailies is, and Simulated or Simulated Coasters (see {@link AeronauticsCompat}). What the mods draw during play, from a
 * sweep of every screen, HUD layer, drawing into the world and line above the hotbar in their jars:
 * <ul>
 * <li>Simulated's screens only the player's game knows: the altitude sensor, the modulating linked receiver and a
 * nameplate ({@link SimulatedScreens}), and the contraption diagram ({@link DiagramPart}); and extras for the linked
 * typewriter's window, which the server opens and Dailies draws itself: its keys as set, the list of keys and a key's
 * entry ({@link TypewriterExtras});</li>
 * <li>Simulated's overlays: the typewriter's binding, and the readout of a lever or wheel held (the physics assembler's
 * lever, the steering wheel, the throttle lever) ({@link SimulatedHud}); the physics staff's locks in the world
 * ({@link StaffLocks});</li>
 * <li>the line the mods put above the hotbar on the player's own game ({@link StatusLine});</li>
 * <li>Simulated Coasters' row of track and row of hints ({@link CoastersHud}) and a track's handles in the world
 * ({@link BezierHandles}).</li>
 * </ul>
 * What needs nothing here: everything the mods outline through Catnip's outliner (glue, rope, springs, the staff's block,
 * a coaster track's placement preview, carts, chains, rivets, Create Propulsion's auto glue) and every value box and its
 * tip, goggles readout and display link setting they add to Create's, which the Create compat records with Create's;
 * Simulated's server window, the typewriter, which Dailies draws itself; and lines the servers send, which arrive in
 * packets. Left out: configuration screens, Ponder, creative tabs and debug drawing, which are not play; how the world
 * looks (balloons' heat, levitite, rope, lasers, plumes, a cart's glue and coupling, chain lifts, the physics staff's
 * beam), which playback draws from its own world; the highlight round a coaster curve looked at, as the game's own
 * highlight round a block is; Gyroscope Stabilizers' joystick, its screen and its readout, which the pack's build of the
 * mod has taken out (its block, menu and HUD layer are never registered); and Simulated Coasters' notice for a build
 * not meant for the player, which 0.1.5 never opens.
 * <p>
 * Each state is the least that draws its part again, and the whole of it; Dailies writes it when it changes and keeps it
 * for snapshots, jumps and trims.
 */
public final class AeronauticsDailies implements DailiesCompat {

    public static final String ID = AeronauticsCompat.ID + ":aeronautics";
    /** Raise when a state in AeroStates changes shape; the parts must still read every version before it */
    static final int VERSION = 1;
    /** Typing, and a value dragged on a screen, are written at most five times a second */
    private static final int TYPING_TICKS = 4;
    /** A diagram's forces arrive every half second; a readout moves with the mouse: at most ten times a second */
    private static final int SETTING_TICKS = 2;

    private final boolean simulated;
    private final boolean coasters;

    private AeronauticsDailies(boolean simulated, boolean coasters) {
        this.simulated = simulated;
        this.coasters = coasters;
    }

    /** Registers the compat and its listeners, with the parts of the mods that are loaded. Only call it when Dailies is loaded */
    public static void register(boolean simulated, boolean coasters) {
        DailiesCompats.register(new AeronauticsDailies(simulated, coasters));
        HandleMarks.on = coasters;
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, RenderFrameEvent.Pre.class, InWorld::frameStarts);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, RenderLevelStageEvent.class, event -> InWorld.draw(event, simulated, coasters));
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
        // The line above the hotbar, which all the mods put up
        claims.overlay(StatusLine.NAME, timedOverlay(StatusLine::capture, StatusLine::draw, 1));
        if (this.simulated) {
            claims.screen(SimulatedScreens.ALTITUDE, AltitudeSensorScreen.class, screen(SimulatedScreens::captureAltitude, SimulatedScreens::buildAltitude, 1));
            claims.screen(SimulatedScreens.RECEIVER, ModulatingLinkedReceiverScreen.class,
                screen(SimulatedScreens::captureReceiver, SimulatedScreens::buildReceiver, SETTING_TICKS));
            claims.screen(SimulatedScreens.NAMEPLATE, NameplateScreen.class, screen(SimulatedScreens::captureNameplate, SimulatedScreens::buildNameplate,
                TYPING_TICKS));
            claims.screen(DiagramPart.NAME, DiagramScreen.class, screen(DiagramPart::capture, DiagramPart::build, SETTING_TICKS));
            claims.windowExtras(TypewriterExtras.NAME, TypewriterExtras.MENU, extras(TypewriterExtras::capture, TypewriterExtras::apply, 1));
            claims.overlay(SimulatedHud.BINDING, overlay(SimulatedHud::captureBinding, SimulatedHud::drawBinding, 1));
            claims.overlay(SimulatedHud.LEVER, overlay(SimulatedHud::captureLever, SimulatedHud::drawLever, 1));
            claims.overlay(StaffLocks.NAME, overlay(StaffLocks::capture, (graphics, state) -> StaffLocks.note(state), 1));
        }
        if (this.coasters) {
            claims.overlay(CoastersHud.TRACK_ROW, overlay(CoastersHud::captureTrackRow, CoastersHud::drawTrackRow, 1));
            claims.overlay(CoastersHud.HINTS, overlay(CoastersHud::captureHints, CoastersHud::drawHints, SETTING_TICKS));
            claims.overlay(BezierHandles.NAME, overlay(BezierHandles::capture, (graphics, state) -> BezierHandles.note(state), 1));
        }
    }

    @Override
    public DisplayTest displayTest() {
        return new AeronauticsDisplayTest(this.simulated, this.coasters);
    }

    // --- The parts, made from what each class gives ---

    /** Where a Simulated screen's window starts: each one built on Catnip's lays itself out from its guiLeft and guiTop */
    static int @Nullable [] corner(Screen screen) {
        if (screen instanceof AbstractContainerScreen<?> container) {
            return new int[]{container.getGuiLeft(), container.getGuiTop()};
        }
        if (screen instanceof net.createmod.catnip.gui.AbstractSimiScreen) {
            int left = Reflect.get(screen, "guiLeft");
            int top = Reflect.get(screen, "guiTop");
            return new int[]{left, top};
        }
        // A nameplate's screen is the game's own kind, laid out from the screen's middle: the pointer is kept as a share
        return null;
    }

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
                return AeronauticsDailies.corner(screen);
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

    /** An overlay drawn with the moment between ticks, for one that fades */
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
