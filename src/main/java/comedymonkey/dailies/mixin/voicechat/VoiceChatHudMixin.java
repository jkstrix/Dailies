package comedymonkey.dailies.mixin.voicechat;

import comedymonkey.dailies.compat.voicechat.VoiceChatLook;
import comedymonkey.dailies.playback.Playback;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Simple Voice Chat's corner of the screen: the viewer's own voice status (not connected, voice chat off, talking,
 * whispering) and the group display. In playback the game is connected to no voice chat server, so voice chat itself
 * would show "not connected" over playback and in exports. While a recording is open the corner shows instead what
 * the recorder's showed, by the owner's rule for everything on the recorder's screen: riding along with the recorder in
 * first person, the HUD look and the voice chat look on (the owner, 20 November). The recorder's status comes from
 * their recorded voice chat state and recorded voice, and the group display from the recorded group the look has put
 * into voice chat. Voice chat draws both itself, with its own settings for where and how large. At every other time in
 * playback the corner is empty. Skipped without complaint when voice chat is absent or has changed.
 */
@Pseudo
@Mixin(targets = "de.maxhenkel.voicechat.voice.client.RenderEvents", remap = false)
public abstract class VoiceChatHudMixin {

    @Shadow(remap = false)
    private void renderIcon(GuiGraphicsExtractor graphics, Identifier texture) {
        throw new AssertionError();
    }

    @Inject(method = "onRenderHUD(Lnet/minecraft/client/gui/GuiGraphicsExtractor;F)V", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void dailies$recordersCorner(GuiGraphicsExtractor graphics, float delta, CallbackInfo info) {
        if (!Playback.active()) {
            return;
        }
        info.cancel();
        if (!VoiceChatLook.hudShown()) {
            return;
        }
        String icon = VoiceChatLook.hudIcon();
        if (icon != null) {
            this.renderIcon(graphics, Identifier.fromNamespaceAndPath("voicechat", icon));
        }
        VoiceChatLook.drawGroup(graphics);
    }

}
