package comedymonkey.dailies.compat.voicechat;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;

/**
 * Which of Simple Voice Chat's recorded messages playback may show again (the "voice_chat" look). Every
 * {@code voicechat:*} message stays out of the game's own network handling in playback (playback.PayloadRules): its
 * handlers would write the recording's names into the viewer's own username cache, open the voice chat's screens and
 * say things in chat. The few that only say what voice chat knows about players and groups are read here instead, and
 * what they say is put into voice chat's client state by {@link VoiceChatLook} while the look is on.
 * <p>
 * Read, because they only carry state that is drawn:
 * <ul>
 * <li>{@code voicechat:state}, {@code voicechat:states}, {@code voicechat:remove_state}: one player's, every player's,
 * or the end of a player's state: name, whether their voice chat is turned off, whether it is connected, their group.</li>
 * <li>{@code voicechat:add_group}, {@code voicechat:remove_group}: the groups there are, by id, name and kind.</li>
 * <li>{@code voicechat:joined_group}: which group the recorder was in.</li>
 * </ul>
 * Never read, whatever the look says:
 * <ul>
 * <li>{@code voicechat:secret}: the address and port of the recorder's voice server, the recorder's secret key and
 * player id. It is what makes voice chat open a connection.</li>
 * <li>{@code voicechat:add_category}, {@code voicechat:remove_category}: volume sliders that plugins add to the viewer's
 * own volume settings. Nothing of them is drawn over the world.</li>
 * <li>{@code voicechat:request_secret}, {@code update_state}, {@code create_group}, {@code set_group},
 * {@code leave_group}: sent by a game to its server, never to a game, so never recorded; listed so they are known.</li>
 * <li>Any other id: a newer voice chat's, unknown here, so not trusted.</li>
 * </ul>
 * Plain Java, so the decision can be tested without the game.
 */
public final class VoiceChatPayloads {

    public static final String NAMESPACE = "voicechat";

    /** What a message that is read says */
    public enum Kind {
        /** One player's state */
        STATE,
        /** Every player's state, in place of all that were known */
        STATES,
        /** A player has no state any more */
        REMOVE_STATE,
        ADD_GROUP,
        REMOVE_GROUP,
        /** The group the recorder is in, or none */
        JOINED_GROUP
    }

    private static final Map<String, Kind> READ = Map.of(
        "voicechat:state", Kind.STATE,
        "voicechat:states", Kind.STATES,
        "voicechat:remove_state", Kind.REMOVE_STATE,
        "voicechat:add_group", Kind.ADD_GROUP,
        "voicechat:remove_group", Kind.REMOVE_GROUP,
        "voicechat:joined_group", Kind.JOINED_GROUP);

    private VoiceChatPayloads() {
    }

    /** Whether a message is Simple Voice Chat's at all */
    public static boolean isVoiceChat(String id) {
        return id.toLowerCase(Locale.ROOT).startsWith(NAMESPACE + ":");
    }

    /** What a recorded message says that may be shown, or null when it stays blocked */
    public static @Nullable Kind shown(String id) {
        return READ.get(id.toLowerCase(Locale.ROOT));
    }

    /** The message id each kind is recorded under, for writing the state into a snapshot */
    public static String id(Kind kind) {
        for (Map.Entry<String, Kind> entry : READ.entrySet()) {
            if (entry.getValue() == kind) {
                return entry.getKey();
            }
        }
        throw new IllegalArgumentException(String.valueOf(kind));
    }

}
