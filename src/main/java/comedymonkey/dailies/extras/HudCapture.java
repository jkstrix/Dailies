package comedymonkey.dailies.extras;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.capture.GameCodec;
import comedymonkey.dailies.capture.Recorder;
import comedymonkey.dailies.capture.SnapshotExtensions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;

/**
 * The recording half of the recorder's HUD (specification REC-13). Always recorded (the owner, 18 November: the HUD is
 * kept in every recording, and the editor chooses whether to show it): written into every snapshot, and again at the
 * end of any recorded tick on which something in it changed.
 */
final class HudCapture {

    /** What the recording holds as the HUD now, or null when it holds none */
    private static HudState written = null;
    private static boolean failed = false;

    private HudCapture() {
    }

    static void init() {
        SnapshotExtensions.register(HudState.ID, HudCapture::snapshot);
        Recorder.everyRecordedTick(HudCapture::tick);
    }

    private static HudState now() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.gameMode == null) {
            return null;
        }
        return HudState.of(player, minecraft.gameMode.getPlayerMode());
    }

    private static void snapshot(Recorder.Out out, GameCodec codec) {
        // A snapshot says what the HUD is for a jump that lands on it. Plain play passes over snapshots, so what was last
        // written for play is left as it was: a change that falls on the very tick of a snapshot is then still written
        // after it. (It used to be taken as written, and play showed the old HUD until the next change.)
        HudState state = now();
        if (state == null) {
            return;
        }
        out.extension(HudState.ID, state.encode(codec.registries(), codec.connectionType()));
    }

    private static void tick() {
        try {
            HudState state = now();
            if (state == null) {
                if (written != null) {
                    // Switched off part way through: playback stops showing the last HUD it was given
                    written = null;
                    Recorder.get().writeExtension(HudState.ID, HudState.NONE);
                }
                return;
            }
            if (written != null && state.same(written)) {
                return;
            }
            ClientPacketListener listener = Minecraft.getInstance().getConnection();
            if (listener == null) {
                return;
            }
            Recorder.get().writeExtension(HudState.ID, state.encode(listener.registryAccess(), listener.getConnectionType()));
            state.keep();
            written = state;
        } catch (Throwable t) {
            if (!failed) {
                failed = true;
                Host.LOGGER.warn("Dailies: the recorder's HUD could not be recorded. The rest of the recording is unharmed. Said once", t);
            }
        }
    }

}
