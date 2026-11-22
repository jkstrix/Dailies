package comedymonkey.dailiescompat.createextras.state;

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
 * What the compat for Create's other add-ons records, as bytes (see {@code client.CreateExtrasDailies}): for each claim,
 * the whole of what it showed, the least that draws it again. Nothing here refers to the mods, Dailies or the game, so it
 * is unit tested without them. Text is kept as the game's own JSON for a component, block states as the game writes them
 * in a command, items, tags and textures by id, and what a mod keeps in the game's own tags (a pulse generator's
 * instructions) as the game writes a tag in a command; here each is only a string. Version 1 of the compat writes these;
 * a later version must still read them, so a state only ever gains fields at its end.
 */
public final class ExtrasStates {

    /** The longest list or string a record is read with, so a damaged record fails instead of filling the memory */
    static final int MOST = 1 << 20;
    /** No place: a block position that is not there */
    public static final long NOWHERE = Long.MIN_VALUE;

    private ExtrasStates() {
    }

    // --- An overlay recorded as drawn ---

    /** A rectangle filled with one colour: numbers are the corners, the depth and the colour */
    public static final int FILL = 0;
    /**
     * Part of a texture: text is the texture, numbers the place, size, the part's corner (as float bits), the part's
     * size, the texture's size and the depth
     */
    public static final int BLIT = 1;
    /** A sprite of the game's GUI atlas: text is the sprite, numbers the place, size and depth */
    public static final int SPRITE = 2;
    /** A line of text: text is the line as the game's JSON, numbers the place, colour and whether it has a shadow */
    public static final int TEXT = 3;

    /**
     * One thing a mod drew over play, as its own drawing handed it to the game: what kind, the drawing's turn and place as a
     * flat transform (the first two columns' x and y, and the place: m00, m01, m10, m11, m30, m31, m32), the numbers the
     * game was given, and a text (a texture, a sprite or a line of text as JSON).
     */
    public record Op(int kind, float[] pose, int[] numbers, String text) {

        public Op {
            pose = pose.clone();
            numbers = numbers.clone();
            Objects.requireNonNull(text);
            if (pose.length != 7) {
                throw new IllegalArgumentException("a pose of " + pose.length + " numbers");
            }
        }

        @Override
        public float[] pose() {
            return this.pose.clone();
        }

        @Override
        public int[] numbers() {
            return this.numbers.clone();
        }

        /** A number given to the game as a float, kept as its bits */
        public float decimal(int index) {
            return Float.intBitsToFloat(this.numbers[index]);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Op op && op.kind == this.kind && Arrays.equals(op.pose, this.pose) && Arrays.equals(op.numbers, this.numbers)
                && op.text.equals(this.text);
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.kind, Arrays.hashCode(this.pose), Arrays.hashCode(this.numbers), this.text);
        }

        @Override
        public String toString() {
            return "Op[" + this.kind + ", " + Arrays.toString(this.pose) + ", " + Arrays.toString(this.numbers) + ", " + this.text + "]";
        }
    }

    /**
     * An overlay as a mod drew it on the recorder's screen: the screen's size in GUI units then, and each thing drawn in
     * order. Playback draws them again in the same order, kept as far from the same edges of the screen.
     */
    public record Drawn(int guiWidth, int guiHeight, List<Op> ops) {

        public Drawn {
            ops = List.copyOf(ops);
        }
    }

    public static byte[] drawn(Drawn state) {
        return write(out -> {
            out.out.writeInt(state.guiWidth());
            out.out.writeInt(state.guiHeight());
            out.count(state.ops().size());
            for (Op op : state.ops()) {
                out.out.writeByte(op.kind());
                for (float each : op.pose) {
                    out.out.writeFloat(each);
                }
                out.ints(op.numbers);
                out.text(op.text());
            }
        });
    }

    public static Drawn readDrawn(byte[] data) {
        return read(data, in -> {
            int width = in.in.readInt();
            int height = in.in.readInt();
            int count = in.count();
            List<Op> ops = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int kind = in.in.readByte();
                float[] pose = new float[7];
                for (int j = 0; j < pose.length; j++) {
                    pose[j] = in.in.readFloat();
                }
                ops.add(new Op(kind, pose, in.ints(), in.text()));
            }
            return new Drawn(width, height, ops);
        });
    }

    /** Whether a rectangle filled the whole of the recorder's screen, drawn with no turn, scale or move */
    public static boolean fillsScreen(Op op, Drawn drawn) {
        if (op.kind() != FILL || op.numbers.length < 4) {
            return false;
        }
        float[] p = op.pose;
        int[] n = op.numbers;
        boolean plain = p[0] == 1.0F && p[1] == 0.0F && p[2] == 0.0F && p[3] == 1.0F && p[4] == 0.0F && p[5] == 0.0F;
        return plain && n[0] <= 0 && n[1] <= 0 && n[2] >= drawn.guiWidth() && n[3] >= drawn.guiHeight();
    }

    /**
     * How far an overlay drawn on the recorder's screen moves on a screen of this size, as {x, y}: by the whole change in
     * size when it sat in the right or lower third of the recorder's screen, by half when in the middle one, and not at all
     * in the left or upper one, so it keeps as far from the edges it sat by. Rectangles that filled the whole screen are
     * left out of where it sat: they are stretched to the new screen instead.
     */
    public static float[] shift(Drawn drawn, int width, int height) {
        if (drawn.guiWidth() == width && drawn.guiHeight() == height || drawn.ops().isEmpty()) {
            return new float[]{0.0F, 0.0F};
        }
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (Op op : drawn.ops()) {
            float[] p = op.pose;
            int[] n = op.numbers;
            if (fillsScreen(op, drawn) || n.length < 2) {
                continue;
            }
            float x = p[0] * n[0] + p[2] * n[1] + p[4];
            float y = p[1] * n[0] + p[3] * n[1] + p[5];
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }
        if (minX > maxX) {
            return new float[]{0.0F, 0.0F};
        }
        return new float[]{share((minX + maxX) / 2.0F, drawn.guiWidth()) * (width - drawn.guiWidth()),
            share((minY + maxY) / 2.0F, drawn.guiHeight()) * (height - drawn.guiHeight())};
    }

    private static float share(float middle, int size) {
        if (middle < size / 3.0F) {
            return 0.0F;
        }
        return middle > size * 2.0F / 3.0F ? 1.0F : 0.5F;
    }

    // --- The line above the hotbar ---

    /**
     * The line a mod put above the hotbar on the player's own game: the text as JSON, the ticks it had left (it fades over
     * its last ten), and whether its colour cycles, as a record's does.
     */
    public record Status(String text, int ticksLeft, boolean animated) {

        public Status {
            Objects.requireNonNull(text);
        }

        /** How opaque the game draws it at this moment, 0 to 255, as Gui.renderOverlayMessage works it out */
        public int alpha(float partialTick) {
            float left = this.ticksLeft - partialTick;
            int alpha = (int) (left * 255.0F / 20.0F);
            return Math.min(alpha, 255);
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

    // --- Create Connected's sequenced pulse generator ---

    /**
     * A sequenced pulse generator's screen: the generator's place and block, and its instructions as they stand while they
     * are set, in Create Connected's own saved form (a list of tags, written as a command writes a tag).
     */
    public record Pulse(long pos, String block, String instructions) {

        public Pulse {
            Objects.requireNonNull(block);
            Objects.requireNonNull(instructions);
        }
    }

    public static byte[] pulse(Pulse state) {
        return write(out -> {
            out.out.writeLong(state.pos());
            out.text(state.block());
            out.text(state.instructions());
        });
    }

    public static Pulse readPulse(byte[] data) {
        return read(data, in -> new Pulse(in.in.readLong(), in.text(), in.text()));
    }

    // --- Create Encased's automatic clutch ---

    /**
     * An automatic clutch's screen: the clutch's place and block, the mode picked, the value set (a stress or a speed) and
     * the operation its block entity holds.
     */
    public record Clutch(long pos, String block, int mode, int value, int operation) {

        public Clutch {
            Objects.requireNonNull(block);
        }
    }

    public static byte[] clutch(Clutch state) {
        return write(out -> {
            out.out.writeLong(state.pos());
            out.text(state.block());
            out.out.writeInt(state.mode());
            out.out.writeInt(state.value());
            out.out.writeInt(state.operation());
        });
    }

    public static Clutch readClutch(byte[] data) {
        return read(data, in -> new Clutch(in.in.readLong(), in.text(), in.in.readInt(), in.in.readInt(), in.in.readInt()));
    }

    // --- A selection wheel of items: Create Encased's recaser, Steam 'n' Rails' tag wheel ---

    /**
     * A wheel of items picked from round the pointer: what it picks for (the recaser held, or the tag cycled through), the
     * items round it by id, the one picked, how many ticks it had been open (it grows in over its first ones), and for a
     * wheel that scrolls, whether it was scrolled and to which, and the item's tag written as a command writes one.
     */
    public record Wheel(String subject, List<String> items, int picked, int ticksOpen, boolean scrolling, int scrolledTo, String tag) {

        public Wheel {
            Objects.requireNonNull(subject);
            items = List.copyOf(items);
            Objects.requireNonNull(tag);
        }
    }

    public static byte[] wheel(Wheel state) {
        return write(out -> {
            out.text(state.subject());
            out.texts(state.items());
            out.out.writeInt(state.picked());
            out.out.writeInt(state.ticksOpen());
            out.out.writeBoolean(state.scrolling());
            out.out.writeInt(state.scrolledTo());
            out.text(state.tag());
        });
    }

    public static Wheel readWheel(byte[] data) {
        return read(data, in -> new Wheel(in.text(), in.texts(), in.in.readInt(), in.in.readInt(), in.in.readBoolean(), in.in.readInt(), in.text()));
    }

    // --- Steam 'n' Rails' bogey menu ---

    /**
     * The bogey menu: the category shown (by its place in Steam 'n' Rails' list), the bogey style picked by id, how far the
     * list was scrolled, and how many ticks it had been open.
     */
    public record Bogeys(int category, String style, float scroll, int ticksOpen) {

        public Bogeys {
            Objects.requireNonNull(style);
        }
    }

    public static byte[] bogeys(Bogeys state) {
        return write(out -> {
            out.out.writeInt(state.category());
            out.text(state.style());
            out.out.writeFloat(state.scroll());
            out.out.writeInt(state.ticksOpen());
        });
    }

    public static Bogeys readBogeys(byte[] data) {
        return read(data, in -> new Bogeys(in.in.readInt(), in.text(), in.in.readFloat(), in.in.readInt()));
    }

    // --- Create Deep Seas' sonar ---

    /** One mark on the sonar's map: where (as the screen placed it), what kind (a mine, a hostile, a creature, a vessel), and its name as JSON */
    public record Contact(float u, float v, int kind, String name) {

        public Contact {
            Objects.requireNonNull(name);
        }
    }

    /**
     * The sonar's screen as it stood: the console's place, the view as set on it (panned, zoomed, turned and orbited), how
     * far below the floor was, and the marks on the map as the recorder's sonar found them.
     */
    public record Sonar(long pos, double panX, double panZ, float zoom, float yaw, float orbit, float floor, List<Contact> contacts) {

        public Sonar {
            contacts = List.copyOf(contacts);
        }
    }

    public static byte[] sonar(Sonar state) {
        return write(out -> {
            out.out.writeLong(state.pos());
            out.out.writeDouble(state.panX());
            out.out.writeDouble(state.panZ());
            out.out.writeFloat(state.zoom());
            out.out.writeFloat(state.yaw());
            out.out.writeFloat(state.orbit());
            out.out.writeFloat(state.floor());
            out.count(state.contacts().size());
            for (Contact contact : state.contacts()) {
                out.out.writeFloat(contact.u());
                out.out.writeFloat(contact.v());
                out.out.writeInt(contact.kind());
                out.text(contact.name());
            }
        });
    }

    public static Sonar readSonar(byte[] data) {
        return read(data, in -> {
            long pos = in.in.readLong();
            double panX = in.in.readDouble();
            double panZ = in.in.readDouble();
            float zoom = in.in.readFloat();
            float yaw = in.in.readFloat();
            float orbit = in.in.readFloat();
            float floor = in.in.readFloat();
            int count = in.count();
            List<Contact> contacts = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                contacts.add(new Contact(in.in.readFloat(), in.in.readFloat(), in.in.readInt(), in.text()));
            }
            return new Sonar(pos, panX, panZ, zoom, yaw, orbit, floor, contacts);
        });
    }

    // --- Create Deep Seas' console ---

    /**
     * A command sub console as the player worked it in the world: the console the pointer was on ({@link #NOWHERE} for
     * none), which of its widgets and where on it, and the console being typed into ({@link #NOWHERE} for none) with what
     * was typed so far.
     */
    public record Console(long hovered, int widget, float u, float v, long typing, String typed) {

        public Console {
            Objects.requireNonNull(typed);
        }
    }

    public static byte[] console(Console state) {
        return write(out -> {
            out.out.writeLong(state.hovered());
            out.out.writeInt(state.widget());
            out.out.writeFloat(state.u());
            out.out.writeFloat(state.v());
            out.out.writeLong(state.typing());
            out.text(state.typed());
        });
    }

    public static Console readConsole(byte[] data) {
        return read(data, in -> new Console(in.in.readLong(), in.in.readInt(), in.in.readFloat(), in.in.readFloat(), in.in.readLong(), in.text()));
    }

    // --- Create: Hypertubes ---

    /** The arrow shown while riding a tube: whether the way can be changed now (white, or orange when not), and its turn in degrees */
    public record Arrow(boolean canTurn, float angle) {
    }

    public static byte[] arrow(Arrow state) {
        return write(out -> {
            out.out.writeBoolean(state.canTurn());
            out.out.writeFloat(state.angle());
        });
    }

    public static Arrow readArrow(byte[] data) {
        return read(data, in -> new Arrow(in.in.readBoolean(), in.in.readFloat()));
    }

    /** The outline round the tube a placement starts from: its place, its block state, and whether the tube can be placed (green, or red) */
    public record Outline(long pos, String block, boolean canPlace) {

        public Outline {
            Objects.requireNonNull(block);
        }
    }

    public static byte[] outline(Outline state) {
        return write(out -> {
            out.out.writeLong(state.pos());
            out.text(state.block());
            out.out.writeBoolean(state.canPlace());
        });
    }

    public static Outline readOutline(byte[] data) {
        return read(data, in -> new Outline(in.in.readLong(), in.text(), in.in.readBoolean()));
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
            byte[] bytes = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
            this.count(bytes.length);
            this.out.write(bytes);
        }

        void texts(List<String> texts) throws IOException {
            this.count(texts.size());
            for (String text : texts) {
                this.text(text);
            }
        }

        void ints(int[] ints) throws IOException {
            this.count(ints.length);
            for (int each : ints) {
                this.out.writeInt(each);
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
            byte[] bytes = new byte[this.count()];
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

        int[] ints() throws IOException {
            int[] ints = new int[this.count()];
            for (int i = 0; i < ints.length; i++) {
                ints[i] = this.in.readInt();
            }
            return ints;
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
