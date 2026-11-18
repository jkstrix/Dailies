package comedymonkey.dailies.mixin.moments;

import comedymonkey.dailies.moments.Moments;
import net.minecraft.advancements.AdvancementTree;
import net.minecraft.client.multiplayer.ClientAdvancements;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * There is no client event for earned advancements, so the moments system reads them from the advancement packet.
 */
@Mixin(ClientAdvancements.class)
public class MixinClientAdvancements {

    @Shadow
    @Final
    private AdvancementTree tree;

    @Inject(method = "update", at = @At("RETURN"))
    public void dailies$momentsUpdate(ClientboundUpdateAdvancementsPacket packet, CallbackInfo ci) {
        Moments.onAdvancements(packet, this.tree);
    }

}
