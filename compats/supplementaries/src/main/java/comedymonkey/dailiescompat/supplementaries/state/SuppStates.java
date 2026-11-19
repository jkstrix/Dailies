package comedymonkey.dailiescompat.supplementaries.state;

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
 * What the Supplementaries compat records, as bytes (see {@code client.SuppDailies}): for each claim, the whole of what it
 * showed, the least that draws it again. Nothing here refers to the mods, Dailies or the game, so it is unit tested
 * without them. Text is kept as the game's own JSON for a component, block states as the game writes them in a command,
 * and what the game keeps in its own formats (a block entity's saved data, an item with its components, a merchant's
 * offers) as those formats' bytes; here each is only a string or bytes. Version 1 of the compat writes these; a later
 * version must still read them, so a state only ever gains fields at its end.
 */
public final class SuppStates {

    /** The longest list or string a record is read with, so a damaged record fails instead of filling the memory */
    static final int MOST = 1 << 20;

    private SuppStates() {
    }

    // --- A doormat or a sign post being written on ---

    /**
     * A doormat's or a sign post's screen as it stood: the block entity's type, place, block and saved data (its signs, wood
     * and fence, its writing's colour and glow), the lines as typed, one list for each of its writings (a sign post has two
     * signs), which writing and line are being typed on, the cursor and the end of the selection, and the screen's tick
     * count, which makes the cursor blink.
     */
    public record TextHolder(String type, long pos, String block, byte[] data, List<List<String>> lines, int holder, int line, int cursor, int selection,
                             int ticks) {

        public TextHolder {
            Objects.requireNonNull(type);
            Objects.requireNonNull(block);
            data = data.clone();
            List<List<String>> copied = new ArrayList<>(lines.size());
            for (List<String> each : lines) {
                copied.add(List.copyOf(each));
            }
            lines = List.copyOf(copied);
        }

        @Override
        public byte[] data() {
            return this.data.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof TextHolder that && this.type.equals(that.type) && this.pos == that.pos && this.block.equals(that.block)
                && Arrays.equals(this.data, that.data) && this.lines.equals(that.lines) && this.holder == that.holder && this.line == that.line
                && this.cursor == that.cursor && this.selection == that.selection && this.ticks == that.ticks;
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.type, this.pos, this.block, Arrays.hashCode(this.data), this.lines, this.holder, this.line, this.cursor, this.selection,
                this.ticks);
        }
    }

    public static byte[] textHolder(TextHolder state) {
        return write(out -> {
            out.text(state.type());
            out.out.writeLong(state.pos());
            out.text(state.block());
            out.bytes(state.data);
            out.count(state.lines().size());
            for (List<String> each : state.lines()) {
                out.texts(each);
            }
            out.out.writeInt(state.holder());
            out.out.writeInt(state.line());
            out.out.writeInt(state.cursor());
            out.out.writeInt(state.selection());
            out.out.writeInt(state.ticks());
        });
    }

    public static TextHolder readTextHolder(byte[] data) {
        return read(data, in -> {
            String type = in.text();
            long pos = in.in.readLong();
            String block = in.text();
            byte[] saved = in.bytes();
            int count = in.count();
            List<List<String>> lines = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                lines.add(in.texts());
            }
            return new TextHolder(type, pos, block, saved, lines, in.in.readInt(), in.in.readInt(), in.in.readInt(), in.in.readInt(), in.in.readInt());
        });
    }

    // --- A blackboard being drawn on ---

    /**
     * A blackboard's screen as it stood: the boards shown together, column by column and row by row (with one of them, a
     * board joined to the one used on, as Supplementaries joins them), each as its 256 pixels' colours or null where
     * there is none; which board was used, the colour picked, and whether there was a stroke to undo (the Undo button lit).
     */
    public record Blackboard(int columns, int rows, List<byte[]> boards, int used, byte colour, boolean undo) {

        public Blackboard {
            if (columns < 1 || rows < 1 || boards.size() != columns * rows) {
                throw new IllegalArgumentException(columns + " by " + rows + " boards, with " + boards.size() + " given");
            }
            List<byte[]> copied = new ArrayList<>(boards.size());
            for (byte[] board : boards) {
                if (board != null && board.length != PIXELS) {
                    throw new IllegalArgumentException("a board of " + board.length + " pixels");
                }
                copied.add(board == null ? null : board.clone());
            }
            boards = java.util.Collections.unmodifiableList(copied);
        }

        /** A board's pixels, 16 by 16, column after column; null where there is no board */
        public byte[] board(int column, int row) {
            byte[] board = this.boards.get(column * this.rows + row);
            return board == null ? null : board.clone();
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Blackboard that) || this.columns != that.columns || this.rows != that.rows || this.used != that.used
                || this.colour != that.colour || this.undo != that.undo) {
                return false;
            }
            for (int i = 0; i < this.boards.size(); i++) {
                if (!Arrays.equals(this.boards.get(i), that.boards.get(i))) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public int hashCode() {
            int hash = Objects.hash(this.columns, this.rows, this.used, this.colour, this.undo);
            for (byte[] board : this.boards) {
                hash = hash * 31 + Arrays.hashCode(board);
            }
            return hash;
        }
    }

    /** The pixels of one board: 16 by 16 */
    public static final int PIXELS = 256;

    public static byte[] blackboard(Blackboard state) {
        return write(out -> {
            out.out.writeInt(state.columns());
            out.out.writeInt(state.rows());
            for (byte[] board : state.boards()) {
                out.out.writeBoolean(board != null);
                if (board != null) {
                    out.out.write(board);
                }
            }
            out.out.writeInt(state.used());
            out.out.writeByte(state.colour());
            out.out.writeBoolean(state.undo());
        });
    }

    public static Blackboard readBlackboard(byte[] data) {
        return read(data, in -> {
            int columns = in.in.readInt();
            int rows = in.in.readInt();
            if (columns < 1 || rows < 1 || columns > 16 || rows > 16) {
                throw new IOException(columns + " by " + rows + " boards");
            }
            List<byte[]> boards = new ArrayList<>(columns * rows);
            for (int i = 0; i < columns * rows; i++) {
                if (in.in.readBoolean()) {
                    byte[] board = new byte[PIXELS];
                    in.in.readFully(board);
                    boards.add(board);
                } else {
                    boards.add(null);
                }
            }
            return new Blackboard(columns, rows, boards, in.in.readInt(), in.in.readByte(), in.in.readBoolean());
        });
    }

    // --- A speaker block being set ---

    /**
     * A speaker block's screen as it stood: the message as typed, the cursor and the end of the selection, how it is said
     * (chat, the line above the hotbar, a title or the narrator, by name), and how far it carries.
     */
    public record Speaker(String message, int cursor, int selection, String mode, double volume) {

        public Speaker {
            Objects.requireNonNull(message);
            Objects.requireNonNull(mode);
        }
    }

    public static byte[] speaker(Speaker state) {
        return write(out -> {
            out.text(state.message());
            out.out.writeInt(state.cursor());
            out.out.writeInt(state.selection());
            out.text(state.mode());
            out.out.writeDouble(state.volume());
        });
    }

    public static Speaker readSpeaker(byte[] data) {
        return read(data, in -> new Speaker(in.text(), in.in.readInt(), in.in.readInt(), in.text(), in.in.readDouble()));
    }

    // --- Amendments' book and quill on a lectern ---

    /**
     * A book and quill written in on a lectern, as it stood: its pages as typed (with the colours and styles Amendments'
     * ink and quill put in them), the page open, the cursor and the end of the selection, whether it was being signed and
     * the title typed, the writer's name for the signing page's "by" line, and the ink and quill picked.
     */
    public record LecternBook(List<String> pages, int page, int cursor, int selection, boolean signing, String title, String owner, int ink, int quill) {

        public LecternBook {
            pages = List.copyOf(pages);
            Objects.requireNonNull(title);
            Objects.requireNonNull(owner);
        }
    }

    public static byte[] lecternBook(LecternBook state) {
        return write(out -> {
            out.texts(state.pages());
            out.out.writeInt(state.page());
            out.out.writeInt(state.cursor());
            out.out.writeInt(state.selection());
            out.out.writeBoolean(state.signing());
            out.text(state.title());
            out.text(state.owner());
            out.out.writeInt(state.ink());
            out.out.writeInt(state.quill());
        });
    }

    public static LecternBook readLecternBook(byte[] data) {
        return read(data, in -> new LecternBook(in.texts(), in.in.readInt(), in.in.readInt(), in.in.readInt(), in.in.readBoolean(), in.text(), in.text(),
            in.in.readInt(), in.in.readInt()));
    }

    // --- What the present window holds besides its slot ---

    /**
     * A present's window: who it is for and the message as typed (the server is told only as it is packed), and whether it
     * was packed. For a trapped present only {@code packed} is used, as whether it was primed.
     */
    public record Present(String recipient, String description, boolean packed) {

        public Present {
            Objects.requireNonNull(recipient);
            Objects.requireNonNull(description);
        }
    }

    public static byte[] present(Present state) {
        return write(out -> {
            out.text(state.recipient());
            out.text(state.description());
            out.out.writeBoolean(state.packed());
        });
    }

    public static Present readPresent(byte[] data) {
        return read(data, in -> new Present(in.text(), in.text(), in.in.readBoolean()));
    }

    // --- What the cannon window holds besides its slots ---

    /** The cannon's window: its pitch and yaw as typed (sent only as the window closes) and the power picked */
    public record Cannon(String pitch, String yaw, byte power) {

        public Cannon {
            Objects.requireNonNull(pitch);
            Objects.requireNonNull(yaw);
        }
    }

    public static byte[] cannon(Cannon state) {
        return write(out -> {
            out.text(state.pitch());
            out.text(state.yaw());
            out.out.writeByte(state.power());
        });
    }

    public static Cannon readCannon(byte[] data) {
        return read(data, in -> new Cannon(in.text(), in.text(), in.in.readByte()));
    }

    // --- What the red merchant's window holds besides its slots ---

    /**
     * The red merchant's window: its offers as the game's own codec writes them (they come in a message of Supplementaries'
     * own, which Dailies' window records do not hold), its level and experience, whether it shows its progress bar and can
     * restock, how far the list of offers is scrolled and which offer is picked.
     */
    public record Merchant(byte[] offers, int level, int xp, boolean progress, boolean restock, int scroll, int picked) {

        public Merchant {
            offers = offers.clone();
        }

        @Override
        public byte[] offers() {
            return this.offers.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Merchant that && Arrays.equals(this.offers, that.offers) && this.level == that.level && this.xp == that.xp
                && this.progress == that.progress && this.restock == that.restock && this.scroll == that.scroll && this.picked == that.picked;
        }

        @Override
        public int hashCode() {
            return Objects.hash(Arrays.hashCode(this.offers), this.level, this.xp, this.progress, this.restock, this.scroll, this.picked);
        }
    }

    public static byte[] merchant(Merchant state) {
        return write(out -> {
            out.bytes(state.offers);
            out.out.writeInt(state.level());
            out.out.writeInt(state.xp());
            out.out.writeBoolean(state.progress());
            out.out.writeBoolean(state.restock());
            out.out.writeInt(state.scroll());
            out.out.writeInt(state.picked());
        });
    }

    public static Merchant readMerchant(byte[] data) {
        return read(data, in -> new Merchant(in.bytes(), in.in.readInt(), in.in.readInt(), in.in.readBoolean(), in.in.readBoolean(), in.in.readInt(),
            in.in.readInt()));
    }

    // --- The slimed overlay ---

    /** The slime over the screen of a player hit by a slimeball: the ticks it has left (it fades over the last 70) */
    public record Slimed(int ticks) {
    }

    public static byte[] slimed(Slimed state) {
        return write(out -> out.out.writeInt(state.ticks()));
    }

    public static Slimed readSlimed(byte[] data) {
        return read(data, in -> new Slimed(in.in.readInt()));
    }

    /** How opaque the slime is drawn, as Supplementaries works it out from the ticks left and the moment between ticks */
    public static float slimeAlpha(int ticks, float partialTick) {
        float left = ticks - partialTick;
        return left > 70.0F ? 1.0F : Math.max(0.0F, Math.min(1.0F, left / 70.0F));
    }

    // --- The quiver's or lunch basket's row ---

    /**
     * The row of a quiver or lunch basket being picked from: each item in it as the game's own codec writes an item (empty
     * for an empty place), the one picked, and where the row is moved by the recorder's settings, across and down.
     */
    public record Selectable(List<byte[]> items, int picked, int offsetX, int offsetY) {

        public Selectable {
            List<byte[]> copied = new ArrayList<>(items.size());
            for (byte[] item : items) {
                copied.add(item.clone());
            }
            items = java.util.Collections.unmodifiableList(copied);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Selectable that) || this.picked != that.picked || this.offsetX != that.offsetX || this.offsetY != that.offsetY
                || this.items.size() != that.items.size()) {
                return false;
            }
            for (int i = 0; i < this.items.size(); i++) {
                if (!Arrays.equals(this.items.get(i), that.items.get(i))) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public int hashCode() {
            int hash = Objects.hash(this.picked, this.offsetX, this.offsetY);
            for (byte[] item : this.items) {
                hash = hash * 31 + Arrays.hashCode(item);
            }
            return hash;
        }
    }

    public static byte[] selectable(Selectable state) {
        return write(out -> {
            out.count(state.items().size());
            for (byte[] item : state.items()) {
                out.bytes(item);
            }
            out.out.writeInt(state.picked());
            out.out.writeInt(state.offsetX());
            out.out.writeInt(state.offsetY());
        });
    }

    public static Selectable readSelectable(byte[] data) {
        return read(data, in -> {
            int count = in.count();
            List<byte[]> items = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                items.add(in.bytes());
            }
            return new Selectable(items, in.in.readInt(), in.in.readInt(), in.in.readInt());
        });
    }

    // --- The cannon's controls ---

    /** The cannon's controls: nothing but the barrel, when the player is loaded in a cannon and not aiming it */
    public static final int CANNON_NONE = 0;
    /** Aiming a cannon: its own hotbar, the charge and power, the trajectory's icons, the crosshair */
    public static final int CANNON_AIMING = 1;
    /** Riding a cannon boat: the charge and power */
    public static final int CANNON_BOAT = 2;

    /** The crosshair while aiming: straight, the shot hitting, or the shot falling short */
    public static final int CROSSHAIR_NONE = 0;
    public static final int CROSSHAIR_AIM = 1;
    public static final int CROSSHAIR_HIT = 2;
    public static final int CROSSHAIR_MISS = 3;

    /**
     * What Supplementaries draws over play around a cannon: the barrel's view, while the player is loaded in one; how it is
     * worked ({@link #CANNON_NONE}, {@link #CANNON_AIMING} or {@link #CANNON_BOAT}); while aiming, whether the trajectory's
     * icons show, which way it shoots (by number), whether the trajectory is shown, and the crosshair; the cannon's
     * projectile and fuel as the game's codec writes an item, and the player loaded in it, by id (empty for none); its
     * cooldown and firing as drawn at the start and the end of the tick, and its power.
     */
    public record CannonHud(boolean barrel, int kind, boolean icons, int shooting, boolean trajectory, int crosshair, byte[] projectile, byte[] fuel,
                            String rider, float cooldownFrom, float cooldownTo, float firingFrom, float firingTo, byte power) {

        public CannonHud {
            projectile = projectile.clone();
            fuel = fuel.clone();
            Objects.requireNonNull(rider);
        }

        @Override
        public byte[] projectile() {
            return this.projectile.clone();
        }

        @Override
        public byte[] fuel() {
            return this.fuel.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof CannonHud that && this.barrel == that.barrel && this.kind == that.kind && this.icons == that.icons
                && this.shooting == that.shooting && this.trajectory == that.trajectory && this.crosshair == that.crosshair
                && Arrays.equals(this.projectile, that.projectile) && Arrays.equals(this.fuel, that.fuel) && this.rider.equals(that.rider)
                && Float.compare(this.cooldownFrom, that.cooldownFrom) == 0 && Float.compare(this.cooldownTo, that.cooldownTo) == 0
                && Float.compare(this.firingFrom, that.firingFrom) == 0 && Float.compare(this.firingTo, that.firingTo) == 0 && this.power == that.power;
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.barrel, this.kind, this.icons, this.shooting, this.trajectory, this.crosshair, Arrays.hashCode(this.projectile),
                Arrays.hashCode(this.fuel), this.rider, this.cooldownFrom, this.cooldownTo, this.firingFrom, this.firingTo, this.power);
        }

        public float cooldown(float partialTick) {
            return this.cooldownFrom + (this.cooldownTo - this.cooldownFrom) * partialTick;
        }

        public float firing(float partialTick) {
            return this.firingFrom + (this.firingTo - this.firingFrom) * partialTick;
        }
    }

    public static byte[] cannonHud(CannonHud state) {
        return write(out -> {
            out.out.writeBoolean(state.barrel());
            out.out.writeInt(state.kind());
            out.out.writeBoolean(state.icons());
            out.out.writeInt(state.shooting());
            out.out.writeBoolean(state.trajectory());
            out.out.writeInt(state.crosshair());
            out.bytes(state.projectile);
            out.bytes(state.fuel);
            out.text(state.rider());
            out.out.writeFloat(state.cooldownFrom());
            out.out.writeFloat(state.cooldownTo());
            out.out.writeFloat(state.firingFrom());
            out.out.writeFloat(state.firingTo());
            out.out.writeByte(state.power());
        });
    }

    public static CannonHud readCannonHud(byte[] data) {
        return read(data, in -> new CannonHud(in.in.readBoolean(), in.in.readInt(), in.in.readBoolean(), in.in.readInt(), in.in.readBoolean(),
            in.in.readInt(), in.bytes(), in.bytes(), in.text(), in.in.readFloat(), in.in.readFloat(), in.in.readFloat(), in.in.readFloat(),
            in.in.readByte()));
    }

    // --- The cannon's trajectory, drawn into the world ---

    /** What the aim of a cannon pointed at: nothing, a block (with its face and place), or something else */
    public static final int HIT_NONE = 0;
    public static final int HIT_BLOCK = 1;
    public static final int HIT_OTHER = 2;

    /**
     * The trajectory of a cannon being aimed, as Supplementaries works it out on the player's own game and draws it into the
     * world: the cannon's place; the trajectory itself (where it lands across and down from the barrel, its pitch, its
     * time of flight, whether it falls short, gravity, drag and its speed across and up); which way it shoots, by number;
     * and what the aim pointed at: its kind, the point hit, the face (by number) and the block's place.
     */
    public record Trajectory(long cannon, float hitAcross, float hitDown, float pitch, double time, boolean miss, float gravity, float drag, float speedAcross,
                             float speedUp, int shooting, int hitKind, double hitX, double hitY, double hitZ, int face, long hitBlock) {
    }

    public static byte[] trajectory(Trajectory state) {
        return write(out -> {
            out.out.writeLong(state.cannon());
            out.out.writeFloat(state.hitAcross());
            out.out.writeFloat(state.hitDown());
            out.out.writeFloat(state.pitch());
            out.out.writeDouble(state.time());
            out.out.writeBoolean(state.miss());
            out.out.writeFloat(state.gravity());
            out.out.writeFloat(state.drag());
            out.out.writeFloat(state.speedAcross());
            out.out.writeFloat(state.speedUp());
            out.out.writeInt(state.shooting());
            out.out.writeInt(state.hitKind());
            out.out.writeDouble(state.hitX());
            out.out.writeDouble(state.hitY());
            out.out.writeDouble(state.hitZ());
            out.out.writeInt(state.face());
            out.out.writeLong(state.hitBlock());
        });
    }

    public static Trajectory readTrajectory(byte[] data) {
        return read(data, in -> new Trajectory(in.in.readLong(), in.in.readFloat(), in.in.readFloat(), in.in.readFloat(), in.in.readDouble(),
            in.in.readBoolean(), in.in.readFloat(), in.in.readFloat(), in.in.readFloat(), in.in.readFloat(), in.in.readInt(), in.in.readInt(),
            in.in.readDouble(), in.in.readDouble(), in.in.readDouble(), in.in.readInt(), in.in.readLong()));
    }

    // --- The slingshot's far outline ---

    /** The outline a slingshot with Stasis draws round the far block it points at: the block's place and the outline's colour */
    public record Outline(long pos, int colour) {
    }

    public static byte[] outline(Outline state) {
        return write(out -> {
            out.out.writeLong(state.pos());
            out.out.writeInt(state.colour());
        });
    }

    public static Outline readOutline(byte[] data) {
        return read(data, in -> new Outline(in.in.readLong(), in.in.readInt()));
    }

    // --- The line above the hotbar ---

    /**
     * A line Supplementaries or Amendments put above the hotbar on the player's own game: its text as the game's JSON, the
     * ticks it had left, and whether its colour cycles.
     */
    public record Status(String text, int ticksLeft, boolean animated) {

        public Status {
            Objects.requireNonNull(text);
        }

        /** How opaque the line is drawn, as the game fades it out over its last twenty ticks */
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
