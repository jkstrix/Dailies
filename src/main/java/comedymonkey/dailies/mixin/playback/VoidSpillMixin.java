package comedymonkey.dailies.mixin.playback;

import comedymonkey.dailies.playback.VoidSpill;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** In playback an item that falls out of the world is left to fall for a while instead of going at once ({@link VoidSpill}) */
@Mixin(Entity.class)
public abstract class VoidSpillMixin {

    @Inject(method = "onBelowWorld", at = @At("HEAD"), cancellable = true, require = 0)
    private void dailies$letItFall(CallbackInfo ci) {
        if (VoidSpill.keeps((Entity) (Object) this)) {
            ci.cancel();
        }
    }

}
