package comedymonkey.dailiescompat.aeronautics.mixin;

import comedymonkey.dailiescompat.aeronautics.client.AeroMarks;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Simulated, Offroad and Simulated Coasters put many of their lines above the hotbar on the player's own game (a zipline
 * too far or too steep, a tyre that cannot go there, a cart's link lengths, a rivet's angle), where no packet carries
 * them; the line is noted as it is put there, for the compat to record while it shows. A line the server sends comes in
 * a packet the recording already holds.
 */
@Mixin(value = LocalPlayer.class, remap = false)
public abstract class StatusLineMixin {

    @Inject(method = "displayClientMessage(Lnet/minecraft/network/chat/Component;Z)V", at = @At("HEAD"), remap = false, require = 0)
    private void dailiesAeronautics$noteStatus(Component message, boolean actionBar, CallbackInfo ci) {
        if (actionBar) {
            AeroMarks.statusShown(message);
        }
    }

}
