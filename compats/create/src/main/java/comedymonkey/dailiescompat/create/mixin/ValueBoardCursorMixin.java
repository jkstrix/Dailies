package comedymonkey.dailiescompat.create.mixin;

import comedymonkey.dailiescompat.create.client.RecordedScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Create's value settings board puts the player's mouse pointer on the value set as it lays itself out, which is how the
 * board starts at the block's own setting. A board built for playback is laid out again each time the value picked
 * changes, and must leave the pointer of whoever is watching alone: the recorded pointer is drawn over it instead.
 * Skipped without complaint when Create is absent or has changed.
 */
@Pseudo
@Mixin(targets = "com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsScreen", remap = false)
public abstract class ValueBoardCursorMixin {

    @Inject(method = "setCursor(Lnet/minecraft/world/phys/Vec2;)V", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void dailiesCreate$leavePointerInPlayback(CallbackInfo ci) {
        if ((Object) this instanceof RecordedScreen) {
            ci.cancel();
        }
    }

}
