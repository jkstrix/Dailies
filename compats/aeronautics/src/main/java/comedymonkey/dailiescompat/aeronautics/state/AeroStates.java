package comedymonkey.dailiescompat.aeronautics.state;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * What the Create Aeronautics compat records, as bytes (see {@code client.AeronauticsDailies}): for each claim, the whole
 * of what it showed, the least that draws it again. Nothing here refers to the mods, Dailies or the game, so it is unit
 * tested without them. Text is kept as the game's own JSON for a component, block states as the game writes them in a
 * command, items by id, and what the mods keep in their own codecs (the diagram's settings and the forces the server sent
 * it) as those codecs' bytes; here each is only a string or bytes. Version 1 of the compat writes these; a later version
 * must still read them, so a state only ever gains fields at its end.
 */
public final class AeroStates {

    /** The longest list or string a record is read with, so a damaged record fails instead of filling the memory */
    static final int MOST = 1 << 20;

    private AeroStates() {
    }

    // --- Simulated's altitude sensor ---

    /**
     * The altitude sensor's screen as it stood: the sensor's place, the two heights being set (as shares of the world's
     * height), where the bars' handles were drawn on their way to them, how long the screen had been open (its backdrop
     * fades in over a second), and which handle was being dragged (its figure is drawn lit).
     */
    public record Altitude(long pos, float high, float low, float shownHigh, float shownLow, int ticksOpen, boolean draggingLow, boolean draggingHigh) {
    }

    public static byte[] altitude(Altitude state) {
        return write(out -> {
            out.out.writeLong(state.pos());
            out.out.writeFloat(state.high());
            out.out.writeFloat(state.low());
            out.out.writeFloat(state.shownHigh());
            out.out.writeFloat(state.shownLow());
            out.out.writeInt(state.ticksOpen());
            out.out.writeBoolean(state.draggingLow());
            out.out.writeBoolean(state.draggingHigh());
        });
    }

    public static Altitude readAltitude(byte[] data) {
        return read(data, in -> new Altitude(in.in.readLong(), in.in.readFloat(), in.in.readFloat(), in.in.readFloat(), in.in.readFloat(),
            in.in.readInt(), in.in.readBoolean(), in.in.readBoolean()));
    }

    // --- Simulated's modulating linked receiver ---

    /**
     * The modulating linked receiver's screen: the receiver's place and block, the nearest and farthest ranges being set,
     * and how far off the nearest transmitter was as the receiver showed it (the marker on the screen's lane).
     */
    public record Receiver(long pos, String block, int minRange, int maxRange, double distance) {

        public Receiver {
            Objects.requireNonNull(block);
        }
    }

    public static byte[] receiver(Receiver state) {
        return write(out -> {
            out.out.writeLong(state.pos());
            out.text(state.block());
            out.out.writeInt(state.minRange());
            out.out.writeInt(state.maxRange());
            out.out.writeDouble(state.distance());
        });
    }

    public static Receiver readReceiver(byte[] data) {
        return read(data, in -> new Receiver(in.in.readLong(), in.text(), in.in.readInt(), in.in.readInt(), in.in.readDouble()));
    }

    // --- Simulated's nameplate ---

    /**
     * A nameplate being named: the plate's place and block (its colour), the colour of its writing and whether it glows,
     * the name as typed, the cursor and the end of the selection, and the screen's frame count, which makes the cursor
     * blink.
     */
    public record Nameplate(long pos, String block, String textColour, boolean glowing, String name, int cursor, int selection, int frame) {

        public Nameplate {
            Objects.requireNonNull(block);
            Objects.requireNonNull(textColour);
            Objects.requireNonNull(name);
        }
    }

    public static byte[] nameplate(Nameplate state) {
        return write(out -> {
            out.out.writeLong(state.pos());
            out.text(state.block());
            out.text(state.textColour());
            out.out.writeBoolean(state.glowing());
            out.text(state.name());
            out.out.writeInt(state.cursor());
            out.out.writeInt(state.selection());
            out.out.writeInt(state.frame());
        });
    }

    public static Nameplate readNameplate(byte[] data) {
        return read(data, in -> new Nameplate(in.in.readLong(), in.text(), in.text(), in.in.readBoolean(), in.text(), in.in.readInt(), in.in.readInt(),
            in.in.readInt()));
    }

    // --- Simulated's contraption diagram ---

    /**
     * The contraption diagram as it stood: the diagram's entity, its settings as Simulated's codec writes them (which way
     * it looks, the forces shown, merged or not, the centre of mass, the sticky note), the forces and mass the server last
     * sent it as Simulated's codec writes them (empty before the first answer), whether the paper of forces was out, and
     * how far the paper and its tab had slid.
     */
    public record Diagram(int entity, byte[] config, byte[] forces, boolean paperOut, float paperOffset, float tabOffset) {

        public Diagram {
            config = config.clone();
            forces = forces.clone();
        }

        @Override
        public byte[] config() {
            return this.config.clone();
        }

        @Override
        public byte[] forces() {
            return this.forces.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Diagram diagram && diagram.entity == this.entity && Arrays.equals(diagram.config, this.config)
                && Arrays.equals(diagram.forces, this.forces) && diagram.paperOut == this.paperOut
                && Float.compare(diagram.paperOffset, this.paperOffset) == 0 && Float.compare(diagram.tabOffset, this.tabOffset) == 0;
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.entity, Arrays.hashCode(this.config), Arrays.hashCode(this.forces), this.paperOut);
        }
    }

    public static byte[] diagram(Diagram state) {
        return write(out -> {
            out.out.writeInt(state.entity());
            out.bytes(state.config());
            out.bytes(state.forces());
            out.out.writeBoolean(state.paperOut());
            out.out.writeFloat(state.paperOffset());
            out.out.writeFloat(state.tabOffset());
        });
    }

    public static Diagram readDiagram(byte[] data) {
        return read(data, in -> new Diagram(in.in.readInt(), in.bytes(), in.bytes(), in.in.readBoolean(), in.in.readFloat(), in.in.readFloat()));
    }

    // --- Simulated's linked typewriter window ---

    /** One key the typewriter is set to: the key's code and the items of its two frequencies, by id (empty for none) */
    public record Key(int code, String first, String second) {

        public Key {
            Objects.requireNonNull(first);
            Objects.requireNonNull(second);
        }
    }

    /**
     * What the linked typewriter's window holds that the server never sent: the keys as being set (saved only when the
     * window closes), whether the list of keys is open in place of the keyboard and how far it is scrolled, and whether a
     * key's entry is open over it, for which key ({@code -1} for a new one) and whether it waits for a key to be pressed.
     */
    public record Typewriter(List<Key> keys, boolean listOpen, float scroll, boolean entryOpen, int entryKey, boolean waitingForKey) {

        public Typewriter {
            keys = List.copyOf(keys);
        }
    }

    public static byte[] typewriter(Typewriter state) {
        return write(out -> {
            out.count(state.keys().size());
            for (Key key : state.keys()) {
                out.out.writeInt(key.code());
                out.text(key.first());
                out.text(key.second());
            }
            out.out.writeBoolean(state.listOpen());
            out.out.writeFloat(state.scroll());
            out.out.writeBoolean(state.entryOpen());
            out.out.writeInt(state.entryKey());
            out.out.writeBoolean(state.waitingForKey());
        });
    }

    public static Typewriter readTypewriter(byte[] data) {
        return read(data, in -> {
            int count = in.count();
            List<Key> keys = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                keys.add(new Key(in.in.readInt(), in.text(), in.text()));
            }
            return new Typewriter(keys, in.in.readBoolean(), in.in.readFloat(), in.in.readBoolean(), in.in.readInt(), in.in.readBoolean());
        });
    }

    // --- Lines of text over play: the typewriter's binding, and a row of hints ---

    /**
     * Lines drawn over play, each as drawn: its text, where it was drawn (across from the middle of the screen, up from its
     * foot), the width it was drawn at, its colour, and whether it had a backdrop behind it. Across is kept from the middle
     * and up from the foot so the lines land where they did on any size of screen.
     */
    public record Line(String text, int fromMiddle, int fromFoot, int width, int colour, boolean backdrop) {

        public Line {
            Objects.requireNonNull(text);
        }
    }

    public record Lines(List<Line> lines) {

        public Lines {
            lines = List.copyOf(lines);
        }
    }

    public static byte[] lines(Lines state) {
        return write(out -> {
            out.count(state.lines().size());
            for (Line line : state.lines()) {
                out.text(line.text());
                out.out.writeInt(line.fromMiddle());
                out.out.writeInt(line.fromFoot());
                out.out.writeInt(line.width());
                out.out.writeInt(line.colour());
                out.out.writeBoolean(line.backdrop());
            }
        });
    }

    public static Lines readLines(byte[] data) {
        return read(data, in -> {
            int count = in.count();
            List<Line> lines = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                lines.add(new Line(in.text(), in.in.readInt(), in.in.readInt(), in.in.readInt(), in.in.readInt(), in.in.readBoolean()));
            }
            return new Lines(lines);
        });
    }

    /** The typewriter's binding: the lines of its box, as a tooltip, drawn a third of the way across */
    public record Binding(List<String> lines) {

        public Binding {
            lines = List.copyOf(lines);
        }
    }

    public static byte[] binding(Binding state) {
        return write(out -> out.texts(state.lines()));
    }

    public static Binding readBinding(byte[] data) {
        return read(data, in -> new Binding(in.texts()));
    }

    // --- The readout of a lever or wheel being held ---

    /** The physics assembler's lever: {@code numbers} how far it was pulled at the tick before and at this one */
    public static final int ASSEMBLER = 0;
    /**
     * The steering wheel: {@code numbers} the recorder's offsets of the readout across and down, how far the wheel turns
     * each way, which way it counts and how far it is turned
     */
    public static final int WHEEL = 1;
    /**
     * The throttle lever: {@code numbers} the signal and how far the cursor stood at the tick before and at this one;
     * {@code flag} whether the lever is inverted
     */
    public static final int THROTTLE = 2;

    /** The readout Simulated draws while a lever or wheel is held and moved with the mouse */
    public record Lever(int kind, float[] numbers, boolean flag) {

        public Lever {
            numbers = numbers.clone();
        }

        @Override
        public float[] numbers() {
            return this.numbers.clone();
        }

        /** The number at {@code i}, or 0 */
        public float number(int i) {
            return i >= 0 && i < this.numbers.length ? this.numbers[i] : 0.0F;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Lever lever && lever.kind == this.kind && Arrays.equals(lever.numbers, this.numbers) && lever.flag == this.flag;
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.kind, Arrays.hashCode(this.numbers), this.flag);
        }
    }

    public static byte[] lever(Lever state) {
        return write(out -> {
            out.out.writeByte(state.kind());
            out.floats(state.numbers());
            out.out.writeBoolean(state.flag());
        });
    }

    public static Lever readLever(byte[] data) {
        return read(data, in -> new Lever(in.in.readByte(), in.floats(), in.in.readBoolean()));
    }

    // --- Places in the world: the physics staff's locks ---

    /** Points in the world, three numbers each, as drawn */
    public record Points(double[] coords) {

        public Points {
            coords = coords.clone();
        }

        @Override
        public double[] coords() {
            return this.coords.clone();
        }

        public int count() {
            return this.coords.length / 3;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Points points && Arrays.equals(points.coords, this.coords);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(this.coords);
        }
    }

    public static byte[] points(Points state) {
        return write(out -> out.doubles(state.coords()));
    }

    public static Points readPoints(byte[] data) {
        return read(data, in -> new Points(in.doubles()));
    }

    // --- The line above the hotbar ---

    /** A line the mods put above the hotbar on the player's own game: its text, the ticks it had left, whether its colour cycles */
    public record Status(String text, int ticksLeft, boolean animated) {

        public Status {
            Objects.requireNonNull(text);
        }

        /** How opaque the line is drawn, 0 to 255, as the game works it out from the ticks left */
        public int alpha(float partialTick) {
            return Math.min(255, (int) ((this.ticksLeft - partialTick) * 255.0F / 20.0F));
        }
    }

    public static byte[] status(Status state) {
        return write(out -> {
            out.text(state.text());
            out.out.writeInt(state.ticksLeft());
            out.out.writeBoolean(state.animated());
        });
    }

    public static Status readStatus(byte[] data) {
        return read(data, in -> new Status(in.text(), in.in.readInt(), in.in.readBoolean()));
    }

    // --- Simulated Coasters' track placement row ---

    /** The row of track Simulated Coasters shows above the hotbar while a track is placed: how many, whether there are enough, whether it fits */
    public record TrackRow(int required, boolean enough, boolean valid) {
    }

    public static byte[] trackRow(TrackRow state) {
        return write(out -> {
            out.out.writeInt(state.required());
            out.out.writeBoolean(state.enough());
            out.out.writeBoolean(state.valid());
        });
    }

    public static TrackRow readTrackRow(byte[] data) {
        return read(data, in -> new TrackRow(in.in.readInt(), in.in.readBoolean(), in.in.readBoolean()));
    }

    // --- Simulated Coasters' handles on a track being shaped ---

    /** An arm from a joint to a handle: {@code coords} its tail and tip; {@code flag} whether its tail is capped */
    public static final int ARM = 0;
    /** A curve's tangent handle: {@code coords} its tip */
    public static final int TANGENT = 1;
    /** The handle at an open end: {@code coords} its tip */
    public static final int OPEN_END = 2;
    /** A lift handle: {@code coords} its tip and which way it faces */
    public static final int LIFT = 3;
    /** A tilt handle: {@code coords} its tip, which way it faces and which way into the track */
    public static final int TILT = 4;

    /** One handle as Simulated Coasters drew it: its kind, its points, its texture by id, its colour and alpha */
    public record Handle(int kind, double[] coords, String texture, float r, float g, float b, float a, boolean flag) {

        public Handle {
            coords = coords.clone();
            Objects.requireNonNull(texture);
        }

        @Override
        public double[] coords() {
            return this.coords.clone();
        }

        /** The number at {@code i}, or 0 */
        public double coord(int i) {
            return i >= 0 && i < this.coords.length ? this.coords[i] : 0.0;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Handle handle && handle.kind == this.kind && Arrays.equals(handle.coords, this.coords) && handle.texture.equals(this.texture)
                && Float.compare(handle.r, this.r) == 0 && Float.compare(handle.g, this.g) == 0 && Float.compare(handle.b, this.b) == 0
                && Float.compare(handle.a, this.a) == 0 && handle.flag == this.flag;
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.kind, Arrays.hashCode(this.coords), this.texture, this.flag);
        }
    }

    public record Handles(List<Handle> handles) {

        public Handles {
            handles = List.copyOf(handles);
        }
    }

    public static byte[] handles(Handles state) {
        return write(out -> {
            out.count(state.handles().size());
            for (Handle handle : state.handles()) {
                out.out.writeByte(handle.kind());
                out.doubles(handle.coords());
                out.text(handle.texture());
                out.out.writeFloat(handle.r());
                out.out.writeFloat(handle.g());
                out.out.writeFloat(handle.b());
                out.out.writeFloat(handle.a());
                out.out.writeBoolean(handle.flag());
            }
        });
    }

    public static Handles readHandles(byte[] data) {
        return read(data, in -> {
            int count = in.count();
            List<Handle> handles = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                handles.add(new Handle(in.in.readByte(), in.doubles(), in.text(), in.in.readFloat(), in.in.readFloat(), in.in.readFloat(), in.in.readFloat(),
                    in.in.readBoolean()));
            }
            return new Handles(handles);
        });
    }

    // --- Bytes ---

    @FunctionalInterface
    private interface Writer {
        void write(Out out) throws IOException;
    }

    @FunctionalInterface
    private interface Reader<T> {
        T read(In in) throws IOException;
    }

    private static final class Out {
        final DataOutputStream out;

        Out(DataOutputStream out) {
            this.out = out;
        }

        void count(int count) throws IOException {
            this.out.writeInt(count);
        }

        void text(String text) throws IOException {
            this.bytes((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
        }

        void texts(List<String> texts) throws IOException {
            this.count(texts.size());
            for (String text : texts) {
                this.text(text);
            }
        }

        void bytes(byte[] bytes) throws IOException {
            this.count(bytes.length);
            this.out.write(bytes);
        }

        void floats(float[] floats) throws IOException {
            this.count(floats.length);
            for (float each : floats) {
                this.out.writeFloat(each);
            }
        }

        void doubles(double[] doubles) throws IOException {
            this.count(doubles.length);
            for (double each : doubles) {
                this.out.writeDouble(each);
            }
        }
    }

    private static final class In {
        final DataInputStream in;

        In(DataInputStream in) {
            this.in = in;
        }

        int count() throws IOException {
            int count = this.in.readInt();
            if (count < 0 || count > MOST) {
                throw new IOException("a length of " + count);
            }
            return count;
        }

        String text() throws IOException {
            return new String(this.bytes(), StandardCharsets.UTF_8);
        }

        List<String> texts() throws IOException {
            int count = this.count();
            List<String> texts = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                texts.add(this.text());
            }
            return texts;
        }

        byte[] bytes() throws IOException {
            byte[] bytes = new byte[this.count()];
            this.in.readFully(bytes);
            return bytes;
        }

        float[] floats() throws IOException {
            float[] floats = new float[this.count()];
            for (int i = 0; i < floats.length; i++) {
                floats[i] = this.in.readFloat();
            }
            return floats;
        }

        double[] doubles() throws IOException {
            double[] doubles = new double[this.count()];
            for (int i = 0; i < doubles.length; i++) {
                doubles[i] = this.in.readDouble();
            }
            return doubles;
        }
    }

    private static byte[] write(Writer writer) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            writer.write(new Out(out));
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
        return bytes.toByteArray();
    }

    /** @throws IllegalArgumentException when the bytes are not a whole record of this kind */
    private static <T> T read(byte[] data, Reader<T> reader) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            // Anything after what this version knows was written by a later one, and is left unread
            return reader.read(new In(in));
        } catch (IOException damaged) {
            throw new IllegalArgumentException("not a record this compat can read: " + damaged.getMessage(), damaged);
        }
    }

}
