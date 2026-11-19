package comedymonkey.dailies.windows;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The window open on the recorder's screen, as a recording holds it (specification REC-25): which window it is, what
 * is in its slots, its data slots (a furnace's flame and arrow), a villager's offers, the item on the pointer, and
 * where the pointer is. It is written into a recording as {@code dailies:windows} records and read back in playback.
 * Nothing in here knows the game: item stacks, the title and the offers are kept as the game's own bytes, read only
 * when the window is drawn, so a stack this game cannot read costs only its own slot.
 * <p>
 * A record is a version byte (1) followed by parts, each a tag byte and what that part holds. A record written as
 * play goes on holds only what changed; one written into a snapshot, or for a trimmed recording, holds the whole
 * window, or only a close when there is none. Parts:
 * <ul>
 * <li>1 open: where it came from (see {@link Source}), the container id, the menu type id, the title, the extra data
 * a mod's window was opened with (or none), and for a horse's window the horse's entity id and its columns. It
 * forgets everything about the window before</li>
 * <li>2 close</li>
 * <li>3 slot: the slot's number in the window, and its stack (no bytes for an empty slot)</li>
 * <li>4 data: a data slot's number and value</li>
 * <li>5 offers: a villager's offers with its level and experience</li>
 * <li>6 pointer: where it is, in the window's own units from its top left corner (or as a share of the screen for a
 * window that has no corner of its own), and the slot under it (-1 for none)</li>
 * <li>7 carried: the stack on the pointer</li>
 * </ul>
 * Numbers that cannot be negative are varints, the others zigzag varints; byte strings and text carry a varint length
 * first.
 */
public final class WindowState {

    public static final String ID = "dailies:windows";
    static final int VERSION = 1;

    static final int OPEN = 1;
    static final int CLOSE = 2;
    static final int SLOT = 3;
    static final int DATA = 4;
    static final int OFFERS = 5;
    static final int POINTER = 6;
    static final int CARRIED = 7;

    /** What {@link #apply} found in a record: a window opened (or another took its place) */
    static final int OPENED = 1;
    /** The window closed */
    static final int CLOSED = 2;
    /** Slots, data slots, offers or the carried stack changed */
    static final int CONTENTS = 4;
    /** The pointer moved */
    static final int MOVED = 8;

    static final byte[] NONE = new byte[0];

    /** How a window came to be open, which says how playback builds it again */
    public enum Source {
        /** The server opened it with the game's open-screen packet, by its menu type */
        SERVER,
        /** The server opened a mod's window with NeoForge's open-screen message, which carries extra data for the menu */
        ADVANCED,
        /** A horse's (or donkey's, llama's) inventory, which the server opens by the animal */
        HORSE,
        /** The player's own inventory, which the game opens with no word from the server */
        INVENTORY;

        static Source of(int index) {
            Source[] all = values();
            if (index < 0 || index >= all.length) {
                throw new IllegalArgumentException("window record names a source " + index + " that this version of Dailies does not know");
            }
            return all[index];
        }
    }

    /** Null while no window is open */
    Source source = null;
    int containerId;
    String menuType = "";
    byte[] title = NONE;
    /** The extra data a mod's window was opened with; null when there was none */
    byte[] extra = null;
    int horseEntity = -1;
    int horseColumns = 0;
    final List<byte[]> slots = new ArrayList<>();
    final TreeMap<Integer, Integer> data = new TreeMap<>();
    byte[] offers = null;
    byte[] carried = NONE;
    boolean pointer = false;
    boolean pointerShare = false;
    float pointerX;
    float pointerY;
    int hovered = -1;

    public boolean open() {
        return this.source != null;
    }

    public Source source() {
        return this.source;
    }

    public String menuType() {
        return this.menuType;
    }

    /** The slot under the pointer as recorded, -1 for none */
    public int hovered() {
        return this.hovered;
    }

    void clear() {
        this.source = null;
        this.containerId = 0;
        this.menuType = "";
        this.title = NONE;
        this.extra = null;
        this.horseEntity = -1;
        this.horseColumns = 0;
        this.slots.clear();
        this.data.clear();
        this.offers = null;
        this.carried = NONE;
        this.pointer = false;
        this.pointerShare = false;
        this.pointerX = 0.0F;
        this.pointerY = 0.0F;
        this.hovered = -1;
    }

    byte[] slot(int index) {
        return index >= 0 && index < this.slots.size() ? this.slots.get(index) : NONE;
    }

    int data(int index) {
        return this.data.getOrDefault(index, 0);
    }

    /**
     * Reads one record into this state.
     *
     * @return what changed, as {@link #OPENED}, {@link #CLOSED}, {@link #CONTENTS} and {@link #MOVED} together
     * @throws IllegalArgumentException for a record this version cannot read; the state is then left part way
     */
    int apply(byte[] record, int from, int length) {
        Reader in = new Reader(record, from, length);
        int version = in.readByte();
        if (version != VERSION) {
            throw new IllegalArgumentException("window record of version " + version + ", which this version of Dailies does not know");
        }
        int changed = 0;
        while (in.more()) {
            int tag = in.readByte();
            switch (tag) {
                case OPEN -> {
                    this.clear();
                    this.source = Source.of(in.varint());
                    this.containerId = in.zigzag();
                    this.menuType = in.text();
                    this.title = in.bytes();
                    this.extra = in.maybeBytes();
                    this.horseEntity = in.zigzag();
                    this.horseColumns = in.varint();
                    changed |= OPENED | CONTENTS | MOVED;
                }
                case CLOSE -> {
                    if (this.open()) {
                        changed |= CLOSED;
                    }
                    this.clear();
                }
                case SLOT -> {
                    int index = in.varint();
                    byte[] stack = in.bytes();
                    while (this.slots.size() <= index) {
                        this.slots.add(NONE);
                    }
                    this.slots.set(index, stack);
                    changed |= CONTENTS;
                }
                case DATA -> {
                    int index = in.varint();
                    this.data.put(index, in.zigzag());
                    changed |= CONTENTS;
                }
                case OFFERS -> {
                    this.offers = in.bytes();
                    changed |= CONTENTS;
                }
                case POINTER -> {
                    this.pointer = true;
                    this.pointerShare = in.readByte() != 0;
                    this.pointerX = in.readFloat();
                    this.pointerY = in.readFloat();
                    this.hovered = in.zigzag();
                    changed |= MOVED;
                }
                case CARRIED -> {
                    this.carried = in.bytes();
                    changed |= CONTENTS;
                }
                default -> throw new IllegalArgumentException("window record has a part " + tag + " that this version of Dailies does not know");
            }
        }
        return changed;
    }

    /** The whole window as one record, as a snapshot holds it; a close alone when none is open */
    byte[] whole() {
        Parts parts = new Parts();
        if (!this.open()) {
            return parts.close().bytes();
        }
        parts.open(this.source, this.containerId, this.menuType, this.title, this.extra, this.horseEntity, this.horseColumns);
        for (int i = 0; i < this.slots.size(); i++) {
            if (this.slots.get(i).length > 0) {
                parts.slot(i, this.slots.get(i));
            }
        }
        this.data.forEach((index, value) -> {
            if (value != 0) {
                parts.data(index, value);
            }
        });
        if (this.offers != null) {
            parts.offers(this.offers);
        }
        if (this.carried.length > 0) {
            parts.carried(this.carried);
        }
        if (this.pointer) {
            parts.pointer(this.pointerShare, this.pointerX, this.pointerY, this.hovered);
        }
        return parts.bytes();
    }

    WindowState copy() {
        WindowState copy = new WindowState();
        byte[] whole = this.whole();
        copy.apply(whole, 0, whole.length);
        return copy;
    }

    /** The same window holding the same things, an empty slot being the same as one never written */
    @Override
    public boolean equals(Object other) {
        if (!(other instanceof WindowState that)) {
            return false;
        }
        if (this.source != that.source) {
            return false;
        }
        if (!this.open()) {
            return true;
        }
        if (this.containerId != that.containerId || !this.menuType.equals(that.menuType) || !Arrays.equals(this.title, that.title)
            || !Arrays.equals(this.extra, that.extra) || this.horseEntity != that.horseEntity || this.horseColumns != that.horseColumns
            || !Arrays.equals(this.offers, that.offers) || !Arrays.equals(this.carried, that.carried) || this.pointer != that.pointer
            || this.pointerShare != that.pointerShare || this.pointerX != that.pointerX || this.pointerY != that.pointerY || this.hovered != that.hovered) {
            return false;
        }
        for (int i = 0; i < Math.max(this.slots.size(), that.slots.size()); i++) {
            if (!Arrays.equals(this.slot(i), that.slot(i))) {
                return false;
            }
        }
        TreeSet<Integer> indices = new TreeSet<>(this.data.keySet());
        indices.addAll(that.data.keySet());
        for (int index : indices) {
            if (this.data(index) != that.data(index)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.source, this.containerId, this.menuType);
    }

    @Override
    public String toString() {
        if (!this.open()) {
            return "no window";
        }
        int filled = 0;
        for (byte[] slot : this.slots) {
            if (slot.length > 0) {
                filled++;
            }
        }
        return this.source + " " + (this.menuType.isEmpty() ? "" : this.menuType + " ") + "#" + this.containerId + ", " + filled + " slots filled, data "
            + this.data + (this.pointer ? ", pointer " + this.pointerX + "," + this.pointerY + " over " + this.hovered : "");
    }

    /** Writes the parts of one record, in the order they are given */
    static final class Parts {

        private final ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        private boolean any = false;

        Parts() {
            this.out.write(VERSION);
        }

        boolean isEmpty() {
            return !this.any;
        }

        byte[] bytes() {
            return this.out.toByteArray();
        }

        Parts open(Source source, int containerId, String menuType, byte[] title, byte[] extra, int horseEntity, int horseColumns) {
            this.tag(OPEN);
            this.varint(source.ordinal());
            this.zigzag(containerId);
            this.text(menuType);
            this.bytes(title);
            if (extra == null) {
                this.varint(0);
            } else {
                this.varint(extra.length + 1);
                this.out.write(extra, 0, extra.length);
            }
            this.zigzag(horseEntity);
            this.varint(Math.max(0, horseColumns));
            return this;
        }

        Parts close() {
            this.tag(CLOSE);
            return this;
        }

        Parts slot(int index, byte[] stack) {
            this.tag(SLOT);
            this.varint(index);
            this.bytes(stack);
            return this;
        }

        Parts data(int index, int value) {
            this.tag(DATA);
            this.varint(index);
            this.zigzag(value);
            return this;
        }

        Parts offers(byte[] offers) {
            this.tag(OFFERS);
            this.bytes(offers);
            return this;
        }

        Parts pointer(boolean share, float x, float y, int hovered) {
            this.tag(POINTER);
            this.out.write(share ? 1 : 0);
            this.writeFloat(x);
            this.writeFloat(y);
            this.zigzag(hovered);
            return this;
        }

        Parts carried(byte[] stack) {
            this.tag(CARRIED);
            this.bytes(stack);
            return this;
        }

        private void tag(int tag) {
            this.any = true;
            this.out.write(tag);
        }

        private void varint(int value) {
            while ((value & ~0x7F) != 0) {
                this.out.write((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            this.out.write(value);
        }

        private void zigzag(int value) {
            this.varint((value << 1) ^ (value >> 31));
        }

        private void bytes(byte[] bytes) {
            this.varint(bytes.length);
            this.out.write(bytes, 0, bytes.length);
        }

        private void text(String text) {
            this.bytes(text.getBytes(StandardCharsets.UTF_8));
        }

        private void writeFloat(float value) {
            int bits = Float.floatToIntBits(value);
            this.out.write(bits >>> 24);
            this.out.write(bits >>> 16);
            this.out.write(bits >>> 8);
            this.out.write(bits);
        }
    }

    private static final class Reader {

        private final byte[] bytes;
        private int at;
        private final int end;

        Reader(byte[] bytes, int from, int length) {
            if (from < 0 || length < 0 || from + length > bytes.length) {
                throw new IllegalArgumentException("window record runs past its end");
            }
            this.bytes = bytes;
            this.at = from;
            this.end = from + length;
        }

        boolean more() {
            return this.at < this.end;
        }

        int readByte() {
            if (this.at >= this.end) {
                throw new IllegalArgumentException("window record ends part way through");
            }
            return this.bytes[this.at++] & 0xFF;
        }

        int varint() {
            int value = 0;
            for (int shift = 0; shift < 35; shift += 7) {
                int b = this.readByte();
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return value;
                }
            }
            throw new IllegalArgumentException("window record has a number too long to be one");
        }

        int zigzag() {
            int raw = this.varint();
            return (raw >>> 1) ^ -(raw & 1);
        }

        byte[] bytes() {
            int length = this.varint();
            return this.take(length);
        }

        byte[] maybeBytes() {
            int length = this.varint();
            return length == 0 ? null : this.take(length - 1);
        }

        String text() {
            return new String(this.bytes(), StandardCharsets.UTF_8);
        }

        float readFloat() {
            int bits = (this.readByte() << 24) | (this.readByte() << 16) | (this.readByte() << 8) | this.readByte();
            return Float.intBitsToFloat(bits);
        }

        private byte[] take(int length) {
            if (length < 0 || length > this.end - this.at) {
                throw new IllegalArgumentException("window record runs past its end");
            }
            byte[] taken = Arrays.copyOfRange(this.bytes, this.at, this.at + length);
            this.at += length;
            return taken;
        }
    }

    /** For tests: the data slots as they stand, zeros left out */
    Map<Integer, Integer> dataSlots() {
        TreeMap<Integer, Integer> set = new TreeMap<>();
        this.data.forEach((index, value) -> {
            if (value != 0) {
                set.put(index, value);
            }
        });
        return set;
    }
}
