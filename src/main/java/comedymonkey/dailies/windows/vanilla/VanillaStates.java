package comedymonkey.dailies.windows.vanilla;

import org.jetbrains.annotations.Nullable;

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
 * What the game's own books, lectern and signs are recorded as (see {@link VanillaCompat}): one state per screen, each
 * the least that draws it again and the whole of it. A book's pages can run to a hundred pages of a thousand characters,
 * too much to carry in every write and every snapshot, so they are stored once in the recording and a state names them
 * by their handle; while a book is written in, only the page being typed on rides in the state, and the rest are stored
 * once. Text a written book styles is kept as the game's own JSON for a component, made and read on the game's side;
 * here it is only a string. Nothing here refers to the game, so it is unit tested. Version 1 of the compat writes these;
 * a later version must still read them, so a state only ever gains fields at its end.
 */
public final class VanillaStates {

    /** The longest list or string a state is read with, so a damaged state fails instead of filling the memory */
    static final int MOST = 1 << 22;
    /** The most pages a book holds, as the game allows */
    static final int MOST_PAGES = 100;

    private VanillaStates() {
    }

    // --- Pages, stored once ---

    /** A book's pages as stored once in the recording: each page's text, or a written book's page as the game's JSON */
    public static byte[] pages(List<String> pages) {
        return write(out -> out.texts(pages.subList(0, Math.min(pages.size(), MOST_PAGES))));
    }

    public static List<String> readPages(byte[] data) {
        return read(data, In::texts);
    }

    // --- A written book read from the hand ---

    /**
     * A written book being read from the hand.
     *
     * @param hand  which hand holds it: 0 the main hand, 1 the other; -1 when neither held these pages as it was read
     * @param pages the handle of its pages, stored once ({@link #pages}), each a page as the game's JSON
     * @param page  the page open, from 0
     */
    public record BookRead(int hand, String pages, int page) {
    }

    public static byte[] bookRead(BookRead state) {
        return write(out -> {
            out.out.writeByte(state.hand());
            out.text(state.pages());
            out.out.writeInt(state.page());
        });
    }

    public static BookRead readBookRead(byte[] data) {
        return read(data, in -> new BookRead(in.in.readByte(), in.text(), in.in.readInt()));
    }

    // --- A book on a lectern ---

    /**
     * A book on a lectern being read: its window is the server's, but its screen is the book's, so it is recorded here.
     *
     * @param pages    the handle of the book's pages, stored once, each a page as the game's JSON
     * @param page     the page open, from 0
     * @param mayBuild whether the reader could take the book, which gives the screen its Take Book button
     */
    public record Lectern(String pages, int page, boolean mayBuild) {
    }

    public static byte[] lectern(Lectern state) {
        return write(out -> {
            out.text(state.pages());
            out.out.writeInt(state.page());
            out.out.writeBoolean(state.mayBuild());
        });
    }

    public static Lectern readLectern(byte[] data) {
        return read(data, in -> new Lectern(in.text(), in.in.readInt(), in.in.readBoolean()));
    }

    // --- A book and quill being written in ---

    /**
     * A book and quill being written in, or signed.
     *
     * @param otherPages the handle of every page with the one open left empty, stored once; null when the book has one page
     * @param page       the page open, from 0
     * @param pageText   the open page's text as typed
     * @param cursor     where the cursor is on the open page, in characters
     * @param selection  the other end of the selection, the same as the cursor when nothing is selected
     * @param signing    whether the signing screen shows, with the title
     * @param title      the title as typed on the signing screen
     * @param owner      the writer's name, which the signing screen shows under the title
     */
    public record BookWrite(@Nullable String otherPages, int page, String pageText, int cursor, int selection, boolean signing, String title, String owner) {

        /** The book's pages, with the open page's text put back among the others (read from what was stored once) */
        public List<String> pagesWith(@Nullable List<String> others) {
            List<String> pages = new ArrayList<>(others == null || others.isEmpty() ? List.of("") : others);
            while (pages.size() <= this.page && pages.size() < MOST_PAGES) {
                pages.add("");
            }
            if (this.page >= 0 && this.page < pages.size()) {
                pages.set(this.page, this.pageText);
            }
            return pages;
        }
    }

    /** Every page of a book with the one open left empty: what is stored once while the open page is typed on */
    public static List<String> others(List<String> pages, int page) {
        List<String> others = new ArrayList<>(pages);
        if (page >= 0 && page < others.size()) {
            others.set(page, "");
        }
        return others;
    }

    public static byte[] bookWrite(BookWrite state) {
        return write(out -> {
            out.out.writeBoolean(state.otherPages() != null);
            if (state.otherPages() != null) {
                out.text(state.otherPages());
            }
            out.out.writeInt(state.page());
            out.text(state.pageText());
            out.out.writeInt(state.cursor());
            out.out.writeInt(state.selection());
            out.out.writeBoolean(state.signing());
            out.text(state.title());
            out.text(state.owner());
        });
    }

    public static BookWrite readBookWrite(byte[] data) {
        return read(data, in -> new BookWrite(in.in.readBoolean() ? in.text() : null, in.in.readInt(), in.text(), in.in.readInt(), in.in.readInt(),
            in.in.readBoolean(), in.text(), in.text()));
    }

    // --- A sign being edited ---

    /**
     * A sign or hanging sign being edited.
     *
     * @param pos       where the sign stands, as the game packs a block position into a long
     * @param block     the sign's block id, such as {@code minecraft:oak_wall_sign}, which says its wood and whether it
     *                  stands, hangs on a wall or hangs from above
     * @param wood      its wood type's name, such as {@code oak}
     * @param hanging   whether it is a hanging sign
     * @param front     whether its front is being edited, rather than its back
     * @param lines     the four lines as typed
     * @param line      the line being typed on, 0 to 3
     * @param cursor    where the cursor is on that line, in characters
     * @param selection the other end of the selection, the same as the cursor when nothing is selected
     * @param colour    the text's dye colour by name, such as {@code black}
     * @param glowing   whether the text glows
     */
    public record SignEdit(long pos, String block, String wood, boolean hanging, boolean front, List<String> lines, int line, int cursor, int selection,
                           String colour, boolean glowing) {

        /** Line {@code i} as typed; empty when there is none */
        public String line(int i) {
            return i >= 0 && i < this.lines.size() ? this.lines.get(i) : "";
        }
    }

    public static byte[] signEdit(SignEdit state) {
        return write(out -> {
            out.out.writeLong(state.pos());
            out.text(state.block());
            out.text(state.wood());
            out.out.writeBoolean(state.hanging());
            out.out.writeBoolean(state.front());
            out.texts(state.lines());
            out.out.writeByte(state.line());
            out.out.writeInt(state.cursor());
            out.out.writeInt(state.selection());
            out.text(state.colour());
            out.out.writeBoolean(state.glowing());
        });
    }

    public static SignEdit readSignEdit(byte[] data) {
        return read(data, in -> new SignEdit(in.in.readLong(), in.text(), in.text(), in.in.readBoolean(), in.in.readBoolean(), in.texts(), in.in.readByte(),
            in.in.readInt(), in.in.readInt(), in.text(), in.in.readBoolean()));
    }

    // --- Bytes ---

    private static final class Out {
        final DataOutputStream out;

        Out(DataOutputStream out) {
            this.out = out;
        }

        void text(String text) throws IOException {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            this.out.writeInt(bytes.length);
            this.out.write(bytes);
        }

        void texts(List<String> texts) throws IOException {
            this.out.writeInt(texts.size());
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

        String text() throws IOException {
            int length = this.in.readInt();
            if (length < 0 || length > MOST) {
                throw new IOException("a text of " + length + " bytes");
            }
            return new String(this.in.readNBytes(checked(length)), StandardCharsets.UTF_8);
        }

        List<String> texts() throws IOException {
            int count = this.in.readInt();
            if (count < 0 || count > MOST) {
                throw new IOException("a list of " + count);
            }
            List<String> texts = new ArrayList<>(Math.min(count, 256));
            for (int i = 0; i < count; i++) {
                texts.add(this.text());
            }
            return texts;
        }

        private int checked(int length) throws IOException {
            if (this.in.available() < length) {
                throw new IOException("the state ends early");
            }
            return length;
        }
    }

    private interface Writer {
        void write(Out out) throws IOException;
    }

    private interface Reader<T> {
        T read(In in) throws IOException;
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

    /** @throws IllegalArgumentException when the bytes are not a whole state of this kind */
    private static <T> T read(byte[] data, Reader<T> reader) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            // Anything after what this version knows was written by a later one, and is left unread
            return reader.read(new In(in));
        } catch (IOException damaged) {
            throw new IllegalArgumentException("not a state this compat can read: " + damaged.getMessage(), damaged);
        }
    }

}
