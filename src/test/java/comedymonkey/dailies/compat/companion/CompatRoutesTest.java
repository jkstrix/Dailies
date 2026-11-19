package comedymonkey.dailies.compat.companion;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The companion's server plugins reach their client halves by id, live and from a recording */
class CompatRoutesTest {

    private static final class Kept implements CompatRoutes.Handler {
        final List<String> live = new ArrayList<>();
        final List<String> played = new ArrayList<>();
        int resets = 0;

        @Override
        public void received(int version, byte[] data) {
            this.live.add(version + ":" + new String(data, StandardCharsets.UTF_8));
        }

        @Override
        public void played(int version, byte[] data, boolean skipping) {
            this.played.add(version + ":" + new String(data, StandardCharsets.UTF_8) + (skipping ? " skipping" : ""));
        }

        @Override
        public void reset() {
            this.resets++;
        }
    }

    private static CompatRoutes.Arrival arrival(String id, int version, String data) {
        return new CompatRoutes.Arrival(id, version, data.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void dataGoesToTheHandlerOfItsIdAndNoOther() {
        CompatRoutes routes = new CompatRoutes();
        Kept mail = new Kept();
        Kept jobs = new Kept();
        routes.register("postrider:mail", mail);
        routes.register("mcpcraft:jobs", jobs);
        assertTrue(routes.live(arrival("postrider:mail", 2, "three letters")));
        assertTrue(routes.live(arrival("mcpcraft:jobs", 1, "build a bridge")));
        assertTrue(routes.played(arrival("postrider:mail", 2, "one letter"), true));
        assertEquals(List.of("2:three letters"), mail.live);
        assertEquals(List.of("2:one letter skipping"), mail.played);
        assertEquals(List.of("1:build a bridge"), jobs.live);
        assertTrue(jobs.played.isEmpty());
        routes.resetAll();
        assertEquals(1, mail.resets);
        assertEquals(1, jobs.resets);
    }

    @Test
    void dataWithNoHandlerIsNotedAndDropped() {
        CompatRoutes routes = new CompatRoutes();
        Kept mail = new Kept();
        routes.register("postrider:mail", mail);
        assertFalse(routes.live(arrival("othermod:thing", 1, "x")));
        assertFalse(routes.played(arrival("Postrider:Mail", 1, "case matters"), false));
        assertEquals(Set.of("othermod:thing", "Postrider:Mail"), routes.unrouted());
        assertTrue(mail.live.isEmpty() && mail.played.isEmpty());
    }

    @Test
    void anIdIsTakenOnceAndMustBeNamespaced() {
        CompatRoutes routes = new CompatRoutes();
        routes.register("postrider:mail", new Kept());
        assertThrows(IllegalArgumentException.class, () -> routes.register("postrider:mail", new Kept()));
        assertThrows(IllegalArgumentException.class, () -> routes.register("mail", new Kept()));
        assertThrows(IllegalArgumentException.class, () -> routes.register(":mail", new Kept()));
        assertThrows(IllegalArgumentException.class, () -> routes.register("postrider:", new Kept()));
        assertThrows(IllegalArgumentException.class, () -> routes.register(null, new Kept()));
    }

    @Test
    void anArrivalReadsBackAsTheRecordingKeptIt() {
        byte[] data = new byte[300];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }
        CompatRoutes.Arrival arrival = new CompatRoutes.Arrival("dailies_companion:plugins", 70000, data);
        byte[] record = arrival.encode();
        // Read from the middle of a larger buffer, as playback hands records over
        byte[] padded = new byte[record.length + 10];
        System.arraycopy(record, 0, padded, 4, record.length);
        CompatRoutes.Arrival read = CompatRoutes.Arrival.decode(padded, 4, record.length);
        assertEquals(arrival, read);
        assertArrayEquals(data, read.data());
        assertEquals(70000, read.version());

        CompatRoutes.Arrival empty = new CompatRoutes.Arrival("a:b", 0, new byte[0]);
        assertEquals(empty, CompatRoutes.Arrival.decode(empty.encode(), 0, empty.encode().length));
        assertThrows(IllegalArgumentException.class, () -> CompatRoutes.Arrival.decode(record, 0, 3));
    }

    @Test
    void theListOfAServersPluginsReadsBack() {
        Map<String, Integer> plugins = new LinkedHashMap<>();
        plugins.put("dailies_companion:plugins", 1);
        plugins.put("postrider:mail", 3);
        plugins.put("comedymonkeysaddons:trade_bot", 200);
        Map<String, Integer> read = CompatRoutes.pluginList(CompatRoutes.pluginList(plugins));
        assertEquals(plugins, read);
        assertEquals(List.copyOf(plugins.keySet()), List.copyOf(read.keySet()), "in the order the server registered them");
        assertEquals(Map.of(), CompatRoutes.pluginList(new byte[]{0}));
        assertThrows(IllegalArgumentException.class, () -> CompatRoutes.pluginList(new byte[]{2, 5, 'a'}));
    }

    /** The one real use: the companion's list of plugins, recorded and played, tells a client half its server half's version */
    @Test
    @SuppressWarnings("unchecked")
    void theBuiltInListOfPluginsGivesEachCompatItsServerHalfsVersion() {
        CompatRoutes routes = new CompatRoutes();
        Map<String, Integer>[] known = new Map[]{Map.of()};
        routes.register("dailies_companion:plugins", new CompatRoutes.Handler() {
            @Override
            public void played(int version, byte[] data, boolean skipping) {
                known[0] = CompatRoutes.pluginList(data);
            }
        });
        Map<String, Integer> plugins = new LinkedHashMap<>();
        plugins.put("dailies_companion:plugins", 1);
        plugins.put("postrider:mail", 2);
        // As live play wrote it into the recording, and playback read it back
        byte[] record = new CompatRoutes.Arrival("dailies_companion:plugins", 1, CompatRoutes.pluginList(plugins)).encode();
        assertTrue(routes.played(CompatRoutes.Arrival.decode(record, 0, record.length), false));
        assertEquals(2, known[0].getOrDefault("postrider:mail", 0));
        assertEquals(0, known[0].getOrDefault("othermod:thing", 0));
    }

}
