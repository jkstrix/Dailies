package comedymonkey.dailies.mixin.looks;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import comedymonkey.dailies.looks.LooksRenderer;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A scene can make the weather clear, rainy or stormy whatever was recorded (specification chapter V, "The world's
 * look"). Only what the playback world reports is changed; the recorded weather underneath is left as it is, so
 * going back to "as recorded" shows it again.
 */
@Mixin(Level.class)
public abstract class WeatherMixin {

    @Shadow
    @Final
    public boolean isClientSide;

    @ModifyReturnValue(method = "getRainLevel", at = @At("RETURN"))
    private float dailies$rain(float recorded) {
        return this.isClientSide ? LooksRenderer.rain((Level) (Object) this, recorded) : recorded;
    }

    @ModifyReturnValue(method = "getThunderLevel", at = @At("RETURN"))
    private float dailies$thunder(float recorded) {
        return this.isClientSide ? LooksRenderer.thunder((Level) (Object) this, recorded) : recorded;
    }

}
