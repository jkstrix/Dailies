package comedymonkey.dailiescompat.extras.state;

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
 * What the compat for the rest of the pack records, as bytes (see {@code client.ExtrasDailies}): for each claim, the whole
 * of what it showed, the least that draws it again. Nothing here refers to the mods, Dailies or the game, so it is unit
 * tested without them. Text is kept as the game's own JSON for a component (or, for the compasses, the very string they
 * drew), and items as the game's own saved form of a list of stacks, which here is only bytes. Version 1 of the compat
 * writes these; a later version must still read them, so a state only ever gains fields at its end.
 */
public final class ExtrasStates {

    /** The longest list or string a record is read with, so a damaged record fails instead of filling the memory */
    static final int MOST = 1 << 20;

    private ExtrasStates() {
    }

    // --- Explorer's Compass and Nature's Compass: the lines drawn while one is held ---

    /**
     * One line a compass drew over play: the string as drawn, its colour, how far down the screen, and how far in from
     * the side it keeps to (from the left edge to its start, or from the right edge to its end), so it lands where it did
     * on a screen of any width.
     */
    public record Line(String text, int fromSide, int y, int colour, boolean right) {

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
                out.out.writeInt(line.fromSide());
                out.out.writeInt(line.y());
                out.out.writeInt(line.colour());
                out.out.writeBoolean(line.right());
            }
        });
    }

    public static Lines readLines(byte[] data) {
        return read(data, in -> {
            int count = in.count();
            List<Line> lines = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                lines.add(new Line(in.text(), in.in.readInt(), in.in.readInt(), in.in.readInt(), in.in.readBoolean()));
            }
            return new Lines(lines);
        });
    }

    // --- Items, as the game saves them ---

    /**
     * A window's items as the game saves a list of stacks (each slot's stack, the item on the pointer, and for a backpack
     * the backpack itself): opaque bytes here, read and written by the client half with the game's own codec
     */
    public record Items(byte[] saved) {

        public Items {
            saved = saved.clone();
        }

        @Override
        public byte[] saved() {
            return this.saved.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Items items && Arrays.equals(items.saved, this.saved);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(this.saved);
        }
    }

    // --- Sophisticated Backpacks' backpack ---

    /**
     * A backpack's window, recorded whole: its title, its items as saved (the backpack, every slot of the window, the
     * upgrades and their settings' slots among them, and the item on the pointer), the upgrade tab open ({@code -1} for
     * none), how far its storage was scrolled, and the search as typed.
     */
    public record Backpack(String title, Items items, int openTab, float scroll, String search) {

        public Backpack {
            Objects.requireNonNull(title);
            Objects.requireNonNull(items);
            Objects.requireNonNull(search);
        }
    }

    public static byte[] backpack(Backpack state) {
        return write(out -> {
            out.text(state.title());
            out.bytes(state.items().saved());
            out.out.writeInt(state.openTab());
            out.out.writeFloat(state.scroll());
            out.text(state.search());
        });
    }

    public static Backpack readBackpack(byte[] data) {
        return read(data, in -> new Backpack(in.text(), new Items(in.bytes()), in.in.readInt(), in.in.readFloat(), in.text()));
    }

    // --- VanillaBackport's nautilus ---

    /** A nautilus' inventory: the nautilus by its id in the world, how many slots its own container has, and the window's items as saved */
    public record Nautilus(int entity, int size, Items items) {

        public Nautilus {
            Objects.requireNonNull(items);
        }
    }

    public static byte[] nautilus(Nautilus state) {
        return write(out -> {
            out.out.writeInt(state.entity());
            out.out.writeInt(state.size());
            out.bytes(state.items().saved());
        });
    }

    public static Nautilus readNautilus(byte[] data) {
        return read(data, in -> new Nautilus(in.in.readInt(), in.in.readInt(), new Items(in.bytes())));
    }

    // --- Extras for windows Dailies draws itself ---

    /** The Curios window: the page of slots shown, and whether its cosmetic slots are shown in place of the others */
    public record Curios(int page, boolean cosmetics) {
    }

    public static byte[] curios(Curios state) {
        return write(out -> {
            out.out.writeInt(state.page());
            out.out.writeBoolean(state.cosmetics());
        });
    }

    public static Curios readCurios(byte[] data) {
        return read(data, in -> new Curios(in.in.readInt(), in.in.readBoolean()));
    }

    /** A page number a window shows of its own, such as a corpse's further items */
    public record Page(int page) {
    }

    public static byte[] page(Page state) {
        return write(out -> out.out.writeInt(state.page()));
    }

    public static Page readPage(byte[] data) {
        return read(data, in -> new Page(in.in.readInt()));
    }

    // --- The line above the hotbar ---

    /**
     * The line put above the hotbar, as the game shows it: its text as the game's JSON, the ticks it has left (it fades
     * over the last twenty) and whether its colour cycles.
     */
    public record Status(String text, int ticksLeft, boolean animated) {

        public Status {
            Objects.requireNonNull(text);
        }

        /** How opaque the game draws it with this many ticks left, as Gui does: 255 until the last twenty ticks */
        public int alpha(float partialTick) {
            float left = this.ticksLeft - partialTick;
            int alpha = (int) (left * 255.0F / 20.0F);
            return Math.max(0, Math.min(255, alpha));
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

        void bytes(byte[] bytes) throws IOException {
            this.count(bytes.length);
            this.out.write(bytes);
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

        byte[] bytes() throws IOException {
            byte[] bytes = new byte[this.count()];
            this.in.readFully(bytes);
            return bytes;
        }
    }

    private static byte[] write(Writer writer) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            writer.write(new Out(out));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static <T> T read(byte[] data, Reader<T> reader) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            return reader.read(new In(in));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
