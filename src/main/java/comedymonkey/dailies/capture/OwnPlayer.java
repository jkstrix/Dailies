package comedymonkey.dailies.capture;

import com.mojang.datafixers.util.Pair;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The recorder's own player, as packets. A server tells a game nothing about its own player's movement, arm swings
 * or equipment, so these are written here in the form the server sends them about everyone else. In playback the
 * recorder is then an ordinary player.
 */
final class OwnPlayer {

    private double x, y, z;
    private float yRot, xRot;
    private boolean onGround;
    private int head;
    private boolean known = false;
    private final Map<EquipmentSlot, ItemStack> equipment = new EnumMap<>(EquipmentSlot.class);
    private int ticks = 0;

    /** Forget everything, so the next tick describes the player in full */
    void reset() {
        this.known = false;
        this.equipment.clear();
        this.ticks = 0;
    }

    /**
     * @param every write movement once in this many ticks (1 is every tick)
     */
    void tick(LocalPlayer player, int every, Consumer<Packet<? super ClientGamePacketListener>> out) {
        boolean due = this.ticks++ % Math.max(1, every) == 0;
        if (due || !this.known) {
            boolean moved = !this.known || player.getX() != this.x || player.getY() != this.y || player.getZ() != this.z;
            boolean turned = !this.known || player.getYRot() != this.yRot || player.getXRot() != this.xRot || player.onGround() != this.onGround;
            if (player.isPassenger()) {
                // A rider's place comes from what they ride, as it does for every other player
                if (turned) {
                    out.accept(new ClientboundMoveEntityPacket.Rot(player.getId(), angle(player.getYRot()), angle(player.getXRot()), player.onGround()));
                }
            } else if (moved || turned) {
                out.accept(new ClientboundTeleportEntityPacket(player));
            }
            this.x = player.getX();
            this.y = player.getY();
            this.z = player.getZ();
            this.yRot = player.getYRot();
            this.xRot = player.getXRot();
            this.onGround = player.onGround();

            int headNow = angle(player.getYHeadRot());
            if (!this.known || headNow != this.head) {
                out.accept(new ClientboundRotateHeadPacket(player, (byte) headNow));
                this.head = headNow;
            }
            this.known = true;
        }

        List<Pair<EquipmentSlot, ItemStack>> changed = null;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack now = player.getItemBySlot(slot);
            ItemStack before = this.equipment.get(slot);
            if (before == null ? !now.isEmpty() : !ItemStack.matches(before, now)) {
                if (changed == null) {
                    changed = new ArrayList<>();
                }
                ItemStack copy = now.copy();
                changed.add(Pair.of(slot, copy));
                this.equipment.put(slot, copy);
            }
        }
        if (changed != null) {
            out.accept(new ClientboundSetEquipmentPacket(player.getId(), changed));
        }
    }

    static Packet<? super ClientGamePacketListener> swing(LocalPlayer player, InteractionHand hand) {
        return new ClientboundAnimatePacket(player, hand == InteractionHand.MAIN_HAND ? ClientboundAnimatePacket.SWING_MAIN_HAND : ClientboundAnimatePacket.SWING_OFF_HAND);
    }

    private static byte angle(float degrees) {
        return (byte) Mth.floor(degrees * 256.0F / 360.0F);
    }

}
