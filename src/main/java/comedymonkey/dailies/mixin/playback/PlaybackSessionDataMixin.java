package comedymonkey.dailies.mixin.playback;

import comedymonkey.dailies.playback.Playback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.neoforged.neoforge.client.attachment.ClientSessionData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * NeoForge keeps a client's own attachments in a file named after the singleplayer world or the server, and refuses a
 * connection that has neither. Playback's connection is to a recording: it gets a fresh store of its own, kept in
 * Dailies' folder, so nothing from a real world or server is read into a replay or written from one.
 */
@Mixin(ClientSessionData.class)
public abstract class PlaybackSessionDataMixin {

    @Inject(method = "create", at = @At("HEAD"), cancellable = true, remap = false)
    private static void dailies$playbackStore(Minecraft minecraft, ClientPacketListener listener, CallbackInfoReturnable<ClientSessionData> cir) {
        if (Playback.creatingSession()) {
            cir.setReturnValue(new ClientSessionData(listener.registryAccess(),
                minecraft.gameDirectory.toPath().resolve("dailies").resolve("playback-session-attachments.dat")));
        }
    }
}
