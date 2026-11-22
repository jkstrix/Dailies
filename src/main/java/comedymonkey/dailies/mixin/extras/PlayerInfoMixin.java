package comedymonkey.dailies.mixin.extras;

import com.mojang.authlib.GameProfile;
import comedymonkey.dailies.extras.RecordedSkins;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.PlayerSkin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While a recording is watched, a player in its tab list wears the skin and cape the recording holds for them, and
 * the game's own lookup (which would ask the network) is never started (specification REC-14).
 */
@Mixin(PlayerInfo.class)
public abstract class PlayerInfoMixin {

    @Shadow
    @Final
    private GameProfile profile;

    @Inject(method = "getSkin", at = @At("HEAD"), cancellable = true)
    private void dailies$recordedSkin(CallbackInfoReturnable<PlayerSkin> callback) {
        PlayerSkin recorded = RecordedSkins.replacement(this.profile, true);
        if (recorded != null) {
            callback.setReturnValue(recorded);
        }
    }

}
