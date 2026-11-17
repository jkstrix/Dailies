package comedymonkey.dailies.mixin.capture;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import comedymonkey.dailies.capture.Snapshots;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Some modded block entities assume they are only ever asked to describe themselves on a server, and fail when a
 * snapshot asks on a client. While a snapshot is being taken, such a failure costs that one block entity its data
 * instead of the whole recording (specification REC-22). At any other time nothing changes.
 */
@Mixin(targets = "net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData$BlockEntityInfo")
public abstract class BlockEntityInfoMixin {

    @WrapOperation(method = "create", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/block/entity/BlockEntity;getUpdateTag(Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/nbt/CompoundTag;"))
    private static CompoundTag dailies$guardUpdateTag(BlockEntity blockEntity, HolderLookup.Provider registries, Operation<CompoundTag> original) {
        if (!Snapshots.taking()) {
            return original.call(blockEntity, registries);
        }
        try {
            return original.call(blockEntity, registries);
        } catch (Throwable t) {
            Snapshots.blockEntityFailed(blockEntity, t);
            try {
                // Its plain saved data is better than nothing: most of what a game draws is in there
                return blockEntity.saveWithoutMetadata(registries);
            } catch (Throwable again) {
                return new CompoundTag();
            }
        }
    }

}
