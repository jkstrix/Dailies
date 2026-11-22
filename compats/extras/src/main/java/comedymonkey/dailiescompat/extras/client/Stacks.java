package comedymonkey.dailiescompat.extras.client;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A window's items as the game saves them, every component and all, by the items' names rather than their numbers in
 * this game: a list of slots, the item on the pointer and, for a backpack, the backpack itself, in one compound written
 * as the game writes its saved data. The same items write the same bytes, so a window is written again only when what
 * it shows changes.
 */
final class Stacks {

    static final String SLOTS = "slots";
    static final String CARRIED = "carried";

    private Stacks() {
    }

    static Tag save(ItemStack stack) {
        return stack.isEmpty() ? new CompoundTag()
            : ItemStack.CODEC.encodeStart(Texts.registries().createSerializationContext(NbtOps.INSTANCE), stack).result().orElseGet(CompoundTag::new);
    }

    static ItemStack load(Tag tag) {
        return tag instanceof CompoundTag compound
            ? ItemStack.OPTIONAL_CODEC.parse(Texts.registries().createSerializationContext(NbtOps.INSTANCE), compound).result().orElse(ItemStack.EMPTY)
            : ItemStack.EMPTY;
    }

    static ListTag saveAll(List<ItemStack> stacks) {
        ListTag list = new ListTag();
        for (ItemStack stack : stacks) {
            list.add(save(stack));
        }
        return list;
    }

    static List<ItemStack> loadAll(CompoundTag from, String key) {
        ListTag list = from.getListOrEmpty(key);
        List<ItemStack> stacks = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            stacks.add(load(list.get(i)));
        }
        return stacks;
    }

    static byte[] bytes(CompoundTag tag) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            NbtIo.write(tag, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    static CompoundTag tag(byte[] bytes) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            return NbtIo.read(in, NbtAccounter.create(64L << 20));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
