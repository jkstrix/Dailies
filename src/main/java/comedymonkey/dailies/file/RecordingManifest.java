package comedymonkey.dailies.file;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * What a recording says about itself: everything a list or a menu shows without reading the recorded data
 * (specification FILE-2). Stored as JSON, so a field this version does not know is simply not read.
 */
public final class RecordingManifest {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** Never changes, whatever the file is called or wherever it is moved. Everything refers to a recording by this */
    public String id = "";
    public String name = "";
    /** When recording started, in milliseconds since 1970 */
    public long created = 0;
    public String gameVersion = "";
    public int dataVersion = 0;
    public int protocolVersion = 0;
    public String modVersion = "";
    public int totalTicks = 0;
    public String recorderName = "";
    public String recorderId = "";
    /** The world's name in singleplayer, the server's name or address otherwise */
    public String worldName = "";
    public boolean singleplayer = false;
    /** False until the recording was finished properly. A file left like that by a crash can be recovered */
    public boolean finished = false;
    /** True when the file was repaired after a crash */
    public boolean recovered = false;
    public List<Segment> segments = new ArrayList<>();
    public List<Marker> markers = new ArrayList<>();
    public List<ModEntry> mods = new ArrayList<>();
    /** The names of everyone who was in the tab list at some point, the recorder included, for the list of recordings */
    public List<String> players = new ArrayList<>();
    /**
     * The stretches of original recordings this one is made of, when it was made by combining or trimming
     * (specification FILE-11). Empty for a recording that was never edited: it stands for itself
     */
    public List<SpanEntry> spans = new ArrayList<>();
    /** What is known about each original the spans point at, so it can still be named once its file is gone */
    public List<SourceEntry> sources = new ArrayList<>();
    /** When the recording was made by combining or trimming, in milliseconds since 1970; 0 for one that was recorded */
    public long edited = 0;
    /** The real time and game time of each segment's first tick (specification FILE-16). May lack segments after a crash */
    public List<SegmentTime> times = new ArrayList<>();
    /** The earliest and latest real time the recording holds, in milliseconds since 1970; 0 when not known */
    public long realStart = 0;
    public long realEnd = 0;

    public static final class SpanEntry {
        public String source = "";
        public int sourceStart;
        public int sourceEnd;
        public int here;

        public SpanEntry() {
        }

        public SpanEntry(Spans.Span span) {
            this.source = span.source();
            this.sourceStart = span.sourceStart();
            this.sourceEnd = span.sourceEnd();
            this.here = span.here();
        }

        public Spans.Span span() {
            return new Spans.Span(this.source, this.sourceStart, this.sourceEnd, this.here);
        }
    }

    public static final class SourceEntry {
        public String id = "";
        public String name = "";
        public String worldName = "";
        public long created = 0;
        public int totalTicks = 0;
    }

    public static final class SegmentTime {
        public int segment;
        public long real;
        public long game;

        public SegmentTime() {
        }

        public SegmentTime(int segment, long real, long game) {
            this.segment = segment;
            this.real = real;
            this.game = game;
        }
    }

    /** The source map as {@link Spans} works with it: the stored spans, or the recording itself when it has none */
    public List<Spans.Span> sourceMap() {
        List<Spans.Span> list = new ArrayList<>();
        for (SpanEntry entry : this.spans) {
            list.add(entry.span());
        }
        return Spans.effective(list, this.id, this.totalTicks);
    }

    /** Whether the recording was made by combining or trimming others */
    public boolean isEdited() {
        return !this.spans.isEmpty();
    }

    public static final class Segment {
        public int number;
        public int startTick;
        public int endTick;

        public Segment() {
        }

        public Segment(int number, int startTick, int endTick) {
            this.number = number;
            this.startTick = startTick;
            this.endTick = endTick;
        }
    }

    public static final class Marker {
        public static final String BOUNDARY = "boundary";
        /** Where a trimmed recording has had a stretch taken out */
        public static final String CUT = "cut";

        public int tick;
        /** 0xRRGGBB */
        public int colour;
        public String description = "";
        /** Null for a marker someone dropped; {@link #BOUNDARY} where a combined recording goes from one source to the next */
        public String kind = null;
        /** Where the recorder stood and looked (x, y, z, yaw, pitch), or null when the marker does not store it */
        public double[] position = null;
        public String dimension = null;
    }

    public static final class ModEntry {
        public String id = "";
        public String version = "";

        public ModEntry() {
        }

        public ModEntry(String id, String version) {
            this.id = id;
            this.version = version;
        }
    }

    public byte[] toBytes() {
        return GSON.toJson(this).getBytes(StandardCharsets.UTF_8);
    }

    public static RecordingManifest fromBytes(byte[] bytes) throws RecordingFormatException {
        try {
            RecordingManifest manifest = GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), RecordingManifest.class);
            if (manifest == null || manifest.id == null || manifest.id.isEmpty()) {
                throw new RecordingFormatException(RecordingFormatException.Reason.DAMAGED, "The recording's description is empty");
            }
            if (manifest.segments == null) {
                manifest.segments = new ArrayList<>();
            }
            if (manifest.markers == null) {
                manifest.markers = new ArrayList<>();
            }
            if (manifest.mods == null) {
                manifest.mods = new ArrayList<>();
            }
            if (manifest.players == null) {
                manifest.players = new ArrayList<>();
            }
            if (manifest.spans == null) {
                manifest.spans = new ArrayList<>();
            }
            if (manifest.sources == null) {
                manifest.sources = new ArrayList<>();
            }
            if (manifest.times == null) {
                manifest.times = new ArrayList<>();
            }
            return manifest;
        } catch (JsonSyntaxException e) {
            throw new RecordingFormatException(RecordingFormatException.Reason.DAMAGED, "The recording's description cannot be read");
        }
    }

    public RecordingManifest copy() {
        try {
            return fromBytes(this.toBytes());
        } catch (RecordingFormatException e) {
            throw new IllegalStateException(e);
        }
    }

}
