package comedymonkey.dailiescompat.create.client;

import com.simibubi.create.content.contraptions.elevator.ElevatorContactScreen;
import com.simibubi.create.content.decoration.slidingDoor.DoorControl;
import com.simibubi.create.content.kinetics.transmission.sequencer.Instruction;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencedGearshiftBlockEntity;
import com.simibubi.create.content.kinetics.transmission.sequencer.SequencedGearshiftScreen;
import com.simibubi.create.content.redstone.thresholdSwitch.ThresholdSwitchBlockEntity;
import com.simibubi.create.content.redstone.thresholdSwitch.ThresholdSwitchScreen;
import com.simibubi.create.foundation.gui.widget.ScrollInput;
import comedymonkey.dailiescompat.create.state.States;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

/**
 * Create's machine screens that only the player's game knows, where a machine is set: the sequenced gearshift's
 * instructions, the threshold switch's thresholds and an elevator contact's floor names. Each is recorded as what is being set, as it stands while the
 * player sets it, and drawn in playback with Create's own screen, which never sends anything.
 */
final class MachineScreens {

    static final String GEARSHIFT = "sequenced_gearshift";
    static final String THRESHOLD = "threshold_switch";
    static final String ELEVATOR = "elevator_contact";

    private static final ResourceLocation GEARSHIFT_ID = ResourceLocation.fromNamespaceAndPath("create", "sequenced_gearshift");

    private MachineScreens() {
    }

    // --- The sequenced gearshift ---

    /** The instructions as the screen holds them while they are set (Create's own way of keeping them, read back) */
    static byte[] captureGearshift(Screen screen) {
        Vector<Instruction> instructions = Reflect.get(screen, "instructions");
        ListTag saved = Instruction.serializeAll(instructions);
        List<States.Instruction> recorded = new ArrayList<>(saved.size());
        for (int i = 0; i < saved.size(); i++) {
            CompoundTag tag = saved.getCompound(i);
            recorded.add(new States.Instruction(tag.getString("Type"), tag.getString("Modifier"), tag.getInt("Value")));
        }
        return States.gearshift(new States.Gearshift(recorded));
    }

    /**
     * The screen is built on a sequenced gearshift of its own, made for it and in no world, holding the recorded
     * instructions: the playback world's gearshift may already hold the instructions sent when the screen closed
     */
    static @Nullable Screen buildGearshift(byte[] state) {
        States.Gearshift recorded = States.readGearshift(state);
        Block block = BuiltInRegistries.BLOCK.get(GEARSHIFT_ID);
        BlockEntityType<?> type = BuiltInRegistries.BLOCK_ENTITY_TYPE.get(GEARSHIFT_ID);
        if (type == null) {
            return null;
        }
        BlockEntity made = type.create(BlockPos.ZERO, block.defaultBlockState());
        if (!(made instanceof SequencedGearshiftBlockEntity gearshift)) {
            return null;
        }
        ListTag saved = new ListTag();
        for (States.Instruction instruction : recorded.instructions()) {
            CompoundTag tag = new CompoundTag();
            tag.putString("Type", instruction.type());
            tag.putString("Modifier", instruction.modifier());
            tag.putInt("Value", instruction.value());
            saved.add(tag);
        }
        Vector<Instruction> instructions = gearshift.getInstructions();
        instructions.clear();
        instructions.addAll(Instruction.deserializeAll(saved));
        return new RecordedGearshift(gearshift);
    }

    static final class RecordedGearshift extends SequencedGearshiftScreen implements RecordedScreen {
        RecordedGearshift(SequencedGearshiftBlockEntity gearshift) {
            super(gearshift);
        }

        @Override
        public void tick() {
        }

        @Override
        public void sendPacket() {
        }

        @Override
        public void removed() {
        }
    }

    // --- The threshold switch ---

    static byte[] captureThreshold(Screen screen) {
        ThresholdSwitchBlockEntity sw = Reflect.get(screen, "blockEntity");
        ScrollInput offBelow = Reflect.get(screen, "offBelow");
        ScrollInput onAbove = Reflect.get(screen, "onAbove");
        ScrollInput inStacks = Reflect.get(screen, "inStacks");
        return States.threshold(new States.Threshold(sw.getBlockPos().asLong(), offBelow.getState(), onAbove.getState(), inStacks.getState() == 1));
    }

    /**
     * The screen is built on the playback world's switch, as the recording holds it at this moment (what it watches,
     * how full that is, whether it is inverted and powered), with the thresholds and the unit as they were being set.
     * Only read: nothing is written to the switch, and the screen sends nothing.
     */
    static @Nullable Screen buildThreshold(byte[] state) {
        States.Threshold recorded = States.readThreshold(state);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !(minecraft.level.getBlockEntity(BlockPos.of(recorded.pos())) instanceof ThresholdSwitchBlockEntity sw)) {
            return null;
        }
        return new RecordedThreshold(sw, recorded);
    }

    static final class RecordedThreshold extends ThresholdSwitchScreen implements RecordedScreen {
        private final States.Threshold recorded;

        RecordedThreshold(ThresholdSwitchBlockEntity sw, States.Threshold recorded) {
            super(sw);
            this.recorded = recorded;
        }

        @Override
        protected void init() {
            super.init();
            // The unit first: it decides how the thresholds read. Set without their callbacks, which only ever count down
            // to sending the values
            ((ScrollInput) Reflect.get(this, "inStacks")).setState(this.recorded.inStacks() ? 1 : 0);
            ((ScrollInput) Reflect.get(this, "onAbove")).setState(this.recorded.onAbove());
            ((ScrollInput) Reflect.get(this, "offBelow")).setState(this.recorded.offBelow());
        }

        @Override
        public void tick() {
        }

        @Override
        protected void send(boolean invert) {
        }

        @Override
        public void removed() {
        }
    }

    // --- The elevator contact ---

    static byte[] captureElevator(Screen screen) {
        String shortName = Reflect.get(screen, "shortName");
        String longName = Reflect.get(screen, "longName");
        DoorControl doors = Reflect.get(screen, "doorControl");
        return States.elevator(new States.Elevator(shortName == null ? "" : shortName, longName == null ? "" : longName, doors == null ? "" : doors.name()));
    }

    /** Everything the screen shows is in its state: it is built as Create builds it, and sends only when confirmed */
    static Screen buildElevator(byte[] state) {
        States.Elevator recorded = States.readElevator(state);
        DoorControl doors = DoorControl.ALL;
        for (DoorControl each : DoorControl.values()) {
            if (each.name().equals(recorded.doorControl())) {
                doors = each;
            }
        }
        return new RecordedElevator(recorded.shortName(), recorded.longName(), doors);
    }

    static final class RecordedElevator extends ElevatorContactScreen implements RecordedScreen {
        RecordedElevator(String shortName, String longName, DoorControl doors) {
            super(BlockPos.ZERO, shortName, longName, doors);
        }

        @Override
        public void tick() {
        }
    }

}
