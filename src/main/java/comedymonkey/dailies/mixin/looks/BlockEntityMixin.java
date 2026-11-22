package comedymonkey.dailies.mixin.looks;

import com.mojang.blaze3d.vertex.PoseStack;
import comedymonkey.dailies.looks.Drawn;
import comedymonkey.dailies.looks.LooksRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Block entities go with the blocks when a scene leaves the blocks out (specification chapter V, "What is drawn").
 * Those that another mod draws by a road of its own are dealt with where that mod is known
 * ({@link comedymonkey.dailies.compat.create.FlywheelLooks}).
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private <E extends BlockEntity> void dailies$hidden(E blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource buffers, CallbackInfo ci) {
        boolean hidden = LooksRenderer.hidesBlocks();
        Drawn.blockEntity(hidden);
        if (hidden) {
            ci.cancel();
        }
    }

}
