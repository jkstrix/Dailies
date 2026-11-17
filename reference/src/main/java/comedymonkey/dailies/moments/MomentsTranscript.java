package comedymonkey.dailies.moments;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiPredicate;

/**
 * What was said in a stretch of a recording, line by line and word by word, for captions (specification EXP-11 and
 * EXP-15). Everything is in ticks of the recording asked about, which need not be the one the voice was logged in: a
 * {@link Mapper} says where, if anywhere, the original's ticks are in a recording trimmed or combined from it, as
 * for {@link MomentsTimeline}.
 * <p>
 * A line is one segment as the transcription server split the speech. Its words carry the times the server gave them;
 * a segment transcribed before word timings were asked for, or by a server that gives none, has its words spread
 * evenly across it, and says so ({@link Word#timed()} is false).
 * <p>
 * Plain data and arithmetic, with no game in it, so it can be tested without one.
 */
public final class MomentsTranscript {

    /** Ticks of the game per second, which a recording keeps to when nothing better is known */
    private static final double TICKS_PER_SECOND = 20.0;

    /**
     * One word.
     *
     * @param timed whether the server said when it was spoken; false for words spread across their line
     */
    public record Word(String text, double startTick, double endTick, boolean timed) {
    }

    /** One line, as the server split the speech. Its times are those of its first and last words */
    public record Line(String speakerId, String speaker, double startTick, double endTick, String text, List<Word> words) {
    }

    public record Transcript(String recordingId, List<Line> lines) {
        public static Transcript empty(String recordingId) {
            return new Transcript(recordingId, List.of());
        }

        public boolean isEmpty() {
            return this.lines.isEmpty();
        }
    }

    /**
     * A stretch of an original that is in the recording being looked at.
     *
     * @param tick          where it starts here
     * @param endTick       where it ends here
     * @param sourceTick    the original's tick it starts with
     * @param sourceEndTick the original's tick it ends with
     */
    public record Piece(int tick, int endTick, int sourceTick, int sourceEndTick) {
    }

    /** Where ticks of other recordings are in the one being looked at */
    public interface Mapper {
        /** What is left here of ticks start to end of an original, in the order they play; empty when none of it is */
        List<Piece> pieces(String recordingId, int start, int end);
    }

    private MomentsTranscript() {
    }

    /**
     * @param dialogue the voice segments as they are stored, from every session that may hold some of the recording
     * @param excluded whether a speaker, by uuid and name, is on the list of those never transcribed (MOM-36)
     * @param fromTick the first tick wanted, here
     * @param toTick   the last tick wanted, here
     */
    public static Transcript build(String recordingId, List<JsonObject> dialogue, Mapper mapper, BiPredicate<String, String> excluded, int fromTick, int toTick) {
        List<Line> lines = new ArrayList<>();
        for (JsonObject record : dialogue) {
            if (!"done".equals(MJson.str(record, "status")) || MJson.bool(record, "non_speech")) {
                continue;
            }
            String from = MJson.str(record, "start_replay_uuid");
            if (from == null || !record.has("start_replay_tick")) {
                continue;
            }
            JsonObject speaker = MJson.obj(record, "speaker");
            String speakerId = MJson.str(speaker, "uuid", "unknown");
            String name = MJson.str(speaker, "name", "Unknown");
            if (excluded.test(speakerId, name)) {
                continue;
            }
            int start = (int) Math.max(0, MJson.lng(record, "start_replay_tick", 0));
            double duration = Math.max(0.0, MJson.dbl(record, "duration_seconds", 0));
            String endFrom = MJson.str(record, "end_replay_uuid", from);
            long endTick = endFrom.equals(from) ? MJson.lng(record, "end_replay_tick", start) : -1;
            // The audio's seconds are spread over the ticks the recording logged for it. A sentence that ran on into the
            // next recording, or a record without an end, goes at the game's own rate
            double perSecond = endTick > start && duration > 0.0 ? (endTick - start) / duration : TICKS_PER_SECOND;

            List<double[]> spans = new ArrayList<>();
            List<List<Word>> segmentWords = new ArrayList<>();
            boolean any = false;
            for (JsonElement element : MJson.arr(record, "segments")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject segment = element.getAsJsonObject();
                String said = MJson.str(segment, "text", "").trim();
                if (MJson.bool(segment, "non_speech") || said.isEmpty()) {
                    continue;
                }
                any = true;
                double segmentStart = Math.max(0.0, MJson.dbl(segment, "start", 0));
                double segmentEnd = Math.max(segmentStart, MJson.dbl(segment, "end", segmentStart));
                spans.add(new double[]{segmentStart, segmentEnd});
                segmentWords.add(wordsOf(segment, said, segmentStart, segmentEnd));
            }
            if (!any) {
                // A server that gave no segments: its text is one line over the whole of the audio
                String said = MJson.str(record, "text", "").trim();
                if (said.isEmpty()) {
                    continue;
                }
                double length = duration > 0.0 ? duration : endTick > start ? (endTick - start) / TICKS_PER_SECOND : 1.0;
                spans.add(new double[]{0.0, length});
                segmentWords.add(spread(said, 0.0, length));
            }

            double lastSecond = 0.0;
            for (List<Word> words : segmentWords) {
                for (Word word : words) {
                    lastSecond = Math.max(lastSecond, word.endTick());
                }
            }
            List<Piece> pieces = mapper.pieces(from, start, start + (int) Math.ceil(lastSecond * perSecond) + 1);
            if (pieces.isEmpty()) {
                continue;
            }
            for (List<Word> words : segmentWords) {
                Line line = line(speakerId, name, words, start, perSecond, pieces, fromTick, toTick);
                if (line != null) {
                    lines.add(line);
                }
            }
        }
        lines.sort(Comparator.comparingDouble(Line::startTick).thenComparing(Line::speaker));
        return new Transcript(recordingId, List.copyOf(lines));
    }

    /** The words of a segment in seconds of its audio (held in the tick fields until they are placed) */
    private static List<Word> wordsOf(JsonObject segment, String said, double start, double end) {
        List<Word> words = new ArrayList<>();
        for (JsonElement element : MJson.arr(segment, "words")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject word = element.getAsJsonObject();
            String text = MJson.str(word, "word", MJson.str(word, "text", "")).trim();
            if (text.isEmpty()) {
                continue;
            }
            double wordStart = Math.max(0.0, MJson.dbl(word, "start", start));
            words.add(new Word(text, wordStart, Math.max(wordStart, MJson.dbl(word, "end", wordStart)), true));
        }
        return words.isEmpty() ? spread(said, start, end) : words;
    }

    /**
     * The words of a line without timings of their own, each given an equal share of the line's time, one after
     * another. Used for segments transcribed before word timings were asked for.
     */
    public static List<Word> spread(String text, double start, double end) {
        String[] parts = text.trim().split("\\s+");
        List<Word> words = new ArrayList<>();
        if (text.isBlank()) {
            return words;
        }
        double share = Math.max(0.0, end - start) / parts.length;
        for (int i = 0; i < parts.length; i++) {
            words.add(new Word(parts[i], start + i * share, start + (i + 1) * share, false));
        }
        return words;
    }

    /** One segment placed in the recording being looked at, without what was cut from it or lies outside the ticks wanted */
    private static Line line(String speakerId, String speaker, List<Word> words, int start, double perSecond, List<Piece> pieces, int fromTick, int toTick) {
        List<Word> placed = new ArrayList<>();
        for (Word word : words) {
            double sourceStart = start + word.startTick() * perSecond;
            double sourceEnd = start + word.endTick() * perSecond;
            Piece piece = holding(pieces, sourceStart);
            if (piece == null) {
                // Cut from this recording
                continue;
            }
            double here = piece.tick() + (sourceStart - piece.sourceTick());
            double hereEnd = Math.min(piece.endTick(), piece.tick() + (sourceEnd - piece.sourceTick()));
            if (hereEnd < here) {
                hereEnd = here;
            }
            if (hereEnd < fromTick || here > toTick) {
                continue;
            }
            placed.add(new Word(word.text(), here, hereEnd, word.timed()));
        }
        if (placed.isEmpty()) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (Word word : placed) {
            if (!text.isEmpty()) {
                text.append(' ');
            }
            text.append(word.text());
        }
        return new Line(speakerId, speaker, placed.get(0).startTick(), placed.get(placed.size() - 1).endTick(), text.toString(), List.copyOf(placed));
    }

    private static Piece holding(List<Piece> pieces, double sourceTick) {
        for (Piece piece : pieces) {
            if (sourceTick >= piece.sourceTick() && sourceTick <= piece.sourceEndTick()) {
                return piece;
            }
        }
        return null;
    }

}
