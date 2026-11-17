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

    /**
     * Whether the recorder wants to see this connection's packets as the game applies them. Asked on the network thread,
     * where it cannot be known whether a packet will be applied before or after the next snapshot; so the answer is yes
     * for as long as there is a recording at all, and what to do with each packet is decided when it is applied.
     */
    static boolean wantsApplied() {
        return Recorder.get().state() != Recorder.State.IDLE;
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

    /** Something a player's own act set off in the world, which the server tells everyone but them */
    public static void ownEvent(net.minecraft.world.entity.player.Player player, int type, net.minecraft.core.BlockPos pos, int data) {
        try {
            Recorder.get().onOwnEvent(player, type, pos, data);
        } catch (Throwable t) {
            failed(t);
        }
    }

    // The game plays a sound the server sent through the same door as one of the player's own making, naming its own
    // player both times. The server's is already in the recording as the packet it came in; it is told apart by its seed
    private static boolean serverSound = false;
    private static long serverSeed = 0L;

    /** The game is about to play a sound the server sent. Client thread */
    public static void soundFromServer(long seed) {
        serverSound = true;
        serverSeed = seed;
    }

    /**
     * A sound the game plays for a player of its own accord, which the server sends everyone else: the recorder's own
     * footsteps, and the sounds of what they do. {@code entity} is the entity it follows, or null for a sound at a place
     */
    public static void ownSound(net.minecraft.world.entity.player.Player player, @org.jetbrains.annotations.Nullable net.minecraft.world.entity.Entity entity,
                                double x, double y, double z, net.minecraft.core.Holder<net.minecraft.sounds.SoundEvent> sound, net.minecraft.sounds.SoundSource source,
                                float volume, float pitch, long seed) {
        if (serverSound && seed == serverSeed) {
            serverSound = false;
            return;
        }
        try {
            Recorder.get().onOwnSound(player, entity, x, y, z, sound, source, volume, pitch, seed);
        } catch (Throwable t) {
            failed(t);
        }
    }

    /** Cracks on a block being mined, by whom. Only the recorder's own are written: the server sends everyone else's */
    public static void cracks(int breaker, net.minecraft.core.BlockPos pos, int stage) {
        try {
            Recorder.get().onCracks(breaker, pos, stage);
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
