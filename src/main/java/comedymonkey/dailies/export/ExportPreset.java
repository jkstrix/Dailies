package comedymonkey.dailies.export;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A named set of export settings (specification EXP-14). A preset names codecs, not encoders, so the same preset
 * works on any machine: which encoder makes the codec, or what it falls back to, is settled when it is used.
 *
 * @param name         what the person calls it
 * @param builtIn      the id of the built-in preset this began as, or empty for one the person made
 * @param codecs       the codecs wanted, most wanted first ("H.264 or AV1")
 * @param quality      high, moderate, small, or custom for {@code bitrate}. Ignored by editing masters
 * @param bitrate      bits per second, used when quality is custom
 * @param audioCodec   auto, aac, opus or pcm
 * @param stemFiles    also write each sound source as a WAV file beside the video (EXP-12)
 * @param stemTracks   also put each sound source in the file as a named track, where the file can hold them
 * @param maxSeconds   the longest video this preset is meant for, or 0: a longer range gets a warning
 */
public record ExportPreset(String name, String builtIn, int width, int height, double framesPerSecond, String container, List<String> codecs,
                           String quality, long bitrate, boolean audio, boolean stereo, String audioCodec, boolean stemFiles, boolean stemTracks, int maxSeconds) {

    public ExportPreset {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A preset needs a name");
        }
        name = name.trim();
        if (name.length() > 60) {
            throw new IllegalArgumentException("A preset's name may be 60 characters at most");
        }
        builtIn = builtIn == null ? "" : builtIn;
        if (width < 16 || height < 16 || width > 16384 || height > 16384) {
            throw new IllegalArgumentException("Width and height must be between 16 and 16384");
        }
        if (!(framesPerSecond >= 1.0 && framesPerSecond <= 240.0)) {
            throw new IllegalArgumentException("The frame rate must be between 1 and 240");
        }
        container = container == null ? "" : container.toLowerCase(Locale.ROOT);
        if (!Codecs.CONTAINERS.contains(container)) {
            throw new IllegalArgumentException("The kind of file must be one of " + String.join(", ", Codecs.CONTAINERS));
        }
        if (codecs == null || codecs.isEmpty()) {
            throw new IllegalArgumentException("A preset needs at least one codec");
        }
        List<String> cleaned = new ArrayList<>();
        for (String codec : codecs) {
            String lower = codec.trim().toLowerCase(Locale.ROOT);
            if (!Codecs.known(lower)) {
                throw new IllegalArgumentException("There is no codec '" + codec + "'. There are: " + String.join(", ", Codecs.ORDER));
            }
            if (!cleaned.contains(lower)) {
                cleaned.add(lower);
            }
        }
        codecs = List.copyOf(cleaned);
        quality = quality == null ? "high" : quality.toLowerCase(Locale.ROOT);
        if (!Codecs.QUALITIES.contains(quality)) {
            throw new IllegalArgumentException("The quality must be one of " + String.join(", ", Codecs.QUALITIES));
        }
        if (quality.equals("custom") && bitrate <= 0) {
            throw new IllegalArgumentException("A custom quality needs a bitrate");
        }
        audioCodec = audioCodec == null ? "auto" : audioCodec.toLowerCase(Locale.ROOT);
        if (!List.of("auto", "aac", "opus", "pcm").contains(audioCodec)) {
            throw new IllegalArgumentException("The sound codec must be one of auto, aac, opus, pcm");
        }
        if (maxSeconds < 0) {
            throw new IllegalArgumentException("The longest length cannot be negative");
        }
    }

    public ExportPreset named(String newName) {
        return new ExportPreset(newName, this.builtIn, this.width, this.height, this.framesPerSecond, this.container, this.codecs, this.quality, this.bitrate,
            this.audio, this.stereo, this.audioCodec, this.stemFiles, this.stemTracks, this.maxSeconds);
    }

    /** The bitrate this preset means for a codec; 0 for an editing master, whose profile fixes it */
    public long bitrateFor(String codec) {
        if (Codecs.master(codec)) {
            return 0L;
        }
        return this.quality.equals("custom") ? this.bitrate : Codecs.bitrate(this.quality, codec, this.width, this.height, this.framesPerSecond);
    }

    /** Whether two presets set the same things, whatever they are called */
    public boolean sameSettings(ExportPreset other) {
        return this.named("x").withBuiltIn("").equals(other.named("x").withBuiltIn(""));
    }

    private ExportPreset withBuiltIn(String id) {
        return new ExportPreset(this.name, id, this.width, this.height, this.framesPerSecond, this.container, this.codecs, this.quality, this.bitrate,
            this.audio, this.stereo, this.audioCodec, this.stemFiles, this.stemTracks, this.maxSeconds);
    }

    /** One line saying what the preset makes: "1920x1080, 60 fps, H.264 or AV1 in MP4, high quality, AAC stereo" */
    public String summary() {
        StringBuilder said = new StringBuilder();
        said.append(this.width).append('x').append(this.height).append(", ").append(number(this.framesPerSecond)).append(" fps, ");
        for (int i = 0; i < this.codecs.size(); i++) {
            said.append(i == 0 ? "" : " or ").append(Codecs.codecLabel(this.codecs.get(i)));
        }
        said.append(" in ").append(this.container.toUpperCase(Locale.ROOT));
        if (!Codecs.master(this.codecs.get(0))) {
            said.append(", ").append(this.quality.equals("custom") ? Codecs.bitrateWords(this.bitrate) + " bitrate" : this.quality + " quality");
        }
        if (!this.audio) {
            said.append(", no sound");
        } else {
            String codec = this.audioCodec.equals("auto") ? (this.container.equals("webm") || this.container.equals("mkv") ? "Opus" : "AAC")
                : this.audioCodec.equals("pcm") ? "uncompressed" : this.audioCodec.equals("aac") ? "AAC" : "Opus";
            said.append(", ").append(codec).append(this.stereo ? " stereo" : " mono");
            if (this.stemFiles || this.stemTracks) {
                said.append(", separate sound tracks");
            }
        }
        if (this.maxSeconds > 0) {
            said.append(", up to ").append(this.maxSeconds).append(" s");
        }
        return said.toString();
    }

    private static String number(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : String.format(Locale.ROOT, "%.3f", value).replaceAll("0+$", "");
    }

    // ------------------------------------------------------------------ As JSON

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("name", this.name);
        if (!this.builtIn.isEmpty()) {
            json.addProperty("built_in", this.builtIn);
        }
        json.addProperty("width", this.width);
        json.addProperty("height", this.height);
        json.addProperty("framerate", this.framesPerSecond);
        json.addProperty("container", this.container);
        JsonArray codecs = new JsonArray();
        this.codecs.forEach(codecs::add);
        json.add("video_codecs", codecs);
        json.addProperty("quality", this.quality);
        json.addProperty("bitrate", this.bitrate);
        json.addProperty("record_audio", this.audio);
        json.addProperty("stereo_audio", this.stereo);
        json.addProperty("audio_codec", this.audioCodec);
        json.addProperty("separate_audio", separateWord(this.stemFiles, this.stemTracks));
        json.addProperty("max_seconds", this.maxSeconds);
        return json;
    }

    /**
     * Reads a preset. Fields that are missing are taken from {@code base}, so a change can name only what changes.
     *
     * @throws IllegalArgumentException when a value is not allowed
     */
    public static ExportPreset fromJson(JsonObject json, ExportPreset base) {
        try {
            List<String> codecs = base.codecs;
            if (json.has("video_codecs")) {
                codecs = new ArrayList<>();
                JsonElement given = json.get("video_codecs");
                if (given.isJsonArray()) {
                    for (JsonElement codec : given.getAsJsonArray()) {
                        codecs.add(codec.getAsString());
                    }
                } else {
                    codecs.add(given.getAsString());
                }
            }
            boolean files = base.stemFiles;
            boolean tracks = base.stemTracks;
            if (json.has("separate_audio")) {
                boolean[] parsed = separate(json.get("separate_audio").getAsString());
                files = parsed[0];
                tracks = parsed[1];
            }
            long bitrate = base.bitrate;
            if (json.has("bitrate")) {
                bitrate = Codecs.parseBitrate(json.get("bitrate").getAsString());
                if (bitrate <= 0) {
                    throw new IllegalArgumentException("The bitrate must be a number of bits per second, or text like '20m' or '800k'");
                }
            }
            return new ExportPreset(
                json.has("name") ? json.get("name").getAsString() : base.name,
                json.has("built_in") ? json.get("built_in").getAsString() : base.builtIn,
                json.has("width") ? json.get("width").getAsInt() : base.width,
                json.has("height") ? json.get("height").getAsInt() : base.height,
                json.has("framerate") ? json.get("framerate").getAsDouble() : base.framesPerSecond,
                json.has("container") ? json.get("container").getAsString() : base.container,
                codecs,
                json.has("quality") ? json.get("quality").getAsString() : base.quality,
                bitrate,
                json.has("record_audio") ? json.get("record_audio").getAsBoolean() : base.audio,
                json.has("stereo_audio") ? json.get("stereo_audio").getAsBoolean() : base.stereo,
                json.has("audio_codec") ? json.get("audio_codec").getAsString() : base.audioCodec,
                files, tracks,
                json.has("max_seconds") ? json.get("max_seconds").getAsInt() : base.maxSeconds);
        } catch (ClassCastException | IllegalStateException | UnsupportedOperationException | NumberFormatException e) {
            throw new IllegalArgumentException("A preset's value is of the wrong kind: " + e.getMessage());
        }
    }

    public static String separateWord(boolean files, boolean tracks) {
        return files && tracks ? "both" : files ? "files" : tracks ? "tracks" : "none";
    }

    /** "none", "files", "tracks" or "both" as {files, tracks} */
    public static boolean[] separate(String word) {
        return switch (word == null ? "" : word.trim().toLowerCase(Locale.ROOT)) {
            case "none", "" -> new boolean[]{false, false};
            case "files" -> new boolean[]{true, false};
            case "tracks" -> new boolean[]{false, true};
            case "both" -> new boolean[]{true, true};
            default -> throw new IllegalArgumentException("'separate_audio' must be one of none, files, tracks, both");
        };
    }

}
