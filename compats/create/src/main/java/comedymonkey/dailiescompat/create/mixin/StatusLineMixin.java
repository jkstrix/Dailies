package comedymonkey.dailiescompat.create.mixin;

import comedymonkey.dailiescompat.create.client.CreateMarks;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Create puts many of its lines above the hotbar on the player's own game (what a click will do, "too far", a key
 * bound), where no packet carries them; the line is noted as it is put there, for the compat to record while it shows.
 */
@Mixin(value = LocalPlayer.class, remap = false)
public abstract class StatusLineMixin {

    @Inject(method = "displayClientMessage(Lnet/minecraft/network/chat/Component;Z)V", at = @At("HEAD"), remap = false, require = 0)
    private void dailiesCreate$noteStatus(Component message, boolean actionBar, CallbackInfo ci) {
        if (actionBar) {
            CreateMarks.statusShown(message);
        }
    }

}
