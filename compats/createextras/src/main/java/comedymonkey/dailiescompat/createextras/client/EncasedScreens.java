package comedymonkey.dailiescompat.createextras.client;

import com.simibubi.create.foundation.gui.widget.ScrollInput;
import comedymonkey.dailiescompat.createextras.state.ExtrasStates;
import fr.iglee42.createcasing.blockEntities.AutoClutchBlockEntity;
import fr.iglee42.createcasing.items.recaser.RadialRecaserMenu;
import fr.iglee42.createcasing.screen.AutoClutchScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Create Encased's screens only the player's game knows:
 * <ul>
 * <li>an automatic clutch's settings ({@link #CLUTCH}): the mode and the value as they are set (sent only as the screen
 * closes), drawn with Create Encased's own screen on a clutch of the compat's own made for it and in no world, which never
 * sends anything;</li>
 * <li>the recaser's wheel ({@link #RECASER}), a selection wheel used in play like Create's toolbox wheel: the casings
 * offered round it as the recorder's inventory gave them, the one picked and how far it had grown in; drawn with Create
 * Encased's own wheel, held by a screen of the compat's that never ticks it, closes it or hands it a key, so the casing it
 * sends as it closes is never sent.</li>
 * </ul>
 */
final class EncasedScreens {

    static final String CLUTCH = "automatic_clutch";
    static final String RECASER = "recaser_wheel";

    private EncasedScreens() {
    }

    // --- An automatic clutch ---

    static byte[] captureClutch(Screen screen) {
        AutoClutchBlockEntity clutch = Reflect.get(screen, "be");
        ScrollInput mode = Reflect.get(screen, "scrollInput");
        ScrollInput value = Reflect.get(screen, "maxStressWidget");
        return ExtrasStates.clutch(new ExtrasStates.Clutch(clutch.getBlockPos().asLong(), Texts.block(clutch.getBlockState()),
            mode == null ? clutch.getMode().ordinal() : mode.getState(), value == null ? clutch.getConfiguredValue() : value.getState(),
            clutch.getOperation() == null ? 0 : clutch.getOperation().ordinal()));
    }

    /** Built on a clutch of the compat's own of the recorded block, holding the mode, value and operation as they were set */
    static @Nullable Screen buildClutch(byte[] state) {
        ExtrasStates.Clutch recorded = ExtrasStates.readClutch(state);
        BlockState block = Texts.block(recorded.block());
        if (block == null) {
            return null;
        }
        // Made as its block makes one: each casing's clutch is a block of its own
        BlockEntity made = block.getBlock() instanceof EntityBlock maker ? maker.newBlockEntity(BlockPos.of(recorded.pos()), block) : null;
        if (!(made instanceof AutoClutchBlockEntity clutch)) {
            return null;
        }
        AutoClutchBlockEntity.Mode[] modes = AutoClutchBlockEntity.Mode.values();
        AutoClutchBlockEntity.Operation[] operations = AutoClutchBlockEntity.Operation.values();
        clutch.setMode(modes[Math.clamp(recorded.mode(), 0, modes.length - 1)]);
        clutch.setOperation(operations[Math.clamp(recorded.operation(), 0, operations.length - 1)]);
        clutch.setConfiguredValue(recorded.value());
        return new RecordedClutch(clutch);
    }

    static final class RecordedClutch extends AutoClutchScreen {
        RecordedClutch(AutoClutchBlockEntity clutch) {
            super(clutch);
        }

        @Override
        public void tick() {
        }

        /** Sends the settings to the server: a screen drawn from a recording sends nothing */
        @Override
        public void removed() {
        }
    }

    // --- The recaser's wheel ---

    static byte[] captureRecaser(Screen screen) {
        ItemStack held = Reflect.get(screen, "stack");
        List<Item> items = Reflect.get(screen, "allItems");
        int picked = Reflect.get(screen, "selectedItemIndex");
        int ticksOpen = Reflect.get(screen, "ticksOpen");
        List<String> ids = new ArrayList<>(items == null ? 0 : items.size());
        if (items != null) {
            for (Item item : items) {
                ids.add(BuiltInRegistries.ITEM.getKey(item).toString());
            }
        }
        return ExtrasStates.wheel(new ExtrasStates.Wheel(Texts.item(held), ids, picked, ticksOpen, false, 0, ""));
    }

    /**
     * Create Encased's own wheel, made as the recaser makes it (its constructor is its own), with the casings offered as the
     * recorder's inventory gave them, not as the watcher's does
     */
    static @Nullable Screen buildRecaser(byte[] state) {
        ExtrasStates.Wheel recorded = ExtrasStates.readWheel(state);
        ItemStack held = Texts.stack(recorded.subject());
        if (held.isEmpty()) {
            return null;
        }
        RadialRecaserMenu wheel = Reflect.make(RadialRecaserMenu.class, new Class<?>[]{ItemStack.class}, held);
        List<Item> items = new ArrayList<>();
        for (String id : recorded.items()) {
            ItemStack stack = Texts.stack(id);
            if (!stack.isEmpty()) {
                items.add(stack.getItem());
            }
        }
        Reflect.set(wheel, "allItems", items);
        Reflect.set(wheel, "selectedItemIndex", recorded.picked());
        Reflect.set(wheel, "ticksOpen", recorded.ticksOpen());
        return new Held(wheel);
    }

}
