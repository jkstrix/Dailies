package comedymonkey.dailies.compat.voicechat;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which of Simple Voice Chat's recorded messages the voice chat look may read, and which stay blocked */
class VoiceChatPayloadsTest {

    @Test
    void onlyPlayerStatesAndGroupsAreRead() {
        assertEquals(VoiceChatPayloads.Kind.STATE, VoiceChatPayloads.shown("voicechat:state"));
        assertEquals(VoiceChatPayloads.Kind.STATES, VoiceChatPayloads.shown("voicechat:states"));
        assertEquals(VoiceChatPayloads.Kind.REMOVE_STATE, VoiceChatPayloads.shown("voicechat:remove_state"));
        assertEquals(VoiceChatPayloads.Kind.ADD_GROUP, VoiceChatPayloads.shown("voicechat:add_group"));
        assertEquals(VoiceChatPayloads.Kind.REMOVE_GROUP, VoiceChatPayloads.shown("voicechat:remove_group"));
        assertEquals(VoiceChatPayloads.Kind.JOINED_GROUP, VoiceChatPayloads.shown("voicechat:joined_group"));
        assertEquals(VoiceChatPayloads.Kind.STATES, VoiceChatPayloads.shown("VoiceChat:States"));
    }

    @Test
    void whatSetsUpAConnectionOrTouchesTheViewersOwnSettingsStaysBlocked() {
        for (String id : List.of("voicechat:secret", "voicechat:request_secret", "voicechat:update_state", "voicechat:create_group",
                "voicechat:set_group", "voicechat:leave_group", "voicechat:add_category", "voicechat:remove_category",
                "voicechat:something_new", "voicechat:", "voicechat")) {
            assertNull(VoiceChatPayloads.shown(id), id);
        }
    }

    @Test
    void otherModsMessagesAreNotVoiceChats() {
        assertTrue(VoiceChatPayloads.isVoiceChat("voicechat:secret"));
        assertTrue(VoiceChatPayloads.isVoiceChat("VOICECHAT:state"));
        assertFalse(VoiceChatPayloads.isVoiceChat("sable:freeze_player"));
        assertFalse(VoiceChatPayloads.isVoiceChat("voicechatx:state"));
        assertFalse(VoiceChatPayloads.isVoiceChat("minecraft:brand"));
        assertNull(VoiceChatPayloads.shown("othermod:state"));
        assertNull(VoiceChatPayloads.shown("minecraft:state"));
    }

    @Test
    void eachKindIsWrittenUnderTheIdItIsReadBy() {
        for (VoiceChatPayloads.Kind kind : VoiceChatPayloads.Kind.values()) {
            String id = VoiceChatPayloads.id(kind);
            assertTrue(VoiceChatPayloads.isVoiceChat(id), id);
            assertEquals(kind, VoiceChatPayloads.shown(id), id);
        }
    }

}
