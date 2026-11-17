package comedymonkey.dailies.mixin.looks;

import com.mojang.blaze3d.vertex.PoseStack;
import comedymonkey.dailies.looks.LooksRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A scene can hide the armour worn by one player or other two-legged mob (specification chapter V, per-entity changes) */
@Mixin(HumanoidArmorLayer.class)
public abstract class ArmourLayerMixin {

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFFFFF)V",
            at = @At("HEAD"), cancellable = true)
    private void dailies$noArmour(PoseStack poseStack, MultiBufferSource buffers, int light, LivingEntity entity, float limbSwing, float limbSwingAmount,
                                  float partialTick, float age, float headYaw, float headPitch, CallbackInfo ci) {
        if (LooksRenderer.hidesArmour(entity)) {
            ci.cancel();
        }
    }

}
