package comedymonkey.dailies.mixin.hidden;

import comedymonkey.dailies.playback.Playback;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * In its classic style the Locator Bar mod hides the vanilla experience bar and level to draw its own bar in their
 * place. In playback its bar is never drawn ({@link LocatorBarMixin}), so the recorder's experience bar and level are
 * left to show as the game draws them. Skipped without complaint when the mod is absent or has changed.
 */
@Pseudo
@Mixin(targets = "pl.fuzjajadrowa.locatorbar.client.ClassicExperienceBarState", remap = false)
public abstract class LocatorBarXpMixin {

    @Inject(method = "shouldHideVanillaExperienceBar(Lnet/minecraft/client/Minecraft;)Z", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void dailies$vanillaBarInPlayback(Minecraft minecraft, CallbackInfoReturnable<Boolean> info) {
        if (Playback.active()) {
            info.setReturnValue(false);
        }
    }

}
