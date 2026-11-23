package comedymonkey.dailies.mixin.looks;

import com.mojang.blaze3d.vertex.PoseStack;
import comedymonkey.dailies.looks.Drawn;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Notes what each hand of a player's or a mob's body holds as it is drawn, for the self test */
@Mixin(ItemInHandLayer.class)
public abstract class HeldItemLayerMixin {

    @Inject(method = "submitArmWithItem", at = @At("HEAD"))
    private void dailies$held(ArmedEntityRenderState state, ItemStackRenderState item, ItemStack stack, HumanoidArm arm, PoseStack poseStack,
                              SubmitNodeCollector collector, int light, CallbackInfo ci) {
        if (!item.isEmpty()) {
            Drawn.held(arm, stack);
        }
    }
}
