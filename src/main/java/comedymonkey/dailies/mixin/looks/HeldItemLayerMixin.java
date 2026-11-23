package comedymonkey.dailies.mixin.looks;

import com.mojang.blaze3d.vertex.PoseStack;
import comedymonkey.dailies.looks.Drawn;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Notes what each hand of a player's or a mob's body holds as it is drawn, for the self test */
@Mixin(ItemInHandLayer.class)
public abstract class HeldItemLayerMixin {

    @Inject(method = "renderArmWithItem", at = @At("HEAD"))
    private void dailies$held(LivingEntity entity, ItemStack stack, ItemDisplayContext context, HumanoidArm arm, PoseStack poseStack,
                              MultiBufferSource buffers, int light, CallbackInfo ci) {
        if (!stack.isEmpty()) {
            Drawn.held(arm, stack);
        }
    }
}
