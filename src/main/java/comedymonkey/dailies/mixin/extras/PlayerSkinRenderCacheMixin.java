package comedymonkey.dailies.mixin.extras;

import comedymonkey.dailies.extras.RecordedSkins;
import net.minecraft.client.renderer.PlayerSkinRenderCache;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.component.ResolvableProfile;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * While a recording is watched, anything else that shows a recorded player's skin (their head as a block or an item)
 * is given the one the recording holds, without the network (specification REC-14). Skins of anyone the recording
 * does not hold are left to the game. Asked afresh each time and never kept by the game, so nothing recorded is shown
 * once the recording is closed.
 */
@Mixin(PlayerSkinRenderCache.class)
public abstract class PlayerSkinRenderCacheMixin {

    @Inject(method = "getOrDefault", at = @At("HEAD"), cancellable = true)
    private void dailies$recordedSkin(ResolvableProfile profile, CallbackInfoReturnable<PlayerSkinRenderCache.RenderInfo> callback) {
        PlayerSkinRenderCache.RenderInfo recorded = this.dailies$recorded(profile);
        if (recorded != null) {
            callback.setReturnValue(recorded);
        }
    }

    @Inject(method = "createLookup", at = @At("HEAD"), cancellable = true)
    private void dailies$recordedLookup(ResolvableProfile profile, CallbackInfoReturnable<Supplier<PlayerSkinRenderCache.RenderInfo>> callback) {
        PlayerSkinRenderCache.RenderInfo recorded = this.dailies$recorded(profile);
        if (recorded != null) {
            callback.setReturnValue(() -> recorded);
        }
    }

    @Inject(method = "lookup", at = @At("HEAD"), cancellable = true)
    private void dailies$recordedFuture(ResolvableProfile profile, CallbackInfoReturnable<CompletableFuture<Optional<PlayerSkinRenderCache.RenderInfo>>> callback) {
        PlayerSkinRenderCache.RenderInfo recorded = this.dailies$recorded(profile);
        if (recorded != null) {
            callback.setReturnValue(CompletableFuture.completedFuture(Optional.of(recorded)));
        }
    }

    private PlayerSkinRenderCache.@Nullable RenderInfo dailies$recorded(ResolvableProfile profile) {
        PlayerSkin skin = RecordedSkins.replacement(profile.partialProfile(), false);
        return skin == null ? null : ((PlayerSkinRenderCache) (Object) this).new RenderInfo(profile.partialProfile(), skin, profile.skinPatch());
    }

}
