package comedymonkey.dailies.file;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The source map of a recording made by combining or trimming (specification FILE-11): which stretches of which
 * original recordings it is made of, and the arithmetic that keeps moments working across edits. A moment keeps the
 * id and tick of the original it was recorded in for good; a recording answers "where is that moment in me" through
 * its spans.
 * <p>
 * Spans always point at originals. Editing a recording that was itself edited goes through {@link #slice} or
 * {@link #join}, which carry the spans across with their ranges adjusted, so every map is one step deep however many
 * edits there were. Ranges are half open: a span, range or stretch holds its first tick and not its last.
 */
public final class Spans {

    /** Ticks sourceStart (included) to sourceEnd (not included) of an original recording are found here from tick here */
    public record Span(String source, int sourceStart, int sourceEnd, int here) {
        public int length() {
            return this.sourceEnd - this.sourceStart;
        }

        public int hereEnd() {
            return this.here + this.length();
        }
    }

    /** A moment of an original recording */
    public record Place(String source, int tick) {
    }

    /** Part of a recording to keep or to cut: from (included) to to (not included) */
    public record Range(int from, int to) {
        public int length() {
            return this.to - this.from;
        }
    }

    /** A piece of a clip that is still there: where it is here, and which ticks of the original it holds */
    public record Stretch(int here, int hereEnd, int sourceStart, int sourceEnd) {
        public int length() {
            return this.hereEnd - this.here;
        }
    }

    /**
     * What is left of a clip in a recording.
     *
     * @param stretches the surviving pieces in the order they play here; empty when the whole clip was cut
     * @param cut       whether any of the clip is missing
     * @param removed   how many ticks of the clip are missing
     */
    public record Clip(List<Stretch> stretches, boolean cut, int removed) {
        public boolean present() {
            return !this.stretches.isEmpty();
        }
    }

    private Spans() {
    }

    /** What a recording that was never edited is made of: itself */
    public static List<Span> own(String id, int totalTicks) {
        return totalTicks <= 0 ? List.of() : List.of(new Span(id, 0, totalTicks, 0));
    }

    /** A recording's spans, or itself when it has none */
    public static List<Span> effective(@Nullable List<Span> spans, String id, int totalTicks) {
        return spans == null || spans.isEmpty() ? own(id, totalTicks) : spans;
    }

    /**
     * Where a moment of an original is in this recording, or -1 when it is not here (cut, or never part of it). When
     * the same moment is here more than once, the first. A moment exactly at the end of a stretch that nothing else
     * holds counts as the tick just after that stretch: the world it shows is the one the stretch ends on.
     */
    public static int toHere(List<Span> spans, String source, int tick) {
        int atAnEnd = -1;
        for (Span span : spans) {
            if (!span.source().equals(source)) {
                continue;
            }
            if (tick >= span.sourceStart() && tick < span.sourceEnd()) {
                return span.here() + (tick - span.sourceStart());
            }
            if (tick == span.sourceEnd() && atAnEnd < 0) {
                atAnEnd = span.hereEnd();
            }
        }
        return atAnEnd;
    }

    /** Which moment of which original a tick of this recording shows, or null when the tick is outside it */
    public static @Nullable Place toSource(List<Span> spans, int here) {
        for (Span span : spans) {
            if (here >= span.here() && here < span.hereEnd()) {
                return new Place(span.source(), span.sourceStart() + (here - span.here()));
            }
        }
        return null;
    }

    /**
     * The spans of a recording made by keeping ranges of this one, in the order given (a trim). Because the result is
     * built from this recording's own spans, it points at the originals, never at this recording.
     *
     * @param keep ranges of this recording's ticks, as {@link #normalise} returns them
     */
    public static List<Span> slice(List<Span> spans, List<Range> keep) {
        List<Span> result = new ArrayList<>();
        int running = 0;
        for (Range range : keep) {
            for (Span span : byHere(spans)) {
                int from = Math.max(range.from(), span.here());
                int to = Math.min(range.to(), span.hereEnd());
                if (to > from) {
                    int sourceStart = span.sourceStart() + (from - span.here());
                    result.add(new Span(span.source(), sourceStart, sourceStart + (to - from), running + (from - range.from())));
                }
            }
            running += range.length();
        }
        return merge(result);
    }

    /**
     * The spans of a recording made by playing several one after another (a combine). Nothing is added between them.
     *
     * @param parts   each recording's spans, as {@link #effective} returns them
     * @param lengths each recording's length in ticks
     */
    public static List<Span> join(List<List<Span>> parts, List<Integer> lengths) {
        List<Span> result = new ArrayList<>();
        int running = 0;
        for (int i = 0; i < parts.size(); i++) {
            for (Span span : byHere(parts.get(i))) {
                result.add(new Span(span.source(), span.sourceStart(), span.sourceEnd(), running + span.here()));
            }
            running += lengths.get(i);
        }
        return merge(result);
    }

    /**
     * What is left here of a clip recorded as ticks from (included) to to (not included) of an original: every
     * stretch of it that survives, in the order it plays, with how much is missing.
     */
    public static Clip clip(List<Span> spans, String source, int from, int to) {
        if (to <= from) {
            to = from + 1;
        }
        List<Stretch> stretches = new ArrayList<>();
        for (Span span : byHere(spans)) {
            if (!span.source().equals(source)) {
                continue;
            }
            int start = Math.max(from, span.sourceStart());
            int end = Math.min(to, span.sourceEnd());
            if (end > start) {
                int here = span.here() + (start - span.sourceStart());
                stretches.add(new Stretch(here, here + (end - start), start, end));
            }
        }
        // Counted over the original's ticks, so a stretch that is here twice does not hide one that is missing
        List<Range> covered = new ArrayList<>();
        for (Stretch stretch : stretches) {
            covered.add(new Range(stretch.sourceStart(), stretch.sourceEnd()));
        }
        int kept = 0;
        for (Range range : normalise(covered, Integer.MAX_VALUE)) {
            kept += range.length();
        }
        int removed = (to - from) - kept;
        return new Clip(List.copyOf(stretches), removed > 0, removed);
    }

    /**
     * Where a tick of a recording ends up when only some ranges of it are kept, or -1 when it was cut. For markers and
     * keyframes, which sit on ticks of the recording itself.
     */
    public static int remap(List<Range> keep, int tick) {
        int running = 0;
        for (Range range : keep) {
            if (tick >= range.from() && tick < range.to()) {
                return running + (tick - range.from());
            }
            running += range.length();
        }
        return -1;
    }

    /** Ranges in order, inside 0 to total, with overlapping and touching ones made one and empty ones left out */
    public static List<Range> normalise(List<Range> ranges, int total) {
        List<Range> sorted = new ArrayList<>();
        for (Range range : ranges) {
            int from = Math.max(0, range.from());
            int to = Math.min(total, range.to());
            if (to > from) {
                sorted.add(new Range(from, to));
            }
        }
        sorted.sort(Comparator.comparingInt(Range::from));
        List<Range> result = new ArrayList<>();
        for (Range range : sorted) {
            Range last = result.isEmpty() ? null : result.get(result.size() - 1);
            if (last != null && range.from() <= last.to()) {
                result.set(result.size() - 1, new Range(last.from(), Math.max(last.to(), range.to())));
            } else {
                result.add(range);
            }
        }
        return result;
    }

    /** What is left of 0 to total when these ranges are cut out */
    public static List<Range> invert(List<Range> cuts, int total) {
        List<Range> kept = new ArrayList<>();
        int from = 0;
        for (Range cut : normalise(cuts, total)) {
            if (cut.from() > from) {
                kept.add(new Range(from, cut.from()));
            }
            from = cut.to();
        }
        if (from < total) {
            kept.add(new Range(from, total));
        }
        return kept;
    }

    /** Spans that follow straight on from one another, here and in the same original, made one */
    public static List<Span> merge(List<Span> spans) {
        List<Span> result = new ArrayList<>();
        for (Span span : byHere(spans)) {
            if (span.length() <= 0) {
                continue;
            }
            Span last = result.isEmpty() ? null : result.get(result.size() - 1);
            if (last != null && last.source().equals(span.source()) && last.sourceEnd() == span.sourceStart() && last.hereEnd() == span.here()) {
                result.set(result.size() - 1, new Span(last.source(), last.sourceStart(), span.sourceEnd(), last.here()));
            } else {
                result.add(span);
            }
        }
        return result;
    }

    private static List<Span> byHere(List<Span> spans) {
        List<Span> sorted = new ArrayList<>(spans);
        sorted.sort(Comparator.comparingInt(Span::here));
        return sorted;
    }

}
