package comedymonkey.dailiescompat.extras.mixin;

import comedymonkey.dailiescompat.extras.client.ExtrasMarks;
import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vista, Sophisticated Backpacks and Corpse put a few lines above the hotbar on the player's own game (Vista sets its
 * viewfinder hint on the game's interface directly), where no packet carries them; the line is noted as it is put there,
 * for the compat to record while it shows. A line the server sends comes in a packet the recording already holds.
 */
@Mixin(value = Gui.class, remap = false)
public abstract class StatusLineMixin {

    @Inject(method = "setOverlayMessage(Lnet/minecraft/network/chat/Component;Z)V", at = @At("HEAD"), remap = false, require = 0)
    private void dailiesExtras$noteStatus(Component message, boolean animateColor, CallbackInfo ci) {
        ExtrasMarks.statusShown(message);
    }

}
