package comedymonkey.dailies.windows;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The records a compat writes into a recording (specification REC-26), under the compat's own id. A record is the
 * compat's version as a varint, then one or more parts, each a kind byte and the claim's name (a varint length and
 * UTF-8), then:
 * <ul>
 * <li>1 state: the claim's whole state, a varint length and the bytes. It replaces whatever the claim held before</li>
 * <li>2 gone: nothing more; the claim is no longer showing</li>
 * <li>3 pointer: whether it is a share of the screen (a byte), then x and y as floats, in the window's own units from
 * its corner or as shares of the screen</li>
 * </ul>
 * No game in here, so it is unit tested.
 */
public final class CompatRecord {

    static final int STATE = 1;
    static final int GONE = 2;
    static final int POINTER = 3;

    /** One part of a record. The state is set only for {@link #STATE}, the pointer only for {@link #POINTER} */
    record Op(int kind, String name, byte[] state, boolean share, float x, float y) {

        static Op state(String name, byte[] state) {
            return new Op(STATE, name, state, false, 0.0F, 0.0F);
        }

        static Op gone(String name) {
            return new Op(GONE, name, null, false, 0.0F, 0.0F);
        }

        static Op pointer(String name, boolean share, float x, float y) {
            return new Op(POINTER, name, null, share, x, y);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Op that && this.kind == that.kind && this.name.equals(that.name) && Arrays.equals(this.state, that.state)
                && this.share == that.share && this.x == that.x && this.y == that.y;
        }

        @Override
        public int hashCode() {
            return this.name.hashCode() * 31 + this.kind;
        }
    }

    /** A record read back */
    record Read(int version, List<Op> ops) {
    }

    private CompatRecord() {
    }

    static byte[] write(int version, List<Op> ops) {
        if (ops.isEmpty()) {
            throw new IllegalArgumentException("a compat record needs at least one part");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(64);
        varint(out, version);
        for (Op op : ops) {
            out.write(op.kind());
            bytes(out, op.name().getBytes(StandardCharsets.UTF_8));
            switch (op.kind()) {
                case STATE -> bytes(out, op.state());
                case GONE -> {
                }
                case POINTER -> {
                    out.write(op.share() ? 1 : 0);
                    writeFloat(out, op.x());
                    writeFloat(out, op.y());
                }
                default -> throw new IllegalArgumentException("no compat record part of kind " + op.kind());
            }
        }
        return out.toByteArray();
    }

    /** @throws IllegalArgumentException for a record that cannot be read: it is then left out whole */
    static Read read(byte[] data, int from, int length) {
        if (from < 0 || length < 0 || from + length > data.length) {
            throw new IllegalArgumentException("compat record runs past its end");
        }
        int[] at = {from};
        int end = from + length;
        int version = varint(data, at, end);
        List<Op> ops = new ArrayList<>();
        while (at[0] < end) {
            int kind = data[at[0]++] & 0xFF;
            String name = new String(take(data, at, end, varint(data, at, end)), StandardCharsets.UTF_8);
            switch (kind) {
                case STATE -> ops.add(Op.state(name, take(data, at, end, varint(data, at, end))));
                case GONE -> ops.add(Op.gone(name));
                case POINTER -> {
                    boolean share = take(data, at, end, 1)[0] != 0;
                    ops.add(Op.pointer(name, share, readFloat(data, at, end), readFloat(data, at, end)));
                }
                default -> throw new IllegalArgumentException("compat record has a part of kind " + kind + ", which this version of Dailies does not know");
            }
        }
        if (ops.isEmpty()) {
            throw new IllegalArgumentException("compat record has no parts");
        }
        return new Read(version, ops);
    }

    private static void varint(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static void bytes(ByteArrayOutputStream out, byte[] bytes) {
        varint(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    private static void writeFloat(ByteArrayOutputStream out, float value) {
        int bits = Float.floatToIntBits(value);
        out.write(bits >>> 24);
        out.write(bits >>> 16);
        out.write(bits >>> 8);
        out.write(bits);
    }

    private static int varint(byte[] data, int[] at, int end) {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            if (at[0] >= end) {
                throw new IllegalArgumentException("compat record ends part way through a number");
            }
            int b = data[at[0]++] & 0xFF;
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new IllegalArgumentException("compat record has a number too long to be one");
    }

    private static byte[] take(byte[] data, int[] at, int end, int length) {
        if (length < 0 || length > end - at[0]) {
            throw new IllegalArgumentException("compat record runs past its end");
        }
        byte[] taken = Arrays.copyOfRange(data, at[0], at[0] + length);
        at[0] += length;
        return taken;
    }

    private static float readFloat(byte[] data, int[] at, int end) {
        byte[] b = take(data, at, end, 4);
        return Float.intBitsToFloat(((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF));
    }

}
