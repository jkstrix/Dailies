package comedymonkey.dailies.mixin.playback;

import comedymonkey.dailies.playback.PlaybackChat;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Only what the recording holds, and Dailies' own words, are written in the chat during playback ({@link PlaybackChat}) */
@Mixin(ChatComponent.class)
public abstract class ChatMixin {

    @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
        at = @At("HEAD"), cancellable = true, require = 0)
    private void dailies$onlyWhatWasRecorded(Component message, MessageSignature signature, net.minecraft.client.multiplayer.chat.GuiMessageSource source, GuiMessageTag tag,
                                             CallbackInfo ci) {
        if (!PlaybackChat.allowed(message)) {
            ci.cancel();
        }
    }

}
