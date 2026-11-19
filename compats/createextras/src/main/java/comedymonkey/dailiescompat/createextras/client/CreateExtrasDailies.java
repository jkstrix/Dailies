package comedymonkey.dailiescompat.createextras.client;

import com.hlysine.create_connected.content.sequencedpulsegenerator.SequencedPulseGeneratorScreen;
import com.maxenonyme.createsubmarine.submarine.client.SonarScreen;
import com.railwayteam.railways.content.bogey_menu.BogeyMenuScreen;
import com.railwayteam.railways.content.cycle_menu.RadialTagCycleMenu;
import comedymonkey.dailies.api.compat.Claims;
import comedymonkey.dailies.api.compat.DailiesCompat;
import comedymonkey.dailies.api.compat.DailiesCompats;
import comedymonkey.dailies.api.compat.DisplayTest;
import comedymonkey.dailies.api.compat.OverlayPart;
import comedymonkey.dailies.api.compat.ScreenPart;
import comedymonkey.dailiescompat.createextras.CreateExtrasCompat;
import fr.iglee42.createcasing.items.recaser.RadialRecaserMenu;
import fr.iglee42.createcasing.screen.AutoClutchScreen;
import net.createmod.catnip.gui.AbstractSimiScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The compat for Create's other add-ons itself (specification CPT-11): the class that registers with Dailies, loaded only
 * when Dailies is, and at least one of the add-ons (see {@link CreateExtrasCompat}). What the add-ons draw during play,
 * from a sweep of every screen, HUD layer, drawing into the world and line above the hotbar in their jars:
 * <ul>
 * <li>Create Deep Seas: the sonar's screen, the PDA's message, the implosion's blackout, a boat engine's throttle held,
 * and a command sub's console as it is worked ({@link DeepSeasParts});</li>
 * <li>Create Connected: the sequenced pulse generator's screen ({@link ConnectedScreens});</li>
 * <li>Create Encased: an automatic clutch's screen and the recaser's wheel ({@link EncasedScreens});</li>
 * <li>Steam 'n' Rails: the bogey menu, the tag wheel and the switch on the train's bar ({@link RailsParts});</li>
 * <li>Create Jetpack: its controls beside the hotbar ({@link JetpackHud});</li>
 * <li>Create: Hypertubes: the ride's arrow, the escape bar and the placement outline ({@link HypertubeParts});</li>
 * <li>all of them, and Create: Tracks: the line put above the hotbar on the player's own game ({@link StatusLine}).</li>
 * </ul>
 * What needs nothing here: everything the add-ons outline through Catnip's outliner (a kinetic bridge's or a steel
 * cable's reach, a tube's curve, track switches and edge points, Steam 'n' Rails' glue), every value box, goggles line and
 * change to Create's own screens (Create Connected's extra gearshift instructions, Steam 'n' Rails' toolbox and station
 * changes), which the Create compat records with Create's own; the add-ons' server windows (Create Deep Seas' electrolyzer
 * and boat engine, Steam 'n' Rails' conductor toolbox and workbench cart), which Dailies draws itself; and lines the
 * servers send, which arrive in packets. Left out: configuration, notices and debug drawing, which are not play
 * (Create: Tracks' render tuning screen is a permission-gated tool that writes its config, so it is one of them); how the
 * world looks and feels, which playback draws from its own world; the highlight round a block or tube looked at, as the
 * game's own highlight is.
 * <p>
 * Each state is the least that draws its part again, and the whole of it; Dailies writes it when it changes and keeps it
 * for snapshots, jumps and trims.
 */
public final class CreateExtrasDailies implements DailiesCompat {

    public static final String ID = CreateExtrasCompat.ID + ":createextras";
    /** Raise when a state in ExtrasStates changes shape; the parts must still read every version before it */
    static final int VERSION = 1;
    /** Typing, and a value dragged on a screen, are written at most five times a second */
    private static final int TYPING_TICKS = 4;
    /** An overlay that moves with the mouse or slides: at most ten times a second */
    private static final int MOVING_TICKS = 2;

    /** The wheels, laid out round the middle of the screen */
    private static final Set<String> WHEELS = Set.of("fr.iglee42.createcasing.items.recaser.RadialRecaserMenu",
        "com.railwayteam.railways.content.cycle_menu.RadialTagCycleMenu");

    private final Mods mods;

    /** Which of the add-ons are loaded */
    public record Mods(boolean deepSeas, boolean connected, boolean encased, boolean tracks, boolean rails, boolean jetpack, boolean hypertube) {

        public boolean any() {
            return this.deepSeas || this.connected || this.encased || this.tracks || this.rails || this.jetpack || this.hypertube;
        }
    }

    private CreateExtrasDailies(Mods mods) {
        this.mods = mods;
    }

    /** Registers the compat and its listeners, with the parts of the add-ons that are loaded. Only call it when Dailies is loaded */
    public static void register(Mods mods) {
        DailiesCompats.register(new CreateExtrasDailies(mods));
        if (mods.deepSeas() || mods.hypertube()) {
            NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, RenderFrameEvent.Pre.class, InWorld::frameStarts);
            // After the add-ons' own listeners: Create Deep Seas works out the console under the camera as the sky is drawn
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, RenderLevelStageEvent.class,
                event -> InWorld.draw(event, mods.deepSeas(), mods.hypertube()));
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
        // The line above the hotbar, which all the add-ons put up
        claims.overlay(StatusLine.NAME, timedOverlay(StatusLine::capture, StatusLine::draw, 1));
        if (this.mods.deepSeas()) {
            claims.screen(DeepSeasParts.SONAR, SonarScreen.class, screen(DeepSeasParts::captureSonar, DeepSeasParts::buildSonar, MOVING_TICKS));
            claims.overlay(DeepSeasParts.PDA, overlay(DeepSeasParts::capturePda, Page::draw, MOVING_TICKS));
            claims.overlay(DeepSeasParts.IMPLOSION, overlay(DeepSeasParts::captureImplosion, Page::draw, 1));
            claims.overlay(DeepSeasParts.THROTTLE, overlay(DeepSeasParts::captureThrottle, Page::draw, MOVING_TICKS));
            claims.overlay(DeepSeasParts.CONSOLE, overlay(DeepSeasParts::captureConsole, (graphics, state) -> DeepSeasParts.noteConsole(state), MOVING_TICKS));
        }
        if (this.mods.connected()) {
            claims.screen(ConnectedScreens.PULSE, SequencedPulseGeneratorScreen.class,
                screen(ConnectedScreens::capturePulse, ConnectedScreens::buildPulse, 1));
        }
        if (this.mods.encased()) {
            claims.screen(EncasedScreens.CLUTCH, AutoClutchScreen.class, screen(EncasedScreens::captureClutch, EncasedScreens::buildClutch, TYPING_TICKS));
            claims.screen(EncasedScreens.RECASER, RadialRecaserMenu.class, screen(EncasedScreens::captureRecaser, EncasedScreens::buildRecaser, 1));
        }
        if (this.mods.rails()) {
            claims.screen(RailsParts.BOGEYS, BogeyMenuScreen.class, screen(RailsParts::captureBogeys, RailsParts::buildBogeys, 1));
            claims.screen(RailsParts.TAG_WHEEL, RadialTagCycleMenu.class, screen(RailsParts::captureTagWheel, RailsParts::buildTagWheel, 1));
            claims.overlay(RailsParts.SWITCH, overlay(RailsParts::captureSwitch, Page::draw, MOVING_TICKS));
        }
        if (this.mods.jetpack()) {
            claims.overlay(JetpackHud.NAME, overlay(JetpackHud::capture, Page::draw, 1));
        }
        if (this.mods.hypertube()) {
            claims.overlay(HypertubeParts.ARROW, overlay(HypertubeParts::captureArrow, HypertubeParts::drawArrow, 1));
            claims.overlay(HypertubeParts.ESCAPE, overlay(HypertubeParts::captureEscape, Page::draw, 1));
            claims.overlay(HypertubeParts.OUTLINE, overlay(HypertubeParts::captureOutline, (graphics, state) -> HypertubeParts.noteOutline(state), 1));
        }
    }

    @Override
    public DisplayTest displayTest() {
        return new CreateExtrasDisplayTest(this.mods);
    }

    // --- The parts, made from what each class gives ---

    /**
     * Where a screen's window starts: each one built on Catnip's lays itself out from its guiLeft and guiTop; a wheel or a
     * screen of the game's own kind keeps the pointer as a share of the screen
     */
    static int @Nullable [] corner(Screen screen) {
        if (screen instanceof AbstractContainerScreen<?> container) {
            return new int[]{container.getGuiLeft(), container.getGuiTop()};
        }
        // By name, so a game without one of the wheels' mods never looks for its class
        if (screen instanceof AbstractSimiScreen && !WHEELS.contains(screen.getClass().getName())) {
            int left = Reflect.get(screen, "guiLeft");
            int top = Reflect.get(screen, "guiTop");
            return new int[]{left, top};
        }
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
                return CreateExtrasDailies.corner(screen);
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

}
