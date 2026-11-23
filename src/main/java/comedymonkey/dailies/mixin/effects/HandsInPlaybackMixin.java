package comedymonkey.dailies.mixin.effects;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import comedymonkey.dailies.effects.RecorderHands;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.level.GameType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The game draws no hands for a spectator, and in playback the viewer is one. Through someone's eyes the hands are
 * theirs, so the viewer's game mode does not keep them from being drawn
 */
@Mixin(GameRenderer.class)
public abstract class HandsInPlaybackMixin {

    @WrapOperation(method = "renderItemInHand", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;getPlayerMode()Lnet/minecraft/world/level/GameType;"))
    private GameType dailies$theirHands(MultiPlayerGameMode gameMode, Operation<GameType> original) {
        return RecorderHands.subject() != null ? GameType.SURVIVAL : original.call(gameMode);
    }
}
