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
    /** A move of more than this many blocks in one report is a teleport, not travel */
    private static final double JUMP_BLOCKS = 4.0;
    private boolean jumped = false;

    /** Whether the last tick reported a teleport. Asking clears it */
    boolean takeJumped() {
        boolean was = this.jumped;
        this.jumped = false;
        return was;
    }

    // Where a game watching this player would be drawing them: it eases towards each reported position over three
    // ticks. A snapshot places the recorder here, so a jump shows them exactly where continuous play would
    private double shownX, shownY, shownZ;
    private float shownYRot, shownXRot;
    private double targetX, targetY, targetZ;
    private float targetYRot, targetXRot;
    private int steps = 0;
    private boolean shown = false;

    /** x, y, z, yaw and pitch as a watching game would show them now, or null when nothing has been reported yet */
    double[] shown() {
        return this.shown ? new double[]{this.shownX, this.shownY, this.shownZ, this.shownYRot, this.shownXRot} : null;
    }

    /** Nothing before this moment leads up to it: a watching game would start from where the player is */
    void forgetShown() {
        this.shown = false;
        this.steps = 0;
    }

    private void report(LocalPlayer player, boolean position) {
        if (!this.shown) {
            this.shownX = player.getX();
            this.shownY = player.getY();
            this.shownZ = player.getZ();
            this.shownYRot = player.getYRot();
            this.shownXRot = player.getXRot();
            this.shown = true;
        }
        if (position) {
            this.targetX = player.getX();
            this.targetY = player.getY();
            this.targetZ = player.getZ();
        } else {
            this.targetX = this.shownX;
            this.targetY = this.shownY;
            this.targetZ = this.shownZ;
        }
        // The wire carries angles as 256ths of a turn
        this.targetYRot = angle(player.getYRot()) * 360.0F / 256.0F;
        this.targetXRot = angle(player.getXRot()) * 360.0F / 256.0F;
        this.steps = 3;
    }

    private void ease() {
        if (this.steps > 0) {
            double part = 1.0 / this.steps;
            this.shownX = Mth.lerp(part, this.shownX, this.targetX);
            this.shownY = Mth.lerp(part, this.shownY, this.targetY);
            this.shownZ = Mth.lerp(part, this.shownZ, this.targetZ);
            this.shownYRot = (float) Mth.rotLerp(part, (double) this.shownYRot, (double) this.targetYRot);
            this.shownXRot = (float) Mth.lerp(part, (double) this.shownXRot, (double) this.targetXRot);
            this.steps--;
        }
    }

    private net.minecraft.core.BlockPos crackedAt = null;
    private int crackedStage = -1;
    private int crackedTick = 0;

    /**
     * Whether this is news about the block the player is mining. The game repeats the same stage on every tick of
     * mining; only a change is worth writing. After a snapshot the stage is news again, since cracks are no part of one,
     * and so it is every ten seconds: a game forgets cracks it has not been told of for twenty, and a hard block
     * mined by hand holds one stage for longer than that
     */
    boolean cracked(net.minecraft.core.BlockPos pos, int stage) {
        if (stage == this.crackedStage && pos.equals(this.crackedAt) && (stage < 0 || this.ticks - this.crackedTick < 200)) {
            return false;
        }
        this.crackedAt = pos.immutable();
        this.crackedStage = stage;
        this.crackedTick = this.ticks;
        return true;
    }

    /** Forget everything, so the next tick describes the player in full */
    void reset() {
        this.crackedAt = null;
        this.known = false;
        // What was last said to be held is kept, and all of it is said again on the next tick, empty hands included. A
        // snapshot describes the player as they are, but plain play runs straight past a snapshot; if what was held were
        // forgotten here, a hand emptied on the very tick of a snapshot would never be emptied for anyone watching
        this.sayHeld = true;
        this.ticks = 0;
    }

    private boolean sayHeld = false;

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
                    this.report(player, false);
                    out.accept(new ClientboundMoveEntityPacket.Rot(player.getId(), angle(player.getYRot()), angle(player.getXRot()), player.onGround()));
                }
            } else if (moved || turned) {
                // Further in one step than anyone walks, runs or flies: a teleport. A watching game would slide the player
                // there over three ticks, through whatever lies between, so playback is told to put them there at once
                double far = this.known ? (player.getX() - this.x) * (player.getX() - this.x) + (player.getY() - this.y) * (player.getY() - this.y)
                    + (player.getZ() - this.z) * (player.getZ() - this.z) : 0.0;
                this.report(player, true);
                out.accept(new ClientboundTeleportEntityPacket(player));
                if (far > JUMP_BLOCKS * JUMP_BLOCKS) {
                    this.jumped = true;
                    this.shownX = this.targetX;
                    this.shownY = this.targetY;
                    this.shownZ = this.targetZ;
                    this.shownYRot = this.targetYRot;
                    this.shownXRot = this.targetXRot;
                    this.steps = 0;
                }
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

        this.ease();

        List<Pair<EquipmentSlot, ItemStack>> changed = null;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack now = player.getItemBySlot(slot);
            ItemStack before = this.equipment.get(slot);
            if (before == null ? !now.isEmpty() : this.sayHeld || !ItemStack.matches(before, now)) {
                if (changed == null) {
                    changed = new ArrayList<>();
                }
                ItemStack copy = now.copy();
                changed.add(Pair.of(slot, copy));
                this.equipment.put(slot, copy);
            }
        }
        this.sayHeld = false;
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
