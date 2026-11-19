package comedymonkey.dailiescompat.jade.state;

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
 * What the Jade compat records, as bytes (see {@code client.JadeDailies}): the box Jade drew over play, the whole of it,
 * the least that draws it again. Nothing here refers to Jade, Dailies or the game, so it is unit tested without them.
 * Text is kept as the game's own JSON for a component, items and fluids as the game's JSON for them, and a box's style
 * as Jade's own JSON for a style, made and read on the game's side; here each is only a string. Version 1 of the compat
 * writes these; a later version must still read them, so the box only ever gains fields at its end, and an element only
 * gains entries at the ends of its texts, numbers and children, whose lengths are written before them.
 */
public final class JadeStates {

    /** The longest list or string a record is read with, so a damaged record fails instead of filling the memory */
    static final int MOST = 1 << 16;
    /** How deep elements may sit inside each other: Jade nests a view group's box in the main box, and little more */
    static final int DEEPEST = 16;

    // --- The kinds of element, one for each of Jade's own ---

    /** An element of a plugin's own, which only that plugin can draw: its place is kept, empty, and its class named */
    public static final int OTHER = 0;
    /** Text: {@code texts} its JSON; {@code floats} empty, or the scale and the depth of a scaled text */
    public static final int TEXT = 1;
    /**
     * An item: {@code texts} its JSON, as one of it, and, when one is drawn over it, its count text; {@code ints} how many;
     * {@code floats} its scale
     */
    public static final int ITEM = 2;
    /**
     * A progress bar: {@code texts} the bar's box style and, when it has one, its text; {@code floats} how far along;
     * {@code ints} whether it may go down, the bar's kind ({@link #SIMPLE_BAR} and the rest), its colours, its text colour,
     * whether the text colour is worked out, its direction and whether it fits its line across and down; {@code children}
     * what fills it, when something does (a fluid)
     */
    public static final int PROGRESS = 3;
    /** A fluid: {@code texts} its JSON */
    public static final int FLUID = 4;
    /** Hearts: {@code floats} the health; {@code ints} hearts to a row, rows and hearts; {@code texts} the figures drawn instead, if any */
    public static final int HEALTH = 5;
    /** Armour: as for hearts, with the armour */
    public static final int ARMOR = 6;
    /** A sprite: {@code texts} its id; {@code ints} its width and height */
    public static final int SPRITE = 7;
    /** Space only */
    public static final int SPACER = 8;
    /** A line across: {@code ints} its colour */
    public static final int RULE = 9;
    /**
     * A box of lines, the main box itself or one inside it: {@code texts} its style; {@code ints} its padding (up, right,
     * down, left), its progress's kind (or -1 for none) and whether it hints at more details; {@code floats} its
     * progress and the size of its lines; {@code children} its icon, when it has one; {@code lines} its lines
     */
    public static final int BOX = 10;
    /** A large element with a small one over its corner: {@code children} both */
    public static final int COMPOUND = 11;

    /** A progress bar of Jade's simple kind, which may be coloured, have text and run upwards */
    public static final int SIMPLE_BAR = 0;
    /** A progress bar of Jade's slim kind, a thin coloured bar */
    public static final int SLIM_BAR = 1;
    /** A progress bar of a plugin's own kind, drawn as the simple kind */
    public static final int OTHER_BAR = 2;

    private JadeStates() {
    }

    /**
     * One element as Jade laid it out: its kind, which side of its line it keeps to, how far it was moved, the size it
     * took, its tag, and what its kind needs (see each kind).
     */
    public record Element(int kind, int align, float moveX, float moveY, float width, float height, String tag, List<String> texts, int[] ints,
                          float[] floats, List<Element> children, List<Line> lines) {

        public Element {
            Objects.requireNonNull(tag);
            texts = List.copyOf(texts);
            ints = ints.clone();
            floats = floats.clone();
            children = List.copyOf(children);
            lines = List.copyOf(lines);
        }

        /** The text at {@code i}, or empty */
        public String text(int i) {
            return i >= 0 && i < this.texts.size() ? this.texts.get(i) : "";
        }

        /** The whole number at {@code i}, or {@code otherwise} */
        public int number(int i, int otherwise) {
            return i >= 0 && i < this.ints.length ? this.ints[i] : otherwise;
        }

        /** The number at {@code i}, or {@code otherwise} */
        public float fraction(int i, float otherwise) {
            return i >= 0 && i < this.floats.length ? this.floats[i] : otherwise;
        }

        @Override
        public int[] ints() {
            return this.ints.clone();
        }

        @Override
        public float[] floats() {
            return this.floats.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Element element && element.kind == this.kind && element.align == this.align
                && Float.compare(element.moveX, this.moveX) == 0 && Float.compare(element.moveY, this.moveY) == 0
                && Float.compare(element.width, this.width) == 0 && Float.compare(element.height, this.height) == 0 && element.tag.equals(this.tag)
                && element.texts.equals(this.texts) && Arrays.equals(element.ints, this.ints) && Arrays.equals(element.floats, this.floats)
                && element.children.equals(this.children) && element.lines.equals(this.lines);
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.kind, this.tag, this.texts, this.children, this.lines);
        }
    }

    /** One line of a box: the space above and below it as Jade set it, and its elements in the order Jade drew them */
    public record Line(int marginTop, int marginBottom, List<Element> elements) {

        public Line {
            elements = List.copyOf(elements);
        }
    }

    /**
     * The box Jade drew, as the recorder's game drew it at the end of a tick.
     *
     * @param x            where the box was drawn, in the recorder's interface units (as Jade's chasing left it)
     * @param scale        the box's scale, after Jade's own shrinking of a tall box
     * @param fade         how far it had faded in, 0 to 1
     * @param guiWidth     the recorder's interface size, which the box's place is read against
     * @param anchorX      the point of the screen the box keeps to, as a share of the interface across, after flipping
     *                     for a left hand
     * @param anchorY      the same, down
     * @param square       whether the recorder had Jade's square corners
     * @param opacity      Jade's own setting for how solid the box's background is
     * @param iconMode     where Jade put the block's icon, by the order of Jade's icon modes
     * @param theme        the theme's id
     * @param themeStored  the theme as Jade's JSON for a theme, stored once in the recording, by its handle; empty when it
     *                     could not be stored
     * @param breakingFade how far the line of a block being broken had faded in, 0 for none
     * @param breaking     how far the block was broken, 0 to 1
     * @param canHarvest   whether the block broken drops something, which colours the line
     * @param root         the box's own element, a {@link #BOX}
     */
    public record Box(int x, int y, int width, int height, float scale, float fade, int guiWidth, int guiHeight, float anchorX, float anchorY,
                      boolean square, float opacity, int iconMode, String theme, String themeStored, float breakingFade, float breaking,
                      boolean canHarvest, Element root) {

        public Box {
            Objects.requireNonNull(theme);
            Objects.requireNonNull(themeStored);
            Objects.requireNonNull(root);
        }

        /** Where the box goes across on an interface of this width: as far from the anchor as it was on the recorder's */
        public int xOn(int width) {
            return this.x + Math.round(width * this.anchorX - this.guiWidth * this.anchorX);
        }

        /** Where the box goes down on an interface of this height */
        public int yOn(int height) {
            return this.y + Math.round(height * this.anchorY - this.guiHeight * this.anchorY);
        }
    }

    public static byte[] box(Box box) {
        return write(out -> {
            out.out.writeInt(box.x());
            out.out.writeInt(box.y());
            out.out.writeInt(box.width());
            out.out.writeInt(box.height());
            out.out.writeFloat(box.scale());
            out.out.writeFloat(box.fade());
            out.out.writeInt(box.guiWidth());
            out.out.writeInt(box.guiHeight());
            out.out.writeFloat(box.anchorX());
            out.out.writeFloat(box.anchorY());
            out.out.writeBoolean(box.square());
            out.out.writeFloat(box.opacity());
            out.out.writeByte(box.iconMode());
            out.text(box.theme());
            out.text(box.themeStored());
            out.out.writeFloat(box.breakingFade());
            out.out.writeFloat(box.breaking());
            out.out.writeBoolean(box.canHarvest());
            out.element(box.root(), 0);
        });
    }

    public static Box readBox(byte[] data) {
        return read(data, in -> new Box(in.in.readInt(), in.in.readInt(), in.in.readInt(), in.in.readInt(), in.in.readFloat(), in.in.readFloat(),
            in.in.readInt(), in.in.readInt(), in.in.readFloat(), in.in.readFloat(), in.in.readBoolean(), in.in.readFloat(), in.in.readByte(), in.text(),
            in.text(), in.in.readFloat(), in.in.readFloat(), in.in.readBoolean(), in.element(0)));
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
            this.out.writeInt(bytes.length);
            this.out.write(bytes);
        }

        void element(Element element, int depth) throws IOException {
            if (depth > DEEPEST) {
                throw new IOException("elements nested more than " + DEEPEST + " deep");
            }
            this.out.writeByte(element.kind());
            this.out.writeByte(element.align());
            this.out.writeFloat(element.moveX());
            this.out.writeFloat(element.moveY());
            this.out.writeFloat(element.width());
            this.out.writeFloat(element.height());
            this.text(element.tag());
            this.count(element.texts().size());
            for (String text : element.texts()) {
                this.text(text);
            }
            int[] ints = element.ints();
            this.count(ints.length);
            for (int each : ints) {
                this.out.writeInt(each);
            }
            float[] floats = element.floats();
            this.count(floats.length);
            for (float each : floats) {
                this.out.writeFloat(each);
            }
            this.count(element.children().size());
            for (Element child : element.children()) {
                this.element(child, depth + 1);
            }
            this.count(element.lines().size());
            for (Line line : element.lines()) {
                this.out.writeInt(line.marginTop());
                this.out.writeInt(line.marginBottom());
                this.count(line.elements().size());
                for (Element each : line.elements()) {
                    this.element(each, depth + 1);
                }
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

        Element element(int depth) throws IOException {
            if (depth > DEEPEST) {
                throw new IOException("elements nested more than " + DEEPEST + " deep");
            }
            int kind = this.in.readByte();
            int align = this.in.readByte();
            float moveX = this.in.readFloat();
            float moveY = this.in.readFloat();
            float width = this.in.readFloat();
            float height = this.in.readFloat();
            String tag = this.text();
            int textCount = this.count();
            List<String> texts = new ArrayList<>(textCount);
            for (int i = 0; i < textCount; i++) {
                texts.add(this.text());
            }
            int[] ints = new int[this.count()];
            for (int i = 0; i < ints.length; i++) {
                ints[i] = this.in.readInt();
            }
            float[] floats = new float[this.count()];
            for (int i = 0; i < floats.length; i++) {
                floats[i] = this.in.readFloat();
            }
            int childCount = this.count();
            List<Element> children = new ArrayList<>(childCount);
            for (int i = 0; i < childCount; i++) {
                children.add(this.element(depth + 1));
            }
            int lineCount = this.count();
            List<Line> lines = new ArrayList<>(lineCount);
            for (int i = 0; i < lineCount; i++) {
                int top = this.in.readInt();
                int bottom = this.in.readInt();
                int count = this.count();
                List<Element> elements = new ArrayList<>(count);
                for (int j = 0; j < count; j++) {
                    elements.add(this.element(depth + 1));
                }
                lines.add(new Line(top, bottom, elements));
            }
            return new Element(kind, align, moveX, moveY, width, height, tag, texts, ints, floats, children, lines);
        }
    }

    private static byte[] write(Writer writer) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            writer.write(new Out(out));
        } catch (IOException tooDeep) {
            throw new UncheckedIOException(tooDeep);
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
