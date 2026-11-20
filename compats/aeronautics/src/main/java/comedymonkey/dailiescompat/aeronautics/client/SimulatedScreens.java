package comedymonkey.dailiescompat.aeronautics.client;

import comedymonkey.dailiescompat.aeronautics.state.AeroStates;
import dev.simulated_team.simulated.content.blocks.altitude_sensor.AltitudeSensorBlockEntity;
import dev.simulated_team.simulated.content.blocks.altitude_sensor.AltitudeSensorScreen;
import dev.simulated_team.simulated.content.blocks.nameplate.NameplateBlockEntity;
import dev.simulated_team.simulated.content.blocks.nameplate.NameplateScreen;
import dev.simulated_team.simulated.content.blocks.redstone.modulating_receiver.ModulatingLinkedReceiverBlockEntity;
import dev.simulated_team.simulated.content.blocks.redstone.modulating_receiver.ModulatingLinkedReceiverScreen;
import net.createmod.catnip.animation.LerpedFloat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.font.TextFieldHelper;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Simulated's screens that only the player's game knows, where a block is set: the altitude sensor's heights, the
 * modulating linked receiver's ranges and a nameplate's name. Each is recorded as what is being set, as it stands while
 * the player sets it, and drawn in playback with Simulated's own screen, built on a block entity of the compat's own made
 * for it and in no world (the playback world's block may already hold what was sent when the screen closed), and never
 * sending anything.
 */
final class SimulatedScreens {

    static final String ALTITUDE = "altitude_sensor";
    static final String RECEIVER = "modulating_linked_receiver";
    static final String NAMEPLATE = "nameplate";

    private static final ResourceLocation ALTITUDE_ID = ResourceLocation.fromNamespaceAndPath("simulated", "altitude_sensor");
    private static final ResourceLocation RECEIVER_ID = ResourceLocation.fromNamespaceAndPath("simulated", "modulating_linked_receiver");
    private static final ResourceLocation NAMEPLATE_TYPE = ResourceLocation.fromNamespaceAndPath("simulated", "nameplate");

    private SimulatedScreens() {
    }

    /** A block entity of this type and block state, made for a screen in playback and in no world */
    private static @Nullable BlockEntity made(ResourceLocation type, BlockPos pos, @Nullable BlockState state) {
        BlockEntityType<?> entityType = BuiltInRegistries.BLOCK_ENTITY_TYPE.get(type);
        if (entityType == null || state == null) {
            return null;
        }
        return entityType.create(pos, state);
    }

    // --- The altitude sensor ---

    static byte[] captureAltitude(Screen screen) {
        AltitudeSensorBlockEntity sensor = Reflect.get(screen, "blockEntity");
        LerpedFloat shownHigh = Reflect.get(screen, "visualHighSignal");
        LerpedFloat shownLow = Reflect.get(screen, "visualLowSignal");
        float high = Reflect.get(screen, "highSignal");
        float low = Reflect.get(screen, "lowSignal");
        int ticksOpen = Reflect.get(screen, "ticksOpen");
        boolean draggingLow = Reflect.get(screen, "draggingLeft");
        boolean draggingHigh = Reflect.get(screen, "draggingRight");
        return AeroStates.altitude(new AeroStates.Altitude(sensor.getBlockPos().asLong(), high, low, shownHigh.getValue(), shownLow.getValue(), ticksOpen,
            draggingLow, draggingHigh));
    }

    /**
     * Built on a sensor of the compat's own in the playback world, which only gives it the world's height to turn the
     * shares into heights by: the sensor is never placed in it
     */
    static @Nullable Screen buildAltitude(byte[] state) {
        AeroStates.Altitude recorded = AeroStates.readAltitude(state);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return null;
        }
        BlockState block = BuiltInRegistries.BLOCK.get(ALTITUDE_ID).defaultBlockState();
        if (!(made(ALTITUDE_ID, BlockPos.of(recorded.pos()), block) instanceof AltitudeSensorBlockEntity sensor)) {
            return null;
        }
        sensor.setLevel(minecraft.level);
        sensor.highSignal = recorded.high();
        sensor.lowSignal = recorded.low();
        RecordedAltitude screen = new RecordedAltitude(sensor);
        Reflect.set(screen, "highSignal", recorded.high());
        Reflect.set(screen, "lowSignal", recorded.low());
        Reflect.set(screen, "ticksOpen", recorded.ticksOpen());
        Reflect.set(screen, "draggingLeft", recorded.draggingLow());
        Reflect.set(screen, "draggingRight", recorded.draggingHigh());
        ((LerpedFloat) Reflect.get(screen, "visualHighSignal")).startWithValue(recorded.shownHigh());
        ((LerpedFloat) Reflect.get(screen, "visualLowSignal")).startWithValue(recorded.shownLow());
        return screen;
    }

    static final class RecordedAltitude extends AltitudeSensorScreen {
        RecordedAltitude(AltitudeSensorBlockEntity sensor) {
            super(sensor);
        }

        @Override
        public void tick() {
        }

        /** Closing sends the heights set: a screen drawn from a recording sends nothing */
        @Override
        public void onClose() {
        }
    }

    // --- The modulating linked receiver ---

    static byte[] captureReceiver(Screen screen) {
        ModulatingLinkedReceiverBlockEntity receiver = Reflect.get(screen, "be");
        double distance = Reflect.get(receiver, "clientDistance");
        return AeroStates.receiver(new AeroStates.Receiver(receiver.getBlockPos().asLong(), Texts.block(receiver.getBlockState()), receiver.minRange,
            receiver.maxRange, distance));
    }

    /** Built on a receiver of the compat's own holding the ranges as set, and the distance as the recorder's showed it */
    static @Nullable Screen buildReceiver(byte[] state) {
        AeroStates.Receiver recorded = AeroStates.readReceiver(state);
        if (!(made(RECEIVER_ID, BlockPos.of(recorded.pos()), Texts.block(recorded.block())) instanceof ModulatingLinkedReceiverBlockEntity receiver)) {
            return null;
        }
        receiver.minRange = recorded.minRange();
        receiver.maxRange = recorded.maxRange();
        Reflect.set(receiver, "clientDistance", recorded.distance());
        Reflect.set(receiver, "clientOldDistance", recorded.distance());
        return new RecordedReceiver(receiver);
    }

    static final class RecordedReceiver extends ModulatingLinkedReceiverScreen {
        RecordedReceiver(ModulatingLinkedReceiverBlockEntity receiver) {
            super(receiver);
        }

        @Override
        public void tick() {
        }

        @Override
        public void removed() {
        }

        /** Sends the ranges a second after they change: a screen drawn from a recording sends nothing */
        @Override
        protected void send() {
        }
    }

    // --- A nameplate ---

    static byte[] captureNameplate(Screen screen) {
        NameplateBlockEntity plate = Reflect.get(screen, "be");
        String name = Reflect.get(screen, "message");
        int frame = Reflect.get(screen, "frame");
        TextFieldHelper field = Reflect.get(screen, "nameField");
        boolean glowing = Reflect.get(plate, "glowing");
        return AeroStates.nameplate(new AeroStates.Nameplate(plate.getBlockPos().asLong(), Texts.block(plate.getBlockState()), plate.getTextColor().getName(),
            glowing, name == null ? "" : name, field == null ? 0 : field.getCursorPos(), field == null ? 0 : field.getSelectionPos(), frame));
    }

    /** Built on a nameplate of the compat's own, of the recorded colour, its writing as coloured and lit */
    static @Nullable Screen buildNameplate(byte[] state) {
        AeroStates.Nameplate recorded = AeroStates.readNameplate(state);
        if (!(made(NAMEPLATE_TYPE, BlockPos.of(recorded.pos()), Texts.block(recorded.block())) instanceof NameplateBlockEntity plate)) {
            return null;
        }
        Reflect.set(plate, "textColor", DyeColor.byName(recorded.textColour(), DyeColor.BLACK));
        Reflect.set(plate, "glowing", recorded.glowing());
        Reflect.set(plate, "name", recorded.name());
        return new RecordedNameplate(plate, recorded);
    }

    static final class RecordedNameplate extends NameplateScreen {
        private final AeroStates.Nameplate recorded;

        RecordedNameplate(NameplateBlockEntity plate, AeroStates.Nameplate recorded) {
            super(plate);
            this.recorded = recorded;
        }

        @Override
        protected void init() {
            super.init();
            Reflect.set(this, "message", this.recorded.name());
            Reflect.set(this, "frame", this.recorded.frame());
            TextFieldHelper field = Reflect.get(this, "nameField");
            if (field != null) {
                int length = this.recorded.name().length();
                field.setSelectionRange(Math.clamp(this.recorded.cursor(), 0, length), Math.clamp(this.recorded.selection(), 0, length));
            }
        }

        /** Counts its frames, and closes itself when the plate is out of reach: neither in playback */
        @Override
        public void tick() {
        }

        /** Sends the name as it closes: a screen drawn from a recording sends nothing */
        @Override
        public void removed() {
        }

        @Override
        public void onClose() {
        }
    }

}
