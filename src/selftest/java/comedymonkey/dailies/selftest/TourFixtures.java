package comedymonkey.dailies.selftest;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import comedymonkey.dailies.Dailies;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.capture.Recorder;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Recordings kept between runs of the scripted test, with what the live game showed while each was made, so that the
 * long tours can check playback without staging and recording everything again each time. They live in the run
 * folder's dailies/fixtures, outside git. When one is used and when it is made again is decided by {@link FixtureRules}.
 * <p>
 * A fixture's notes are JSON, gzipped when large. Every one begins with the version of the staging that made it and the
 * fingerprint of the recording code; the rest is written and read back in one fixed order. Strings are written in
 * full the first time and by number after that, because a tour's notes say the same few thousand things a million times.
 */
final class TourFixtures {

    /** The switch that records the two tours again: -PdailiesFlags=dailies.test.recordTours */
    static final String RECORD_TOURS = "dailies.test.recordTours";

    private static String fingerprint;
    private static boolean fingerprinted = false;

    private TourFixtures() {
    }

    static Path folder() {
        return Host.get().dataDirectory().resolve("fixtures");
    }

    /** The fingerprint of the code that writes recordings, as this game has it; null when it cannot be read */
    static synchronized String fingerprint() {
        if (!fingerprinted) {
            fingerprinted = true;
            try {
                fingerprint = FixtureRules.fingerprint(recordingCode());
            } catch (Throwable t) {
                Host.LOGGER.warn("Dailies self test: the recording code could not be read for the fixtures' fingerprint", t);
                fingerprint = null;
            }
        }
        return fingerprint;
    }

    /** The class files of the recording code, by path inside the mod, from the mod's own jar or class folders */
    private static Map<String, byte[]> recordingCode() throws IOException {
        Map<String, byte[]> classes = new HashMap<>();
        Path root = null;
        try {
            // The mod loader reads the mod's contents itself, whether a jar or folders; paths come from the mod's root
            net.neoforged.fml.ModList.get().getModFileById(Dailies.ID).getFile().getContents().visitContent("comedymonkey/dailies", (path, resource) -> {
                String full = path.startsWith("comedymonkey/dailies/") ? path : "comedymonkey/dailies/" + path;
                if (FixtureRules.writesRecordings(full)) {
                    try {
                        classes.put(full, resource.readAllBytes());
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                }
            });
            if (!classes.isEmpty()) {
                return classes;
            }
        } catch (Throwable t) {
            classes.clear();
            Host.LOGGER.debug("Dailies self test: the mod file could not be asked for its classes", t);
        }
        if (root == null || !Files.isDirectory(root)) {
            // Classes straight from a folder, as a development run without the mod loader's own file system has them
            java.net.URL own = Recorder.class.getResource("Recorder.class");
            if (own != null && "file".equals(own.getProtocol())) {
                root = Path.of(java.net.URI.create(own.toString())).getParent().getParent();
            }
        }
        if (root == null || !Files.isDirectory(root)) {
            return classes;
        }
        Path base = root;
        try (Stream<Path> files = Files.walk(base)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String path = "comedymonkey/dailies/" + base.relativize(file).toString().replace('\\', '/');
                if (FixtureRules.writesRecordings(path)) {
                    classes.put(path, Files.readAllBytes(file));
                }
            }
        }
        return classes;
    }

    /** What a fixture's notes say of themselves, from the first two things in them */
    static FixtureRules.Stored stored(Path notes) throws IOException {
        try (JsonReader reader = reader(notes)) {
            reader.beginObject();
            int version = Integer.MIN_VALUE;
            String print = null;
            for (int i = 0; i < 2; i++) {
                String name = reader.nextName();
                if (name.equals("tourVersion")) {
                    version = reader.nextInt();
                } else if (name.equals("fingerprint")) {
                    print = reader.peek() == JsonToken.NULL ? nextNull(reader) : reader.nextString();
                } else {
                    throw new IOException("the notes begin with '" + name + "', not their version and fingerprint");
                }
            }
            return new FixtureRules.Stored(version, print);
        }
    }

    private static String nextNull(JsonReader reader) throws IOException {
        reader.nextNull();
        return null;
    }

    /**
     * Whether a fixture can be used as it is. Says in the log either way, and why.
     *
     * @param what    the fixture's name in the log
     * @param flag    the switch that asks for it to be recorded again
     * @param version the version of the staging this run has
     */
    static boolean reuse(SelfTest test, String what, Path recording, Path notes, String flag, int version) {
        String print = fingerprint();
        FixtureRules.Stored stored = null;
        String unreadable = null;
        if (Files.exists(recording)) {
            try {
                stored = stored(notes);
            } catch (Exception e) {
                unreadable = Files.exists(notes) ? String.valueOf(e) : "there are no notes at " + notes;
            }
        }
        String why = FixtureRules.recordAgain(Boolean.getBoolean(flag), flag, Files.exists(recording), stored, unreadable, version, print);
        if (print == null) {
            test.log("fixtures: the code that writes recordings could not be read, so a change to it will not make " + what + " be recorded again");
        }
        if (why == null) {
            test.log("fixtures: REUSING " + what + " from " + recording + " (version " + version + ", recording code " + print + ")");
            return true;
        }
        test.log("fixtures: RECORDING " + what + " AGAIN: " + why);
        return false;
    }

    // ------------------------------------------------------------------ Writing and reading notes

    /** Writes notes to a file beside the one they are for, moved into place only once whole */
    static void write(Path notes, int version, Body body) throws Exception {
        Files.createDirectories(notes.getParent());
        Path part = notes.resolveSibling(notes.getFileName() + ".part");
        OutputStream stream = Files.newOutputStream(part);
        if (notes.getFileName().toString().endsWith(".gz")) {
            stream = new GZIPOutputStream(stream, 1 << 16);
        }
        try (Out out = new Out(new JsonWriter(new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8), 1 << 16)))) {
            out.json.beginObject();
            out.json.name("tourVersion").value(version);
            out.json.name("fingerprint");
            String print = fingerprint();
            if (print == null) {
                out.json.nullValue();
            } else {
                out.json.value(print);
            }
            out.name("recorded").string(LocalDateTime.now().format(DateTimeFormatter.ofPattern("d MMMM yyyy HH:mm")));
            body.write(out);
            out.json.endObject();
        }
        Files.move(part, notes, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Reads notes back, past their version and fingerprint, which {@link #stored} has already looked at; gives when they were made */
    static String read(Path notes, Reading body) throws Exception {
        try (In in = new In(reader(notes))) {
            in.json.beginObject();
            in.json.nextName();
            in.json.skipValue();
            in.json.nextName();
            in.json.skipValue();
            String recorded = in.name("recorded").string();
            body.read(in);
            in.json.endObject();
            return recorded;
        }
    }

    private static JsonReader reader(Path notes) throws IOException {
        InputStream stream = new BufferedInputStream(Files.newInputStream(notes), 1 << 16);
        stream.mark(2);
        int first = stream.read();
        int second = stream.read();
        stream.reset();
        if (first == 0x1f && second == 0x8b) {
            stream = new GZIPInputStream(stream, 1 << 16);
        }
        return new JsonReader(new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8), 1 << 16));
    }

    /** Copies a fixture's recording to where a tour opens it: playback and the editor may write into what they open */
    static void copy(Path fixture, Path to) throws IOException {
        Files.createDirectories(to.getParent());
        Files.copy(fixture, to, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Keeps a recording as a fixture, replacing the one before */
    static void keep(Path recording, Path fixture) throws IOException {
        Files.createDirectories(fixture.getParent());
        Files.copy(recording, fixture, StandardCopyOption.REPLACE_EXISTING);
    }

    interface Body {
        void write(Out out) throws Exception;
    }

    interface Reading {
        void read(In in) throws Exception;
    }

    /** Writing, with strings by number after their first time */
    static final class Out implements AutoCloseable {
        final JsonWriter json;
        private final Map<String, Integer> said = new HashMap<>();

        Out(JsonWriter json) {
            this.json = json;
            // A number the game holds may be NaN, which plain JSON has no word for
            this.json.setLenient(true);
        }

        Out name(String name) throws IOException {
            this.json.name(name);
            return this;
        }

        void string(String text) throws IOException {
            if (text == null) {
                this.json.nullValue();
                return;
            }
            Integer number = this.said.get(text);
            if (number != null) {
                this.json.value(number);
            } else {
                this.said.put(text, this.said.size());
                this.json.value(text);
            }
        }

        void strings(List<String> texts) throws IOException {
            if (texts == null) {
                this.json.nullValue();
                return;
            }
            this.json.beginArray();
            for (String text : texts) {
                this.string(text);
            }
            this.json.endArray();
        }

        void bytes(byte[] bytes) throws IOException {
            if (bytes == null) {
                this.json.nullValue();
            } else {
                this.json.value(Base64.getEncoder().encodeToString(bytes));
            }
        }

        void shorts(short[] values) throws IOException {
            if (values == null) {
                this.json.nullValue();
                return;
            }
            ByteBuffer buffer = ByteBuffer.allocate(values.length * 2);
            buffer.asShortBuffer().put(values);
            this.bytes(buffer.array());
        }

        void ints(int[] values) throws IOException {
            if (values == null) {
                this.json.nullValue();
                return;
            }
            ByteBuffer buffer = ByteBuffer.allocate(values.length * 4);
            buffer.asIntBuffer().put(values);
            this.bytes(buffer.array());
        }

        void longs(long[] values) throws IOException {
            if (values == null) {
                this.json.nullValue();
                return;
            }
            ByteBuffer buffer = ByteBuffer.allocate(values.length * 8);
            buffer.asLongBuffer().put(values);
            this.bytes(buffer.array());
        }

        /** A file, such as a picture the live game showed, by name; nothing when it is not there */
        void file(Path file) throws IOException {
            this.bytes(Files.exists(file) ? Files.readAllBytes(file) : null);
        }

        @Override
        public void close() throws IOException {
            this.json.close();
        }
    }

    /** Reading, in the order written; strings given by number are the ones read before */
    static final class In implements AutoCloseable {
        final JsonReader json;
        private final List<String> said = new ArrayList<>();

        In(JsonReader json) {
            this.json = json;
            this.json.setLenient(true);
        }

        In name(String expected) throws IOException {
            String name = this.json.nextName();
            if (!name.equals(expected)) {
                throw new IOException("the notes have '" + name + "' where '" + expected + "' belongs");
            }
            return this;
        }

        /** Whether an array or object has more in it */
        boolean more() throws IOException {
            return this.json.hasNext();
        }

        private boolean isNull() throws IOException {
            if (this.json.peek() == JsonToken.NULL) {
                this.json.nextNull();
                return true;
            }
            return false;
        }

        String string() throws IOException {
            if (this.isNull()) {
                return null;
            }
            if (this.json.peek() == JsonToken.NUMBER) {
                return this.said.get(this.json.nextInt());
            }
            String text = this.json.nextString();
            this.said.add(text);
            return text;
        }

        List<String> strings() throws IOException {
            if (this.isNull()) {
                return null;
            }
            List<String> texts = new ArrayList<>();
            this.json.beginArray();
            while (this.json.hasNext()) {
                texts.add(this.string());
            }
            this.json.endArray();
            return texts;
        }

        int integer() throws IOException {
            return this.json.nextInt();
        }

        long number() throws IOException {
            return this.json.nextLong();
        }

        double decimal() throws IOException {
            return this.json.nextDouble();
        }

        boolean truth() throws IOException {
            return this.json.nextBoolean();
        }

        byte[] bytes() throws IOException {
            return this.isNull() ? null : Base64.getDecoder().decode(this.json.nextString());
        }

        short[] shorts() throws IOException {
            byte[] bytes = this.bytes();
            if (bytes == null) {
                return null;
            }
            short[] values = new short[bytes.length / 2];
            ByteBuffer.wrap(bytes).asShortBuffer().get(values);
            return values;
        }

        int[] ints() throws IOException {
            byte[] bytes = this.bytes();
            if (bytes == null) {
                return null;
            }
            int[] values = new int[bytes.length / 4];
            ByteBuffer.wrap(bytes).asIntBuffer().get(values);
            return values;
        }

        long[] longs() throws IOException {
            byte[] bytes = this.bytes();
            if (bytes == null) {
                return null;
            }
            long[] values = new long[bytes.length / 8];
            ByteBuffer.wrap(bytes).asLongBuffer().get(values);
            return values;
        }

        /** Puts a file written by {@link Out#file} back where it belongs */
        void file(Path file) throws IOException {
            byte[] bytes = this.bytes();
            if (bytes != null) {
                Files.createDirectories(file.getParent());
                Files.write(file, bytes);
            }
        }

        @Override
        public void close() throws IOException {
            this.json.close();
        }
    }
}
