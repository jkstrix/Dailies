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
    /**
     * Where Distant Horizons kept its far terrain for each dimension of the world recorded, by dimension id, relative
     * to the game's folder where it is inside it: playback shows that far terrain from a copy of its own
     * (compat.distanthorizons.DistantHorizonsCompat). Empty without Distant Horizons
     */
    public java.util.Map<String, String> distantHorizonsFolders = new java.util.TreeMap<>();
    /**
     * The compats that wrote screens or overlays into the recording (specification REC-26), by id, with the version each
     * wrote with (the newest, for a combined recording): playback says once when one is missing (PLAY-22)
     */
    public java.util.Map<String, Integer> compats = new java.util.TreeMap<>();

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
        /**
         * How the mods this original was recorded with differ from the list in this recording's header, which is the
         * first source's with the others' added. Every span that points at this original was made with these, so
         * the mods can be compared with those installed span by span (specification PLAY-5). Empty when there is no difference
         */
        public List<ModDifference> modDifferences = new ArrayList<>();

        /** The mods this original was recorded with: the header's list with this source's differences applied */
        public List<ModEntry> mods(List<ModEntry> header) {
            java.util.Map<String, String> mods = new java.util.LinkedHashMap<>();
            for (ModEntry mod : header) {
                mods.put(mod.id, mod.version);
            }
            if (this.modDifferences != null) {
                for (ModDifference difference : this.modDifferences) {
                    if (difference.version == null) {
                        mods.remove(difference.id);
                    } else {
                        mods.put(difference.id, difference.version);
                    }
                }
            }
            List<ModEntry> list = new ArrayList<>();
            mods.forEach((id, version) -> list.add(new ModEntry(id, version)));
            return list;
        }
    }

    /** One mod that a source of an edited recording had in another version than the header says, or did not have, or had alone */
    public static final class ModDifference {
        public String id = "";
        /** The version this source was recorded with, or null when it did not have the mod */
        public String version = null;
        /** The version in the recording's header, or null when the header does not list the mod */
        public String header = null;

        public ModDifference() {
        }

        public ModDifference(String id, String version, String header) {
            this.id = id;
            this.version = version;
            this.header = header;
        }
    }

    /** How a list of mods differs from the header's: for {@link SourceEntry#modDifferences} */
    public static List<ModDifference> differences(List<ModEntry> source, List<ModEntry> header) {
        java.util.Map<String, String> theirs = new java.util.TreeMap<>();
        source.forEach(mod -> theirs.put(mod.id, mod.version));
        java.util.Map<String, String> ours = new java.util.TreeMap<>();
        header.forEach(mod -> ours.put(mod.id, mod.version));
        List<ModDifference> list = new ArrayList<>();
        java.util.Set<String> ids = new java.util.TreeSet<>(theirs.keySet());
        ids.addAll(ours.keySet());
        for (String id : ids) {
            if (!java.util.Objects.equals(theirs.get(id), ours.get(id))) {
                list.add(new ModDifference(id, theirs.get(id), ours.get(id)));
            }
        }
        return list;
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

    /** The server resource packs whose bytes the recording holds (REC-19), each stored once */
    public List<Pack> packs = new ArrayList<>();

    /**
     * What compats stored once in the recording (specification REC-28), each by its name as shared data: the SHA-256 of
     * its bytes, in hex. A compat's states name them, and playback finds them by that name wherever it jumps (PLAY-23)
     */
    public List<String> compatBlobs = new ArrayList<>();

    /** A resource pack kept inside the recording as shared pieces, so that it is never held whole in memory */
    public static final class Pack {
        /** What the recorded pushes call this pack's content */
        public String key = "";
        /** SHA-256 of the whole file, in hex */
        public String sha256 = "";
        public long size = 0;
        /** The names of the shared pieces, in order */
        public List<String> pieces = new ArrayList<>();
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
            if (manifest.compats == null) {
                manifest.compats = new java.util.TreeMap<>();
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
            if (manifest.packs == null) {
                manifest.packs = new ArrayList<>();
            }
            if (manifest.compatBlobs == null) {
                manifest.compatBlobs = new ArrayList<>();
            }
            // Names only ever asked for as shared data, but a recording from elsewhere could name anything: what is not a hash is dropped
            manifest.compatBlobs.removeIf(name -> !SafePaths.matches(name, SafePaths.SHA256));
            for (Pack pack : manifest.packs) {
                // Playback names the file it writes a pack out to by this hash, so a recording from elsewhere (a share
                // pack) must not be able to make it a path: anything but the hash written as the recorder writes it is refused
                if (pack == null || !SafePaths.matches(pack.sha256, SafePaths.SHA256)) {
                    throw new RecordingFormatException(RecordingFormatException.Reason.DAMAGED, "The recording names a resource pack by something that is not its hash");
                }
                if (pack.key == null) {
                    pack.key = "";
                }
                if (pack.pieces == null) {
                    pack.pieces = new ArrayList<>();
                }
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
