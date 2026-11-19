package comedymonkey.dailiescompat.create.client;

import com.simibubi.create.content.contraptions.elevator.ElevatorContactScreen;
import com.simibubi.create.content.equipment.clipboard.ClipboardScreen;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencedGearshiftScreen;
import com.simibubi.create.content.redstone.thresholdSwitch.ThresholdSwitchScreen;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsScreen;
import comedymonkey.dailies.api.compat.Claims;
import comedymonkey.dailies.api.compat.DailiesCompat;
import comedymonkey.dailies.api.compat.DailiesCompats;
import comedymonkey.dailies.api.compat.DisplayTest;
import comedymonkey.dailies.api.compat.OverlayPart;
import comedymonkey.dailies.api.compat.ScreenPart;
import comedymonkey.dailies.api.compat.WindowExtras;
import comedymonkey.dailiescompat.create.CreateCompat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderTooltipEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The Create compat itself (specification CPT-6): the class that registers with Dailies, loaded only when both Dailies
 * and Create are (see {@link CreateCompat}). Create's play screens and overlays, in the owner's order:
 * <ul>
 * <li>every machine setting: the value settings board ({@link ValueBoard}), the value boxes on a block looked at
 * ({@link ValueBoxes}) and their tip ({@link ValueTip}), the sequenced gearshift, the threshold switch and the elevator
 * contact ({@link MachineScreens}), and extras for the filter windows and the windows with an address, which Dailies
 * draws itself ({@link FilterExtras});</li>
 * <li>the engineer's goggles' readout ({@link Goggles});</li>
 * <li>the clipboard ({@link ClipboardPart}), the backtank's remaining air ({@link RemainingAir}), and a train schedule's
 * entries as they are edited ({@link ScheduleExtras}).</li>
 * </ul>
 * Create's other windows the server opens (the linked controller, the toolbox, the schematic table and cannon, the
 * crafting blueprint, the stock keeper and the rest) are recorded and drawn by Dailies itself and need nothing here.
 * Each state is the least that draws its part again, and the whole of it; Dailies writes it when it changes and keeps
 * it for snapshots, jumps and trims.
 */
public final class CreateDailies implements DailiesCompat {

    public static final String ID = CreateCompat.ID + ":create";
    /** Raise when a state in States changes shape; the parts must still read every version before it */
    static final int VERSION = 1;
    /** Typing, and a value picked on the board, are written at most five times a second */
    private static final int TYPING_TICKS = 4;
    /** A board's value, the boxes, the goggles: at most ten times a second */
    private static final int SETTING_TICKS = 2;

    private CreateDailies() {
    }

    /** Registers the compat and its listeners. Only call it when Dailies and Create are both loaded */
    public static void register() {
        DailiesCompats.register(new CreateDailies());
        // After Create's own work each tick, so the value boxes it worked out in playback can be taken out again
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, ClientTickEvent.Post.class, ValueBoxes::afterTick);
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, RenderLevelStageEvent.class, ValueBoxes::drawWorld);
        // The goggles' layer is followed only when nothing has cancelled it, and the lines taken before anyone changes them
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, RenderGuiLayerEvent.Pre.class, Goggles::layerStarts);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, RenderGuiLayerEvent.Post.class, Goggles::layerEnds);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, RenderGuiEvent.Post.class, event -> Goggles.hudEnds());
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, RenderTooltipEvent.GatherComponents.class, Goggles::tooltip);
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
        // 1. Every machine setting
        claims.screen(ValueBoard.NAME, ValueSettingsScreen.class, screen(ValueBoard::capture, ValueBoard::build, SETTING_TICKS));
        claims.overlay(ValueBoxes.NAME, overlay(ValueBoxes::capture, (graphics, state) -> ValueBoxes.draw(state), SETTING_TICKS));
        claims.overlay(ValueTip.NAME, overlay(ValueTip::capture, ValueTip::draw, 1));
        claims.screen(MachineScreens.GEARSHIFT, SequencedGearshiftScreen.class,
            screen(MachineScreens::captureGearshift, MachineScreens::buildGearshift, SETTING_TICKS));
        claims.screen(MachineScreens.THRESHOLD, ThresholdSwitchScreen.class,
            screen(MachineScreens::captureThreshold, MachineScreens::buildThreshold, SETTING_TICKS));
        claims.screen(MachineScreens.ELEVATOR, ElevatorContactScreen.class,
            screen(MachineScreens::captureElevator, MachineScreens::buildElevator, TYPING_TICKS));
        claims.windowExtras(FilterExtras.FILTER, "create:filter", extras(FilterExtras::captureFilter, FilterExtras::applyFilter, 1));
        claims.windowExtras(FilterExtras.ATTRIBUTE_FILTER, "create:attribute_filter",
            extras(FilterExtras::captureAttributes, FilterExtras::applyAttributes, 1));
        for (String address : new String[]{FilterExtras.PACKAGE_FILTER, FilterExtras.PACKAGE_PORT, FilterExtras.REDSTONE_REQUESTER}) {
            claims.windowExtras(address, "create:" + address, extras(FilterExtras::captureAddress, FilterExtras::applyAddress, TYPING_TICKS));
        }
        // 2. The goggles
        claims.overlay(Goggles.NAME, overlay(Goggles::capture, Goggles::draw, SETTING_TICKS));
        // 3. The clipboard, the remaining air and the schedule
        claims.screen(ClipboardPart.NAME, ClipboardScreen.class, screen(ClipboardPart::capture, ClipboardPart::build, TYPING_TICKS));
        claims.overlay(RemainingAir.NAME, overlay(RemainingAir::capture, RemainingAir::draw, 1));
        claims.windowExtras(ScheduleExtras.NAME, "create:schedule", extras(ScheduleExtras::capture, ScheduleExtras::apply, TYPING_TICKS));
    }

    @Override
    public DisplayTest displayTest() {
        return new CreateDisplayTest();
    }

    // --- The parts, made from what each class gives ---

    /** Where a Create screen's window starts: every one of them lays itself out from Catnip's guiLeft and guiTop */
    static int[] corner(Screen screen) {
        return new int[]{Reflect.getInt(screen, "guiLeft"), Reflect.getInt(screen, "guiTop")};
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
            public int[] corner(Screen screen) {
                return CreateDailies.corner(screen);
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
