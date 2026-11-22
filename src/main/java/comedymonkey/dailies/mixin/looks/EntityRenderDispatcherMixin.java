package comedymonkey.dailies.mixin.looks;

import comedymonkey.dailies.looks.LooksRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Entities a scene hides (a whole kind, or one picked by itself) are not drawn at all (specification chapter V, "What
 * is drawn"). The viewer's own shadow, the count of entities drawn and the per-entity changes are made where each
 * entity's picture is taken from it ({@link LooksRenderer}).
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void dailies$hidden(Entity entity, Frustum frustum, double x, double y, double z, float partialTicks, CallbackInfoReturnable<Boolean> cir) {
        if (LooksRenderer.hidesEntity(entity)) {
            cir.setReturnValue(false);
        }
    }

}
