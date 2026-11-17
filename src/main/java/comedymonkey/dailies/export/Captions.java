package comedymonkey.dailies.export;

import comedymonkey.dailies.moments.MomentsTranscript;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleUnaryOperator;

/**
 * Captions of what was said, for an export (specification EXP-11 and EXP-15): the transcript of the range turned into
 * cues timed in seconds of the finished video, written as SRT or WebVTT, or asked what to draw into a frame.
 * <p>
 * Times go through the same mapping as the frames (EXP-2), handed in as a function from a recording tick to seconds
 * of video, so speed, timelapse and freeze keyframes move the words exactly as they move the picture. No Minecraft
 * here, so it can be tested without a game.
 */
public final class Captions {

    public enum Style {
        /** A line at a time, as subtitles are */
        LINES,
        /** Each word as it is said, alone, as short videos have them */
        WORD_BY_WORD
    }

    /** A cue is never shorter than this, so even a word said in a timelapse can be read */
    public static final double SHORTEST = 0.3;
    /** Longest cue in characters before it is broken at a word, and longest row of one */
    public static final int CUE_CHARACTERS = 84;
    public static final int ROW_CHARACTERS = 42;

    /** A word, in seconds of video */
    public record Word(double start, double end, String text) {
    }

    /** What is shown at once: some of a line, in seconds of video */
    public record Cue(double start, double end, String speaker, String text, List<Word> words) {
    }

    private Captions() {
    }

    // ------------------------------------------------------------------ Timing

    /**
     * The cues of a transcript.
     *
     * @param secondsAt    seconds of video at which a recording tick is shown; for an export, its TimeMap's realSecondsAt
     * @param totalSeconds how long the video is; nothing runs past it
     */
    public static List<Cue> cues(MomentsTranscript.Transcript transcript, DoubleUnaryOperator secondsAt, double totalSeconds) {
        List<Cue> cues = new ArrayList<>();
        for (MomentsTranscript.Line line : transcript.lines()) {
            List<Word> words = new ArrayList<>();
            for (MomentsTranscript.Word word : line.words()) {
                double start = clamp(secondsAt.applyAsDouble(word.startTick()), totalSeconds);
                double end = clamp(secondsAt.applyAsDouble(word.endTick()), totalSeconds);
                words.add(new Word(start, Math.max(start, end), word.text()));
            }
            // A long line is shown a piece at a time, each piece when its words are said
            List<Word> piece = new ArrayList<>();
            int length = 0;
            for (Word word : words) {
                int adding = (piece.isEmpty() ? 0 : 1) + word.text().length();
                if (!piece.isEmpty() && length + adding > CUE_CHARACTERS) {
                    cues.add(cue(line.speaker(), piece));
                    piece = new ArrayList<>();
                    length = 0;
                    adding = word.text().length();
                }
                piece.add(word);
                length += adding;
            }
            if (!piece.isEmpty()) {
                cues.add(cue(line.speaker(), piece));
            }
        }
        cues.sort(Comparator.comparingDouble(Cue::start));
        // Long enough to read, where the video and the speaker's next cue leave room
        List<Cue> lengthened = new ArrayList<>(cues.size());
        for (int i = 0; i < cues.size(); i++) {
            Cue cue = cues.get(i);
            double end = cue.end();
            if (end - cue.start() < SHORTEST) {
                double room = totalSeconds;
                for (int j = i + 1; j < cues.size(); j++) {
                    if (cues.get(j).speaker().equals(cue.speaker())) {
                        room = Math.min(room, cues.get(j).start());
                        break;
                    }
                }
                end = Math.max(end, Math.min(cue.start() + SHORTEST, room));
            }
            lengthened.add(new Cue(cue.start(), end, cue.speaker(), cue.text(), cue.words()));
        }
        return lengthened;
    }

    private static Cue cue(String speaker, List<Word> words) {
        StringBuilder text = new StringBuilder();
        for (Word word : words) {
            if (!text.isEmpty()) {
                text.append(' ');
            }
            text.append(word.text());
        }
        return new Cue(words.get(0).start(), words.get(words.size() - 1).end(), speaker, text.toString(), List.copyOf(words));
    }

    private static double clamp(double seconds, double total) {
        return Math.max(0.0, Math.min(total, seconds));
    }

    // ------------------------------------------------------------------ Files

    /** The cues as an SRT file */
    public static String srt(List<Cue> cues, boolean speakers) {
        StringBuilder out = new StringBuilder();
        int number = 1;
        for (Cue cue : cues) {
            out.append(number++).append('\n');
            out.append(timestamp(cue.start(), ',')).append(" --> ").append(timestamp(cue.end(), ',')).append('\n');
            for (String row : rows(said(cue, speakers), ROW_CHARACTERS)) {
                out.append(row).append('\n');
            }
            out.append('\n');
        }
        return out.toString();
    }

    /** The cues as a WebVTT file. The speaker is also given as a voice span, which players can style */
    public static String vtt(List<Cue> cues, boolean speakers) {
        StringBuilder out = new StringBuilder("WEBVTT\n\n");
        for (Cue cue : cues) {
            out.append(timestamp(cue.start(), '.')).append(" --> ").append(timestamp(cue.end(), '.')).append('\n');
            List<String> rows = rows(said(cue, speakers), ROW_CHARACTERS);
            for (int i = 0; i < rows.size(); i++) {
                String row = escapeVtt(rows.get(i));
                out.append(speakers && i == 0 ? "<v " + escapeVtt(cue.speaker()) + ">" + row : row).append('\n');
            }
            out.append('\n');
        }
        return out.toString();
    }

    /** "Name: words" with the speaker's name, or the words alone */
    public static String said(Cue cue, boolean speakers) {
        return speakers && !cue.speaker().isBlank() ? cue.speaker() + ": " + cue.text() : cue.text();
    }

    /** hh:mm:ss,mmm (SRT) or hh:mm:ss.mmm (WebVTT) */
    public static String timestamp(double seconds, char separator) {
        long millis = Math.max(0L, Math.round(seconds * 1000.0));
        long hours = millis / 3_600_000L;
        long minutes = millis / 60_000L % 60L;
        long secs = millis / 1000L % 60L;
        return String.format(Locale.ROOT, "%02d:%02d:%02d%c%03d", hours, minutes, secs, separator, millis % 1000L);
    }

    /** Breaks text into rows of at most this many characters, at spaces; a word longer than a row has one to itself */
    public static List<String> rows(String text, int perRow) {
        List<String> rows = new ArrayList<>();
        StringBuilder row = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!row.isEmpty() && row.length() + 1 + word.length() > perRow) {
                rows.add(row.toString());
                row.setLength(0);
            }
            if (!row.isEmpty()) {
                row.append(' ');
            }
            row.append(word);
        }
        if (!row.isEmpty()) {
            rows.add(row.toString());
        }
        return rows;
    }

    private static String escapeVtt(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("-->", "--&gt;");
    }

    // ------------------------------------------------------------------ Drawing

    /**
     * What to draw at this moment of the video, or null for nothing. For lines, every cue being shown, one under
     * another (two people may talk at once). For word by word, the word being said, or the last one said while the
     * line is still going on.
     */
    public static @Nullable String at(List<Cue> cues, double seconds, Style style, boolean speakers) {
        List<Cue> showing = new ArrayList<>();
        for (Cue cue : cues) {
            if (cue.start() > seconds) {
                break;
            }
            if (seconds < cue.end()) {
                showing.add(cue);
            }
        }
        if (showing.isEmpty()) {
            return null;
        }
        if (style == Style.LINES) {
            StringBuilder text = new StringBuilder();
            for (Cue cue : showing) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(said(cue, speakers));
            }
            return text.toString();
        }
        // The line begun most lately has the floor
        Cue latest = showing.get(showing.size() - 1);
        Word current = null;
        for (Word word : latest.words()) {
            if (word.start() <= seconds) {
                current = word;
            } else {
                break;
            }
        }
        if (current == null) {
            return null;
        }
        return speakers && !latest.speaker().isBlank() ? latest.speaker() + ": " + current.text() : current.text();
    }

    /** The caption files an export writes beside its video: "none", "srt", "vtt" or "both" as {srt, vtt} */
    public static boolean[] files(String word) {
        return switch (word == null ? "" : word.trim().toLowerCase(Locale.ROOT)) {
            case "none", "" -> new boolean[]{false, false};
            case "srt" -> new boolean[]{true, false};
            case "vtt" -> new boolean[]{false, true};
            case "both" -> new boolean[]{true, true};
            default -> throw new IllegalArgumentException("'caption_files' must be one of none, srt, vtt, both");
        };
    }

    public static Style style(String word) {
        return word != null && word.trim().toLowerCase(Locale.ROOT).replace(' ', '_').equals("word_by_word") ? Style.WORD_BY_WORD : Style.LINES;
    }

}
