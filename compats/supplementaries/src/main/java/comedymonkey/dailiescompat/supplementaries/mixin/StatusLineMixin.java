package comedymonkey.dailiescompat.supplementaries.mixin;

import comedymonkey.dailiescompat.supplementaries.client.SuppMarks;
import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Supplementaries and Amendments put some of their lines above the hotbar on the player's own game (a cannon's keys as it
 * is aimed, straight into the game's line; an altimeter's height, a safe's owner and others through the player), where no
 * packet carries them; the line is noted as it is put there, for the compat to record while it shows. A line the server
 * sends comes in a packet the recording already holds.
 */
@Mixin(value = Gui.class, remap = false)
public abstract class StatusLineMixin {

    @Inject(method = "setOverlayMessage(Lnet/minecraft/network/chat/Component;Z)V", at = @At("HEAD"), remap = false, require = 0)
    private void dailiesSupplementaries$noteStatus(Component message, boolean animateColor, CallbackInfo ci) {
        SuppMarks.statusShown(message);
    }

}
