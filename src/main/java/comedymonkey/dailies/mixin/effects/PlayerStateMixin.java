package comedymonkey.dailies.mixin.effects;

import comedymonkey.dailies.effects.RecorderEyes;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The frame's player state (the warp, fire, water, a block in the face) from the one whose eyes the view is through */
@Mixin(LevelExtractor.class)
public abstract class PlayerStateMixin {

    @Inject(method = "extractPlayerState", at = @At("TAIL"))
    private void dailies$throughTheirEyes(Camera camera, DeltaTracker deltaTracker, float worldPartialTicks, PlayerRenderState state, CallbackInfo ci) {
        try {
            RecorderEyes.fillPlayerState(state, camera, worldPartialTicks);
            RecorderEyes.shownState(state);
        } catch (RuntimeException | LinkageError e) {
            comedymonkey.dailies.effects.RecorderOverlays.failOnce("the frame's player state", e);
        }
    }
}
