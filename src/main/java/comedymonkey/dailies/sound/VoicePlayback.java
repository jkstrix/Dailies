package comedymonkey.dailies.sound;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.export.Export;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.playback.PlaybackExtensions;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Plays recorded voice chat while a recording is watched (specification PLAY-16), only while the recording runs at
 * its normal speed. How loud each piece is follows {@link VoiceLevels}: a nearby speaker by their distance from the
 * camera, group chat and the recorder's own voice at full volume, and none of them placed left or right. It speaks to
 * OpenAL directly, in the game's own sound context, with every source in the middle of the listener's head and its
 * gain set here, so nothing of Simple Voice Chat is needed to hear uncompressed pieces. Pieces stored as Opus need
 * that mod's decoder; without it they stay silent.
 * <p>
 * Each speaker has one source that is fed 20 millisecond buffers as their records come by. Because the sources live
 * in the game's context, an export that captures the game's sound captures the voices with it.
 * Everything here runs on the client thread.
 */
public final class VoicePlayback implements PlaybackExtensions.Extension {

    public static final VoicePlayback INSTANCE = new VoicePlayback();

    private static final int MAX_VOICES = 8;
    /** Buffers a source may have waiting: half a second. More means the recording is not running at normal speed */
    private static final int MAX_QUEUED = 25;
    /** Silence put in front of a stretch of speech, so that pieces arriving a tick apart do not leave gaps */
    private static final int LEAD_SAMPLES = VoiceFormat.SAMPLE_RATE * 60 / 1000;
    private static final int IDLE_TICKS = 100;
    private static final double NORMAL_SPEED = 20.0;
    private static final double SPEED_TOLERANCE = 0.5;

    private static final class Voice {
        int source;
        final List<Integer> buffers = new ArrayList<>();
        final ArrayDeque<Integer> spare = new ArrayDeque<>();
        @Nullable VoiceDecoder decoder;
        int lastTick;
    }

    private final Map<UUID, Voice> voices = new HashMap<>();
    /** The speakers of an export that keeps voices apart: only their decoders are used, they have no sound source */
    private final Map<UUID, Voice> apart = new HashMap<>();
    private final Set<String> warned = new HashSet<>();

    private VoicePlayback() {
    }

    @Override
    public void play(byte[] data, int from, int length, boolean skipping) {
        // Nor while the path preview plays a stretch at twenty times its speed, unheard
        if (skipping || Playback.skipping() || comedymonkey.dailies.editor.StretchPreview.running()) {
            return;
        }
        VoiceFormat.Frame frame = VoiceFormat.read(data, from, length);
        // Voice chat's own talking icons, for the voice chat look: at any speed, heard or not
        comedymonkey.dailies.compat.voicechat.VoiceChatLook.spoke(frame.speaker(), (frame.flags() & VoiceFormat.WHISPER) != 0);
        if (!audible()) {
            return;
        }
        if (ExportSound.keepsVoicesApart()) {
            this.keepApart(frame);
            return;
        }
        if (ALC10.alcGetCurrentContext() == 0L) {
            return;
        }
        int tick = Playback.position();
        this.dropIdle(tick);
        Voice voice = this.voice(frame.speaker());
        if (voice == null) {
            return;
        }
        voice.lastTick = tick;
        short[] samples = this.samples(voice, frame);
        if (samples == null || samples.length == 0) {
            return;
        }

        AL10.alGetError();
        int processed = AL10.alGetSourcei(voice.source, AL10.AL_BUFFERS_PROCESSED);
        for (int i = 0; i < processed; i++) {
            voice.spare.add(AL10.alSourceUnqueueBuffers(voice.source));
        }
        int queued = AL10.alGetSourcei(voice.source, AL10.AL_BUFFERS_QUEUED);
        if (queued >= MAX_QUEUED) {
            return;
        }
        // The source stays in the middle of the listener's head, where it was put; only its loudness follows the distance
        AL10.alSourcef(voice.source, AL10.AL_GAIN, Minecraft.getInstance().options.getSoundSourceVolume(SoundSource.VOICE) * cameraGain(frame));
        boolean playing = AL10.alGetSourcei(voice.source, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING;
        if (!playing && queued == 0) {
            this.queue(voice, new short[LEAD_SAMPLES]);
        }
        this.queue(voice, samples);
        if (!playing) {
            AL10.alSourcePlay(voice.source);
        }
        int error = AL10.alGetError();
        if (error != AL10.AL_NO_ERROR) {
            this.warnOnce("OpenAL error " + error, null);
        }
    }

    /**
     * An export that keeps each speaker on a track of their own (EXP-12): the voice is not played into the game's
     * sound device, where it would be mixed with everything else for good, but given its loudness here, exactly as
     * {@link #play} gives it to the sound device, and handed to the export. The same in both ears, so a speaker's
     * track carries the distance and the tracks still add up to the mix.
     */
    private void keepApart(VoiceFormat.Frame frame) {
        Voice voice = this.apart.computeIfAbsent(frame.speaker(), speaker -> new Voice());
        short[] samples = this.samples(voice, frame);
        if (samples == null || samples.length == 0) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        float gain = minecraft.options.getSoundSourceVolume(SoundSource.VOICE) * minecraft.options.getSoundSourceVolume(SoundSource.MASTER);
        ExportSound.voice(frame.speaker(), speakerName(frame.speaker()), samples, gain * cameraGain(frame));
    }

    /** How loud a piece is from the camera being watched or exported from, by {@link VoiceLevels} */
    private static float cameraGain(VoiceFormat.Frame frame) {
        net.minecraft.world.phys.Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
        return (float) VoiceLevels.gain(frame, camera.x, camera.y, camera.z);
    }

    /** What a speaker is called: the player of that id in the recording, else the start of the id */
    private static String speakerName(UUID speaker) {
        Minecraft minecraft = Minecraft.getInstance();
        try {
            if (minecraft.level != null) {
                net.minecraft.world.entity.player.Player player = minecraft.level.getPlayerByUUID(speaker);
                if (player != null) {
                    return player.getGameProfile().name();
                }
            }
            if (minecraft.getConnection() != null) {
                net.minecraft.client.multiplayer.PlayerInfo info = minecraft.getConnection().getPlayerInfo(speaker);
                if (info != null) {
                    return info.getProfile().name();
                }
            }
        } catch (RuntimeException e) {
            Host.LOGGER.debug("Dailies: a speaker could not be looked up by id", e);
        }
        return "Speaker " + speaker.toString().substring(0, 8);
    }

    /** Voices are only right at the speed they were spoken at, and never while the recording stands still */
    private static boolean audible() {
        if (Export.active()) {
            // In an export only what is captured into the file may sound, and the export steps the recording itself
            return ExportSound.capturing() && Math.abs(Export.ticksPerSecondNow() - NORMAL_SPEED) <= SPEED_TOLERANCE;
        }
        if (!Playback.loaded() || Playback.paused()) {
            return false;
        }
        return Math.abs(1000.0 / Playback.millisPerTick() - NORMAL_SPEED) <= SPEED_TOLERANCE;
    }

    private @Nullable short[] samples(Voice voice, VoiceFormat.Frame frame) {
        if (frame.format() == VoiceFormat.PCM) {
            return VoiceFormat.pcmSamples(frame.audio());
        }
        if (frame.format() != VoiceFormat.OPUS) {
            this.warnOnce("voice stored in a way this version does not know (format " + frame.format() + ")", null);
            return null;
        }
        if (voice.decoder == null) {
            if (!Host.get().isModLoaded("voicechat")) {
                this.warnOnce("this recording's voice chat is stored as Opus, which needs Simple Voice Chat installed to be heard", null);
                return null;
            }
            try {
                // The only place playback touches the voice chat's classes, and only when that mod is there
                voice.decoder = VoiceCapture.newDecoder();
            } catch (Throwable t) {
                this.warnOnce("Simple Voice Chat's decoder could not be made", t);
                return null;
            }
            if (voice.decoder == null) {
                this.warnOnce("Simple Voice Chat has not started, so recorded voice cannot be decoded", null);
                return null;
            }
        } else if (frame.streamStart()) {
            voice.decoder.reset();
        }
        try {
            return voice.decoder.decode(frame.audio());
        } catch (Throwable t) {
            this.warnOnce("a piece of recorded voice could not be decoded", t);
            return null;
        }
    }

    private @Nullable Voice voice(UUID speaker) {
        Voice voice = this.voices.get(speaker);
        if (voice != null) {
            return voice;
        }
        if (this.voices.size() >= MAX_VOICES) {
            // The one that has been quiet longest makes room
            UUID quietest = null;
            for (Map.Entry<UUID, Voice> entry : this.voices.entrySet()) {
                if (quietest == null || entry.getValue().lastTick < this.voices.get(quietest).lastTick) {
                    quietest = entry.getKey();
                }
            }
            free(this.voices.remove(quietest));
        }
        AL10.alGetError();
        int source = AL10.alGenSources();
        if (AL10.alGetError() != AL10.AL_NO_ERROR || source == 0) {
            this.warnOnce("no free sound source for recorded voice", null);
            return null;
        }
        // Never placed in the world: in the middle of the listener's head, with no fading by OpenAL. The distance is
        // taken into account in the gain set for every piece, and neither ear is favoured
        AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
        AL10.alSource3f(source, AL10.AL_POSITION, 0.0F, 0.0F, 0.0F);
        AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 0.0F);
        AL10.alSourcei(source, AL10.AL_LOOPING, AL10.AL_FALSE);
        voice = new Voice();
        voice.source = source;
        this.voices.put(speaker, voice);
        return voice;
    }

    private void queue(Voice voice, short[] samples) {
        Integer buffer = voice.spare.poll();
        if (buffer == null) {
            buffer = AL10.alGenBuffers();
            voice.buffers.add(buffer);
        }
        AL10.alBufferData(buffer, AL10.AL_FORMAT_MONO16, samples, VoiceFormat.SAMPLE_RATE);
        AL10.alSourceQueueBuffers(voice.source, buffer);
    }

    private void dropIdle(int tick) {
        Iterator<Voice> all = this.voices.values().iterator();
        while (all.hasNext()) {
            Voice voice = all.next();
            if (tick < voice.lastTick || tick - voice.lastTick > IDLE_TICKS) {
                free(voice);
                all.remove();
            }
        }
    }

    private static void free(@Nullable Voice voice) {
        if (voice == null) {
            return;
        }
        try {
            if (voice.source != 0 && ALC10.alcGetCurrentContext() != 0L) {
                AL10.alSourceStop(voice.source);
                AL10.alSourcei(voice.source, AL10.AL_BUFFER, 0);
                AL10.alDeleteSources(voice.source);
                for (int buffer : voice.buffers) {
                    AL10.alDeleteBuffers(buffer);
                }
                AL10.alGetError();
            }
        } catch (Throwable t) {
            Host.LOGGER.debug("Dailies: a voice source could not be freed", t);
        }
        if (voice.decoder != null) {
            try {
                voice.decoder.close();
            } catch (Throwable t) {
                Host.LOGGER.debug("Dailies: a voice decoder could not be closed", t);
            }
            voice.decoder = null;
        }
    }

    /**
     * The recording paused, or is jumping: each voice stops at once, and what it still had waiting (up to half a second)
     * is dropped rather than heard over a world that stands still or has moved on. The sources are kept for what comes next
     */
    @Override
    public void stopped() {
        if (this.voices.isEmpty() || ALC10.alcGetCurrentContext() == 0L) {
            return;
        }
        for (Voice voice : this.voices.values()) {
            try {
                AL10.alGetError();
                AL10.alSourceStop(voice.source);
                // A stopped source has used up every buffer it had queued: all of them can be taken back
                int processed = AL10.alGetSourcei(voice.source, AL10.AL_BUFFERS_PROCESSED);
                for (int i = 0; i < processed; i++) {
                    voice.spare.add(AL10.alSourceUnqueueBuffers(voice.source));
                }
                AL10.alGetError();
            } catch (Throwable t) {
                Host.LOGGER.debug("Dailies: a voice could not be stopped", t);
            }
        }
    }

    /** Stops every voice and gives back everything held: before a jump, when the recording closes */
    @Override
    public void reset() {
        for (Voice voice : this.voices.values()) {
            free(voice);
        }
        this.voices.clear();
        for (Voice voice : this.apart.values()) {
            free(voice);
        }
        this.apart.clear();
    }

    /** The game is about to close its sound device: the sources die with it, so let go of them while they can still be freed */
    public static void contextClosing() {
        try {
            INSTANCE.reset();
        } catch (Throwable t) {
            Host.LOGGER.debug("Dailies: voice playback could not be cleared before the sound device closed", t);
        }
    }

    private void warnOnce(String what, @Nullable Throwable problem) {
        if (this.warned.add(what)) {
            if (problem == null) {
                Host.LOGGER.warn("Dailies: recorded voice chat is not being played: {}. Said once", what);
            } else {
                Host.LOGGER.warn("Dailies: recorded voice chat is not being played: {}. Said once", what, problem);
            }
        }
    }

}
