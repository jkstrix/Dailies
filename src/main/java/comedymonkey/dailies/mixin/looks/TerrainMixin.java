package comedymonkey.dailies.mixin.looks;

import comedymonkey.dailies.looks.Drawn;
import comedymonkey.dailies.looks.LooksRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A scene can leave the terrain out (specification chapter V, "What is drawn"): every group of layers of it is turned
 * away where the game hands it to be drawn, with see-through blocks drawn the classic way or the newer order-free way.
 * <p>
 * Renderer mods replace the body of these methods with their own drawing and keep the methods, so the hooks are put in
 * after theirs (the priority) and so still stand in front of whatever draws the terrain. They are allowed to be
 * missing, and {@link Drawn#terrainLayers} says whether they are there.
 */
@Mixin(value = ChunkSectionsToRender.class, priority = 1500)
public abstract class TerrainMixin {

    @Inject(method = "renderGroup", at = @At("HEAD"), cancellable = true, require = 0)
    private void dailies$noTerrain(CallbackInfo ci) {
        boolean hidden = LooksRenderer.hidesBlocks();
        Drawn.terrainLayer(hidden);
        if (hidden) {
            ci.cancel();
        }
    }

    @Inject(method = "renderOit", at = @At("HEAD"), cancellable = true, require = 0)
    private void dailies$noSeeThroughTerrain(CallbackInfo ci) {
        boolean hidden = LooksRenderer.hidesBlocks();
        Drawn.terrainLayer(hidden);
        if (hidden) {
            ci.cancel();
        }
    }

}
