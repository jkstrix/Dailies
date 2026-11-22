package comedymonkey.dailies.playback;

import comedymonkey.dailies.Host;
import net.minecraft.network.chat.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * What may be written in the chat while a recording plays: what the recording holds (its chat, and what the server's
 * mods said, as it arrives in recorded packets) and what Dailies itself says (its notes and the answers to /dailies).
 * Nothing else. Other mods write to the chat of their own accord when a world opens, and playback opens worlds: Distant
 * Horizons, seeing a replay, wrote four lines about where to put its databases, which then stood in the recording's
 * chat and in exports (the owner, 18 November). Such lines are left out, and each is said once in the log instead.
 * Render thread.
 */
public final class PlaybackChat {

    /** How deep the feed is in applying a recorded packet that may speak in the chat */
    private static int recorded = 0;
    /** How deep Dailies is in saying something of its own */
    private static int ours = 0;
    private static final Set<String> leftOut = new HashSet<>();

    private PlaybackChat() {
    }

    static void enterRecorded() {
        recorded++;
    }

    static void leaveRecorded() {
        recorded = Math.max(0, recorded - 1);
    }

    /** Says something of Dailies' own in the chat, whatever is playing */
    public static void say(Component message) {
        ours++;
        try {
            net.minecraft.client.Minecraft.getInstance().gui.getChat().addMessage(message);
        } finally {
            ours--;
        }
    }

    /** Whether a line may go into the chat now. Asked by the chat itself (mixin.playback.ChatMixin) */
    public static boolean allowed(Component message) {
        if (!Playback.active() || recorded > 0 || ours > 0 || Boolean.getBoolean("dailies.playback.everyChatLine")) {
            return true;
        }
        // The answers to /dailies, typed in playback, are written by its command as it runs
        boolean command = StackWalker.getInstance().walk(frames -> frames.anyMatch(frame -> frame.getClassName().startsWith("comedymonkey.dailies.command.")));
        if (command) {
            return true;
        }
        String text = message == null ? "" : message.getString();
        if (leftOut.size() < 200 && leftOut.add(text)) {
            Host.LOGGER.info("Dailies: a chat line no recording holds was left out of playback: \"{}\"", text.length() > 200 ? text.substring(0, 200) + "..." : text);
        }
        return false;
    }

}
