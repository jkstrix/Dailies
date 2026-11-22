package comedymonkey.dailies.mixin.looks;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import comedymonkey.dailies.looks.LooksRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A scene can force the glowing outline onto an entity or take it away (specification chapter V, per-entity
 * changes). Asked where the game decides whether to draw the outline, so the entity itself is never altered.
 */
@Mixin(Minecraft.class)
public abstract class GlowMixin {

    @ModifyReturnValue(method = "shouldEntityAppearGlowing", at = @At("RETURN"))
    private boolean dailies$glow(boolean recorded, Entity entity) {
        return LooksRenderer.glows(entity, recorded);
    }

}
