package comedymonkey.dailies.moments;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * What the editor's timeline shows of a recording's moments (specification EDIT-9): events as marks, clips as bands,
 * and one strip per speaker for when they talked. Everything is in ticks of the recording being looked at, which need
 * not be the recording the moments were made in: a moment keeps the id and tick of its original for good, and a
 * {@link Mapper} says where, if anywhere, that is in a recording trimmed or combined from it.
 * <p>
 * Plain data and arithmetic, with no game in it, so it can be tested without one.
 */
public final class MomentsTimeline {

    /** An event. The tick is where it is in the recording being looked at */
    public record Mark(int tick, String kind, @Nullable String subject, String description, double score, String eventId, String sessionId) {
    }

    /**
     * A clip.
     *
     * @param cut whether part of the clip is missing from this recording; the band then runs over what is left
     */
    public record Band(int start, int end, String title, double score, String clipId, String sessionId, boolean cut) {
    }

    /**
     * A stretch of someone talking.
     *
     * @param words     what was said, or empty when it has not been written down
     * @param nonSpeech whether it was sound without words: laughter, a shout
     */
    public record Talk(int start, int end, String words, boolean nonSpeech) {
    }

    public record Strip(String speakerId, String speaker, List<Talk> talks) {
    }

    public record Timeline(String recordingId, List<Mark> marks, List<Band> bands, List<Strip> strips) {
        public static Timeline empty(String recordingId) {
            return new Timeline(recordingId, List.of(), List.of(), List.of());
        }

        public boolean isEmpty() {
            return this.marks.isEmpty() && this.bands.isEmpty() && this.strips.isEmpty();
        }
    }

    /** Where moments of other recordings are in the one being looked at */
    public interface Mapper {
        /** The tick here of a tick of an original, or -1 when it is not here: cut, or never part of it */
        int tick(String recordingId, int tick);

        /**
         * What is left here of ticks start to end of an original, as {first tick, last tick} pieces in the order
         * they play. Empty when none of it is here.
         */
        List<int[]> stretch(String recordingId, int start, int end);
    }

    /** One session's records, as they are stored, with the two things only the caller can say about an event */
    public record Source(String sessionId, List<JsonObject> events, List<JsonObject> clips, List<JsonObject> dialogue,
                         ToDoubleFunction<JsonObject> score, java.util.function.Function<JsonObject, String> describe) {
    }

    /** Several marks too close together on screen to tell apart, drawn as one */
    public record Cluster(double x, List<Mark> marks) {
        /** The mark that speaks for the cluster: the highest score, the earliest of equals */
        public Mark best() {
            Mark best = this.marks.get(0);
            for (Mark mark : this.marks) {
                if (mark.score() > best.score()) {
                    best = mark;
                }
            }
            return best;
        }

        public int count() {
            return this.marks.size();
        }
    }

    private MomentsTimeline() {
    }

    /**
     * @param ignored whether an event is on the ignore list, by type and subject
     * @param total   the length of the recording being looked at, in ticks
     */
    public static Timeline build(String recordingId, List<Source> sources, Mapper mapper, java.util.function.BiPredicate<String, String> ignored, int total) {
        List<Mark> marks = new ArrayList<>();
        List<Band> bands = new ArrayList<>();
        Map<String, Strip> strips = new LinkedHashMap<>();

        for (Source source : sources) {
            for (JsonObject event : source.events()) {
                String from = MJson.str(event, "replay_uuid");
                String type = MJson.str(event, "type", "");
                String subject = MJson.str(event, "subject");
                if (from == null || !event.has("replay_tick") || ignored.test(type, subject)) {
                    continue;
                }
                int tick = mapper.tick(from, (int) MJson.lng(event, "replay_tick", -1));
                if (tick < 0 || tick > total) {
                    continue;
                }
                marks.add(new Mark(tick, type, subject, source.describe().apply(event), source.score().applyAsDouble(event), MJson.str(event, "id", ""), source.sessionId()));
            }

            for (JsonObject clip : source.clips()) {
                String from = MJson.str(clip, "replay_uuid");
                if (from == null || !clip.has("start_replay_tick") || allIgnored(clip, ignored)) {
                    continue;
                }
                int start = (int) Math.max(0, MJson.lng(clip, "start_replay_tick", 0));
                int end = (int) Math.max(start, MJson.lng(clip, "end_replay_tick", start));
                List<int[]> pieces = mapper.stretch(from, start, end);
                if (pieces.isEmpty()) {
                    continue;
                }
                int first = Integer.MAX_VALUE;
                int last = Integer.MIN_VALUE;
                int kept = 0;
                for (int[] piece : pieces) {
                    first = Math.min(first, piece[0]);
                    last = Math.max(last, piece[1]);
                    kept += piece[1] - piece[0];
                }
                first = Math.max(0, first);
                last = Math.min(total, last);
                if (last < first) {
                    continue;
                }
                // A tick or two lost to rounding at the ends is not a cut; a missing stretch is
                boolean cut = pieces.size() > 1 || kept < end - start - 2;
                bands.add(new Band(first, last, MJson.str(clip, "title", "Clip"), MJson.dbl(clip, "importance", 0), MJson.str(clip, "id", ""), source.sessionId(), cut));
            }

            for (JsonObject segment : source.dialogue()) {
                String from = MJson.str(segment, "start_replay_uuid");
                if (from == null || !segment.has("start_replay_tick")) {
                    continue;
                }
                int start = (int) Math.max(0, MJson.lng(segment, "start_replay_tick", 0));
                String endFrom = MJson.str(segment, "end_replay_uuid", from);
                // A sentence that ran on into the next recording is drawn up to where this one ends
                int end = endFrom.equals(from) ? (int) Math.max(start, MJson.lng(segment, "end_replay_tick", start))
                    : start + (int) Math.round(MJson.dbl(segment, "duration_seconds", 0) * 20.0);
                JsonObject speaker = MJson.obj(segment, "speaker");
                String speakerId = MJson.str(speaker, "uuid", "unknown");
                String name = MJson.str(speaker, "name", "Unknown");
                boolean done = "done".equals(MJson.str(segment, "status"));
                String words = done ? MJson.str(segment, "text", "").trim() : "";
                boolean nonSpeech = MJson.bool(segment, "non_speech");
                for (int[] piece : mapper.stretch(from, start, end)) {
                    int pieceStart = Math.max(0, piece[0]);
                    int pieceEnd = Math.min(total, piece[1]);
                    if (pieceEnd < pieceStart) {
                        continue;
                    }
                    Strip strip = strips.computeIfAbsent(speakerId, id -> new Strip(id, name, new ArrayList<>()));
                    strip.talks().add(new Talk(pieceStart, pieceEnd, words, nonSpeech));
                }
            }
        }

        marks.sort(Comparator.comparingInt(Mark::tick));
        bands.sort(Comparator.comparingInt(Band::start));
        List<Strip> ordered = new ArrayList<>(strips.values());
        for (Strip strip : ordered) {
            strip.talks().sort(Comparator.comparingInt(Talk::start));
        }
        // Whoever talked most comes first, so the strips that get folded away are the quietest
        ordered.sort(Comparator.comparingInt((Strip strip) -> -talked(strip)).thenComparing(Strip::speaker));
        return new Timeline(recordingId, List.copyOf(marks), List.copyOf(bands), List.copyOf(ordered));
    }

    private static int talked(Strip strip) {
        int ticks = 0;
        for (Talk talk : strip.talks()) {
            ticks += talk.end() - talk.start();
        }
        return ticks;
    }

    /** A clip built round events is hidden once every one of them is ignored; a conversation has none and stays */
    private static boolean allIgnored(JsonObject clip, java.util.function.BiPredicate<String, String> ignored) {
        int events = 0;
        for (JsonElement element : MJson.arr(clip, "events")) {
            if (element.isJsonObject()) {
                events += 1;
                if (!ignored.test(MJson.str(element.getAsJsonObject(), "type", ""), MJson.str(element.getAsJsonObject(), "subject"))) {
                    return false;
                }
            }
        }
        return events > 0;
    }

    /**
     * Gathers marks that would be drawn within a few pixels of each other into one, so a busy stretch reads as "five
     * things happened about here" and not as a smear. A cluster is drawn at the middle of what it holds.
     *
     * @param marks     in order of tick
     * @param viewStart the tick at the left edge
     * @param perTick   pixels per tick
     * @param apart     the least distance in pixels between two things drawn
     * @param wanted    which marks to draw at all; the others are left out, not clustered
     */
    public static List<Cluster> cluster(List<Mark> marks, double viewStart, double perTick, double apart, @Nullable Predicate<Mark> wanted) {
        List<Cluster> clusters = new ArrayList<>();
        List<Mark> open = new ArrayList<>();
        double firstX = 0.0;
        double lastX = 0.0;
        for (Mark mark : marks) {
            if (wanted != null && !wanted.test(mark)) {
                continue;
            }
            double x = (mark.tick() - viewStart) * perTick;
            // Measured from the cluster's first mark, so a long run of close marks breaks into several and none drifts
            if (!open.isEmpty() && x - firstX >= apart) {
                clusters.add(new Cluster((firstX + lastX) / 2.0, List.copyOf(open)));
                open.clear();
            }
            if (open.isEmpty()) {
                firstX = x;
            }
            lastX = x;
            open.add(mark);
        }
        if (!open.isEmpty()) {
            clusters.add(new Cluster((firstX + lastX) / 2.0, List.copyOf(open)));
        }
        return clusters;
    }

}
