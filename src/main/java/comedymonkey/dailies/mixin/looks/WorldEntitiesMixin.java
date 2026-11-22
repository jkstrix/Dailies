package comedymonkey.dailies.mixin.looks;

import comedymonkey.dailies.looks.Drawn;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks the stretch in which the game takes the pictures of the entities it draws in the world. Entities are also
 * pictured elsewhere (the player in an inventory screen, which playback shows for recorded windows), and only those of
 * the world are counted as drawn.
 */
@Mixin(LevelExtractor.class)
public abstract class WorldEntitiesMixin {

    @Inject(method = "extractVisibleEntities", at = @At("HEAD"))
    private void dailies$worldEntitiesStart(CallbackInfo ci) {
        Drawn.inWorld(true);
    }

    @Inject(method = "extractVisibleEntities", at = @At("RETURN"))
    private void dailies$worldEntitiesEnd(CallbackInfo ci) {
        Drawn.inWorld(false);
    }
}
