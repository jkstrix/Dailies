package comedymonkey.dailies.capture;

import comedymonkey.dailies.mixin.access.ClientLevelAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.lighting.LevelLightEngine;

import java.util.Deque;

/**
 * When the game takes in its light, recorded so that playback takes it in at the same moments.
 * <p>
 * A game is sent each chunk's light with the chunk, and works out for itself what a block that changes does to the
 * light. What it is sent waits in a queue that it takes from once a frame, a tenth of what is waiting, and the blocks it
 * has to work out wait for the same moment. A block that changes in a chunk whose light is still waiting is worked out
 * against no light and comes to nothing; the chunk's light then arrives as the server sent it. After a teleport, with
 * hundreds of chunks arriving, that goes on for seconds. Playback used to take everything waiting in at the start of
 * every tick, so it worked those same changes out against light the live game did not have yet, and through chunks
 * next door that had not arrived: a cave lit from the chunk next door came out a level darker than the live game had
 * it, for good (the shipwreck in the tour of generated terrain).
 * <p>
 * So the live game's every light pass is recorded with how much of the queue it took, and in playback, once a
 * recording is seen to have them, light is taken in at those passes and nowhere else while the recording runs. Older
 * recordings, without them, play as before.
 */
public final class LightPasses {

    /** The extension record of one pass: how many waiting light jobs the game took, then worked out its changed blocks */
    public static final String ID = "dailies:light";

    /** Live: how long the queue was when the game began to take from it, or -1 */
    private static int waiting = -1;
    /** Live: jobs taken since the last pass was written */
    private static int taken = 0;
    /** Playback: the recording says when its light was taken in */
    private static boolean paced = false;
    /** Playback: a pass Dailies asked for itself, which goes ahead */
    private static int ours = 0;

    private LightPasses() {
    }

    // --- Both: called from mixin.capture.ClientLevelMixin and LevelLightEngineMixin, on any thread ---

    /** The game is about to take from its queue of light. True when playback holds it back */
    public static boolean beforePoll(ClientLevel level) {
        if (comedymonkey.dailies.playback.Playback.active()) {
            return held();
        }
        waiting = live() ? queue(level).size() : -1;
        return false;
    }

    public static void afterPoll(ClientLevel level) {
        if (waiting >= 0) {
            taken += Math.max(0, waiting - queue(level).size());
            waiting = -1;
        }
    }

    /** A light engine is about to work out its changed blocks. True when playback holds it back */
    public static boolean beforeRun(LevelLightEngine engine) {
        Minecraft minecraft = Minecraft.getInstance();
        // The integrated server's own engines come through here too, on their own threads
        if (!minecraft.isSameThread() || minecraft.level == null || minecraft.level.getChunkSource().getLightEngine() != engine) {
            return false;
        }
        if (comedymonkey.dailies.playback.Playback.active()) {
            return held();
        }
        if (live() && (taken > 0 || engine.hasLightWork())) {
            Recorder.get().writeExtension(ID, varInt(taken));
        }
        taken = 0;
        return false;
    }

    /**
     * Whether the live game's light passes are to be written now. Not while a snapshot is taken: it takes in all the
     * light there is, and playback does the same where each segment begins
     */
    private static boolean live() {
        return !Snapshots.taking() && Recorder.get().capturing();
    }

    // --- Playback ---

    /** A new recording: whether it has light passes is not known until one is met */
    public static void reset() {
        paced = false;
        ours = 0;
    }

    /** Whether light is taken in only where the recording says */
    public static boolean paced() {
        return paced;
    }

    /**
     * Whether a light pass the game makes by itself is held back: while the recording runs, a jump is under way or an
     * export is made. Paused, the game's own passes go ahead, as nothing new arrives meanwhile
     */
    private static boolean held() {
        return paced && ours == 0
            && (!comedymonkey.dailies.playback.Playback.paused() || comedymonkey.dailies.playback.Playback.jumping() || comedymonkey.dailies.export.Export.active());
    }

    /** One light pass of the live game, as recorded. Client thread */
    public static void play(byte[] data, int from, int length) {
        paced = true;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        int count = readVarInt(data, from, from + length);
        Deque<Runnable> queue = queue(level);
        ours++;
        try {
            for (int i = 0; i < count; i++) {
                Runnable job = queue.poll();
                if (job == null) {
                    break;
                }
                job.run();
            }
            level.getChunkSource().getLightEngine().runLightUpdates();
        } finally {
            ours--;
        }
    }

    /** Takes in all the light there is and works out every changed block, held back or not. Client thread */
    public static void settle(ClientLevel level) {
        ours++;
        try {
            for (int i = 0; i < 100_000 && !queue(level).isEmpty(); i++) {
                level.pollLightUpdates();
            }
            level.getChunkSource().getLightEngine().runLightUpdates();
        } finally {
            ours--;
        }
    }

    private static Deque<Runnable> queue(ClientLevel level) {
        return ((ClientLevelAccess) level).dailies$lightQueue();
    }

    static byte[] varInt(int value) {
        byte[] out = new byte[5];
        int length = 0;
        while ((value & ~0x7F) != 0) {
            out[length++] = (byte) (value & 0x7F | 0x80);
            value >>>= 7;
        }
        out[length++] = (byte) value;
        return java.util.Arrays.copyOf(out, length);
    }

    static int readVarInt(byte[] data, int from, int end) {
        int value = 0;
        for (int shift = 0, at = from; at < end && shift < 35; shift += 7, at++) {
            value |= (data[at] & 0x7F) << shift;
            if ((data[at] & 0x80) == 0) {
                break;
            }
        }
        return value;
    }
}
