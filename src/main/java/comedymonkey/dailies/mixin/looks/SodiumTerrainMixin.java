package comedymonkey.dailies.mixin.looks;

import comedymonkey.dailies.looks.Drawn;
import comedymonkey.dailies.looks.LooksRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sodium draws the terrain itself, in place of the game's sections ({@link TerrainMixin}): the scene's blocks look is
 * applied to its layers here. Does nothing without Sodium.
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer", remap = false)
public abstract class SodiumTerrainMixin {

    @Inject(method = "drawChunkLayer", at = @At("HEAD"), cancellable = true, require = 0)
    private void dailies$noTerrain(CallbackInfo ci) {
        boolean hidden = LooksRenderer.hidesBlocks();
        Drawn.terrainLayer(hidden);
        if (hidden) {
            ci.cancel();
        }
    }
}
