package comedymonkey.dailies.mixin.effects;

import com.mojang.blaze3d.vertex.PoseStack;
import comedymonkey.dailies.effects.RecorderHands;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Notes what the hands are drawn holding through someone's eyes in playback, for the self test */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class HandsDrawnMixin {

    @Inject(method = "submitHandsWithItems", at = @At("HEAD"))
    private void dailies$theirHands(float partialTicks, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, PlayerRenderState playerState,
                                    FirstPersonHandsAndItemsRenderState state, CallbackInfo ci) {
        RecorderHands.drawn(playerState, state);
    }
}
