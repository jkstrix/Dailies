package comedymonkey.dailies.library;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import comedymonkey.dailies.file.Spans;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * What has been exported, in ticks of the original recordings (specification REC-8): {@code exported.json} in the
 * recordings folder. The retention rule that moves old recordings to the bin spares any recording that holds footage
 * a video was made from, and this is how it knows. Stored by original and tick, as moments are (FILE-11), so a
 * recording trimmed or combined from an exported one is known to hold the exported stretch too. Thread safe.
 */
public final class ExportLog {

    public static final String FILE_NAME = "exported.json";
    /** The most exports remembered; the oldest are let go first */
    private static final int MOST = 2000;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    /** A stretch of an original recording that went into a video */
    public static final class Stretch {
        public String source = "";
        public int from = 0;
        public int to = 0;

        public Stretch() {
        }

        public Stretch(String source, int from, int to) {
            this.source = source;
            this.from = from;
            this.to = to;
        }
    }

    /** One export */
    public static final class Exported {
        /** The recording it was exported from */
        public String recordingId = "";
        public String world = "";
        /** The file written, relative to the Dailies folder where it is inside it */
        public String output = "";
        public long when = 0;
        public long size = 0;
        public List<Stretch> stretches = new ArrayList<>();
    }

    private static final class Stored {
        List<Exported> exports = new ArrayList<>();
    }

    private final Path file;
    private final List<Exported> exports = new ArrayList<>();
    private boolean loaded = false;

    public ExportLog(Path folder) {
        this.file = folder.resolve(FILE_NAME);
    }

    private void load() {
        if (this.loaded) {
            return;
        }
        this.loaded = true;
        if (!Files.isRegularFile(this.file)) {
            return;
        }
        try {
            Stored stored = GSON.fromJson(Files.readString(this.file, StandardCharsets.UTF_8), Stored.class);
            if (stored != null && stored.exports != null) {
                for (Exported exported : stored.exports) {
                    if (exported != null && exported.stretches != null) {
                        this.exports.add(exported);
                    }
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            // Without it every recording is treated as never exported: retention is off by default, and the bin keeps what it moves
        }
    }

    public synchronized List<Exported> all() {
        this.load();
        return List.copyOf(this.exports);
    }

    /**
     * Remembers an export.
     *
     * @param sourceMap the source map of the recording exported from
     * @param from      the first tick exported, in that recording
     * @param to        the tick after the last
     */
    public synchronized void add(String recordingId, String world, String output, long size, long when, List<Spans.Span> sourceMap, int from, int to) throws IOException {
        this.load();
        Exported exported = new Exported();
        exported.recordingId = recordingId;
        exported.world = world == null ? "" : world;
        exported.output = output == null ? "" : output;
        exported.size = size;
        exported.when = when;
        for (Spans.Span span : sourceMap) {
            int start = Math.max(from, span.here());
            int end = Math.min(to, span.here() + (span.sourceEnd() - span.sourceStart()));
            if (end > start) {
                int shift = span.sourceStart() - span.here();
                exported.stretches.add(new Stretch(span.source(), start + shift, end + shift));
            }
        }
        this.exports.add(exported);
        while (this.exports.size() > MOST) {
            this.exports.remove(0);
        }
        Stored stored = new Stored();
        stored.exports = new ArrayList<>(this.exports);
        Files.createDirectories(this.file.getParent());
        Path temporary = this.file.resolveSibling(FILE_NAME + ".writing");
        Files.writeString(temporary, GSON.toJson(stored), StandardCharsets.UTF_8);
        Files.move(temporary, this.file, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Whether a recording holds any footage that went into a video */
    public static boolean holdsExported(LibraryIndex.Entry entry, List<Exported> exports) {
        List<Spans.Span> map = entry.sourceMap();
        for (Exported exported : exports) {
            if (exported.recordingId.equals(entry.id)) {
                return true;
            }
            for (Stretch stretch : exported.stretches) {
                if (stretch.to > stretch.from && Spans.clip(map, stretch.source, stretch.from, stretch.to).present()) {
                    return true;
                }
            }
        }
        return false;
    }

}
