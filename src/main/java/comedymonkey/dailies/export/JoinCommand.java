package comedymonkey.dailies.export;

import org.jetbrains.annotations.Nullable;

import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The encoder's commands that write the file an export ends with: the picture copied as it is, never encoded again,
 * and the sound, when there is any, encoded into the same file in the same run. The picture is either a soundless file
 * the encoder wrote, or the parts of several encoders ({@link ParallelVideoSink}) piped in one after the other: then
 * the parts are joined and the sound put with them at once, and the video is written to disk only once (EXP-7).
 * <p>
 * The picture is always the encoder's first input and the sound comes after it, so the rest of a command is the same
 * whichever the picture is. The encoder moves each input to start at nought by itself, the picture by the moment its
 * first frame is shown, and the captured sound begins with the first frame, so sound and picture start together with
 * no offset given, however late the parts' own times begin.
 * <p>
 * Every command is a list of arguments for the program, never a line for a shell. No Minecraft here: tested without a
 * game.
 */
public final class JoinCommand {

    private JoinCommand() {
    }

    /** Sound samples as the export captures them: 48 kHz, 16 bits, stereo */
    public static final int SAMPLE_RATE = 48000;

    /** How the encoder reads the parts of several encoders, handed down its input pipe one after the other */
    public static final List<String> PARTS = List.of("-f", "mpegts", "-i", "pipe:0");

    /** How the encoder reads a soundless video file */
    public static List<String> file(Path video) {
        return List.of("-i", video.toString());
    }

    /**
     * The picture that sound is joined to: how the encoder opens it as its first input, and a run of the encoder with
     * a command that opens it so. A soundless file is simply read; the parts of several encoders are piped in
     */
    public interface Picture {

        /** What opens the picture as the encoder's first input: {@link #PARTS} or {@link #file} */
        List<String> arguments();

        /**
         * Runs the encoder with this command to its end. Null when it worked, else what the encoder said or why it was
         * not run
         */
        @Nullable String run(List<String> command);
    }

    /** A sound file mixed in at a moment of the video: an audio keyframe */
    public record Cue(Path file, double volume, double startSeconds, double skipSeconds) {
    }

    /** The picture alone, copied into the file asked for */
    public static List<String> pictureOnly(String program, List<String> picture, Path output, String container) {
        List<String> command = start(program, picture);
        command.addAll(List.of("-c", "copy", "-an"));
        finish(command, container, output);
        return command;
    }

    /**
     * The picture with the game's sound and any audio keyframes, mixed into one track. The game's sound is exactly as
     * long as the picture, so it sets the track's length; nothing is turned down to make room for the keyframes.
     *
     * @param order the byte order the sound samples were written in
     * @param codec the sound encoder's arguments, from "-c:a" on
     */
    public static List<String> withSound(String program, List<String> picture, Path sound, ByteOrder order, List<Cue> cues, List<String> codec,
                                         boolean stereo, String container, Path output) {
        List<String> command = start(program, picture);
        command.addAll(List.of("-f", order == ByteOrder.LITTLE_ENDIAN ? "s16le" : "s16be", "-ar", String.valueOf(SAMPLE_RATE), "-ac", "2", "-i", sound.toString()));
        for (Cue cue : cues) {
            if (cue.skipSeconds() > 0.0) {
                command.addAll(List.of("-ss", String.format(Locale.ROOT, "%.4f", cue.skipSeconds())));
            }
            command.addAll(List.of("-i", cue.file().toString()));
        }
        if (cues.isEmpty()) {
            command.addAll(List.of("-map", "0:v:0", "-map", "1:a:0"));
        } else {
            StringBuilder graph = new StringBuilder("[1:a]anull[game]");
            StringBuilder inputs = new StringBuilder("[game]");
            for (int i = 0; i < cues.size(); i++) {
                Cue cue = cues.get(i);
                graph.append(";[").append(i + 2).append(":a:0]aresample=").append(SAMPLE_RATE).append(",aformat=channel_layouts=stereo,volume=")
                    .append(String.format(Locale.ROOT, "%.4f", cue.volume()));
                long delay = Math.round(cue.startSeconds() * 1000.0);
                if (delay > 0) {
                    graph.append(",adelay=").append(delay).append(":all=1");
                }
                graph.append("[cue").append(i).append(']');
                inputs.append("[cue").append(i).append(']');
            }
            graph.append(';').append(inputs).append("amix=inputs=").append(cues.size() + 1).append(":duration=first:normalize=0[mix]");
            command.addAll(List.of("-filter_complex", graph.toString(), "-map", "0:v:0", "-map", "[mix]"));
        }
        command.addAll(List.of("-c:v", "copy"));
        command.addAll(codec);
        command.addAll(List.of("-ar", String.valueOf(SAMPLE_RATE), "-ac", stereo ? "2" : "1"));
        finish(command, container, output);
        return command;
    }

    /**
     * The picture with a mixed track first and, if any, the separate tracks after it, each named (EXP-12). All are
     * samples already worked out, 16 bits, low byte first.
     *
     * @param titles the tracks' names, the mixed one's first, one for each of {@code tracks}
     * @param tracks the tracks' samples, the mixed one first
     */
    public static List<String> withTracks(String program, List<String> picture, List<String> titles, List<Path> tracks, List<String> codec,
                                          int channels, String container, Path output) {
        if (titles.size() != tracks.size() || tracks.isEmpty()) {
            throw new IllegalArgumentException("Each track needs a name, and there must be at least the mixed one");
        }
        List<String> command = start(program, picture);
        for (Path track : tracks) {
            command.addAll(List.of("-f", "s16le", "-ar", String.valueOf(SAMPLE_RATE), "-ac", String.valueOf(channels), "-i", track.toString()));
        }
        command.addAll(List.of("-map", "0:v:0"));
        for (int i = 0; i < tracks.size(); i++) {
            command.addAll(List.of("-map", (i + 1) + ":a:0"));
        }
        command.addAll(List.of("-c:v", "copy"));
        command.addAll(codec);
        command.addAll(List.of("-ar", String.valueOf(SAMPLE_RATE), "-ac", String.valueOf(channels)));
        for (int i = 0; i < titles.size(); i++) {
            // MKV shows the title; MOV shows the handler name, and keeps the title as the track's name
            command.addAll(List.of("-metadata:s:a:" + i, "title=" + titles.get(i), "-metadata:s:a:" + i, "handler_name=" + titles.get(i)));
            command.addAll(List.of("-disposition:a:" + i, i == 0 ? "default" : "0"));
        }
        finish(command, container, output);
        return command;
    }

    private static List<String> start(String program, List<String> picture) {
        List<String> command = new ArrayList<>(List.of(program, "-hide_banner", "-loglevel", "error", "-y"));
        command.addAll(picture);
        return command;
    }

    /** The file asked for, with its index at the front where the kind of file has one: a file cut short must still play */
    private static void finish(List<String> command, String container, Path output) {
        String kind = container.toLowerCase(Locale.ROOT);
        if (kind.equals("mp4") || kind.equals("mov")) {
            command.addAll(List.of("-movflags", "+faststart"));
        }
        command.add(output.toString());
    }

}
