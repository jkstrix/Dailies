package comedymonkey.dailies.mixin.access;

import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The game's tick clock, which playback has to set exactly: to the end of a tick after a jump, to a frame's time in an export */
@Mixin(DeltaTracker.Timer.class)
public interface TimerAccess {

    @Accessor("deltaTickResidual")
    void dailies$setPartialTick(float partialTick);

    @Accessor("deltaTickResidual")
    float dailies$partialTick();

    @Accessor("pausedDeltaTickResidual")
    void dailies$setPausedPartialTick(float partialTick);

}
