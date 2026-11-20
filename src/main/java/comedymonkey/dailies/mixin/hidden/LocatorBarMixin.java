package comedymonkey.dailies.mixin.hidden;

import comedymonkey.dailies.playback.Playback;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The Locator Bar mod draws its bar over the HUD after everything else, from the viewer's own game. It is left out of
 * recordings on purpose (the owner does not want it in videos, PLAN-UI), yet it showed in playback and exports (the
 * owner, 20 November: "it should never show"). So while a recording is open it is never drawn. The vanilla experience
 * bar it hides in its classic style is given back by {@link LocatorBarXpMixin}. Skipped without complaint when the mod
 * is absent or has changed.
 */
@Pseudo
@Mixin(targets = "pl.fuzjajadrowa.locatorbar.client.LocatorBarHudRenderer", remap = false)
public abstract class LocatorBarMixin {

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;)V", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void dailies$neverInPlayback(GuiGraphics graphics, CallbackInfo info) {
        if (Playback.active()) {
            info.cancel();
        }
    }

}
