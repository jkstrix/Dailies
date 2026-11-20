package comedymonkey.dailies.mixin.voicechat;

import comedymonkey.dailies.playback.Playback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Simple Voice Chat draws a status icon of the viewer's own in a corner of the screen: not connected, speaker off,
 * microphone off, talking, whispering. In playback the game is connected to no voice chat server, so it showed "not
 * connected" over playback and in exports, with or without the voice chat look (the owner, 20 November: "I should
 * never see that voice chat icon during playback"). So while a recording is open, that icon is never drawn. The look's
 * own parts are drawn elsewhere by voice chat and are untouched: the group display beside it, and the icons over
 * players' heads. Skipped without complaint when voice chat is absent or has changed.
 */
@Pseudo
@Mixin(targets = "de.maxhenkel.voicechat.voice.client.RenderEvents", remap = false)
public abstract class VoiceChatHudMixin {

    @Inject(method = "renderIcon(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/resources/ResourceLocation;)V",
        at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void dailies$noOwnIconInPlayback(CallbackInfo info) {
        if (Playback.active()) {
            info.cancel();
        }
    }

}
