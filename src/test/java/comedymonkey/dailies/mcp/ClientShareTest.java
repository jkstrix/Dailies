package comedymonkey.dailies.mcp;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How the MCP server's workers are shared out between the addresses that connect */
class ClientShareTest {

    /** Workers that run nothing until told to, so every step can be looked at */
    private static final class Workers implements Executor {
        final ArrayDeque<Runnable> queued = new ArrayDeque<>();
        boolean refusing = false;

        @Override
        public void execute(Runnable command) {
            if (this.refusing) {
                throw new RejectedExecutionException("busy");
            }
            this.queued.addLast(command);
        }

        void runOne() {
            this.queued.removeFirst().run();
        }
    }

    private static InetAddress address(int last) throws Exception {
        return InetAddress.getByAddress(new byte[]{10, 0, 0, (byte) last});
    }

    @Test
    void oneAddressHoldsOnlyItsShareAndTheRestWaitInOrder() throws Exception {
        Workers workers = new Workers();
        ClientShare share = new ClientShare(3, 2, workers);
        InetAddress a = address(1);
        List<String> ran = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            String name = "a" + i;
            assertTrue(share.submit(a, () -> ran.add(name), () -> ran.add("turned away " + name)));
        }
        assertEquals(3, workers.queued.size());
        assertEquals(3, share.running(a));
        assertEquals(2, share.waiting(a));
        // Past the waiting room the next is refused, and the caller closes it
        assertFalse(share.submit(a, () -> ran.add("a6"), () -> ran.add("turned away a6")));

        // Another address is not held up by the first
        InetAddress b = address(2);
        assertTrue(share.submit(b, () -> ran.add("b1"), () -> {}));
        assertEquals(4, workers.queued.size());

        // Each one that finishes lets the next of its own address in
        workers.runOne();
        assertEquals(List.of("a1"), ran);
        assertEquals(4, workers.queued.size());
        assertEquals(1, share.waiting(a));
        while (!workers.queued.isEmpty()) {
            workers.runOne();
        }
        assertEquals(List.of("a1", "a2", "a3", "b1", "a4", "a5"), ran);
        assertEquals(0, share.running(a));
        assertEquals(0, share.running(b));
        assertEquals(0, share.waiting(a));
    }

    @Test
    void workThatFailsStillGivesItsPlaceBack() throws Exception {
        Workers workers = new Workers();
        ClientShare share = new ClientShare(1, 4, workers);
        InetAddress a = address(1);
        List<String> ran = new ArrayList<>();
        share.submit(a, () -> {
            throw new IllegalStateException("broken");
        }, () -> {});
        share.submit(a, () -> ran.add("second"), () -> {});
        try {
            workers.runOne();
        } catch (IllegalStateException expected) {
            // The worker thread would have seen it; the place is given back all the same
        }
        assertEquals(1, workers.queued.size());
        workers.runOne();
        assertEquals(List.of("second"), ran);
        assertEquals(0, share.running(a));
    }

    @Test
    void whenTheWorkersRefuseTheConnectionIsTurnedAway() throws Exception {
        Workers workers = new Workers();
        ClientShare share = new ClientShare(1, 4, workers);
        InetAddress a = address(1);
        List<String> said = new ArrayList<>();
        share.submit(a, () -> said.add("first ran"), () -> said.add("first turned away"));
        share.submit(a, () -> said.add("second ran"), () -> said.add("second turned away"));
        share.submit(a, () -> said.add("third ran"), () -> said.add("third turned away"));
        // The server stops: the first finishes, and those still waiting are turned away, not left hanging
        workers.refusing = true;
        workers.runOne();
        assertEquals(List.of("first ran", "second turned away", "third turned away"), said);
        assertEquals(0, share.running(a));
        assertEquals(0, share.waiting(a));

        // Refused at once
        assertTrue(share.submit(a, () -> said.add("fourth ran"), () -> said.add("fourth turned away")));
        assertEquals("fourth turned away", said.get(said.size() - 1));
        assertEquals(0, share.running(a));
    }

}
