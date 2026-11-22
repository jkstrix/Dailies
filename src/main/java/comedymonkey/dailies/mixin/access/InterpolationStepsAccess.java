package comedymonkey.dailies.mixin.access;

import net.minecraft.world.entity.AbstractInterpolationHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** How many steps an entity eases over, which playback sets to one for a rider it moves with their craft */
@Mixin(AbstractInterpolationHandler.class)
public interface InterpolationStepsAccess {

    @Accessor("interpolationSteps")
    int dailies$steps();

    @Accessor("interpolationSteps")
    void dailies$setSteps(int steps);

}
