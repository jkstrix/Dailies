package comedymonkey.dailies.mixin.looks;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import comedymonkey.dailies.looks.LooksRenderer;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * While a scene has the sky switched off, the game is told clouds are off too, whoever draws them (specification
 * chapter V, "The world's look"). The player's own cloud setting is not touched.
 */
@Mixin(Options.class)
public abstract class CloudsOptionMixin {

    @ModifyReturnValue(method = "getCloudsType", at = @At("RETURN"))
    private CloudStatus dailies$noCloudsWithoutSky(CloudStatus chosen) {
        return LooksRenderer.skyOff() ? CloudStatus.OFF : chosen;
    }

}
