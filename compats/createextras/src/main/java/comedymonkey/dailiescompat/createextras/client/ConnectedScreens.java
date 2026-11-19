package comedymonkey.dailiescompat.createextras.client;

import com.hlysine.create_connected.content.sequencedpulsegenerator.SequencedPulseGeneratorBlockEntity;
import com.hlysine.create_connected.content.sequencedpulsegenerator.SequencedPulseGeneratorScreen;
import com.hlysine.create_connected.content.sequencedpulsegenerator.instructions.Instruction;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import comedymonkey.dailiescompat.createextras.state.ExtrasStates;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;

import java.util.Vector;

/**
 * Create Connected's one screen only the player's game knows: the sequenced pulse generator's, where its instructions are
 * set. Recorded as the instructions stand while the player sets them (the screen edits them in place, and sends them only
 * as it closes), in Create Connected's own saved form; drawn in playback with Create Connected's own screen, built on a
 * generator of the compat's own made for it and in no world (the playback world's generator may already hold what was
 * sent when the screen closed), which never sends anything.
 */
final class ConnectedScreens {

    static final String PULSE = "sequenced_pulse_generator";

    private static final ResourceLocation PULSE_ID = ResourceLocation.fromNamespaceAndPath("create_connected", "sequenced_pulse_generator");

    private ConnectedScreens() {
    }

    static byte[] capturePulse(Screen screen) {
        SequencedPulseGeneratorBlockEntity generator = Reflect.get(screen, "be");
        Vector<Instruction> instructions = Reflect.get(screen, "instructions");
        return ExtrasStates.pulse(new ExtrasStates.Pulse(generator.getBlockPos().asLong(), Texts.block(generator.getBlockState()),
            Instruction.serializeAll(instructions).toString()));
    }

    static @Nullable Screen buildPulse(byte[] state) {
        ExtrasStates.Pulse recorded = ExtrasStates.readPulse(state);
        BlockEntityType<?> type = BuiltInRegistries.BLOCK_ENTITY_TYPE.get(PULSE_ID);
        BlockState block = Texts.block(recorded.block());
        if (type == null || block == null) {
            return null;
        }
        BlockEntity made = type.create(BlockPos.of(recorded.pos()), block);
        if (!(made instanceof SequencedPulseGeneratorBlockEntity generator)) {
            return null;
        }
        ListTag saved = list(recorded.instructions());
        Vector<Instruction> instructions = Reflect.get(generator, "instructions");
        instructions.clear();
        instructions.addAll(Instruction.deserializeAll(saved));
        return new RecordedPulse(generator);
    }

    /** A list of tags as a command writes it, read back; empty when it cannot be read */
    private static ListTag list(String written) {
        try {
            CompoundTag holder = TagParser.parseTag("{l:" + written + "}");
            Tag list = holder.get("l");
            return list instanceof ListTag tags ? tags : new ListTag();
        } catch (CommandSyntaxException unreadable) {
            return new ListTag();
        }
    }

    static final class RecordedPulse extends SequencedPulseGeneratorScreen {
        RecordedPulse(SequencedPulseGeneratorBlockEntity generator) {
            super(generator);
        }

        @Override
        public void tick() {
        }

        /** Sends the instructions to the server: a screen drawn from a recording sends nothing */
        @Override
        public void sendPacket() {
        }

        @Override
        public void removed() {
        }
    }

}
