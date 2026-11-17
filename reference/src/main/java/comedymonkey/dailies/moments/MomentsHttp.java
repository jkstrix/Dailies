package comedymonkey.dailies.moments;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Outbound HTTP for the moments system. Only ever talks to the configured Whisper and Ollama servers.
 */
public final class MomentsHttp {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    private MomentsHttp() {
    }

    public record Result(int status, String body) {
        public boolean ok() {
            return this.status >= 200 && this.status < 300;
        }
    }

    /** POST multipart/form-data with text fields and one file */
    public static Result postMultipart(String url, Map<String, String> fields, String fileField, String fileName, String fileContentType,
            byte[] fileContent, Duration timeout) throws IOException, InterruptedException {
        String boundary = "----dailies" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream(fileContent.length + 1024);
        for (Map.Entry<String, String> field : fields.entrySet()) {
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field.getKey() + "\"\r\n\r\n"
                + field.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + fileField + "\"; filename=\"" + fileName + "\"\r\n"
            + "Content-Type: " + fileContentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(fileContent);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(timeout)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
            .build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Result(response.statusCode(), response.body());
    }

    public static Result postJson(String url, String json, Duration timeout) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Result(response.statusCode(), response.body());
    }

    /** Whether a GET to the URL gets any successful HTTP answer within the timeout. Never throws */
    public static CompletableFuture<Boolean> reachable(String url, Duration timeout) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build();
            return CLIENT.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .completeOnTimeout(null, timeout.toMillis() + 500, java.util.concurrent.TimeUnit.MILLISECONDS)
                .handle((response, error) -> error == null && response != null && response.statusCode() < 500);
        } catch (Exception e) {
            return CompletableFuture.completedFuture(false);
        }
    }

    public static CompletableFuture<Boolean> whisperReachable() {
        if (!MomentsConfig.whisperConfigured()) {
            return CompletableFuture.completedFuture(false);
        }
        return reachable(MomentsConfig.whisperUrl() + "/v1/models", Duration.ofSeconds(2));
    }

    public static CompletableFuture<Boolean> ollamaReachable() {
        if (!MomentsConfig.ollamaConfigured()) {
            return CompletableFuture.completedFuture(false);
        }
        return reachable(MomentsConfig.ollamaUrl() + "/api/tags", Duration.ofSeconds(2));
    }

    /** True for failures that mean the server could not be reached at all, as opposed to it answering with an error */
    public static boolean isConnectFailure(Throwable error) {
        return error instanceof java.net.ConnectException || error instanceof java.net.http.HttpConnectTimeoutException
            || error instanceof java.nio.channels.UnresolvedAddressException || error instanceof java.net.UnknownHostException
            || error instanceof java.net.NoRouteToHostException || error instanceof IllegalArgumentException;
    }

}
