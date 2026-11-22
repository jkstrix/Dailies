package comedymonkey.dailiescompat.aeronautics.client;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * How the states keep what the mods draw: text as the game's own JSON for a component (so its colours, translations and
 * key names come back as they were), items by id, and block states as a command writes them.
 */
final class Texts {

    private Texts() {
    }

    private static HolderLookup.Provider registries() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level != null ? minecraft.level.registryAccess() : RegistryAccess.EMPTY;
    }

    static String json(Component text) {
        return Component.Serializer.toJson(text, registries());
    }

    static List<String> jsons(List<? extends Component> texts) {
        List<String> out = new ArrayList<>(texts.size());
        for (Component text : texts) {
            out.add(json(text));
        }
        return out;
    }

    static MutableComponent read(String json) {
        if (json == null || json.isEmpty()) {
            return Component.empty();
        }
        try {
            MutableComponent read = Component.Serializer.fromJson(json, registries());
            return read == null ? Component.empty() : read;
        } catch (RuntimeException unreadable) {
            return Component.literal(json);
        }
    }

    static List<Component> reads(List<String> jsons) {
        List<Component> out = new ArrayList<>(jsons.size());
        for (String json : jsons) {
            out.add(read(json));
        }
        return out;
    }

    /** An item's id, or empty for no item */
    static String item(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /** One of the item of this id, or nothing when the id is empty or no such item is in this game */
    static ItemStack stack(String id) {
        ResourceLocation key = id == null || id.isEmpty() ? null : ResourceLocation.tryParse(id);
        if (key == null) {
            return ItemStack.EMPTY;
        }
        return BuiltInRegistries.ITEM.getOptional(key).map(ItemStack::new).orElse(ItemStack.EMPTY);
    }

    static String block(BlockState state) {
        return BlockStateParser.serialize(state);
    }

    /** The block state a command would make of this, or null when this game has no such block */
    static @Nullable BlockState block(String written) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), written, false).blockState();
        } catch (CommandSyntaxException | RuntimeException unknown) {
            return null;
        }
    }

}
