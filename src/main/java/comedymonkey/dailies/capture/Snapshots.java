package comedymonkey.dailies.capture;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

/**
 * Describes the whole world as the recorder's game holds it, at the start of a segment (specification REC-20 to 23).
 */
final class Snapshots {

    private Snapshots() {
    }

    static void write(Recorder.Out out, Minecraft minecraft, ClientPacketListener listener, GameCodec codec) {
    }

}
