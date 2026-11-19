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

    // --- Outlines Create draws into the world ---

    /** A box, by its corners (six numbers: the low corner, then the high one) */
    public static final int BOX = 0;
    /** A cluster of whole blocks, outlined round its outside (the blocks packed as the game packs a block position) */
    public static final int CLUSTER = 1;
    /** A line, by its two ends (six numbers) */
    public static final int LINE = 2;
    /** A line drawn part of the way from one end (six numbers, then how far, then 1 when it is held at its start) */
    public static final int CHASED_LINE = 3;
    /** An item floating at a place (three numbers) */
    public static final int FLOATING_ITEM = 4;

    /**
     * One outline Create's outliner held at the end of a tick (a selection box, glued areas, a block being linked), as
     * Catnip draws it: its shape, its colour with its alpha, how wide its lines are and whether they thin as it fades,
     * whether it draws both sides and leaves its lines unlit, its light, its face textures and the face highlighted (by
     * Create's names; empty or -1 for none), and how faded it is (1 for not at all).
     */
    public record Outline(int kind, double[] coords, long[] blocks, String item, int colour, float lineWidth, boolean fadeLineWidth, boolean disableCull,
                          boolean disableNormals, int lightmap, String faceTexture, String highlightTexture, int highlightedFace, float alpha) {

        @Override
        public boolean equals(Object other) {
            return other instanceof Outline o && o.kind == this.kind && java.util.Arrays.equals(o.coords, this.coords)
                && java.util.Arrays.equals(o.blocks, this.blocks) && o.item.equals(this.item) && o.colour == this.colour && o.lineWidth == this.lineWidth
                && o.fadeLineWidth == this.fadeLineWidth && o.disableCull == this.disableCull && o.disableNormals == this.disableNormals
                && o.lightmap == this.lightmap && o.faceTexture.equals(this.faceTexture) && o.highlightTexture.equals(this.highlightTexture)
                && o.highlightedFace == this.highlightedFace && o.alpha == this.alpha;
        }

        @Override
        public int hashCode() {
            return this.kind * 31 + java.util.Arrays.hashCode(this.coords);
        }

        /** Number {@code i} of its shape, or 0 when there is none */
        public double coord(int i) {
            return i >= 0 && i < this.coords.length ? this.coords[i] : 0.0;
        }
    }

    public static byte[] outlines(List<Outline> outlines) {
        return write(out -> {
            out.count(outlines.size());
            for (Outline outline : outlines) {
                out.out.writeByte(outline.kind());
                out.count(outline.coords().length);
                for (double coord : outline.coords()) {
                    out.out.writeDouble(coord);
                }
                out.count(outline.blocks().length);
                for (long block : outline.blocks()) {
                    out.out.writeLong(block);
                }
                out.text(outline.item());
                out.out.writeInt(outline.colour());
                out.out.writeFloat(outline.lineWidth());
                out.out.writeBoolean(outline.fadeLineWidth());
                out.out.writeBoolean(outline.disableCull());
                out.out.writeBoolean(outline.disableNormals());
                out.out.writeInt(outline.lightmap());
                out.text(outline.faceTexture());
                out.text(outline.highlightTexture());
                out.out.writeByte(outline.highlightedFace());
                out.out.writeFloat(outline.alpha());
            }
        });
    }

    public static List<Outline> readOutlines(byte[] data) {
        return read(data, in -> {
            int count = in.count();
            List<Outline> outlines = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int kind = in.in.readByte();
                double[] coords = new double[in.count()];
                for (int j = 0; j < coords.length; j++) {
                    coords[j] = in.in.readDouble();
                }
                long[] blocks = new long[in.count()];
                for (int j = 0; j < blocks.length; j++) {
                    blocks[j] = in.in.readLong();
                }
                outlines.add(new Outline(kind, coords, blocks, in.text(), in.in.readInt(), in.in.readFloat(), in.in.readBoolean(), in.in.readBoolean(),
                    in.in.readBoolean(), in.in.readInt(), in.text(), in.text(), in.in.readByte(), in.in.readFloat()));
            }
            return outlines;
        });
    }

    // --- Create's status line ---

    /**
     * A line Create put above the hotbar on the player's own game (what a click will do, "too far", a key bound): the
     * text, the ticks it has left as the game counts them down, and whether its colour cycles
     */
    public record Status(String text, int ticksLeft, boolean animated) {

        /** How opaque the line is drawn, 0 to 255, as the game works it out from the ticks left */
        public int alpha(float partialTick) {
            return Math.min(255, (int) ((this.ticksLeft - partialTick) * 255.0F / 20.0F));
        }
    }

    public static byte[] status(Status status) {
        return write(out -> {
            out.text(status.text());
            out.out.writeInt(status.ticksLeft());
            out.out.writeBoolean(status.animated());
        });
    }

    public static Status readStatus(byte[] data) {
        return read(data, in -> new Status(in.text(), in.in.readInt(), in.in.readBoolean()));
    }

    // --- A train being driven ---

    /**
     * The bar Create shows while a train is driven: the speed and the throttle as shown (each 0 to 1), how wide the prompt
     * above it is opened and its text (empty for none) and whether that has a shadow, and the angle of the arrow
     */
    public record TrainHud(float speed, float throttle, float promptSize, String prompt, boolean promptShadow, float angle) {
    }

    public static byte[] trainHud(TrainHud hud) {
        return write(out -> {
            out.out.writeFloat(hud.speed());
            out.out.writeFloat(hud.throttle());
            out.out.writeFloat(hud.promptSize());
            out.text(hud.prompt());
            out.out.writeBoolean(hud.promptShadow());
            out.out.writeFloat(hud.angle());
        });
    }

    public static TrainHud readTrainHud(byte[] data) {
        return read(data, in -> new TrainHud(in.in.readFloat(), in.in.readFloat(), in.in.readFloat(), in.text(), in.in.readBoolean(), in.in.readFloat()));
    }

    // --- A linked controller being bound ---

    /** The lines Create shows while a linked controller waits for a key to bind (with the recorder's own key names) */
    public record BindMode(List<String> lines) {
    }

    public static byte[] bindMode(BindMode bind) {
        return write(out -> out.texts(bind.lines()));
    }

    public static BindMode readBindMode(byte[] data) {
        return read(data, in -> new BindMode(in.texts()));
    }

    // --- A schematic in hand ---

    /**
     * A schematic held, as Create shows it: the hotbar slot it is marked in (-1 for none), the tools offered (by Create's
     * names), the one picked, whether the tool bar is held open and how far it has risen; and in the world, the
     * schematic's size, whether it is placed, where (the transform from the world to the schematic, sixteen numbers, or
     * none), where the deploy tool's preview is (the same, or none), the face picked (-1 for none) and whether it is
     * highlighted as for moving
     */
    public record Schematic(int hotbarSlot, List<String> tools, int selection, boolean focused, float rise, double[] size, boolean deployed, float[] placed,
                            float[] preview, int face, boolean faceHighlighted) {

        @Override
        public boolean equals(Object other) {
            return other instanceof Schematic s && s.hotbarSlot == this.hotbarSlot && s.tools.equals(this.tools) && s.selection == this.selection
                && s.focused == this.focused && s.rise == this.rise && java.util.Arrays.equals(s.size, this.size) && s.deployed == this.deployed
                && java.util.Arrays.equals(s.placed, this.placed) && java.util.Arrays.equals(s.preview, this.preview) && s.face == this.face
                && s.faceHighlighted == this.faceHighlighted;
        }

        @Override
        public int hashCode() {
            return this.tools.hashCode() * 31 + this.selection;
        }

        /** The name of the tool picked, or empty */
        public String tool() {
            return this.selection >= 0 && this.selection < this.tools.size() ? this.tools.get(this.selection) : "";
        }
    }

    public static byte[] schematic(Schematic schematic) {
        return write(out -> {
            out.out.writeInt(schematic.hotbarSlot());
            out.texts(schematic.tools());
            out.out.writeInt(schematic.selection());
            out.out.writeBoolean(schematic.focused());
            out.out.writeFloat(schematic.rise());
            for (int i = 0; i < 3; i++) {
                out.out.writeDouble(i < schematic.size().length ? schematic.size()[i] : 0.0);
            }
            out.out.writeBoolean(schematic.deployed());
            out.floats(schematic.placed());
            out.floats(schematic.preview());
            out.out.writeByte(schematic.face());
            out.out.writeBoolean(schematic.faceHighlighted());
        });
    }

    public static Schematic readSchematic(byte[] data) {
        return read(data, in -> new Schematic(in.in.readInt(), in.texts(), in.in.readInt(), in.in.readBoolean(), in.in.readFloat(),
            new double[]{in.in.readDouble(), in.in.readDouble(), in.in.readDouble()}, in.in.readBoolean(), in.floats(), in.floats(), in.in.readByte(),
            in.in.readBoolean()));
    }

    // --- A train station's screens ---

    /**
     * A train station's screen, or its assembly screen: the station (what it shows besides is read from the playback
     * world, as the recording holds it), the station's name and the train's as typed, what the doors do (by Create's
     * name), the train shown (its id, empty for none) and whether it stands at the station
     */
    public record Station(long pos, boolean assembling, String name, String trainName, String doorControl, String train, boolean trainPresent) {
    }

    public static byte[] station(Station station) {
        return write(out -> {
            out.out.writeLong(station.pos());
            out.out.writeBoolean(station.assembling());
            out.text(station.name());
            out.text(station.trainName());
            out.text(station.doorControl());
            out.text(station.train());
            out.out.writeBoolean(station.trainPresent());
        });
    }

    public static Station readStation(byte[] data) {
        return read(data, in -> new Station(in.in.readLong(), in.in.readBoolean(), in.text(), in.text(), in.text(), in.text(), in.in.readBoolean()));
    }

    // --- A display link's screen ---

    /**
     * A display link's screen: the link (its source and target are read from the playback world), the information picked
     * (by Create's id, empty for none), the line it writes to, and the source's settings as set (Create's own saved form,
     * as bytes of the game's tag format, which only the game's side reads)
     */
    public record DisplayLink(long pos, String source, int targetLine, byte[] config) {

        @Override
        public boolean equals(Object other) {
            return other instanceof DisplayLink link && link.pos == this.pos && link.source.equals(this.source) && link.targetLine == this.targetLine
                && java.util.Arrays.equals(link.config, this.config);
        }

        @Override
        public int hashCode() {
            return Long.hashCode(this.pos) * 31 + this.source.hashCode();
        }
    }

    public static byte[] displayLink(DisplayLink link) {
        return write(out -> {
            out.out.writeLong(link.pos());
            out.text(link.source());
            out.out.writeInt(link.targetLine());
            out.bytes(link.config());
        });
    }

    public static DisplayLink readDisplayLink(byte[] data) {
        return read(data, in -> new DisplayLink(in.in.readLong(), in.text(), in.in.readInt(), in.bytes()));
    }

    // --- A factory gauge's screen ---

    /**
     * A factory gauge's screen: the gauge (its block and its place on it, by Create's name; what it is connected to is read
     * from the playback world), and what is being set: the address, when promises expire, how many it makes, how many of
     * each input it asks for, and whether it crafts
     */
    public record FactoryPanel(long pos, String slot, String address, int promiseExpiry, int outputCount, int[] inputCounts, boolean crafting) {

        @Override
        public boolean equals(Object other) {
            return other instanceof FactoryPanel panel && panel.pos == this.pos && panel.slot.equals(this.slot) && panel.address.equals(this.address)
                && panel.promiseExpiry == this.promiseExpiry && panel.outputCount == this.outputCount
                && java.util.Arrays.equals(panel.inputCounts, this.inputCounts) && panel.crafting == this.crafting;
        }

        @Override
        public int hashCode() {
            return Long.hashCode(this.pos) * 31 + this.slot.hashCode();
        }
    }

    public static byte[] factoryPanel(FactoryPanel panel) {
        return write(out -> {
            out.out.writeLong(panel.pos());
            out.text(panel.slot());
            out.text(panel.address());
            out.out.writeInt(panel.promiseExpiry());
            out.out.writeInt(panel.outputCount());
            out.count(panel.inputCounts().length);
            for (int count : panel.inputCounts()) {
                out.out.writeInt(count);
            }
            out.out.writeBoolean(panel.crafting());
        });
    }

    public static FactoryPanel readFactoryPanel(byte[] data) {
        return read(data, in -> {
            long pos = in.in.readLong();
            String slot = in.text();
            String address = in.text();
            int expiry = in.in.readInt();
            int output = in.in.readInt();
            int[] inputs = new int[in.count()];
            for (int i = 0; i < inputs.length; i++) {
                inputs[i] = in.in.readInt();
            }
            return new FactoryPanel(pos, slot, address, expiry, output, inputs, in.in.readBoolean());
        });
    }

    // --- Naming a schematic ---

    /** The screen that names a schematic saved with the schematic and quill: the name as typed and the cursor in it */
    public record SchematicName(String name, int cursor) {
    }

    public static byte[] schematicName(SchematicName name) {
        return write(out -> {
            out.text(name.name());
            out.out.writeInt(name.cursor());
        });
    }

    public static SchematicName readSchematicName(byte[] data) {
        return read(data, in -> new SchematicName(in.text(), in.in.readInt()));
    }

    // --- The wand of symmetry ---

    /** The wand of symmetry's screen: the mirror picked (0 a plane, 1 two crossed planes, 2 three) and how it is turned */
    public record Symmetry(int mirror, int orientation) {
    }

    public static byte[] symmetry(Symmetry symmetry) {
        return write(out -> {
            out.out.writeByte(symmetry.mirror());
            out.out.writeByte(symmetry.orientation());
        });
    }

    public static Symmetry readSymmetry(byte[] data) {
        return read(data, in -> new Symmetry(in.in.readByte(), in.in.readByte()));
    }

    // --- The toolbox's selection wheel ---

    /** One toolbox as the wheel shows it: its colour (the dye's name), its name as shown, and for the one picked from, its slots */
    public record Toolbox(String colour, String name) {
    }

    /**
     * The toolbox's selection wheel, while its key is held: what it is picking (Create's name for the wheel's state:
     * a toolbox, a slot, a slot with the held item to put back, or only putting it back when out of range), the toolboxes
     * offered, the one picked from with the item each of its eight slots holds as drawn (by id, empty for none) and
     * which of them still have any in stock (a bit for each slot), the segment the pointer is on as the wheel last drew
     * it (a slot 0 to 7, -5 for the middle, -7 for putting all back, -1 for none), whether it is picking by scrolling and
     * the slot scrolled to, how long it has been open (up to 20 ticks, as far as it fades in), and the item held, which
     * the middle's tip names.
     */
    public record ToolboxWheel(String state, List<Toolbox> toolboxes, boolean picked, Toolbox from, List<String> items, int stocked, int hovered,
                               boolean scrolling, int scrolled, int ticksOpen, String held) {

        /** Whether slot {@code i} of the toolbox picked from still has any in stock */
        public boolean stocked(int i) {
            return (this.stocked & (1 << i)) != 0;
        }

        /** The item slot {@code i} holds, by id, or empty */
        public String item(int i) {
            return i >= 0 && i < this.items.size() ? this.items.get(i) : "";
        }

        /** Create's codes for the wheel's middle (putting the held item back) and for putting everything back */
        public static final int UNEQUIP = -5;
        public static final int DEPOSIT = -7;

        /**
         * Where to put the pointer, from the middle of the screen, for the wheel to pick the recorded segment as Create
         * works it out: a slot by its angle, the middle, putting all back to the right, or nothing
         */
        public int[] pointer() {
            if (this.scrolling) {
                // Picking by scrolling lasts only while the pointer stays near the middle
                return new int[]{0, 0};
            }
            if (this.hovered >= 0 && this.hovered < 8) {
                // Create's slot under an angle a (degrees) is floor(a + 517.5) % 360 / 45: the middle of slot s is at 45s + 225
                double angle = Math.toRadians(45 * this.hovered + 225);
                return new int[]{(int) Math.round(40 * Math.cos(angle)), (int) Math.round(40 * Math.sin(angle))};
            }
            if (this.hovered == DEPOSIT) {
                return new int[]{80, 0};
            }
            if (this.hovered == UNEQUIP) {
                return this.state.equals("DETACH") ? new int[]{0, -50} : new int[]{0, 0};
            }
            // Nothing: the very middle, unless the middle is a segment of its own, then beyond the wheel's reach
            return this.state.equals("SELECT_ITEM_UNEQUIP") ? new int[]{0, 120} : new int[]{0, 0};
        }

        /**
         * The segment Create's wheel picks for a pointer this far from the middle, as its drawing works it out (for the
         * test that the pointer chosen lands where it should)
         */
        public static int picked(String state, int x, int y) {
            int hovered = -1;
            float distance = x * x + y * y;
            if (distance > 25 && distance < 10000) {
                hovered = (int) (Math.floor(Math.toDegrees(Math.atan2(y, x)) + 360 + 180 - 22.5f) % 360) / 45;
            }
            if (state.equals("SELECT_ITEM_UNEQUIP") && distance <= 150) {
                hovered = UNEQUIP;
            }
            if (state.equals("DETACH")) {
                if (x > -20 && x < 20 && y > -80 && y < -20) {
                    hovered = UNEQUIP;
                }
            } else if (x > 60 && x < 100 && y > -20 && y < 20) {
                hovered = DEPOSIT;
            }
            return hovered;
        }
    }

    public static byte[] toolboxWheel(ToolboxWheel wheel) {
        return write(out -> {
            out.text(wheel.state());
            out.count(wheel.toolboxes().size());
            for (Toolbox box : wheel.toolboxes()) {
                out.text(box.colour());
                out.text(box.name());
            }
            out.out.writeBoolean(wheel.picked());
            out.text(wheel.from().colour());
            out.text(wheel.from().name());
            out.texts(wheel.items());
            out.out.writeInt(wheel.stocked());
            out.out.writeInt(wheel.hovered());
            out.out.writeBoolean(wheel.scrolling());
            out.out.writeInt(wheel.scrolled());
            out.out.writeByte(wheel.ticksOpen());
            out.text(wheel.held());
        });
    }

    public static ToolboxWheel readToolboxWheel(byte[] data) {
        return read(data, in -> {
            String state = in.text();
            int count = in.count();
            List<Toolbox> boxes = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                boxes.add(new Toolbox(in.text(), in.text()));
            }
            return new ToolboxWheel(state, boxes, in.in.readBoolean(), new Toolbox(in.text(), in.text()), in.texts(), in.in.readInt(), in.in.readInt(),
                in.in.readBoolean(), in.in.readInt(), in.in.readByte(), in.text());
        });
    }

    // --- The wrench's selection wheel ---

    /**
     * The wrench's selection wheel, while its key is held on a block: the block (where, and its state as the game writes
     * a block state), which of its properties is being changed (by its place among those the wheel offers), the states
     * offered as drawn round the wheel, the one highlighted, where the pointer was from the wheel's middle when it was
     * far enough out to pick (or none), how long the wheel has been open (up to 20 ticks), and how the player was turned,
     * which the blocks on the wheel are turned by
     */
    public record WrenchWheel(long pos, String block, int property, List<String> states, int selected, boolean pointed, int pointerX, int pointerY,
                              int ticksOpen, float pitch, float yaw) {
    }

    public static byte[] wrenchWheel(WrenchWheel wheel) {
        return write(out -> {
            out.out.writeLong(wheel.pos());
            out.text(wheel.block());
            out.out.writeInt(wheel.property());
            out.texts(wheel.states());
            out.out.writeInt(wheel.selected());
            out.out.writeBoolean(wheel.pointed());
            out.out.writeInt(wheel.pointerX());
            out.out.writeInt(wheel.pointerY());
            out.out.writeByte(wheel.ticksOpen());
            out.out.writeFloat(wheel.pitch());
            out.out.writeFloat(wheel.yaw());
        });
    }

    public static WrenchWheel readWrenchWheel(byte[] data) {
        return read(data, in -> new WrenchWheel(in.in.readLong(), in.text(), in.in.readInt(), in.texts(), in.in.readInt(), in.in.readBoolean(), in.in.readInt(),
            in.in.readInt(), in.in.readByte(), in.in.readFloat(), in.in.readFloat()));
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

        void floats(float[] floats) throws IOException {
            this.count(floats.length);
            for (float each : floats) {
                this.out.writeFloat(each);
            }
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

        float[] floats() throws IOException {
            float[] floats = new float[this.count()];
            for (int i = 0; i < floats.length; i++) {
                floats[i] = this.in.readFloat();
            }
            return floats;
        }

        byte[] bytes() throws IOException {
            int length = this.in.readInt();
            if (length < 0 || length > MOST * 16) {
                throw new IOException("a block of " + length + " bytes");
            }
            byte[] bytes = new byte[length];
            this.in.readFully(bytes);
            return bytes;
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
