package comedymonkey.dailies.windows;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffers;
import net.neoforged.neoforge.network.connection.ConnectionType;

/**
 * The game's own bytes for what a window record keeps without understanding it: item stacks, the window's title and a
 * villager's offers. Written as the server sends them, so they read back exactly. Client thread.
 */
final class WindowCodec {

    private final RegistryAccess registries;
    private final ConnectionType connectionType;
    private final RegistryFriendlyByteBuf scratch;

    WindowCodec(RegistryAccess registries, ConnectionType connectionType) {
        this.registries = registries;
        this.connectionType = connectionType;
        this.scratch = new RegistryFriendlyByteBuf(Unpooled.buffer(256), registries, connectionType);
    }

    private byte[] taken() {
        byte[] bytes = new byte[this.scratch.readableBytes()];
        this.scratch.readBytes(bytes);
        return bytes;
    }

    private RegistryFriendlyByteBuf reading(byte[] bytes) {
        return new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes), this.registries, this.connectionType);
    }

    /** No bytes for an empty stack, and for one the game itself cannot put on the wire */
    byte[] stack(ItemStack stack) {
        if (stack.isEmpty()) {
            return WindowState.NONE;
        }
        this.scratch.clear();
        try {
            ItemStack.OPTIONAL_STREAM_CODEC.encode(this.scratch, stack);
        } catch (RuntimeException unwritable) {
            return WindowState.NONE;
        }
        return this.taken();
    }

    /** An item this game does not have reads as an empty slot (PLAY-5) */
    ItemStack stack(byte[] bytes) {
        if (bytes.length == 0) {
            return ItemStack.EMPTY;
        }
        try {
            return ItemStack.OPTIONAL_STREAM_CODEC.decode(this.reading(bytes));
        } catch (RuntimeException unreadable) {
            return ItemStack.EMPTY;
        }
    }

    byte[] title(Component title) {
        this.scratch.clear();
        try {
            ComponentSerialization.TRUSTED_STREAM_CODEC.encode(this.scratch, title);
        } catch (RuntimeException unwritable) {
            this.scratch.clear();
            ComponentSerialization.TRUSTED_STREAM_CODEC.encode(this.scratch, Component.literal(title.getString()));
        }
        return this.taken();
    }

    Component title(byte[] bytes) {
        if (bytes.length == 0) {
            return Component.empty();
        }
        try {
            return ComponentSerialization.TRUSTED_STREAM_CODEC.decode(this.reading(bytes));
        } catch (RuntimeException unreadable) {
            return Component.empty();
        }
    }

    /** A villager's offers, its level and experience, and whether it shows its progress and can restock, as the server's packet has them */
    byte[] offers(MerchantMenu menu) {
        this.scratch.clear();
        MerchantOffers.STREAM_CODEC.encode(this.scratch, menu.getOffers());
        this.scratch.writeVarInt(menu.getTraderLevel());
        this.scratch.writeVarInt(menu.getTraderXp());
        this.scratch.writeBoolean(menu.showProgressBar());
        this.scratch.writeBoolean(menu.canRestock());
        return this.taken();
    }

    void putOffers(byte[] bytes, MerchantMenu menu) {
        RegistryFriendlyByteBuf in = this.reading(bytes);
        menu.setOffers(MerchantOffers.STREAM_CODEC.decode(in));
        menu.setMerchantLevel(in.readVarInt());
        menu.setXp(in.readVarInt());
        menu.setShowProgressBar(in.readBoolean());
        menu.setCanRestock(in.readBoolean());
    }

    void release() {
        this.scratch.release();
    }

}
