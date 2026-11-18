package comedymonkey.dailies.ui;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Test button of an address: a server that is there, one that is not, one that never answers */
class ProbeTest {

    @Test
    void aServerThatAnswersIsReportedWithItsStatus() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        try {
            Probe.Result result = Probe.ask("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", 3000).get(10, TimeUnit.SECONDS);
            // A server that says "not found" is still a server that is there
            assertTrue(result.answered());
            assertEquals(404, result.status());
            assertEquals("", result.problem());
            Probe.Result knocked = Probe.knock("127.0.0.1", server.getAddress().getPort(), 3000).get(10, TimeUnit.SECONDS);
            assertTrue(knocked.answered());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void nothingListeningIsReportedAsSuch() throws Exception {
        // A port held for the whole test by a socket that is bound and never listens: nothing can answer on it, and
        // nothing else can take it in the meantime, as it could a port found free and let go of first
        try (Socket held = new Socket()) {
            held.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            int free = held.getLocalPort();
            Probe.Result result = Probe.ask("http://127.0.0.1:" + free + "/", 3000).get(10, TimeUnit.SECONDS);
            assertFalse(result.answered());
            assertEquals("refused", result.problem());
            assertFalse(Probe.knock("127.0.0.1", free, 3000).get(10, TimeUnit.SECONDS).answered());
        }
    }

    @Test
    void aServerThatNeverAnswersIsGivenUpOn() throws Exception {
        // Takes the connection and says nothing
        try (ServerSocket silent = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            long began = System.nanoTime();
            Probe.Result result = Probe.ask("http://127.0.0.1:" + silent.getLocalPort() + "/", 400).get(10, TimeUnit.SECONDS);
            assertFalse(result.answered());
            assertEquals("timeout", result.problem());
            assertTrue((System.nanoTime() - began) / 1_000_000L < 5000, "the short wait was not kept to");
        }
    }

    @Test
    void anAddressThatCannotBeReadIsAResultAndNotAnException() throws Exception {
        Probe.Result result = Probe.ask("http://exa mple/", 500).get(10, TimeUnit.SECONDS);
        assertFalse(result.answered());
        assertFalse(result.problem().isEmpty());
    }

}
