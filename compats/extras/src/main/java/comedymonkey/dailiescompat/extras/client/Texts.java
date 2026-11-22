package comedymonkey.dailiescompat.extras.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * How the states keep what the mods draw: text as the game's own JSON for a component (so its colours, translations and
 * key names come back as they were), and block states as a command writes them.
 */
final class Texts {

    private Texts() {
    }

    static HolderLookup.Provider registries() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level != null ? minecraft.level.registryAccess() : RegistryAccess.EMPTY;
    }

    private static RegistryOps<JsonElement> ops() {
        return registries().createSerializationContext(JsonOps.INSTANCE);
    }

    static String json(Component text) {
        return ComponentSerialization.CODEC.encodeStart(ops(), text).result().map(JsonElement::toString).orElse("");
    }

    static MutableComponent read(String json) {
        if (json == null || json.isEmpty()) {
            return Component.empty();
        }
        try {
            Component read = ComponentSerialization.CODEC.parse(ops(), JsonParser.parseString(json)).result().orElse(null);
            return read == null ? Component.literal(json) : read.copy();
        } catch (RuntimeException unreadable) {
            return Component.literal(json);
        }
    }

    static String block(BlockState state) {
        return BlockStateParser.serialize(state);
    }

    /** The block state a command would make of this, or null when this game has no such block */
    static @Nullable BlockState block(String written) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, written, false).blockState();
        } catch (CommandSyntaxException | RuntimeException unknown) {
            return null;
        }
    }

}
