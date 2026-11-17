package comedymonkey.dailies.mixin.export;

import comedymonkey.dailies.export.Worker;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The background export game shares the game's settings and must never write them (specification EXP-13): it turns
 * its own sound down and its window away, and none of that may reach the options of the game being played.
 */
@Mixin(Options.class)
public abstract class WorkerOptionsMixin {

    @Inject(method = "save", at = @At("HEAD"), cancellable = true)
    private void dailies$workerSavesNothing(CallbackInfo ci) {
        if (Worker.active()) {
            ci.cancel();
        }
    }

}
