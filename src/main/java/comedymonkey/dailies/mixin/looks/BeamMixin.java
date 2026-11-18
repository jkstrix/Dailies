package comedymonkey.dailies.mixin.looks;

import com.mojang.blaze3d.vertex.PoseStack;
import comedymonkey.dailies.looks.Drawn;
import comedymonkey.dailies.looks.LooksRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A scene can leave beams out (specification chapter V, "What is drawn"). Every beam the game draws goes through
 * this one method: a beacon's, an end gateway's, and those of mods that borrow the game's beam for their own blocks.
 * The beam is turned away here and nothing else, so the beacon itself stays in the picture.
 * <p>
 * The priority puts this ahead of other mods' hooks at the same place, so none of them has begun anything by the
 * time the beam is turned away.
 */
@Mixin(value = BeaconRenderer.class, priority = 1500)
public abstract class BeamMixin {

    @Inject(method = "renderBeaconBeam(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/resources/ResourceLocation;FFJIIIFF)V",
            at = @At("HEAD"), cancellable = true)
    private static void dailies$noBeam(PoseStack poseStack, MultiBufferSource buffers, ResourceLocation texture, float partialTick, float textureScale, long gameTime, int yOffset,
                                       int height, int colour, float beamRadius, float glowRadius, CallbackInfo ci) {
        boolean hidden = LooksRenderer.hidesBeaconBeams();
        Drawn.beam(hidden);
        if (hidden) {
            ci.cancel();
        }
    }

}
