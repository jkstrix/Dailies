package comedymonkey.dailiescompat.supplementaries.client;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * How the states keep what the mods draw: text as the game's own JSON for a component (so its colours, translations and
 * key names come back as they were), items and a merchant's offers as the game's own codecs write them (components and
 * all), a block entity's saved data as the game writes it to disk, and block states as a command writes them.
 */
final class Texts {

    private Texts() {
    }

    private static RegistryAccess registries() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level != null ? minecraft.level.registryAccess() : RegistryAccess.EMPTY;
    }

    static HolderLookup.Provider lookup() {
        return registries();
    }

    static String json(Component text) {
        return Component.Serializer.toJson(text, registries());
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

    /** Something written with one of the game's codecs that need the registries, as bytes */
    static <T> byte[] encode(StreamCodec<RegistryFriendlyByteBuf, T> codec, T value) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries());
        try {
            codec.encode(buffer, value);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    /** The same read back; null when this game cannot read it (an item of a mod not installed, say) */
    static <T> @Nullable T decode(StreamCodec<RegistryFriendlyByteBuf, T> codec, byte[] bytes) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes), registries());
        try {
            return codec.decode(buffer);
        } catch (RuntimeException unreadable) {
            return null;
        } finally {
            buffer.release();
        }
    }

    /** An item with its components; an empty item is kept too */
    static byte[] item(ItemStack stack) {
        return encode(ItemStack.OPTIONAL_STREAM_CODEC, stack == null ? ItemStack.EMPTY : stack);
    }

    static ItemStack stack(byte[] bytes) {
        ItemStack stack = bytes.length == 0 ? null : decode(ItemStack.OPTIONAL_STREAM_CODEC, bytes);
        return stack == null ? ItemStack.EMPTY : stack;
    }

    /** A block entity's saved data, as the game writes it to disk */
    static byte[] nbt(CompoundTag tag) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            NbtIo.write(tag, out);
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
        return bytes.toByteArray();
    }

    static CompoundTag nbt(byte[] bytes) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            return NbtIo.read(in, NbtAccounter.create(16L << 20));
        } catch (IOException | RuntimeException unreadable) {
            return new CompoundTag();
        }
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
