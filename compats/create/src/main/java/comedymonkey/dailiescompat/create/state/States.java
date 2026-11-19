package comedymonkey.dailiescompat.create.state;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * What the Create compat records, as bytes (see {@code client.CreateDailies}): one record per claim, each the least that
 * draws it again and the whole of it. Nothing here refers to Create, Dailies or the game, so it is unit tested without
 * them. Text that Create styles (a label, a goggles line) is kept as the game's own JSON for a component, made and read
 * on the game's side; here it is only a string. Version 1 of the compat writes these; a later version must still read
 * them, so a record only ever gains fields at its end.
 */
public final class States {

    /** The longest list or string a record is read with, so a damaged record fails instead of filling the memory */
    static final int MOST = 1 << 16;

    private States() {
    }

    // --- The value settings board ---

    /**
     * The value settings board, while use is held on a block's value box: its title and rows as shown, its range, and the
     * value being picked (its row and column). A board of numbers keeps the label of that value only (such as "64 RPM,
     * clockwise"); a board of modes keeps every mode's name and icon, which the board lays itself out by.
     */
    public record Board(String title, List<String> rows, int maxValue, int milestoneInterval, int row, int value, String label, List<String> optionKeys,
                        List<String> optionIcons) {

        /** Whether this is a board of modes, drawn with an icon for each */
        public boolean iconMode() {
            return !this.optionKeys.isEmpty();
        }

        /** The name of mode {@code i}, a translation key; empty when there is none */
        public String optionKey(int i) {
            return i >= 0 && i < this.optionKeys.size() ? this.optionKeys.get(i) : "";
        }

        /** The icon of mode {@code i}, by the name of Create's icon; empty when there is none */
        public String optionIcon(int i) {
            return i >= 0 && i < this.optionIcons.size() ? this.optionIcons.get(i) : "";
        }
    }

    public static byte[] board(Board board) {
        return write(out -> {
            out.text(board.title());
            out.texts(board.rows());
            out.out.writeInt(board.maxValue());
            out.out.writeInt(board.milestoneInterval());
            out.out.writeInt(board.row());
            out.out.writeInt(board.value());
            out.text(board.label());
            out.texts(board.optionKeys());
            out.texts(board.optionIcons());
        });
    }

    public static Board readBoard(byte[] data) {
        return read(data, in -> new Board(in.text(), in.texts(), in.in.readInt(), in.in.readInt(), in.in.readInt(), in.in.readInt(), in.text(), in.texts(),
            in.texts()));
    }

    // --- The value boxes on a block being looked at ---

    /** A value box with only its outline */
    public static final int PLAIN = 0;
    /** A value box showing a number or a word, such as a speed */
    public static final int TEXT = 1;
    /** A value box showing a mode's icon */
    public static final int ICON = 2;
    /** A filter's value box, with the item filtered and how many */
    public static final int ITEM = 3;

    /**
     * One value box Create showed on a block the recorder looked at: the block, where on it the box sat (the transform
     * from the block's corner to the box, as Create's slot placed it, row by row), how its text is scaled and coloured,
     * whether it was only passive (no outline: the box is not the one aimed at), the outline's icon, and what it showed.
     *
     * @param pos   the block, packed as the game packs a block position
     * @param pose  the sixteen numbers of the transform, column by column as the game keeps a matrix
     * @param count for a filter, its amount as shown, or null when no amount is shown
     */
    public record Box(long pos, float[] pose, float fontScale, int overrideColor, boolean passive, String outline, int kind, String text, String icon,
                      String item, String count) {

        @Override
        public boolean equals(Object other) {
            return other instanceof Box box && box.pos == this.pos && java.util.Arrays.equals(box.pose, this.pose) && box.fontScale == this.fontScale
                && box.overrideColor == this.overrideColor && box.passive == this.passive && box.outline.equals(this.outline) && box.kind == this.kind
                && box.text.equals(this.text) && box.icon.equals(this.icon) && box.item.equals(this.item) && java.util.Objects.equals(box.count, this.count);
        }

        @Override
        public int hashCode() {
            return Long.hashCode(this.pos) * 31 + java.util.Arrays.hashCode(this.pose);
        }
    }

    public static byte[] boxes(List<Box> boxes) {
        return write(out -> {
            out.count(boxes.size());
            for (Box box : boxes) {
                out.out.writeLong(box.pos());
                for (int i = 0; i < 16; i++) {
                    out.out.writeFloat(i < box.pose().length ? box.pose()[i] : 0.0F);
                }
                out.out.writeFloat(box.fontScale());
                out.out.writeInt(box.overrideColor());
                out.out.writeBoolean(box.passive());
                out.text(box.outline());
                out.out.writeByte(box.kind());
                out.text(box.text());
                out.text(box.icon());
                out.text(box.item());
                out.out.writeBoolean(box.count() != null);
                if (box.count() != null) {
                    out.text(box.count());
                }
            }
        });
    }

    public static List<Box> readBoxes(byte[] data) {
        return read(data, in -> {
            int count = in.count();
            List<Box> boxes = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                long pos = in.in.readLong();
                float[] pose = new float[16];
                for (int j = 0; j < 16; j++) {
                    pose[j] = in.in.readFloat();
                }
                float fontScale = in.in.readFloat();
                int overrideColor = in.in.readInt();
                boolean passive = in.in.readBoolean();
                String outline = in.text();
                int kind = in.in.readByte();
                String text = in.text();
                String icon = in.text();
                String item = in.text();
                String amount = in.in.readBoolean() ? in.text() : null;
                boxes.add(new Box(pos, pose, fontScale, overrideColor, passive, outline, kind, text, icon, item, amount));
            }
            return boxes;
        });
    }

    // --- The value box's tip ---

    /**
     * The tip Create shows under the crosshair while a value box is aimed at (its name, and "hold use to edit"), with
     * Create's own count of how far it has faded in or out (11 down to 0), which its opacity is worked out from.
     */
    public record Tip(List<String> lines, int ticks) {

        /** How opaque the tip is drawn, as Create works it out from its count */
        public float alpha() {
            return this.ticks > 5 ? (11 - this.ticks) / 5.0F : Math.min(1.0F, this.ticks / 5.0F);
        }
    }

    public static byte[] tip(Tip tip) {
        return write(out -> {
            out.texts(tip.lines());
            out.out.writeByte(tip.ticks());
        });
    }

    public static Tip readTip(byte[] data) {
        return read(data, in -> new Tip(in.texts(), in.in.readByte()));
    }

    // --- The engineer's goggles ---

    /** The goggles' readout as the recorder's game showed it: its lines, and the item drawn beside it, by id */
    public record Goggles(List<String> lines, String icon) {
    }

    public static byte[] goggles(Goggles goggles) {
        return write(out -> {
            out.texts(goggles.lines());
            out.text(goggles.icon());
        });
    }

    public static Goggles readGoggles(byte[] data) {
        return read(data, in -> new Goggles(in.texts(), in.text()));
    }

    // --- The sequenced gearshift ---

    /** One instruction of a sequenced gearshift, as Create names it: its kind, its speed (forward, back) and its value */
    public record Instruction(String type, String modifier, int value) {
    }

    /** A sequenced gearshift's screen: its instructions as they stood while being set */
    public record Gearshift(List<Instruction> instructions) {
    }

    public static byte[] gearshift(Gearshift gearshift) {
        return write(out -> {
            out.count(gearshift.instructions().size());
            for (Instruction instruction : gearshift.instructions()) {
                out.text(instruction.type());
                out.text(instruction.modifier());
                out.out.writeInt(instruction.value());
            }
        });
    }

    public static Gearshift readGearshift(byte[] data) {
        return read(data, in -> {
            int count = in.count();
            List<Instruction> instructions = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                instructions.add(new Instruction(in.text(), in.text(), in.in.readInt()));
            }
            return new Gearshift(instructions);
        });
    }

    // --- The threshold switch ---

    /**
     * A threshold switch's screen: the switch (what it watches is read from the playback world, as the recording holds
     * it) and the two thresholds and the unit as they stood while being set
     */
    public record Threshold(long pos, int offBelow, int onAbove, boolean inStacks) {
    }

    public static byte[] threshold(Threshold threshold) {
        return write(out -> {
            out.out.writeLong(threshold.pos());
            out.out.writeInt(threshold.offBelow());
            out.out.writeInt(threshold.onAbove());
            out.out.writeBoolean(threshold.inStacks());
        });
    }

    public static Threshold readThreshold(byte[] data) {
        return read(data, in -> new Threshold(in.in.readLong(), in.in.readInt(), in.in.readInt(), in.in.readBoolean()));
    }

    // --- The elevator contact ---

    /** An elevator contact's screen: the floor's short and long names as typed, and what the doors do there, by name */
    public record Elevator(String shortName, String longName, String doorControl) {
    }

    public static byte[] elevator(Elevator elevator) {
        return write(out -> {
            out.text(elevator.shortName());
            out.text(elevator.longName());
            out.text(elevator.doorControl());
        });
    }

    public static Elevator readElevator(byte[] data) {
        return read(data, in -> new Elevator(in.text(), in.text(), in.text()));
    }

    // --- The clipboard ---

    /** One line of a clipboard: ticked or not, its text, and the item shown beside it with its amount (empty for none) */
    public record Entry(boolean checked, String text, String item, int amount) {
    }

    /**
     * A clipboard being read or written: only the page open (its lines), which page it is of how many, the line being
     * written (-1 for none) and where the cursor and the selection's other end are in it, and whether it is read only
     */
    public record Clipboard(List<Entry> entries, int page, int pages, int editing, int cursor, int selection, boolean readOnly) {
    }

    public static byte[] clipboard(Clipboard clipboard) {
        return write(out -> {
            out.count(clipboard.entries().size());
            for (Entry entry : clipboard.entries()) {
                out.out.writeBoolean(entry.checked());
                out.text(entry.text());
                out.text(entry.item());
                out.out.writeInt(entry.amount());
            }
            out.out.writeInt(clipboard.page());
            out.out.writeInt(clipboard.pages());
            out.out.writeInt(clipboard.editing());
            out.out.writeInt(clipboard.cursor());
            out.out.writeInt(clipboard.selection());
            out.out.writeBoolean(clipboard.readOnly());
        });
    }

    public static Clipboard readClipboard(byte[] data) {
        return read(data, in -> {
            int count = in.count();
            List<Entry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                entries.add(new Entry(in.in.readBoolean(), in.text(), in.text(), in.in.readInt()));
            }
            return new Clipboard(entries, in.in.readInt(), in.in.readInt(), in.in.readInt(), in.in.readInt(), in.in.readInt(), in.in.readBoolean());
        });
    }

    // --- The backtank's remaining air ---

    /** The backtank's air under water: the seconds left as Create counts them, and the backtank drawn, by id */
    public record Air(int timeLeft, String backtank, boolean fireResistant) {

        /** Whether the count is drawn reddened: below a minute, every other second, as Create flashes it */
        public boolean flashing() {
            return this.timeLeft < 60 && this.timeLeft % 2 == 0;
        }
    }

    public static byte[] air(Air air) {
        return write(out -> {
            out.out.writeInt(air.timeLeft());
            out.text(air.backtank());
            out.out.writeBoolean(air.fireResistant());
        });
    }

    public static Air readAir(byte[] data) {
        return read(data, in -> new Air(in.in.readInt(), in.text(), in.in.readBoolean()));
    }

    // --- Extras for Create's filter windows, which Dailies draws itself ---

    /**
     * What a filter's window holds that its slots do not: for the list filter, whether it allows or denies and whether
     * it matches data ({@code modes} 0 or 1 each); for the attribute filter, its mode and the attributes picked (as the
     * window lists them); for the package filter, the address typed.
     */
    public record Filter(int[] modes, List<String> lines, String address) {

        @Override
        public boolean equals(Object other) {
            return other instanceof Filter filter && java.util.Arrays.equals(filter.modes, this.modes) && filter.lines.equals(this.lines)
                && filter.address.equals(this.address);
        }

        @Override
        public int hashCode() {
            return java.util.Arrays.hashCode(this.modes) * 31 + this.lines.hashCode();
        }
    }

    public static byte[] filter(Filter filter) {
        return write(out -> {
            out.count(filter.modes().length);
            for (int mode : filter.modes()) {
                out.out.writeInt(mode);
            }
            out.texts(filter.lines());
            out.text(filter.address());
        });
    }

    public static Filter readFilter(byte[] data) {
        return read(data, in -> {
            int[] modes = new int[in.count()];
            for (int i = 0; i < modes.length; i++) {
                modes[i] = in.in.readInt();
            }
            return new Filter(modes, in.texts(), in.text());
        });
    }

    // --- Extras for a train schedule's window ---

    /**
     * A train schedule as its window holds it while it is edited (Create's own saved form of it, as bytes of the game's
     * tag format, which only the game's side reads), and how far its list is scrolled
     */
    public record Schedule(byte[] saved, float scroll) {

        @Override
        public boolean equals(Object other) {
            return other instanceof Schedule schedule && java.util.Arrays.equals(schedule.saved, this.saved) && schedule.scroll == this.scroll;
        }

        @Override
        public int hashCode() {
            return java.util.Arrays.hashCode(this.saved) * 31 + Float.hashCode(this.scroll);
        }
    }

    public static byte[] schedule(Schedule schedule) {
        return write(out -> {
            out.count(schedule.saved().length);
            out.out.write(schedule.saved());
            out.out.writeFloat(schedule.scroll());
        });
    }

    public static Schedule readSchedule(byte[] data) {
        return read(data, in -> {
            int length = in.in.readInt();
            if (length < 0 || length > MOST * 16) {
                throw new IOException("a schedule of " + length + " bytes");
            }
            byte[] saved = new byte[length];
            in.in.readFully(saved);
            return new Schedule(saved, in.in.readFloat());
        });
    }

    // --- Writing and reading ---

    private interface Writer {
        void write(Out out) throws IOException;
    }

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
            byte[] bytes = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
            this.out.writeInt(bytes.length);
            this.out.write(bytes);
        }

        void texts(List<String> texts) throws IOException {
            this.count(texts.size());
            for (String text : texts) {
                this.text(text);
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
                throw new IOException("a list of " + count);
            }
            return count;
        }

        String text() throws IOException {
            int length = this.in.readInt();
            if (length < 0 || length > MOST * 16) {
                throw new IOException("a string of " + length + " bytes");
            }
            byte[] bytes = new byte[length];
            this.in.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }

        List<String> texts() throws IOException {
            int count = this.count();
            List<String> texts = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                texts.add(this.text());
            }
            return texts;
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
