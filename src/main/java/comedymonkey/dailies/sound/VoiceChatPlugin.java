package comedymonkey.dailies.sound;

import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.ClientReceiveSoundEvent;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;

/**
 * The mod's plugin for Simple Voice Chat (specification REC-18 and MOM-30). That mod finds this class by its
 * annotation and creates it; nothing in Dailies ever names it, so it is only loaded when the voice chat is installed.
 * It listens for two things through the public API and nothing else: sound the player is about to hear, and the
 * player's own voice as the voice chat sends it. The microphone is never opened here.
 */
@ForgeVoicechatPlugin
public final class VoiceChatPlugin implements VoicechatPlugin {

    @Override
    public String getPluginId() {
        return "dailies";
    }

    @Override
    public void initialize(VoicechatApi api) {
        VoiceCapture.api = api;
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(ClientReceiveSoundEvent.EntitySound.class, VoiceCapture::heardEntity);
        registration.registerEvent(ClientReceiveSoundEvent.LocationalSound.class, VoiceCapture::heardPlace);
        registration.registerEvent(ClientReceiveSoundEvent.StaticSound.class, VoiceCapture::heardEverywhere);
        registration.registerEvent(ClientSoundEvent.class, VoiceCapture::spoke);
    }

}
