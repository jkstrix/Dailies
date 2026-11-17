package comedymonkey.dailies.packs;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Which of a server's resource packs are in force, worked out from what a recording says happened to them
 * (specification REC-19, PLAY-17). A recording holds four kinds of record under {@link #ID}: a pack came into force, a
 * pack was taken off, a pack was pushed but never applied (declined, or it failed), and, in every snapshot, the whole
 * list as it stood. Reading them in order into one of these gives the packs in force at any tick, bottom first.
 * <p>
 * Nothing here knows the game, so it can be tested without it.
 */
public final class PackLog {

    /** The name of the extension records */
    public static final String ID = "dailies:packs";

    private static final int PUSHED = 1;
    private static final int POPPED = 2;
    private static final int NOT_APPLIED = 3;
    private static final int STATE = 4;

    /**
     * One push of a pack, as the server sent it.
     *
     * @param url      where the server said to fetch it; empty for a pack that came with a world's own files
     * @param hash     the hash the server gave, which may be empty
     * @param prompt   the server's own words for the question, as JSON text, or empty
     * @param content  the key of the stored bytes in the recording's description; empty when there are none
     */
    public record Pack(UUID id, String url, String hash, boolean required, String prompt, String content) {
        public Pack {
            url = url == null ? "" : url;
            hash = hash == null ? "" : hash;
            prompt = prompt == null ? "" : prompt;
            content = content == null ? "" : content;
        }
    }

    /** A push that was never applied, kept for the record: playback does not apply it either */
    public record Refusal(Pack pack, String reason) {
    }

    private final List<Pack> inForce = new ArrayList<>();
    private final List<Refusal> refusals = new ArrayList<>();

    /** The packs in force, in the order the game holds them: the first pushed first */
    public List<Pack> inForce() {
        return List.copyOf(this.inForce);
    }

    /** The content keys of the packs in force, in order */
    public List<String> contents() {
        List<String> keys = new ArrayList<>();
        for (Pack pack : this.inForce) {
            keys.add(pack.content());
        }
        return keys;
    }

    /** The pushes read so far that were not applied */
    public List<Refusal> refusals() {
        return List.copyOf(this.refusals);
    }

    /**
     * A pack has come into force. One with the same id that was there is replaced, as the game replaces it.
     *
     * @param index where in the list it now stands; anything past the end means the end
     */
    public void push(Pack pack, int index) {
        this.pop(pack.id());
        this.inForce.add(Math.max(0, Math.min(index, this.inForce.size())), pack);
    }

    /** @return whether a pack with that id was in force */
    public boolean pop(UUID id) {
        return this.inForce.removeIf(pack -> pack.id().equals(id));
    }

    public void clear() {
        this.inForce.clear();
        this.refusals.clear();
    }

    // ------------------------------------------------------------------ The records

    public static byte[] pushed(Pack pack, int index) {
        return write(PUSHED, out -> {
            out.writeInt(index);
            writePack(out, pack);
        });
    }

    public static byte[] popped(UUID id) {
        return write(POPPED, out -> {
            out.writeLong(id.getMostSignificantBits());
            out.writeLong(id.getLeastSignificantBits());
        });
    }

    public static byte[] notApplied(Pack pack, String reason) {
        return write(NOT_APPLIED, out -> {
            writePack(out, pack);
            out.writeUTF(reason == null ? "" : reason);
        });
    }

    /** The whole list as it stands, for a snapshot */
    public static byte[] state(List<Pack> inForce) {
        return write(STATE, out -> {
            out.writeInt(inForce.size());
            for (Pack pack : inForce) {
                writePack(out, pack);
            }
        });
    }

    /**
     * Takes in one record. A kind this version does not know, or a record cut short, changes nothing.
     *
     * @return whether the list of packs in force may have changed
     */
    public boolean apply(byte[] data, int from, int length) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data, from, length))) {
            switch (in.readUnsignedByte()) {
                case PUSHED -> {
                    int index = in.readInt();
                    this.push(readPack(in), index);
                    return true;
                }
                case POPPED -> {
                    return this.pop(new UUID(in.readLong(), in.readLong()));
                }
                case NOT_APPLIED -> {
                    Pack pack = readPack(in);
                    this.refusals.add(new Refusal(pack, in.readUTF()));
                    return false;
                }
                case STATE -> {
                    int count = in.readInt();
                    List<Pack> listed = new ArrayList<>();
                    for (int i = 0; i < count; i++) {
                        listed.add(readPack(in));
                    }
                    this.inForce.clear();
                    this.inForce.addAll(listed);
                    return true;
                }
                default -> {
                    return false;
                }
            }
        } catch (IOException | RuntimeException damaged) {
            return false;
        }
    }

    private interface Body {
        void write(DataOutputStream out) throws IOException;
    }

    private static byte[] write(int kind, Body body) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(kind);
            body.write(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static void writePack(DataOutputStream out, Pack pack) throws IOException {
        out.writeLong(pack.id().getMostSignificantBits());
        out.writeLong(pack.id().getLeastSignificantBits());
        writeText(out, pack.url());
        writeText(out, pack.hash());
        out.writeBoolean(pack.required());
        writeText(out, pack.prompt());
        writeText(out, pack.content());
    }

    private static Pack readPack(DataInputStream in) throws IOException {
        return new Pack(new UUID(in.readLong(), in.readLong()), readText(in), readText(in), in.readBoolean(), readText(in), readText(in));
    }

    // A server's prompt can be longer than writeUTF allows
    private static void writeText(DataOutputStream out, String text) throws IOException {
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readText(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > in.available()) {
            throw new IOException("A text runs past the end of its record");
        }
        return new String(in.readNBytes(length), java.nio.charset.StandardCharsets.UTF_8);
    }

}
