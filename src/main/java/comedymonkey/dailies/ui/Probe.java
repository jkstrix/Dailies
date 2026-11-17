package comedymonkey.dailies.ui;

import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.util.concurrent.CompletableFuture;

/**
 * Asks a server whether it is there: one request, a short wait, and the answer or the reason there was none. Used by
 * the Test buttons of the settings screen, and only ever when a person presses one. Plain Java; never run on the
 * client thread.
 */
final class Probe {

    /**
     * @param answered whether anything answered at all; a server that answers "not found" is still there
     * @param status   the HTTP status, or 0 when none was given
     * @param millis   how long the answer took
     * @param problem  why there was no answer: "timeout", "unknown_host", "refused" or "other"; empty when there was one
     * @param detail   what the system said, for a problem of the last kind
     */
    record Result(boolean answered, int status, long millis, String problem, String detail) {
    }

    private Probe() {
    }

    /** Asks on a thread of its own and hands back the result when it is known. Never fails: a failure is a result */
    static CompletableFuture<Result> ask(String url, int timeoutMillis) {
        CompletableFuture<Result> answer = new CompletableFuture<>();
        Thread thread = new Thread(() -> answer.complete(askNow(url, timeoutMillis)), "Dailies-Address-Test");
        thread.setDaemon(true);
        thread.start();
        return answer;
    }

    /** Whether something is listening at a machine's port, for an address that is not a URL */
    static CompletableFuture<Result> knock(String host, int port, int timeoutMillis) {
        CompletableFuture<Result> answer = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            long began = System.nanoTime();
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), timeoutMillis);
                answer.complete(new Result(true, 0, (System.nanoTime() - began) / 1_000_000L, "", ""));
            } catch (Exception e) {
                answer.complete(new Result(false, 0, (System.nanoTime() - began) / 1_000_000L, reason(e), detail(e)));
            }
        }, "Dailies-Address-Test");
        thread.setDaemon(true);
        thread.start();
        return answer;
    }

    static Result askNow(String url, int timeoutMillis) {
        long began = System.nanoTime();
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(url.trim()).toURL().openConnection();
            connection.setConnectTimeout(timeoutMillis);
            connection.setReadTimeout(timeoutMillis);
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", "Dailies");
            int status = connection.getResponseCode();
            return new Result(true, status, (System.nanoTime() - began) / 1_000_000L, "", "");
        } catch (Exception e) {
            return new Result(false, 0, (System.nanoTime() - began) / 1_000_000L, reason(e), detail(e));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String reason(Exception e) {
        if (e instanceof java.net.SocketTimeoutException) {
            return "timeout";
        }
        if (e instanceof java.net.UnknownHostException) {
            return "unknown_host";
        }
        if (e instanceof java.net.ConnectException) {
            return "refused";
        }
        return "other";
    }

    private static String detail(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

}
