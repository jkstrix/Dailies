package comedymonkey.dailies.mixin.effects;

import comedymonkey.dailies.effects.RecorderEyes;
import comedymonkey.dailies.playback.Viewer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A totem of undying going off for the one the view rides with is shown to the viewer as the game shows its own: the
 * totem rising over the screen. Drawn only through their eyes with Show HUD on (the frame's player state empties it
 * otherwise)
 */
@Mixin(ClientPacketListener.class)
public abstract class TotemMixin {

    @Inject(method = "handleEntityEvent", at = @At("TAIL"))
    private void dailies$theirTotem(ClientboundEntityEventPacket packet, CallbackInfo ci) {
        if (packet.getEventId() != 35 || !RecorderEyes.inPlayback()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }
        Entity entity = packet.getEntity(minecraft.level);
        if (entity != null && entity == Viewer.riding()) {
            minecraft.player.displayItemActivation(new ItemStack(Items.TOTEM_OF_UNDYING));
        }
    }
}
