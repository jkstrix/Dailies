package comedymonkey.dailies.mixin.looks;

import com.mojang.blaze3d.vertex.PoseStack;
import comedymonkey.dailies.looks.LooksRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.HorseArmorLayer;
import net.minecraft.world.entity.animal.equine.Horse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A scene can hide the armour worn by one horse (specification chapter V, per-entity changes) */
@Mixin(HorseArmorLayer.class)
public abstract class HorseArmourLayerMixin {

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/animal/horse/Horse;FFFFFF)V",
            at = @At("HEAD"), cancellable = true)
    private void dailies$noArmour(PoseStack poseStack, MultiBufferSource buffers, int light, Horse horse, float limbSwing, float limbSwingAmount,
                                  float partialTick, float age, float headYaw, float headPitch, CallbackInfo ci) {
        if (LooksRenderer.hidesArmour(horse)) {
            ci.cancel();
        }
    }

}
