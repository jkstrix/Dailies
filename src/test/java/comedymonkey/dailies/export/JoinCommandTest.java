package comedymonkey.dailies.export;

import org.junit.jupiter.api.Test;

import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The encoder's commands that write the finished file: the parts of several encoders joined alone, or with the sound
 * in the same run, so the picture is written once (EXP-7); and the same sound with a soundless file as the picture
 */
class JoinCommandTest {

    private static final Path OUT = Path.of("out", "clip.mp4");
    private static final Path PCM = Path.of("out", "clip.sound.pcm");
    private static final List<String> AAC = List.of("-c:a", "aac", "-b:a", "192k");

    /** The parts alone: read from the pipe, copied, no sound, the index at the front of an MP4 */
    @Test
    void partsAloneAreCopiedWithTheIndexAtTheFront() {
        assertEquals(List.of("ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "mpegts", "-i", "pipe:0", "-c", "copy", "-an",
            "-movflags", "+faststart", OUT.toString()), ParallelVideoSink.joinCommand("ffmpeg", OUT, "mp4"));
    }

    /** Only MP4 and MOV have an index to move */
    @Test
    void mkvAndWebmHaveNoIndexToMove() {
        assertFalse(JoinCommand.pictureOnly("ffmpeg", JoinCommand.PARTS, Path.of("clip.mkv"), "mkv").contains("-movflags"));
        assertFalse(JoinCommand.pictureOnly("ffmpeg", JoinCommand.PARTS, Path.of("clip.webm"), "webm").contains("-movflags"));
        assertTrue(JoinCommand.pictureOnly("ffmpeg", JoinCommand.PARTS, Path.of("clip.mov"), "MOV").contains("+faststart"));
    }

    /** The parts with the game's sound: one run reads both, copies the picture and encodes the sound into the file asked for */
    @Test
    void partsWithSoundAreJoinedInOneRun() {
        List<String> command = JoinCommand.withSound("ffmpeg", JoinCommand.PARTS, PCM, ByteOrder.LITTLE_ENDIAN, List.of(), AAC, true, "mp4", OUT);
        assertEquals(List.of("ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
            "-f", "mpegts", "-i", "pipe:0",
            "-f", "s16le", "-ar", "48000", "-ac", "2", "-i", PCM.toString(),
            "-map", "0:v:0", "-map", "1:a:0",
            "-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-ac", "2",
            "-movflags", "+faststart", OUT.toString()), command);
        // Nothing leaves the sound out, and nothing moves either input's times: each starts at nought by itself
        assertFalse(command.contains("-an"));
        assertFalse(command.contains("-itsoffset"));
        assertFalse(command.contains("-copyts"));
    }

    /** The same command with a soundless file as the picture differs only in how the picture is opened */
    @Test
    void aSoundlessFileAndThePartsShareTheRestOfTheCommand() {
        Path video = Path.of("out", "clip.picture.mp4");
        List<String> fromFile = JoinCommand.withSound("ffmpeg", JoinCommand.file(video), PCM, ByteOrder.LITTLE_ENDIAN, List.of(), AAC, false, "mp4", OUT);
        List<String> fromParts = JoinCommand.withSound("ffmpeg", JoinCommand.PARTS, PCM, ByteOrder.LITTLE_ENDIAN, List.of(), AAC, false, "mp4", OUT);
        assertEquals(List.of("-i", video.toString()), fromFile.subList(5, 7));
        assertEquals(fromFile.subList(7, fromFile.size()), fromParts.subList(9, fromParts.size()));
        // Mono asked for
        assertEquals("1", fromFile.get(fromFile.lastIndexOf("-ac") + 1));
    }

    /** Sound written the other way round is read so */
    @Test
    void bigEndianSamplesAreReadAsSuch() {
        List<String> command = JoinCommand.withSound("ffmpeg", JoinCommand.PARTS, PCM, ByteOrder.BIG_ENDIAN, List.of(), AAC, true, "mkv", Path.of("clip.mkv"));
        assertEquals("s16be", command.get(command.indexOf(PCM.toString()) - 6));
        assertFalse(command.contains("-movflags"));
    }

    /** Audio keyframes: each its own input after the sound, started late or part way in, mixed with the game's sound */
    @Test
    void audioKeyframesAreMixedInAfterTheGamesSound() {
        Path music = Path.of("music", "theme.ogg");
        Path sting = Path.of("music", "sting.wav");
        List<JoinCommand.Cue> cues = List.of(new JoinCommand.Cue(music, 0.5, 0.0, 12.25), new JoinCommand.Cue(sting, 1.0, 3.5, 0.0));
        List<String> command = JoinCommand.withSound("ffmpeg", JoinCommand.PARTS, PCM, ByteOrder.LITTLE_ENDIAN, cues, AAC, true, "mp4", OUT);
        // Inputs: 0 the picture, 1 the game's sound, 2 and 3 the keyframes, the first started 12.25 s into its file
        int first = command.indexOf(music.toString());
        assertEquals(List.of("-ss", "12.2500", "-i"), command.subList(first - 3, first));
        int second = command.indexOf(sting.toString());
        assertEquals("-i", command.get(second - 1));
        assertFalse(command.subList(first + 1, second).contains("-ss"));
        String graph = command.get(command.indexOf("-filter_complex") + 1);
        assertEquals("[1:a]anull[game]"
            + ";[2:a:0]aresample=48000,aformat=channel_layouts=stereo,volume=0.5000[cue0]"
            + ";[3:a:0]aresample=48000,aformat=channel_layouts=stereo,volume=1.0000,adelay=3500:all=1[cue1]"
            + ";[game][cue0][cue1]amix=inputs=3:duration=first:normalize=0[mix]", graph);
        int map = command.indexOf("-map");
        assertEquals(List.of("-map", "0:v:0", "-map", "[mix]"), command.subList(map, map + 4));
        assertEquals(OUT.toString(), command.get(command.size() - 1));
    }

    /** Separate tracks: the mix first and the default, each named, all read as samples */
    @Test
    void separateTracksAreEachNamedAfterTheMix() {
        Path mix = Path.of("out", "mix.pcm");
        Path game = Path.of("out", "game.pcm");
        Path alex = Path.of("out", "voice0.pcm");
        List<String> command = JoinCommand.withTracks("ffmpeg", JoinCommand.PARTS, List.of("Mix", "Game", "Alex"), List.of(mix, game, alex), AAC, 2, "mkv",
            Path.of("clip.mkv"));
        assertEquals(List.of("-f", "mpegts", "-i", "pipe:0"), command.subList(5, 9));
        for (Path track : List.of(mix, game, alex)) {
            int at = command.indexOf(track.toString());
            assertEquals(List.of("-f", "s16le", "-ar", "48000", "-ac", "2", "-i"), command.subList(at - 7, at));
        }
        int map = command.indexOf("-map");
        assertEquals(List.of("-map", "0:v:0", "-map", "1:a:0", "-map", "2:a:0", "-map", "3:a:0"), command.subList(map, map + 8));
        assertTrue(command.containsAll(List.of("title=Mix", "handler_name=Game", "title=Alex")));
        int disposition = command.indexOf("-disposition:a:0");
        assertEquals("default", command.get(disposition + 1));
        assertEquals("0", command.get(command.indexOf("-disposition:a:2") + 1));
        assertEquals("clip.mkv", command.get(command.size() - 1));
        assertFalse(command.contains("-movflags"));
    }

    @Test
    void everyTrackNeedsAName() {
        assertThrows(IllegalArgumentException.class, () -> JoinCommand.withTracks("ffmpeg", JoinCommand.PARTS, List.of("Mix"), List.of(), AAC, 2, "mkv", OUT));
        assertThrows(IllegalArgumentException.class, () -> JoinCommand.withTracks("ffmpeg", JoinCommand.PARTS, List.of(), List.of(), AAC, 2, "mkv", OUT));
    }

}
