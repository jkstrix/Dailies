package comedymonkey.dailies.mixin.export;

import comedymonkey.dailies.export.Export;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Terrain that has just been built fades in over the player's chunk fade setting, timed by the real clock. An export
 * draws its frames faster or slower than real time, and waits until the terrain is built before each picture, so in a
 * video the fade would run at whatever speed the computer exported at, and a still would catch it half done: while
 * exporting, built terrain is shown at once. The player's setting is not touched.
 */
@Mixin(GameRenderer.class)
public abstract class ExportChunkFadeMixin {

    @Inject(method = "extractOptions", at = @At("TAIL"))
    private void dailies$noFadeWhileExporting(CallbackInfo ci) {
        if (Export.active()) {
            ((GameRenderer) (Object) this).gameRenderState().optionsRenderState.chunkSectionFadeInTime = 0.0;
        }
    }
}
