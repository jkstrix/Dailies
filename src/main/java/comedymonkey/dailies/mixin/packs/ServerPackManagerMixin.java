package comedymonkey.dailies.mixin.packs;

import com.google.common.hash.HashCode;
import comedymonkey.dailies.packs.GamePacks;
import net.minecraft.client.resources.server.ServerPackManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.URL;
import java.util.List;
import java.util.UUID;

/**
 * Lets Dailies see what becomes of each server resource pack (REC-19): the game reports that only to the server, and
 * forgets a pack that was declined or failed in the same breath. Nothing is changed.
 */
@Mixin(ServerPackManager.class)
public abstract class ServerPackManagerMixin {

    @Shadow
    @Final
    private List<ServerPackManager.ServerPackData> packs;

    @Shadow
    private ServerPackManager.PackPromptStatus packPromptStatus;

    // Every change to a pack ends in a pass of the manager, and this is the last moment a dropped pack is still listed
    @Inject(method = "cleanupRemovedPacks", at = @At("HEAD"))
    private void dailies$scan(CallbackInfo ci) {
        GamePacks.scanned(this.packs);
    }

    @Inject(method = "pushPack", at = @At("HEAD"))
    private void dailies$declined(UUID id, URL url, HashCode hash, CallbackInfo ci) {
        if (this.packPromptStatus == ServerPackManager.PackPromptStatus.DECLINED) {
            // Refused without ever being listed
            GamePacks.declinedAtOnce(id);
        }
    }

}
