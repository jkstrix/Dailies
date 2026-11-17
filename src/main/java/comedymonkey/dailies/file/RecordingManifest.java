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
    /** The server resource packs whose bytes the recording holds (REC-19), each stored once */
    public List<Pack> packs = new ArrayList<>();

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
        public int tick;
        /** 0xRRGGBB */
        public int colour;
        public String description = "";
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
            if (manifest.packs == null) {
                manifest.packs = new ArrayList<>();
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
