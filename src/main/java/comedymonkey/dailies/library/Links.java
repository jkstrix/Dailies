package comedymonkey.dailies.library;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.file.Alignment;
import comedymonkey.dailies.file.RecordingFile;
import comedymonkey.dailies.file.RecordingManifest;
import comedymonkey.dailies.file.Spans;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Recordings linked as points of view of each other (PLAY-18): made on the same server, at the same time, by different
 * players, typically one's own and a friend's taken in from a shared session. Nothing is stored: the links are worked out
 * from the recordings' time indexes whenever they are asked for, so a recording imported later links at once.
 * Reads recordings' descriptions, so not for the client thread.
 */
public final class Links {

    /** A recording linked to another, and how to line the two up */
    public record Link(Path file, RecordingManifest manifest, Alignment alignment) {

        public String id() {
            return this.manifest.id;
        }
    }

    private record Known(long modified, RecordingManifest manifest, Alignment alignment) {
    }

    /** Descriptions already read, by file: a recording's description only changes when its file does */
    private static final Map<Path, Known> KNOWN = new ConcurrentHashMap<>();
    /** Recordings whose real times are further apart than this are never looked at closely */
    private static final long REAL_SLACK_MILLIS = Alignment.CLOCK_SLACK_MILLIS + 60_000;

    private Links() {
    }

    /** A recording's description and runs, read once per change of its file; null when it cannot be read */
    public static @Nullable Link describe(Path file) {
        try {
            long modified = Files.getLastModifiedTime(file).toMillis();
            Known known = KNOWN.get(file);
            if (known == null || known.modified() != modified) {
                try (RecordingFile recording = RecordingFile.open(file)) {
                    RecordingManifest manifest = recording.manifest();
                    known = new Known(modified, manifest, Alignment.of(manifest.segments, recording.segmentTimes()));
                }
                KNOWN.put(file, known);
            }
            return new Link(file, known.manifest(), known.alignment());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The recording with this id as a link (to itself), or null when it is not in the folder */
    public static @Nullable Link find(String recordingId) {
        Path file = Library.index().findById(recordingId);
        return file == null ? null : describe(file);
    }

    /** Every other player's recording linked to this one, oldest first */
    public static List<Link> linked(String recordingId) {
        Link own = find(recordingId);
        return own == null ? List.of() : linked(own);
    }

    public static List<Link> linked(Link own) {
        List<Link> found = new ArrayList<>();
        if (own.alignment().isEmpty()) {
            return found; // made before the time index: nothing can be lined up with it
        }
        Set<String> ownSources = sources(own.manifest());
        for (LibraryIndex.Entry entry : Library.list()) {
            if (entry.id.equals(own.id())) {
                continue;
            }
            if (own.manifest().realStart > 0 && entry.realStart > 0 && entry.realEnd > 0
                && (entry.realStart > own.manifest().realEnd + REAL_SLACK_MILLIS || entry.realEnd < own.manifest().realStart - REAL_SLACK_MILLIS)) {
                continue; // a different evening
            }
            Link other = describe(Library.fileOf(entry));
            if (other == null || other.alignment().isEmpty()) {
                continue;
            }
            if (Alignment.sameFootage(own.id(), ownSources, other.id(), sources(other.manifest()))) {
                continue; // a trimmed or combined copy is the same point of view
            }
            if (own.alignment().sameStretch(other.alignment())) {
                found.add(other);
            }
        }
        found.sort(java.util.Comparator.comparingLong(link -> link.manifest().created));
        return found;
    }

    /**
     * The other players' recordings that hold some of a stretch of this one, with the stretch in their own ticks
     * (clamped to what they hold).
     */
    public static List<Host.PointOfView> pointsOfView(String recordingId, int startTick, int endTick) {
        List<Host.PointOfView> views = new ArrayList<>();
        Link own = find(recordingId);
        if (own == null) {
            return views;
        }
        String open = comedymonkey.dailies.playback.Playback.recordingId();
        for (Link other : linked(own)) {
            int[] there = stretchIn(own.alignment(), other.alignment(), startTick, endTick);
            if (there != null) {
                views.add(new Host.PointOfView(other.file(), other.id(), other.manifest().name, other.manifest().recorderName, there[0], there[1],
                    other.id().equals(open)));
            }
        }
        return views;
    }

    /**
     * A stretch of one recording's ticks in another's: the first and last of its ticks that the other holds, as the other's
     * ticks, or null when it holds none of them.
     */
    public static int @Nullable [] stretchIn(Alignment from, Alignment to, int startTick, int endTick) {
        int first = -1;
        int last = -1;
        for (int[] shared : from.shared(to)) {
            int a = Math.max(shared[0], startTick);
            int b = Math.min(shared[1], endTick);
            if (a <= b) {
                if (first < 0) {
                    first = from.map(a, to);
                }
                last = from.map(b, to);
            }
        }
        return first < 0 || last < first ? null : new int[]{first, last};
    }

    private static Set<String> sources(RecordingManifest manifest) {
        Set<String> ids = new HashSet<>();
        for (Spans.Span span : manifest.sourceMap()) {
            ids.add(span.source());
        }
        ids.add(manifest.id);
        return ids;
    }

}
