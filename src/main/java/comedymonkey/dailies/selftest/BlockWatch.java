package comedymonkey.dailies.selftest;

import comedymonkey.dailies.capture.Recorder;
import comedymonkey.dailies.playback.Playback;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Watches for the recorder being inside a solid block. Live, it notes every recording tick on which the recorder's
 * body overlapped a block; in playback it notes the same for the recorded player. A recorder who walked round a block
 * live must not pass through it when watched.
 */
final class BlockWatch {

    static final Set<Integer> liveTicks = new TreeSet<>();
    static final Set<Integer> playbackTicks = new TreeSet<>();
    private static boolean registered = false;
    private static volatile boolean watching = false;

    private BlockWatch() {
    }

    static synchronized void start() {
        if (!registered) {
            registered = true;
            NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> tick());
        }
        watching = true;
    }

    static void stop() {
        watching = false;
    }

    /** Whether an entity's body overlaps a solid block, with a little slack for standing against a wall */
    static boolean inBlock(Entity entity) {
        return !entity.level().noBlockCollision(entity, entity.getBoundingBox().deflate(0.1));
    }

    private static void tick() {
        if (!watching) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        try {
            if (Playback.loaded()) {
                if (Playback.paused() || Playback.jumping() || Playback.manifest() == null) {
                    return;
                }
                Entity recorder = minecraft.level.getPlayerByUUID(UUID.fromString(Playback.manifest().recorderId));
                if (recorder != null && inBlock(recorder)) {
                    playbackTicks.add(Playback.position());
                }
            } else if (Recorder.get().state() == Recorder.State.RECORDING && minecraft.player != null && inBlock(minecraft.player)) {
                liveTicks.add(Recorder.get().recordingTick());
            }
        } catch (RuntimeException ignored) {
            // A world half way through changing: nothing to note this tick
        }
    }

}
