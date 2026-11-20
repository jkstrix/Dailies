package comedymonkey.dailiescompat.createextras.mixin;

import comedymonkey.dailiescompat.createextras.client.ExtrasMarks;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Create's add-ons put some of their lines above the hotbar on the player's own game (Create Connected's dashboard,
 * Create Deep Seas' console, a Create: Tracks key, Steam 'n' Rails' items, Create: Hypertubes' tubes), where no packet
 * carries them; the line is noted as it is put there, for the compat to record while it shows. A line the server sends
 * comes in a packet the recording already holds.
 */
@Mixin(value = LocalPlayer.class, remap = false)
public abstract class StatusLineMixin {

    @Inject(method = "displayClientMessage(Lnet/minecraft/network/chat/Component;Z)V", at = @At("HEAD"), remap = false, require = 0)
    private void dailiesCreateExtras$noteStatus(Component message, boolean actionBar, CallbackInfo ci) {
        if (actionBar) {
            ExtrasMarks.statusShown(message);
        }
    }

}
