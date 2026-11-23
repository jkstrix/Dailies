package comedymonkey.dailies.mixin.effects;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import comedymonkey.dailies.effects.RecorderHands;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * The parts of the game's hand drawing that ask for its own player (whose arm's skin, whose arms hold a map, whether
 * they are invisible) answer with the player whose hands are being drawn through their eyes in playback
 */
@Mixin(ItemInHandRenderer.class)
public abstract class TheirArmMixin {

    @ModifyVariable(method = "renderPlayerArm", at = @At("STORE"), ordinal = 0)
    private AbstractClientPlayer dailies$theirArm(AbstractClientPlayer own) {
        AbstractClientPlayer theirs = RecorderHands.drawing();
        return theirs != null ? theirs : own;
    }

    @WrapOperation(method = "renderMapHand", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;getRenderer(Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/client/renderer/entity/EntityRenderer;"))
    private EntityRenderer<?> dailies$theirMapArmRenderer(EntityRenderDispatcher dispatcher, Entity own, Operation<EntityRenderer<?>> original) {
        AbstractClientPlayer theirs = RecorderHands.drawing();
        return original.call(dispatcher, theirs != null ? theirs : own);
    }

    @WrapOperation(method = "renderMapHand", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/entity/player/PlayerRenderer;renderRightHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;)V"))
    private void dailies$theirRightMapArm(PlayerRenderer renderer, PoseStack poseStack, MultiBufferSource buffers, int light, AbstractClientPlayer own,
                                          Operation<Void> original) {
        AbstractClientPlayer theirs = RecorderHands.drawing();
        original.call(renderer, poseStack, buffers, light, theirs != null ? theirs : own);
    }

    @WrapOperation(method = "renderMapHand", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/entity/player/PlayerRenderer;renderLeftHand(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;)V"))
    private void dailies$theirLeftMapArm(PlayerRenderer renderer, PoseStack poseStack, MultiBufferSource buffers, int light, AbstractClientPlayer own,
                                         Operation<Void> original) {
        AbstractClientPlayer theirs = RecorderHands.drawing();
        original.call(renderer, poseStack, buffers, light, theirs != null ? theirs : own);
    }

    @WrapOperation(method = {"renderOneHandedMap", "renderTwoHandedMap"}, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/player/LocalPlayer;isInvisible()Z"))
    private boolean dailies$theyAreInvisible(LocalPlayer own, Operation<Boolean> original) {
        AbstractClientPlayer theirs = RecorderHands.drawing();
        return theirs != null ? theirs.isInvisible() : original.call(own);
    }
}
