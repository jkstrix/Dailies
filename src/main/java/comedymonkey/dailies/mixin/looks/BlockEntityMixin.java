package comedymonkey.dailies.mixin.looks;

import com.mojang.blaze3d.vertex.PoseStack;
import comedymonkey.dailies.looks.Drawn;
import comedymonkey.dailies.looks.LooksRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Block entities go with the blocks when a scene leaves the blocks out, and a beacon's beam can be left out by
 * itself (specification chapter V, "What is drawn"). The beam is all a beacon's renderer draws, so turning the
 * beacon away here takes the beam and nothing else.
 */
@Mixin(BlockEntityRenderDispatcher.class)
public abstract class BlockEntityMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private <E extends BlockEntity> void dailies$hidden(E blockEntity, float partialTick, PoseStack poseStack, MultiBufferSource buffers, CallbackInfo ci) {
        boolean beacon = blockEntity instanceof BeaconBlockEntity;
        boolean hidden = LooksRenderer.hidesBlocks() || beacon && LooksRenderer.hidesBeaconBeams();
        Drawn.blockEntity(hidden, beacon);
        if (hidden) {
            ci.cancel();
        }
    }

}
