package comedymonkey.dailiescompat.create.client;

import com.simibubi.create.api.behaviour.display.DisplaySource;
import com.simibubi.create.api.registry.CreateBuiltInRegistries;
import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBehaviour;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelBlock.PanelSlot;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelPosition;
import com.simibubi.create.content.logistics.factoryBoard.FactoryPanelScreen;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkBlockEntity;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkScreen;
import com.simibubi.create.foundation.gui.ModularGuiLine;
import com.simibubi.create.foundation.gui.widget.ScrollInput;
import comedymonkey.dailiescompat.create.state.States;
import net.createmod.catnip.data.Couple;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Two of Create's logistics screens that only the player's game knows, each sending what was set when it closes: the
 * display link's (the information picked, its settings, the line it writes to) and the factory gauge's (the address,
 * when promises expire, how many it makes and asks for, whether it crafts). Each is recorded as the block and what is
 * being set; what the block is linked or connected to is read from the playback world, as the recording holds it, and
 * Create's own screen draws it, never sending anything.
 */
final class PanelScreens {

    static final String DISPLAY_LINK = "display_link";
    static final String FACTORY_PANEL = "factory_panel";

    private PanelScreens() {
    }

    // --- The display link ---

    static byte[] captureDisplayLink(Screen screen) {
        DisplayLinkBlockEntity be = Reflect.get(screen, "blockEntity");
        List<DisplaySource> sources = Reflect.get(screen, "sources");
        ScrollInput sourcePicked = Reflect.get(screen, "sourceTypeSelector");
        ScrollInput linePicked = Reflect.get(screen, "targetLineSelector");
        String source = "";
        if (sources != null && !sources.isEmpty()) {
            int picked = sourcePicked == null ? 0 : Math.max(0, Math.min(sources.size() - 1, sourcePicked.getState()));
            ResourceLocation id = CreateBuiltInRegistries.DISPLAY_SOURCE.getKey(sources.get(picked));
            source = id == null ? "" : id.toString();
        }
        // The settings as the screen would send them
        CompoundTag config = new CompoundTag();
        Couple<ModularGuiLine> widgets = Reflect.get(screen, "configWidgets");
        widgets.forEach(line -> line.saveValues(config));
        return States.displayLink(new States.DisplayLink(be.getBlockPos().asLong(), source, linePicked == null ? 0 : linePicked.getState(), tagBytes(config)));
    }

    /**
     * Built on a copy of the playback world's link, in no world's list of blocks, holding the information, settings and
     * line as set: the screen lays itself out from the link it is given, and the playback world's own is left alone
     */
    static @Nullable Screen buildDisplayLink(byte[] state) {
        States.DisplayLink recorded = States.readDisplayLink(state);
        ClientLevel level = Minecraft.getInstance().level;
        BlockPos pos = BlockPos.of(recorded.pos());
        if (level == null || !(level.getBlockEntity(pos) instanceof DisplayLinkBlockEntity live)) {
            return null;
        }
        BlockEntity made = live.getType().create(pos, live.getBlockState());
        if (!(made instanceof DisplayLinkBlockEntity copy)) {
            return null;
        }
        copy.loadWithComponents(live.saveWithoutMetadata(level.registryAccess()), level.registryAccess());
        copy.setLevel(level);
        ResourceLocation source = recorded.source().isEmpty() ? null : ResourceLocation.tryParse(recorded.source());
        copy.activeSource = source == null ? null : CreateBuiltInRegistries.DISPLAY_SOURCE.get(source);
        copy.targetLine = recorded.targetLine();
        copy.setSourceConfig(readTag(recorded.config()));
        return new RecordedDisplayLink(copy);
    }

    static final class RecordedDisplayLink extends DisplayLinkScreen implements RecordedScreen {
        RecordedDisplayLink(DisplayLinkBlockEntity be) {
            super(be);
        }

        @Override
        public void tick() {
        }

        @Override
        public void onClose() {
            // Never sends the settings
        }
    }

    // --- The factory gauge ---

    static byte[] captureFactoryPanel(Screen screen) {
        FactoryPanelBehaviour behaviour = Reflect.get(screen, "behaviour");
        FactoryPanelPosition at = behaviour.getPanelPosition();
        EditBox address = Reflect.get(screen, "addressBox");
        ScrollInput expiry = Reflect.get(screen, "promiseExpiration");
        BigItemStack output = Reflect.get(screen, "outputConfig");
        List<BigItemStack> inputs = Reflect.get(screen, "inputConfig");
        int[] counts = new int[inputs == null ? 0 : inputs.size()];
        for (int i = 0; i < counts.length; i++) {
            counts[i] = inputs.get(i).count;
        }
        boolean crafting = Reflect.get(screen, "craftingActive");
        return States.factoryPanel(new States.FactoryPanel(at.pos().asLong(), at.slot().name(), address == null ? "" : address.getValue(),
            expiry == null ? 0 : expiry.getState(), output == null ? 0 : output.count, counts, crafting));
    }

    /** Built on the playback world's gauge, only read, with what was being set put back on the screen */
    static @Nullable Screen buildFactoryPanel(byte[] state) {
        States.FactoryPanel recorded = States.readFactoryPanel(state);
        ClientLevel level = Minecraft.getInstance().level;
        PanelSlot slot = null;
        for (PanelSlot each : PanelSlot.values()) {
            if (each.name().equals(recorded.slot())) {
                slot = each;
            }
        }
        if (level == null || slot == null) {
            return null;
        }
        FactoryPanelBehaviour behaviour = FactoryPanelBehaviour.at(level, new FactoryPanelPosition(BlockPos.of(recorded.pos()), slot));
        return behaviour == null ? null : new RecordedFactoryPanel(behaviour, recorded);
    }

    static final class RecordedFactoryPanel extends FactoryPanelScreen implements RecordedScreen {
        private final States.FactoryPanel recorded;

        RecordedFactoryPanel(FactoryPanelBehaviour behaviour, States.FactoryPanel recorded) {
            super(behaviour);
            this.recorded = recorded;
            // Crafting shows only with a recipe to craft
            Reflect.setBoolean(this, "craftingActive", recorded.crafting() && Reflect.get(this, "craftingIngredients") != null);
        }

        @Override
        protected void init() {
            super.init();
            ((EditBox) Reflect.get(this, "addressBox")).setValue(this.recorded.address());
            ((ScrollInput) Reflect.get(this, "promiseExpiration")).setState(this.recorded.promiseExpiry());
            BigItemStack output = Reflect.get(this, "outputConfig");
            output.count = this.recorded.outputCount();
            List<BigItemStack> inputs = Reflect.get(this, "inputConfig");
            for (int i = 0; i < inputs.size() && i < this.recorded.inputCounts().length; i++) {
                inputs.get(i).count = this.recorded.inputCounts()[i];
            }
        }

        @Override
        public void tick() {
        }

        @Override
        public void removed() {
            // Never sends what was set
        }
    }

    // --- Tags as bytes ---

    private static byte[] tagBytes(CompoundTag tag) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            NbtIo.write(tag, out);
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
        return bytes.toByteArray();
    }

    private static CompoundTag readTag(byte[] bytes) {
        if (bytes.length == 0) {
            return new CompoundTag();
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            return NbtIo.read(in, NbtAccounter.unlimitedHeap());
        } catch (IOException damaged) {
            throw new UncheckedIOException(damaged);
        }
    }

}
