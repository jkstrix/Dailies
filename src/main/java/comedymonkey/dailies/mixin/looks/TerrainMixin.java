package comedymonkey.dailies.mixin.looks;

import comedymonkey.dailies.looks.Drawn;
import comedymonkey.dailies.looks.LooksRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A scene can leave the terrain out (specification chapter V, "What is drawn"): every layer of it is turned away
 * where the game hands it to be drawn.
 * <p>
 * Renderer mods replace the body of this method with their own drawing and keep the method, so the hook is put in
 * after theirs (the priority) and so still stands in front of whatever draws the terrain. It is allowed to be
 * missing, and {@link Drawn#terrainLayers} says whether it is there.
 */
@Mixin(value = LevelRenderer.class, priority = 1500)
public abstract class TerrainMixin {

    @Inject(method = "renderSectionLayer", at = @At("HEAD"), cancellable = true, require = 0)
    private void dailies$noTerrain(RenderType layer, double x, double y, double z, Matrix4f frustumMatrix, Matrix4f projectionMatrix, CallbackInfo ci) {
        boolean hidden = LooksRenderer.hidesBlocks();
        Drawn.terrainLayer(hidden);
        if (hidden) {
            ci.cancel();
        }
    }

}
