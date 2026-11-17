package comedymonkey.dailies.mixin.looks;

import com.mojang.blaze3d.vertex.PoseStack;
import comedymonkey.dailies.looks.LooksRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelReader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Entities a scene hides (a whole kind, or one picked by itself) are not drawn at all, and the viewer's own shadow
 * is left out when the scene says so (specification chapter V, "What is drawn").
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void dailies$hidden(Entity entity, Frustum frustum, double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
        if (LooksRenderer.hidesEntity(entity)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "renderShadow", at = @At("HEAD"), cancellable = true)
    private static void dailies$noOwnShadow(PoseStack poseStack, MultiBufferSource buffers, Entity entity, float strength, float partialTick, LevelReader level,
                                            float radius, CallbackInfo ci) {
        if (LooksRenderer.hidesShadow(entity)) {
            ci.cancel();
        }
    }

}
