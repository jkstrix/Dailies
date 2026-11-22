package comedymonkey.dailies.extras;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.network.connection.ConnectionType;

/**
 * Everything the recorder's HUD shows at one moment (specification REC-13): the hotbar and off-hand, the selected
 * slot, health, absorption, hunger, air, armour, experience and game mode. It is written into a recording as a
 * {@code dailies:hud} record and read back in playback.
 * <p>
 * The record: a version byte (1; a record holding only a 0 means "no HUD from here on"), game mode, selected slot,
 * main arm (0 left, 1 right), flags (1 = head under water, 2 = hardcore), then health, greatest health and absorption
 * as floats, hunger as a varint, saturation as a float, air and greatest air, armour and experience level as
 * varints, experience progress as a float, and last the nine hotbar stacks and the off-hand stack. Each stack is a
 * varint length followed by the game's own encoding of an optional item stack, so a stack this game cannot read
 * (an item from a mod that is missing) costs only its own slot.
 */
public final class HudState {

    static final String ID = "dailies:hud";
    static final int SLOTS = 10;
    static final int OFF_HAND = 9;
    private static final byte VERSION = 1;
    private static final int FLAG_UNDER_WATER = 1;
    private static final int FLAG_HARDCORE = 2;

    /** What is written when the HUD stops being recorded part way through */
    static final byte[] NONE = {0};

    GameType gameMode = GameType.SURVIVAL;
    int selected;
    boolean rightHanded = true;
    boolean underWater;
    boolean hardcore;
    float health;
    float maxHealth;
    float absorption;
    int food;
    float saturation;
    int air;
    int maxAir;
    int armour;
    int level;
    float progress;
    final ItemStack[] stacks = new ItemStack[SLOTS];

    private HudState() {
    }

    /** The HUD as it is now. The stacks are the player's own, not copies: see {@link #keep} */
    static HudState of(LocalPlayer player, GameType gameMode) {
        HudState state = new HudState();
        state.gameMode = gameMode;
        state.selected = player.getInventory().getSelectedSlot();
        state.rightHanded = player.getMainArm() == HumanoidArm.RIGHT;
        state.underWater = player.isEyeInFluid(FluidTags.WATER);
        state.hardcore = player.level().getLevelData().isHardcore();
        state.health = player.getHealth();
        state.maxHealth = player.getMaxHealth();
        state.absorption = player.getAbsorptionAmount();
        state.food = player.getFoodData().getFoodLevel();
        state.saturation = player.getFoodData().getSaturationLevel();
        state.air = player.getAirSupply();
        state.maxAir = player.getMaxAirSupply();
        state.armour = player.getArmorValue();
        state.level = player.experienceLevel;
        state.progress = player.experienceProgress;
        for (int slot = 0; slot < 9; slot++) {
            state.stacks[slot] = player.getInventory().getNonEquipmentItems().get(slot);
        }
        state.stacks[OFF_HAND] = player.getOffhandItem();
        return state;
    }

    /** Swaps the player's own stacks for copies, so this can be compared with a later moment */
    void keep() {
        for (int slot = 0; slot < SLOTS; slot++) {
            this.stacks[slot] = this.stacks[slot].copy();
        }
    }

    boolean same(HudState other) {
        if (this.gameMode != other.gameMode || this.selected != other.selected || this.rightHanded != other.rightHanded || this.underWater != other.underWater
            || this.hardcore != other.hardcore || this.health != other.health || this.maxHealth != other.maxHealth || this.absorption != other.absorption
            || this.food != other.food || this.saturation != other.saturation || this.air != other.air || this.maxAir != other.maxAir
            || this.armour != other.armour || this.level != other.level || this.progress != other.progress) {
            return false;
        }
        for (int slot = 0; slot < SLOTS; slot++) {
            if (this.stacks[slot] != other.stacks[slot] && !ItemStack.matches(this.stacks[slot], other.stacks[slot])) {
                return false;
            }
        }
        return true;
    }

    byte[] encode(RegistryAccess registries, ConnectionType connectionType) {
        ByteBuf out = Unpooled.buffer(256);
        RegistryFriendlyByteBuf one = new RegistryFriendlyByteBuf(Unpooled.buffer(64), registries, connectionType);
        try {
            out.writeByte(VERSION);
            out.writeByte(this.gameMode.getId());
            out.writeByte(this.selected);
            out.writeByte(this.rightHanded ? 1 : 0);
            out.writeByte((this.underWater ? FLAG_UNDER_WATER : 0) | (this.hardcore ? FLAG_HARDCORE : 0));
            out.writeFloat(this.health);
            out.writeFloat(this.maxHealth);
            out.writeFloat(this.absorption);
            VarInt.write(out, this.food);
            out.writeFloat(this.saturation);
            VarInt.write(out, this.air);
            VarInt.write(out, this.maxAir);
            VarInt.write(out, this.armour);
            VarInt.write(out, this.level);
            out.writeFloat(this.progress);
            for (int slot = 0; slot < SLOTS; slot++) {
                one.clear();
                try {
                    ItemStack.OPTIONAL_STREAM_CODEC.encode(one, this.stacks[slot]);
                } catch (RuntimeException unwritable) {
                    // A stack the game itself cannot put on the wire is recorded as an empty slot
                    one.clear();
                    ItemStack.OPTIONAL_STREAM_CODEC.encode(one, ItemStack.EMPTY);
                }
                VarInt.write(out, one.readableBytes());
                out.writeBytes(one);
            }
            byte[] bytes = new byte[out.readableBytes()];
            out.readBytes(bytes);
            return bytes;
        } finally {
            out.release();
            one.release();
        }
    }

    /**
     * @return the HUD a record holds, or null when the record says that none was recorded from there on
     */
    static HudState decode(byte[] record, RegistryAccess registries, ConnectionType connectionType) {
        ByteBuf in = Unpooled.wrappedBuffer(record);
        int version = in.readByte();
        if (version == 0) {
            return null;
        }
        if (version != VERSION) {
            throw new IllegalArgumentException("HUD record of version " + version + ", which this version of Dailies does not know");
        }
        HudState state = new HudState();
        state.gameMode = GameType.byId(in.readByte());
        state.selected = Math.max(0, Math.min(8, in.readByte()));
        state.rightHanded = in.readByte() != 0;
        int flags = in.readByte();
        state.underWater = (flags & FLAG_UNDER_WATER) != 0;
        state.hardcore = (flags & FLAG_HARDCORE) != 0;
        state.health = in.readFloat();
        state.maxHealth = in.readFloat();
        state.absorption = in.readFloat();
        state.food = VarInt.read(in);
        state.saturation = in.readFloat();
        state.air = VarInt.read(in);
        state.maxAir = VarInt.read(in);
        state.armour = VarInt.read(in);
        state.level = VarInt.read(in);
        state.progress = in.readFloat();
        for (int slot = 0; slot < SLOTS; slot++) {
            int length = VarInt.read(in);
            ItemStack stack = ItemStack.EMPTY;
            try {
                stack = ItemStack.OPTIONAL_STREAM_CODEC.decode(new RegistryFriendlyByteBuf(in.slice(in.readerIndex(), length), registries, connectionType));
            } catch (RuntimeException unreadable) {
                // An item this game does not have: the slot is shown empty (PLAY-5)
            }
            in.skipBytes(length);
            state.stacks[slot] = stack;
        }
        return state;
    }

}
