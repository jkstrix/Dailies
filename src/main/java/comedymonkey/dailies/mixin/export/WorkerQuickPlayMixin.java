package comedymonkey.dailies.mixin.export;

import comedymonkey.dailies.export.Worker;
import net.minecraft.client.quickplay.QuickPlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The background export game never joins a world (specification EXP-13). Its command line has the game's "join
 * this world at start" options taken out; this is for a launcher that hides them in a file Dailies cannot read.
 */
@Mixin(QuickPlay.class)
public abstract class WorkerQuickPlayMixin {

    @Inject(method = "connect", at = @At("HEAD"), cancellable = true, require = 0)
    private static void dailies$workerJoinsNothing(CallbackInfo ci) {
        if (Worker.active()) {
            ci.cancel();
        }
    }

}
