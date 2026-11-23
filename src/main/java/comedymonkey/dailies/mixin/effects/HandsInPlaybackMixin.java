package comedymonkey.dailies.mixin.effects;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import comedymonkey.dailies.effects.RecorderHands;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Through someone's eyes in playback, their hands in place of the viewer's: the game draws no hands for a spectator,
 * and the viewer is one, so the viewer's game mode does not keep them from being drawn; and the hands drawn are theirs
 */
@Mixin(GameRenderer.class)
public abstract class HandsInPlaybackMixin {

    @WrapOperation(method = "renderItemInHand", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;getPlayerMode()Lnet/minecraft/world/level/GameType;"))
    private GameType dailies$theirHandsShow(MultiPlayerGameMode gameMode, Operation<GameType> original) {
        return RecorderHands.subject() != null ? GameType.SURVIVAL : original.call(gameMode);
    }

    @WrapOperation(method = "renderItemInHand", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;renderHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;Lnet/minecraft/client/player/LocalPlayer;I)V"))
    private void dailies$theirHands(ItemInHandRenderer renderer, float partialTicks, PoseStack poseStack, MultiBufferSource.BufferSource buffers,
                                    LocalPlayer player, int light, Operation<Void> original) {
        AbstractClientPlayer theirs = RecorderHands.subject();
        if (theirs == null) {
            original.call(renderer, partialTicks, poseStack, buffers, player, light);
            return;
        }
        try {
            RecorderHands.renderHands(renderer, theirs, partialTicks, poseStack, buffers);
        } catch (RuntimeException | LinkageError e) {
            comedymonkey.dailies.effects.RecorderOverlays.failOnce("the hands through their eyes", e);
        }
    }
}
