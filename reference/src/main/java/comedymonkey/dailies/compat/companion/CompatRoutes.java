package comedymonkey.dailies.compat.companion;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where the data a server plugin sends over the Dailies Companion's shared channel goes on the player's side
 * (specification SRV-6, SRV-7): to the client half registered under the plugin's id, live as it arrives and again in
 * playback from the recording, which keeps each arrival as a {@code dailies:compat} record. No game in here, so it is
 * unit tested.
 * <p>
 * The record: the plugin's id (a varint length and UTF-8), its version (a varint) and its data as the rest. The data
 * of the companion's own {@code dailies_companion:plugins} is a varint count and, for each plugin the server has, its
 * id and version written the same way.
 */
public final class CompatRoutes {

    /** A client half of a compat: what to do with its server half's data */
    public interface Handler {
        /** Its server half's data has arrived in live play. Client thread */
        default void received(int version, byte[] data) {
        }

        /** The same data, reached in playback. Client thread */
        default void played(int version, byte[] data, boolean skipping) {
        }

        /** Playback is about to rebuild the world from a snapshot, or has closed: forget what was played */
        default void reset() {
        }
    }

    /** One arrival, as the recording keeps it */
    public record Arrival(String id, int version, byte[] data) {
        public byte[] encode() {
            ByteArrayOutputStream out = new ByteArrayOutputStream(this.data.length + 32);
            byte[] text = this.id.getBytes(StandardCharsets.UTF_8);
            varint(out, text.length);
            out.write(text, 0, text.length);
            varint(out, this.version);
            out.write(this.data, 0, this.data.length);
            return out.toByteArray();
        }

        public static Arrival decode(byte[] bytes, int from, int length) {
            int[] at = {from};
            int end = from + length;
            int textLength = readVarint(bytes, at, end);
            if (textLength < 0 || textLength > end - at[0]) {
                throw new IllegalArgumentException("compat record runs past its end");
            }
            String id = new String(bytes, at[0], textLength, StandardCharsets.UTF_8);
            at[0] += textLength;
            int version = readVarint(bytes, at, end);
            return new Arrival(id, version, Arrays.copyOfRange(bytes, at[0], end));
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Arrival that && this.id.equals(that.id) && this.version == that.version && Arrays.equals(this.data, that.data);
        }

        @Override
        public int hashCode() {
            return this.id.hashCode() * 31 + Arrays.hashCode(this.data);
        }
    }

    private final Map<String, Handler> handlers = new ConcurrentHashMap<>();
    private final Set<String> unrouted = ConcurrentHashMap.newKeySet();

    /**
     * @throws IllegalArgumentException when the id is not namespaced or already has a handler
     */
    public void register(String id, Handler handler) {
        if (id == null || id.indexOf(':') < 1 || id.endsWith(":")) {
            throw new IllegalArgumentException("A compat needs a namespaced id such as 'mymod:thing', not '" + id + "'");
        }
        if (this.handlers.putIfAbsent(id, handler) != null) {
            throw new IllegalArgumentException("A compat called " + id + " is registered already");
        }
    }

    /** @return whether a handler took it; one with no handler is noted by {@link #unrouted()} and dropped */
    public boolean live(Arrival arrival) {
        Handler handler = this.handlers.get(arrival.id());
        if (handler == null) {
            this.unrouted.add(arrival.id());
            return false;
        }
        handler.received(arrival.version(), arrival.data());
        return true;
    }

    /** @return whether a handler took it */
    public boolean played(Arrival arrival, boolean skipping) {
        Handler handler = this.handlers.get(arrival.id());
        if (handler == null) {
            this.unrouted.add(arrival.id());
            return false;
        }
        handler.played(arrival.version(), arrival.data(), skipping);
        return true;
    }

    public void resetAll() {
        this.handlers.values().forEach(Handler::reset);
    }

    /** Ids that data arrived for with no handler registered: a server plugin whose client half is not installed */
    public Set<String> unrouted() {
        return Collections.unmodifiableSet(new TreeSet<>(this.unrouted));
    }

    /** Reads the companion's list of the plugins a server has, by id, with their versions */
    public static Map<String, Integer> pluginList(byte[] data) {
        int[] at = {0};
        int count = readVarint(data, at, data.length);
        Map<String, Integer> plugins = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            int textLength = readVarint(data, at, data.length);
            if (textLength < 0 || textLength > data.length - at[0]) {
                throw new IllegalArgumentException("list of plugins runs past its end");
            }
            String id = new String(data, at[0], textLength, StandardCharsets.UTF_8);
            at[0] += textLength;
            plugins.put(id, readVarint(data, at, data.length));
        }
        return plugins;
    }

    /** Writes a list of plugins as the companion does, for the tests */
    static byte[] pluginList(Map<String, Integer> plugins) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        varint(out, plugins.size());
        plugins.forEach((id, version) -> {
            byte[] text = id.getBytes(StandardCharsets.UTF_8);
            varint(out, text.length);
            out.write(text, 0, text.length);
            varint(out, version);
        });
        return out.toByteArray();
    }

    private static void varint(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static int readVarint(byte[] bytes, int[] at, int end) {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            if (at[0] >= end) {
                throw new IllegalArgumentException("compat data ends part way through a number");
            }
            int b = bytes[at[0]++] & 0xFF;
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new IllegalArgumentException("compat data has a number too long to be one");
    }

}
