package comedymonkey.dailies.sound;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.capture.Recorder;
import comedymonkey.dailies.moments.Moments;
import de.maxhenkel.voicechat.api.Position;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.events.ClientReceiveSoundEvent;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What happens to voice chat sound once Simple Voice Chat hands it over (specification REC-18, MOM-30). Every piece
 * goes to Moments, which keeps its own dialogue log. When the recordVoiceChat setting is on and a recording is
 * running, every piece is also written into the recording with its speaker and where the voice came from.
 * <p>
 * The voice chat's client events carry plain samples, so pieces are packed with the voice chat's own Opus encoder
 * before they are stored; a piece that cannot be packed is stored as it is. This class names the voice chat's
 * classes and must only be touched when that mod is installed.
 */
final class VoiceCapture {

    /** Set by the plugin when the voice chat starts */
    static volatile @Nullable VoicechatApi api = null;

    private static final long SILENCE_NANOS = 500_000_000L;
    private static final long IDLE_NANOS = 10_000_000_000L;

    private static final class Packer {
        OpusEncoder encoder;
        long lastNanos;
    }

    /** One encoder per speaker, used under this map's lock: the pieces arrive on the voice chat's threads */
    private static final Map<UUID, Packer> PACKERS = new HashMap<>();
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private enum Place {
        ENTITY, FIXED, OWN, NONE
    }

    private VoiceCapture() {
    }

    static void heardEntity(ClientReceiveSoundEvent.EntitySound event) {
        try {
            heard(event.getId(), event.getRawAudio(), Place.ENTITY, event.getEntityId(), 0.0, 0.0, 0.0);
        } catch (Throwable t) {
            warnOnce("heard", t);
        }
    }

    static void heardPlace(ClientReceiveSoundEvent.LocationalSound event) {
        try {
            Position position = event.getPosition();
            if (position == null) {
                heard(event.getId(), event.getRawAudio(), Place.NONE, null, 0.0, 0.0, 0.0);
            } else {
                heard(event.getId(), event.getRawAudio(), Place.FIXED, null, position.getX(), position.getY(), position.getZ());
            }
        } catch (Throwable t) {
            warnOnce("heard", t);
        }
    }

    static void heardEverywhere(ClientReceiveSoundEvent.StaticSound event) {
        try {
            heard(event.getId(), event.getRawAudio(), Place.NONE, null, 0.0, 0.0, 0.0);
        } catch (Throwable t) {
            warnOnce("heard", t);
        }
    }

    /** The player's own voice, on its way to the server */
    static void spoke(ClientSoundEvent event) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            var player = minecraft.player;
            UUID self = player != null ? player.getUUID() : minecraft.getUser().getProfileId();
            heard(self, event.getRawAudio(), Place.OWN, null, 0.0, 0.0, 0.0);
        } catch (Throwable t) {
            warnOnce("spoke", t);
        }
    }

    private static void heard(@Nullable UUID speaker, @Nullable short[] samples, Place place, @Nullable UUID entity, double x, double y, double z) {
        if (speaker == null || samples == null || samples.length == 0) {
            // An empty piece only marks the end of a stretch of speech
            return;
        }
        short[] copy = samples.clone();
        Moments.onVoiceChatAudio(speaker, copy);
        if (!Host.get().setting("recordVoiceChat", false) || Recorder.get().state() != Recorder.State.RECORDING) {
            return;
        }
        int flags = place == Place.OWN ? VoiceFormat.OWN_VOICE : 0;
        int format = VoiceFormat.PCM;
        byte[] audio = null;
        if (copy.length == VoiceFormat.FRAME_SAMPLES) {
            synchronized (PACKERS) {
                long now = System.nanoTime();
                try {
                    Packer packer = packer(speaker, now);
                    if (packer != null) {
                        if (now - packer.lastNanos > SILENCE_NANOS) {
                            packer.encoder.resetState();
                            flags |= VoiceFormat.STREAM_START;
                        }
                        packer.lastNanos = now;
                        audio = packer.encoder.encode(copy);
                        format = VoiceFormat.OPUS;
                    }
                } catch (Throwable t) {
                    warnOnce("pack", t);
                    audio = null;
                    format = VoiceFormat.PCM;
                }
            }
        }
        if (audio == null) {
            audio = VoiceFormat.pcmBytes(copy);
        }
        int finalFormat = format;
        int finalFlags = flags;
        byte[] finalAudio = audio;
        // Records may only be written on the client thread, and that is also where positions can be read
        Minecraft.getInstance().execute(() -> write(finalFormat, finalFlags, speaker, place, entity, x, y, z, finalAudio));
    }

    private static @Nullable Packer packer(UUID speaker, long now) {
        PACKERS.entrySet().removeIf(entry -> {
            if (now - entry.getValue().lastNanos > IDLE_NANOS && !entry.getKey().equals(speaker)) {
                entry.getValue().encoder.close();
                return true;
            }
            return false;
        });
        Packer packer = PACKERS.get(speaker);
        if (packer != null && packer.encoder.isClosed()) {
            PACKERS.remove(speaker);
            packer = null;
        }
        if (packer == null) {
            VoicechatApi voicechat = api;
            if (voicechat == null) {
                return null;
            }
            packer = new Packer();
            packer.encoder = voicechat.createEncoder();
            // Long ago, so that the first piece is marked as the start of a stretch
            packer.lastNanos = now - 2 * SILENCE_NANOS;
            PACKERS.put(speaker, packer);
        }
        return packer;
    }

    private static void write(int format, int flags, UUID speaker, Place place, @Nullable UUID entity, double x, double y, double z, byte[] audio) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            Vec3 where = switch (place) {
                case FIXED -> new Vec3(x, y, z);
                case OWN -> minecraft.player == null ? null : minecraft.player.getEyePosition();
                case ENTITY -> entity == null ? null : eyes(minecraft.level, entity);
                case NONE -> null;
            };
            if (where == null) {
                Recorder.get().writeExtension(VoiceFormat.ID, VoiceFormat.write(format, flags, speaker, 0.0, 0.0, 0.0, audio));
            } else {
                Recorder.get().writeExtension(VoiceFormat.ID, VoiceFormat.write(format, flags | VoiceFormat.HAS_POSITION, speaker, where.x, where.y, where.z, audio));
            }
        } catch (Throwable t) {
            warnOnce("write", t);
        }
    }

    private static @Nullable Vec3 eyes(@Nullable ClientLevel level, UUID id) {
        if (level == null) {
            return null;
        }
        Entity found = level.getPlayerByUUID(id);
        if (found == null) {
            for (Entity candidate : level.entitiesForRendering()) {
                if (id.equals(candidate.getUUID())) {
                    found = candidate;
                    break;
                }
            }
        }
        return found == null ? null : found.getEyePosition();
    }

    /** A decoder for playback, or null when the voice chat has not started */
    static @Nullable VoiceDecoder newDecoder() {
        VoicechatApi voicechat = api;
        if (voicechat == null) {
            return null;
        }
        OpusDecoder decoder = voicechat.createDecoder();
        return new VoiceDecoder() {
            @Override
            public short[] decode(byte[] opusFrame) {
                return decoder.decode(opusFrame);
            }

            @Override
            public void reset() {
                decoder.resetState();
            }

            @Override
            public void close() {
                decoder.close();
            }
        };
    }

    private static void warnOnce(String what, Throwable problem) {
        if (WARNED.add(what)) {
            Host.LOGGER.warn("Dailies: voice chat sound could not be handled ({}) and was left out. Said once per kind", what, problem);
        }
    }

}
