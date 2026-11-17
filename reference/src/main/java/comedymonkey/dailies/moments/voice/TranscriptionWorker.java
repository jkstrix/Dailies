package comedymonkey.dailies.moments.voice;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.moments.MJson;
import comedymonkey.dailies.moments.MomentsConfig;
import comedymonkey.dailies.moments.MomentsHttp;
import comedymonkey.dailies.moments.storage.MomentsSession;
import comedymonkey.dailies.moments.storage.MomentsStorage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * Sends voice segments to the Whisper server, one at a time on a background thread, and writes the result back into
 * the dialogue record. A server that cannot be reached leaves the records pending and is retried with a growing
 * delay; a server that answers with an error counts as a failed attempt.
 */
public class TranscriptionWorker {

    /** Whisper segments at or above this no_speech_prob are dropped (noise, breathing, laughter) */
    public static final double MAX_NO_SPEECH_PROB = 0.6;
    /** Whisper segments at or below this avg_logprob are dropped (the model was guessing) */
    public static final double MIN_AVG_LOGPROB = -1.0;

    private static final int MAX_ATTEMPTS = 3;
    private static final long[] UNREACHABLE_BACKOFF_MILLIS = {5_000, 10_000, 20_000, 40_000, 80_000, 120_000};

    private record Job(String sessionId, String dialogueId) {}

    private final MomentsStorage storage;
    private final LinkedBlockingDeque<Job> queue = new LinkedBlockingDeque<>();
    private final Set<Job> queued = ConcurrentHashMap.newKeySet();
    private Thread thread = null;
    private volatile int unreachableCount = 0;
    private volatile String lastError = null;
    // Set by fail() when the job should go to the back of the queue. Only touched by the worker thread
    private boolean retryLater = false;

    public TranscriptionWorker(MomentsStorage storage) {
        this.storage = storage;
    }

    public synchronized void enqueue(String sessionId, String dialogueId) {
        if (!MomentsConfig.whisperConfigured()) {
            return; // optional: the segment stays pending and can be queued once a server is configured
        }
        Job job = new Job(sessionId, dialogueId);
        if (!this.queued.add(job)) {
            return;
        }
        this.queue.addLast(job);
        if (this.thread == null) {
            this.thread = new Thread(this::run, "Dailies-Moments-Whisper");
            this.thread.setDaemon(true);
            this.thread.start();
        }
    }

    public int queueSize() {
        return this.queued.size();
    }

    public String lastError() {
        return this.lastError;
    }

    private void run() {
        while (true) {
            Job job;
            try {
                job = this.queue.takeFirst();
            } catch (InterruptedException e) {
                return;
            }

            boolean unreachable = false;
            try {
                unreachable = !this.process(job);
            } catch (Throwable t) {
                Host.LOGGER.error("Moments: transcription of {} failed unexpectedly", job, t);
            }

            if (unreachable) {
                // Keep the job (the record stays pending) and give the server some time
                this.queue.addFirst(job);
                long delay = UNREACHABLE_BACKOFF_MILLIS[Math.min(this.unreachableCount, UNREACHABLE_BACKOFF_MILLIS.length - 1)];
                this.unreachableCount += 1;
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    return;
                }
            } else if (this.retryLater) {
                this.retryLater = false;
                this.queue.addLast(job);
            } else {
                this.unreachableCount = 0;
                this.queued.remove(job);
            }
        }
    }

    /** @return false when the Whisper server could not be reached, in which case the job should be retried later */
    private boolean process(Job job) throws InterruptedException {
        MomentsSession session = this.storage.getSession(job.sessionId());
        if (session == null) {
            return true;
        }
        if (!MomentsConfig.whisperConfigured()) {
            return true; // the server was removed from the config while this was waiting
        }
        JsonObject record = session.get(MomentsSession.Kind.DIALOGUE, job.dialogueId());
        if (record == null || "done".equals(MJson.str(record, "status")) || "empty".equals(MJson.str(record, "status"))) {
            return true;
        }

        String audioFile = MJson.str(record, "audio_file");
        if (audioFile == null) {
            this.fail(session, job, "Record has no audio file", true);
            return true;
        }
        // The WAV is written by the storage thread, make sure it got there
        this.storage.flush();
        Path path = session.dir.resolve(audioFile);
        byte[] audio;
        try {
            audio = Files.readAllBytes(path);
        } catch (Exception e) {
            this.fail(session, job, "Audio file is missing: " + audioFile, true);
            return true;
        }

        String model = MomentsConfig.whisperModel();
        String language = MomentsConfig.whisperLanguage();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("model", model);
        fields.put("response_format", "verbose_json");
        // Lets the server skip stretches without voice instead of inventing words for them
        fields.put("vad_filter", "true");
        if (!language.isEmpty()) {
            fields.put("language", language);
        }

        MomentsHttp.Result result;
        try {
            result = MomentsHttp.postMultipart(MomentsConfig.whisperUrl() + "/v1/audio/transcriptions", fields, "file",
                path.getFileName().toString(), "audio/wav", audio, Duration.ofSeconds(120));
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            this.lastError = e.toString();
            if (MomentsHttp.isConnectFailure(e)) {
                return false;
            }
            this.fail(session, job, e.toString(), false);
            return true;
        }

        if (!result.ok()) {
            String body = result.body() == null ? "" : result.body();
            this.fail(session, job, "HTTP " + result.status() + " " + body.substring(0, Math.min(300, body.length())), false);
            return true;
        }

        JsonObject response;
        try {
            response = JsonParser.parseString(result.body()).getAsJsonObject();
        } catch (Exception e) {
            this.fail(session, job, "Whisper did not answer with JSON", false);
            return true;
        }

        this.lastError = null;
        session.update(MomentsSession.Kind.DIALOGUE, job.dialogueId(), json -> applyResponse(json, response, model));
        return true;
    }

    /** Filters the Whisper segments and fills in the text, language and confidence of a dialogue record */
    static void applyResponse(JsonObject record, JsonObject response, String model) {
        JsonArray kept = new JsonArray();
        StringBuilder text = new StringBuilder();
        double logprobSum = 0;
        double noSpeechSum = 0;
        int dropped = 0;

        JsonArray segments = MJson.arr(response, "segments");
        for (JsonElement element : segments) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject segment = element.getAsJsonObject();
            double avgLogprob = MJson.dbl(segment, "avg_logprob", 0);
            double noSpeechProb = MJson.dbl(segment, "no_speech_prob", 0);
            String segmentText = MJson.str(segment, "text", "").trim();
            if (segmentText.isEmpty() || noSpeechProb >= MAX_NO_SPEECH_PROB || avgLogprob <= MIN_AVG_LOGPROB) {
                dropped += 1;
                continue;
            }
            JsonObject json = new JsonObject();
            json.addProperty("start", MJson.dbl(segment, "start", 0));
            json.addProperty("end", MJson.dbl(segment, "end", 0));
            json.addProperty("text", segmentText);
            json.addProperty("avg_logprob", avgLogprob);
            json.addProperty("no_speech_prob", noSpeechProb);
            kept.add(json);
            if (!text.isEmpty()) text.append(' ');
            text.append(segmentText);
            logprobSum += avgLogprob;
            noSpeechSum += noSpeechProb;
        }

        // A server that returns no segment list at all: trust its text
        if (segments.isEmpty()) {
            text.append(MJson.str(response, "text", "").trim());
        }

        record.addProperty("status", text.isEmpty() ? "empty" : "done");
        record.addProperty("text", text.toString());
        record.addProperty("language", MJson.str(response, "language"));
        if (!kept.isEmpty()) {
            record.addProperty("avg_logprob", MJson.round(logprobSum / kept.size()));
            record.addProperty("no_speech_prob", MJson.round(noSpeechSum / kept.size()));
        }
        record.add("segments", kept);
        record.addProperty("whisper_model", model);
        record.addProperty("word_count", MJson.countWords(text.toString()));
        record.remove("error");
        JsonObject metadata = MJson.meta(record);
        metadata.addProperty("whisper_segments", segments.size());
        metadata.addProperty("dropped_segments", dropped);
        metadata.addProperty("raw_text", MJson.str(response, "text", "").trim());
        metadata.addProperty("transcribed_at", System.currentTimeMillis());
    }

    private void fail(MomentsSession session, Job job, String error, boolean permanent) {
        this.lastError = error;
        JsonObject updated = session.update(MomentsSession.Kind.DIALOGUE, job.dialogueId(), json -> {
            int attempts = (int) MJson.lng(json, "attempts", 0) + 1;
            json.addProperty("attempts", attempts);
            json.addProperty("error", error);
            if (permanent || attempts >= MAX_ATTEMPTS) {
                json.addProperty("status", "failed");
            }
        });
        if (updated != null && "pending".equals(MJson.str(updated, "status"))) {
            // Try again after the other queued segments, with a little delay
            try {
                Thread.sleep(2000L * MJson.lng(updated, "attempts", 1));
            } catch (InterruptedException ignored) {
            }
            this.retryLater = true;
        }
        Host.LOGGER.warn("Moments: transcription of {} in session {} failed: {}", job.dialogueId(), job.sessionId(), error);
    }

}
