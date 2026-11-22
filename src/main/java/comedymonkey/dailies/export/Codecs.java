package comedymonkey.dailies.export;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What Dailies knows about video codecs and the encoders for them (specification EXP-1): which encoder makes which
 * codec, whether it runs on a graphics card and whose, which kind of file holds what, and what to fall back to when
 * a machine cannot do what was asked.
 * <p>
 * Two decisions of the owner live here. H.264 and H.265 are only ever made by hardware encoders: a software H.264
 * encoder would need either a GPL build or Cisco's OpenH264, whose patent licence covers only binaries fetched from
 * Cisco. So {@link #FORBIDDEN} is never used even where a build contains it, and a machine without a hardware
 * encoder exports AV1, VP9 or an editing master instead.
 * <p>
 * No Minecraft here: the choosing is unit tested.
 */
public final class Codecs {

    /** In the present bundled build, and never to be used */
    public static final String FORBIDDEN = "libopenh264";

    /** The codecs Dailies can name, in the order they are offered and fallen back through */
    public static final List<String> ORDER = List.of("h264", "h265", "av1", "vp9", "prores", "dnxhr");
    /** What a codec that cannot be made here gives way to, in turn, after a preset's own alternatives */
    public static final List<String> FALLBACK = List.of("av1", "vp9", "prores", "dnxhr");
    public static final List<String> CONTAINERS = List.of("mp4", "mkv", "mov", "webm");
    public static final List<String> QUALITIES = List.of("high", "moderate", "small", "custom");

    private static final Map<String, List<String>> HOLDS = new LinkedHashMap<>();

    static {
        HOLDS.put("mp4", List.of("h264", "h265", "av1", "vp9"));
        HOLDS.put("mkv", List.of("h264", "h265", "av1", "vp9", "prores", "dnxhr"));
        HOLDS.put("mov", List.of("h264", "h265", "prores", "dnxhr"));
        HOLDS.put("webm", List.of("vp9", "av1"));
    }

    /**
     * One way of making a codec on this machine.
     *
     * @param codec    the codec's name as Dailies spells it: h264, h265, av1, vp9, prores, dnxhr
     * @param encoder  the encoder's name as FFmpeg spells it
     * @param hardware whether a graphics card does the work
     * @param on       what it runs on: "NVIDIA", "AMD", "Intel", "Apple", "Windows" (Media Foundation, whoever made the card), or the software's name
     */
    public record Choice(String codec, String encoder, boolean hardware, String on) {

        /** "H.264, NVIDIA hardware" or "AV1, software (SVT-AV1)" */
        public String label() {
            return codecLabel(this.codec) + ", " + this.where();
        }

        /** "NVIDIA hardware" or "software (SVT-AV1)" */
        public String where() {
            return this.hardware ? this.on + " hardware" : "software (" + this.on + ")";
        }
    }

    /** Every encoder Dailies will use, each codec's fastest first. Hardware comes before software */
    private static final List<Choice> KNOWN = List.of(
        new Choice("h264", "h264_nvenc", true, "NVIDIA"),
        new Choice("h264", "h264_amf", true, "AMD"),
        new Choice("h264", "h264_qsv", true, "Intel"),
        new Choice("h264", "h264_videotoolbox", true, "Apple"),
        new Choice("h264", "h264_mf", true, "Windows"),
        new Choice("h265", "hevc_nvenc", true, "NVIDIA"),
        new Choice("h265", "hevc_amf", true, "AMD"),
        new Choice("h265", "hevc_qsv", true, "Intel"),
        new Choice("h265", "hevc_videotoolbox", true, "Apple"),
        new Choice("h265", "hevc_mf", true, "Windows"),
        new Choice("av1", "av1_nvenc", true, "NVIDIA"),
        new Choice("av1", "av1_amf", true, "AMD"),
        new Choice("av1", "av1_qsv", true, "Intel"),
        new Choice("av1", "libsvtav1", false, "SVT-AV1"),
        new Choice("av1", "libaom-av1", false, "libaom, slow"),
        new Choice("vp9", "vp9_qsv", true, "Intel"),
        new Choice("vp9", "libvpx-vp9", false, "libvpx"),
        new Choice("prores", "prores_ks", false, "FFmpeg"),
        new Choice("dnxhr", "dnxhd", false, "FFmpeg"));

    private Codecs() {
    }

    // ------------------------------------------------------------------ What there is

    /** The encoders of a build that Dailies would use, before any has been tried */
    public static List<Choice> candidates(Set<String> inBuild) {
        List<Choice> found = new ArrayList<>();
        for (Choice known : KNOWN) {
            if (inBuild.contains(known.encoder()) && !known.encoder().equals(FORBIDDEN)) {
                found.add(known);
            }
        }
        return found;
    }

    /**
     * The encoders that can really be used: those of the build, less every hardware encoder that did not pass its
     * trial. Ordered codec by codec as {@link #ORDER}, each codec's fastest first, so the first is the default.
     */
    public static List<Choice> working(Set<String> inBuild, Predicate<String> hardwareWorks) {
        List<Choice> found = new ArrayList<>();
        for (Choice candidate : candidates(inBuild)) {
            if (!candidate.hardware() || hardwareWorks.test(candidate.encoder())) {
                found.add(candidate);
            }
        }
        return found;
    }

    public static boolean known(String codec) {
        return ORDER.contains(codec);
    }

    public static List<Choice> forCodec(List<Choice> working, String codec) {
        return working.stream().filter(choice -> choice.codec().equals(codec)).toList();
    }

    public static boolean has(List<Choice> working, String codec) {
        return working.stream().anyMatch(choice -> choice.codec().equals(codec));
    }

    public static String codecLabel(String codec) {
        return switch (codec) {
            case "h264" -> "H.264";
            case "h265" -> "H.265";
            case "av1" -> "AV1";
            case "vp9" -> "VP9";
            case "prores" -> "ProRes 422 HQ";
            case "dnxhr" -> "DNxHR HQ";
            default -> codec;
        };
    }

    /** The codec's name as the file inspector reports it */
    public static String probeName(String codec) {
        return switch (codec) {
            case "h265" -> "hevc";
            case "dnxhr" -> "dnxhd";
            default -> codec;
        };
    }

    /** A codec whose quality is fixed by its profile and takes no bitrate: the editing masters */
    public static boolean master(String codec) {
        return codec.equals("prores") || codec.equals("dnxhr");
    }

    /** Why a codec is not offered on this machine, in a sentence; null when it is, or when nobody would ask */
    public static String whyMissing(List<Choice> working, String codec) {
        if (has(working, codec)) {
            return null;
        }
        if (codec.equals("h264") || codec.equals("h265")) {
            return codecLabel(codec) + " is not offered here: this computer has no working hardware encoder for it, and Dailies carries no software one "
                + "(that would need a GPL or patent-restricted library). Use AV1 or VP9, or an editing master";
        }
        return codecLabel(codec) + " is not offered here: the encoder in this build of Dailies has nothing for it";
    }

    // ------------------------------------------------------------------ Kinds of file

    public static boolean holds(String container, String codec) {
        return HOLDS.getOrDefault(container, List.of()).contains(codec);
    }

    public static List<String> heldBy(String container) {
        return HOLDS.getOrDefault(container, List.of());
    }

    /** The kind of file to put a codec in: the one asked for where it can hold the codec, else the codec's usual one */
    public static String containerFor(String codec, String wanted) {
        if (holds(wanted, codec)) {
            return wanted;
        }
        return switch (codec) {
            case "prores", "dnxhr" -> "mov";
            case "vp9" -> "webm";
            default -> "mp4";
        };
    }

    /** Whether a kind of file can carry several named sound tracks the way Dailies writes them (EXP-12) */
    public static boolean holdsNamedTracks(String container) {
        return container.equals("mkv") || container.equals("mov");
    }

    // ------------------------------------------------------------------ Falling back

    /**
     * What was settled on for a list of wanted codecs.
     *
     * @param codec     the codec to use, or null when no video can be encoded here at all
     * @param container the kind of file, changed from the one asked for when it cannot hold the codec
     * @param asked     the kind of file asked for
     * @param wanted    the first codec asked for
     * @param fellBack  whether {@code codec} is not one of those asked for
     */
    public record Pick(String codec, String container, String asked, String wanted, boolean fellBack, boolean containerChanged) {

        /** What happened, for a person; null when the first choice was taken as asked */
        public String words(List<Choice> working) {
            if (this.codec == null) {
                return "No video encoder works on this computer, so only numbered pictures can be exported";
            }
            if (!this.fellBack && !this.containerChanged) {
                return null;
            }
            StringBuilder said = new StringBuilder();
            if (this.fellBack) {
                Choice used = forCodec(working, this.codec).get(0);
                boolean hardwareOnly = this.wanted.equals("h264") || this.wanted.equals("h265");
                said.append("This computer cannot encode ").append(codecLabel(this.wanted))
                    .append(hardwareOnly ? " (it has no working hardware encoder for it, and Dailies carries no software one)" : "")
                    .append(", so ").append(used.label()).append(" is used instead");
            }
            if (this.containerChanged) {
                String file = this.container.toUpperCase(Locale.ROOT);
                said.append(this.fellBack ? ", in a " + file + " file" : this.asked.toUpperCase(Locale.ROOT) + " cannot hold " + codecLabel(this.codec) + ", so the file is " + file);
            }
            return said.toString();
        }
    }

    /**
     * Chooses the codec for a preset: the first of its own codecs this machine can make, else down {@link #FALLBACK}.
     * The kind of file follows the codec when the one asked for cannot hold it.
     */
    public static Pick pick(List<String> wanted, String container, List<Choice> working) {
        String first = wanted.isEmpty() ? "h264" : wanted.get(0);
        for (String codec : wanted) {
            if (has(working, codec)) {
                String file = containerFor(codec, container);
                return new Pick(codec, file, container, first, false, !file.equals(container));
            }
        }
        for (String codec : FALLBACK) {
            if (has(working, codec)) {
                String file = containerFor(codec, container);
                return new Pick(codec, file, container, first, true, !file.equals(container));
            }
        }
        return new Pick(null, container, container, first, true, false);
    }

    // ------------------------------------------------------------------ Bitrates

    /**
     * The bitrate a named quality means for a picture of this size and rate. "high" is what video sites ask of an
     * upload (about 20 Mbit/s for 1080p60 H.264); the newer codecs need less for the same picture.
     */
    public static long bitrate(String quality, String codec, int width, int height, double framesPerSecond) {
        double perPixel = switch (quality) {
            case "moderate" -> 0.07;
            case "small" -> 0.035;
            default -> 0.16;
        };
        double thrift = switch (codec) {
            case "h265", "vp9" -> 0.65;
            case "av1" -> 0.55;
            default -> 1.0;
        };
        double bits = perPixel * thrift * width * height * Math.max(1.0, framesPerSecond);
        // To the nearest tenth of a megabit: nobody wants to read 19906560
        long rounded = Math.round(bits / 100_000.0) * 100_000L;
        return Math.max(200_000L, rounded);
    }

    /** Bits per second from text like "20m", "800k" or "5000000"; -1 when it cannot be read */
    public static long parseBitrate(String typed) {
        String text = typed == null ? "" : typed.trim().toLowerCase(Locale.ROOT);
        try {
            double scale = text.endsWith("m") ? 1_000_000 : text.endsWith("k") ? 1_000 : 1;
            double number = Double.parseDouble(scale == 1 ? text : text.substring(0, text.length() - 1).trim());
            if (Double.isNaN(number) || number <= 0 || number * scale > 2.0e9) {
                return -1;
            }
            return (long) (number * scale);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 20000000 as "20m", 800000 as "800k" */
    public static String bitrateWords(long bits) {
        if (bits >= 1_000_000 && bits % 100_000 == 0) {
            return bits % 1_000_000 == 0 ? bits / 1_000_000 + "m" : String.format(Locale.ROOT, "%.1fm", bits / 1_000_000.0);
        }
        if (bits >= 1_000 && bits % 1_000 == 0) {
            return bits / 1_000 + "k";
        }
        return Long.toString(bits);
    }

    // ------------------------------------------------------------------ Talking to the encoder

    /**
     * The ways an NVIDIA encoder may split each frame across the card's encoding units, in the order they are tried:
     * three strips, as many as the driver picks, and none. A card with several units (an RTX 5090 has three) then
     * encodes a 4K frame on all of them at once: measured on one, AV1 at 4K went from 105 frames a second to 250
     * through the same pipe, the encoder having been what the whole export waited on. Only HEVC and AV1 can be split.
     */
    public static List<Integer> splitModes(String encoder, boolean wanted) {
        if (!wanted || !(encoder.equals("hevc_nvenc") || encoder.equals("av1_nvenc"))) {
            return List.of(-1);
        }
        return List.of(3, 1, -1);
    }

    /** As {@link #videoArguments(String, long, boolean)}, with an NVIDIA encoder told how to split frames (-1: not told) */
    public static List<String> videoArguments(String encoder, long bitrate, boolean transparent, int split) {
        List<String> arguments = videoArguments(encoder, bitrate, transparent);
        if (split >= 0) {
            arguments.addAll(List.of("-split_encode_mode", String.valueOf(split)));
        }
        return arguments;
    }

    /** The arguments that choose and set up an encoder, from "-c:v" on */
    public static List<String> videoArguments(String encoder, long bitrate, boolean transparent) {
        List<String> arguments = new ArrayList<>(List.of("-c:v", encoder));
        switch (encoder) {
            case "prores_ks" -> {
                // 422 HQ; with a transparent background the 4444 profile, which is the one that keeps it
                arguments.addAll(transparent
                    ? List.of("-profile:v", "4", "-pix_fmt", "yuva444p10le", "-vendor", "apl0")
                    : List.of("-profile:v", "3", "-pix_fmt", "yuv422p10le", "-vendor", "apl0"));
                return arguments;
            }
            case "dnxhd" -> {
                arguments.addAll(List.of("-profile:v", "dnxhr_hq", "-pix_fmt", "yuv422p"));
                return arguments;
            }
            case "libvpx-vp9" -> arguments.addAll(List.of("-pix_fmt", transparent ? "yuva420p" : "yuv420p", "-deadline", "good", "-cpu-used", "4", "-row-mt", "1"));
            case "libsvtav1" -> arguments.addAll(List.of("-pix_fmt", "yuv420p", "-preset", "8"));
            // Left alone, libaom takes minutes over a second of video
            case "libaom-av1" -> arguments.addAll(List.of("-pix_fmt", "yuv420p", "-cpu-used", "6", "-row-mt", "1"));
            // Asked plainly, Media Foundation may quietly encode in software: this makes it hardware or nothing
            case "h264_mf", "hevc_mf" -> arguments.addAll(List.of("-pix_fmt", "nv12", "-hw_encoding", "1"));
            default -> arguments.addAll(List.of("-pix_fmt", "yuv420p"));
        }
        if (bitrate > 0) {
            arguments.addAll(List.of("-b:v", String.valueOf(bitrate)));
        }
        return arguments;
    }

    /**
     * Whether an encoder, set up as {@link #videoArguments} sets it up, writes 4:2:0 YUV without transparency, so frames
     * already in that format ({@link I420}) can go to it as they are. The editing masters keep more colour than that,
     * and transparent VP9 keeps the alpha, so those are given RGBA and make what they need of it themselves.
     */
    public static boolean takesI420(String encoder, boolean transparent) {
        return switch (encoder) {
            case "prores_ks", "dnxhd" -> false;
            case "libvpx-vp9" -> !transparent;
            default -> true;
        };
    }

    /**
     * Whether an export with this encoder can be made by several of it at once, each taking every so many chunks of
     * the video ({@link ParallelVideoSink}). Only NVIDIA's H.264: an NVIDIA card has several encoding units, one H.264
     * encoder uses only one of them, and H.264 frames cannot be split between them as HEVC and AV1 frames are
     * ({@link #splitModes}). Measured on an RTX 5090 at 4K: one encoder 147 frames a second, three 300.
     */
    public static boolean parallel(String encoder) {
        return encoder.equals("h264_nvenc");
    }

    /** A trial small enough to take a moment: a few grey frames, encoded and thrown away */
    public static List<String> trialArguments(String program, String encoder) {
        return trialArguments(program, encoder, 640, 360);
    }

    /** As above, at the size of an export */
    public static List<String> trialArguments(String program, String encoder, int width, int height) {
        List<String> command = new ArrayList<>(List.of(program, "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "color=c=gray:s=" + width + "x" + height + ":r=30", "-frames:v", "5"));
        command.addAll(videoArguments(encoder, 2_000_000L, false));
        command.addAll(List.of("-f", "null", "-"));
        return command;
    }

}
