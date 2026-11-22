package comedymonkey.dailies.mixin.playback;

import comedymonkey.dailies.playback.Playback;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.storage.ValueInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A beacon writes its level (how many tiers of its pyramid it found) with the rest of what it says of itself, but never
 * reads it back: a game works it out afresh only when the game time is a multiple of 80. The live game had it worked
 * out long since; a jump, which builds the beacon again from the snapshot's chunk, left it at nought, beam off, for up
 * to four seconds. So while a snapshot is being read, the level the live game had is taken from what it wrote. Nothing
 * else changes: a beacon arriving in play is read as the game reads it.
 */
@Mixin(BeaconBlockEntity.class)
public abstract class BeaconLevelsMixin {

    @Shadow
    private int levels;

    @Inject(method = "loadAdditional(Lnet/minecraft/world/level/storage/ValueInput;)V", at = @At("TAIL"))
    private void dailies$levelsFromSnapshot(ValueInput input, CallbackInfo ci) {
        if (Playback.readingSnapshot()) {
            input.getInt("Levels").ifPresent(levels -> this.levels = levels);
        }
    }
}
