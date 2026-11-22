package comedymonkey.dailiescompat.extras.client;

import com.blackgear.vanillabackport.client.level.gui.inventory.NautilusInventoryScreen;
import com.blackgear.vanillabackport.common.level.entities.mob.animal.nautilus.AbstractNautilus;
import com.blackgear.vanillabackport.common.level.inventory.NautilusInventoryMenu;
import com.mojang.authlib.GameProfile;
import comedymonkey.dailies.api.compat.Claims;
import comedymonkey.dailiescompat.extras.state.ExtrasStates;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * VanillaBackport's nautilus inventory, its saddle and armour slots beside the player's inventory, as a horse's is. The
 * server opens it with a message of VanillaBackport's own rather than the game's message for a window, so Dailies' window
 * recording never sees it; it is claimed by its screen and recorded whole: the nautilus by its id in the world, the size
 * of its own container, every slot and the item on the pointer. Drawn with VanillaBackport's own screen on the playback
 * world's nautilus, its menu belonging to a stand-in of the compat's own; the armour slot is the nautilus' own, which the
 * world already shows it wearing, so it is not filled again (as Dailies leaves a horse's armour).
 */
final class NautilusPart {

    static final String NAME = "nautilus_inventory";
    /** The armour slot, the nautilus' own */
    private static final int ARMOUR_SLOT = 1;
    private static final int ITEMS_TICKS = 2;
    private static final GameProfile OWNER = new GameProfile(UUID.nameUUIDFromBytes("Dailies nautilus".getBytes(StandardCharsets.UTF_8)), "Nautilus");

    private NautilusPart() {
    }

    static void claim(Claims claims) {
        claims.screen(NAME, NautilusInventoryScreen.class, Parts.screen(NautilusPart::capture, NautilusPart::build, ITEMS_TICKS));
    }

    static byte @Nullable [] capture(Screen screen) {
        if (!(screen instanceof NautilusInventoryScreen window)) {
            return null;
        }
        AbstractNautilus nautilus = Reflect.get(window, "nautilus");
        NautilusInventoryMenu menu = window.getMenu();
        if (nautilus == null || menu.slots.isEmpty()) {
            return null;
        }
        List<ItemStack> slots = new ArrayList<>(menu.slots.size());
        for (int i = 0; i < menu.slots.size(); i++) {
            slots.add(menu.getSlot(i).getItem());
        }
        CompoundTag items = new CompoundTag();
        items.put(Stacks.SLOTS, Stacks.saveAll(slots));
        items.put(Stacks.CARRIED, Stacks.save(menu.getCarried()));
        return ExtrasStates.nautilus(new ExtrasStates.Nautilus(nautilus.getId(), menu.getSlot(0).container.getContainerSize(),
            new ExtrasStates.Items(Stacks.bytes(items))));
    }

    static @Nullable Screen build(byte[] data) {
        ExtrasStates.Nautilus recorded = ExtrasStates.readNautilus(data);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !(minecraft.level.getEntity(recorded.entity()) instanceof AbstractNautilus nautilus)) {
            return null;
        }
        RemotePlayer owner = new RemotePlayer(minecraft.level, OWNER);
        Inventory inventory = owner.getInventory();
        NautilusInventoryMenu menu = new NautilusInventoryMenu(0, inventory, new SimpleContainer(Math.max(1, recorded.size())), nautilus);
        CompoundTag items = Stacks.tag(recorded.items().saved());
        List<ItemStack> slots = Stacks.loadAll(items, Stacks.SLOTS);
        for (int i = 0; i < menu.slots.size() && i < slots.size(); i++) {
            if (i != ARMOUR_SLOT) {
                menu.getSlot(i).set(slots.get(i));
            }
        }
        menu.setCarried(Stacks.load(items.get(Stacks.CARRIED)));
        return new RecordedNautilus(menu, inventory, nautilus);
    }

    /** VanillaBackport's nautilus screen for playback; it hands nothing back as it closes */
    static final class RecordedNautilus extends NautilusInventoryScreen {

        RecordedNautilus(NautilusInventoryMenu menu, Inventory inventory, AbstractNautilus nautilus) {
            super(menu, inventory, nautilus);
        }

        @Override
        public void removed() {
        }

        @Override
        public void onClose() {
        }
    }

}
