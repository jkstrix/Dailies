package comedymonkey.dailiescompat.create.mixin;

import comedymonkey.dailiescompat.create.client.CreateMarks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Catnip's cluster outline (the glued area a super glue selection covers, and others of Create's) keeps only the faces
 * it works out from its blocks, so the blocks are noted as it is made, for the compat to record. Skipped without
 * complaint when Catnip is absent or has changed.
 */
@Pseudo
@Mixin(targets = "net.createmod.catnip.outliner.BlockClusterOutline", remap = false)
public abstract class ClusterOutlineMixin {

    @Inject(method = "<init>(Ljava/lang/Iterable;)V", at = @At("RETURN"), remap = false, require = 0)
    private void dailiesCreate$noteBlocks(Iterable<?> positions, CallbackInfo ci) {
        CreateMarks.clusterMade(this, positions);
    }

}
