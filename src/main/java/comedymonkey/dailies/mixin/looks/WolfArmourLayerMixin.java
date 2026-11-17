package comedymonkey.dailies.mixin.looks;

import com.mojang.blaze3d.vertex.PoseStack;
import comedymonkey.dailies.looks.LooksRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.WolfArmorLayer;
import net.minecraft.world.entity.animal.Wolf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A scene can hide the armour worn by one wolf (specification chapter V, per-entity changes) */
@Mixin(WolfArmorLayer.class)
public abstract class WolfArmourLayerMixin {

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/animal/Wolf;FFFFFF)V",
            at = @At("HEAD"), cancellable = true)
    private void dailies$noArmour(PoseStack poseStack, MultiBufferSource buffers, int light, Wolf wolf, float limbSwing, float limbSwingAmount,
                                  float partialTick, float age, float headYaw, float headPitch, CallbackInfo ci) {
        if (LooksRenderer.hidesArmour(wolf)) {
            ci.cancel();
        }
    }

}
