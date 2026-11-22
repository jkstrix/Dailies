package comedymonkey.dailies.mixin.extras;

import com.mojang.authlib.GameProfile;
import comedymonkey.dailies.extras.RecordedSkins;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.client.resources.SkinManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While a recording is watched, anything else that shows a recorded player's skin (their head as a block or an item)
 * is given the one the recording holds, without the network (specification REC-14). Skins of anyone the recording
 * does not hold are left to the game.
 */
@Mixin(SkinManager.class)
public abstract class SkinManagerMixin {

    @Inject(method = "getInsecureSkin", at = @At("HEAD"), cancellable = true)
    private void dailies$recordedSkin(GameProfile profile, CallbackInfoReturnable<PlayerSkin> callback) {
        PlayerSkin recorded = RecordedSkins.replacement(profile, false);
        if (recorded != null) {
            callback.setReturnValue(recorded);
        }
    }

}
