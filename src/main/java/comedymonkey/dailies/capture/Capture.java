package comedymonkey.dailies.capture;

import comedymonkey.dailies.Host;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.world.InteractionHand;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where the game's hooks meet recording. Everything here must be cheap and must never throw into the game.
 */
public final class Capture {

    /**
     * What came bundled with each entity's spawn, by entity id: the mod messages that carry what vanilla's spawn
     * packet cannot, such as the blocks of a Create contraption. Kept from the moment of joining a world, recording
     * or not, because a snapshot taken later has no other way to get them back.
     */
    private static final Map<Integer, List<byte[]>> SPAWN_EXTRAS = new ConcurrentHashMap<>();

    private Capture() {
    }

    /** A game packet has been decoded. Network thread */
    static void arrived(Packet<?> packet, List<byte[]> frames) {
        if (packet instanceof ClientboundBundlePacket bundle) {
            int entity = Integer.MIN_VALUE;
            List<byte[]> extras = null;
            int index = 1; // frame 0 is the bundle's opening delimiter
            for (Packet<?> part : bundle.subPackets()) {
                if (part instanceof ClientboundAddEntityPacket spawn) {
                    entity = spawn.getId();
                } else if (part instanceof ClientboundCustomPayloadPacket && entity != Integer.MIN_VALUE && index < frames.size()) {
                    if (extras == null) {
                        extras = new ArrayList<>();
                    }
                    extras.add(frames.get(index));
                }
                index++;
            }
            if (extras != null && frames.size() == index + 1) {
                SPAWN_EXTRAS.put(entity, extras);
            }
        } else if (packet instanceof ClientboundRemoveEntitiesPacket removed) {
            for (int id : removed.getEntityIds()) {
                SPAWN_EXTRAS.remove(id);
            }
        } else if (packet instanceof ClientboundLoginPacket) {
            SPAWN_EXTRAS.clear();
        }
    }

    /** The mod messages that came with an entity's spawn, each as a whole packet, or null */
    public static List<byte[]> spawnExtras(int entityId) {
        return SPAWN_EXTRAS.get(entityId);
    }

    static boolean wantsApplied() {
        return Recorder.get().capturing();
    }

    /** The game is about to apply this packet. Client thread */
    static void applied(Packet<?> packet, List<byte[]> frames) {
        try {
            Recorder.get().onPacket(packet, frames);
        } catch (Throwable t) {
            failed(t);
        }
    }

    public static void ownSwing(LocalPlayer player, InteractionHand hand) {
        try {
            Recorder.get().onOwnSwing(player, hand);
        } catch (Throwable t) {
            failed(t);
        }
    }

    static void failed(Throwable t) {
        try {
            Recorder.get().fail(t);
        } catch (Throwable again) {
            Host.LOGGER.error("Dailies: recording failed and could not be stopped cleanly", again);
        }
    }

}
