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
import java.util.ArrayList;
import java.util.List;
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
        if (session == null || comedymonkey.dailies.moments.storage.MomentsShare.theirs(session)) {
            return true; // a friend's session is never sent to this player's transcription server
        }
        if (!MomentsConfig.whisperConfigured()) {
            return true; // the server was removed from the config while this was waiting
        }
        JsonObject record = session.get(MomentsSession.Kind.DIALOGUE, job.dialogueId());
        if (record == null || "done".equals(MJson.str(record, "status")) || "empty".equals(MJson.str(record, "status"))) {
            return true;
        }

        // Specification MOM-36: someone put on the list after they spoke is not sent anywhere either
        JsonObject speaker = MJson.obj(record, "speaker");
        if (MomentsConfig.isVoiceExcluded(MJson.str(speaker, "uuid"), MJson.str(speaker, "name"))) {
            return true;
        }

        String audioFile = MJson.str(record, "audio_file");
        if (audioFile == null) {
            this.fail(session, job, "Record has no audio file", true);
            return true;
        }
        Path path = audioPath(session.dir, audioFile);
        if (path == null) {
            // The record may have come from someone else's shared session: nothing outside this session's audio is sent
            this.fail(session, job, "The audio file is not one of this session's own: " + audioFile, true);
            return true;
        }
        // The WAV is written by the storage thread, make sure it got there
        this.storage.flush();
        byte[] audio;
        try {
            audio = Files.readAllBytes(path);
        } catch (Exception e) {
            this.fail(session, job, "Audio file is missing: " + audioFile, true);
            return true;
        }

        String model = MomentsConfig.whisperModel();
        String language = MomentsConfig.whisperLanguage();
        List<Map.Entry<String, String>> fields = new ArrayList<>();
        fields.add(Map.entry("model", model));
        fields.add(Map.entry("response_format", "verbose_json"));
        // When each word was said, for captions that follow the speech (EXP-11, EXP-15); the sentences as well, which
        // the filter below works on. A server that knows neither leaves the words out and they are spread evenly later
        fields.add(Map.entry("timestamp_granularities[]", "segment"));
        fields.add(Map.entry("timestamp_granularities[]", "word"));
        // Lets the server skip stretches without voice instead of inventing words for them
        fields.add(Map.entry("vad_filter", "true"));
        if (!language.isEmpty()) {
            fields.add(Map.entry("language", language));
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
        try {
            labelNonSpeech(session, job.dialogueId(), path);
        } catch (Exception e) {
            Host.LOGGER.warn("Moments: could not listen to the non-speech of {}: {}", audioFile, e.toString());
        }
        return true;
    }

    /** The only audio a dialogue record can name: a WAV in the session's audio folder, as storeSegments writes them */
    private static final java.util.regex.Pattern AUDIO_FILE = java.util.regex.Pattern.compile("audio/[A-Za-z0-9_-]{1,120}\\.wav");

    /**
     * The audio file a dialogue record names, inside its session's folder, or null when the name is anything but one
     * of the session's own WAVs (an absolute path, a share on another computer, a way out with "..").
     */
    static @org.jetbrains.annotations.Nullable Path audioPath(Path sessionDir, String audioFile) {
        String name = audioFile.replace('\\', '/');
        if (!AUDIO_FILE.matcher(name).matches()) {
            return null;
        }
        Path folder = sessionDir.toAbsolutePath().normalize();
        Path file = folder.resolve(name).normalize();
        return file.startsWith(folder.resolve("audio")) ? file : null;
    }

    /** The longest stretch of a non-speech segment the sound classifier hears; a burst is over well within it */
    private static final double MAX_LISTEN_SECONDS = 10.0;

    /**
     * Plan 2.9: the segments Whisper heard as no words are offered to the sound classifier as well, and keep what it
     * made of them as "sound" (laughter, cheering, screaming or shouting). Runs on this worker's own thread
     */
    private static void labelNonSpeech(MomentsSession session, String dialogueId, Path audio) throws java.io.IOException {
        JsonObject record = session.get(MomentsSession.Kind.DIALOGUE, dialogueId);
        JsonArray segments = MJson.arr(record, "segments");
        Map<Integer, String> labels = new java.util.HashMap<>();
        WavFiles.Pcm pcm = null;
        for (int i = 0; i < segments.size(); i++) {
            if (!segments.get(i).isJsonObject() || !MJson.bool(segments.get(i).getAsJsonObject(), "non_speech")) {
                continue;
            }
            JsonObject segment = segments.get(i).getAsJsonObject();
            if (pcm == null) {
                pcm = WavFiles.read(audio);
            }
            int rate = pcm.sampleRate();
            double start = MJson.dbl(segment, "start", 0);
            double end = Math.min(MJson.dbl(segment, "end", 0), start + MAX_LISTEN_SECONDS);
            int from = (int) Math.max(0, Math.min(pcm.samples().length, Math.round(start * rate)));
            int to = (int) Math.max(from, Math.min(pcm.samples().length, Math.round(end * rate)));
            if (to - from < rate / 4) {
                continue;
            }
            String label = comedymonkey.dailies.moments.Moments.classifySound(java.util.Arrays.copyOfRange(pcm.samples(), from, to), rate);
            if (label != null) {
                labels.put(i, label);
            }
        }
        if (labels.isEmpty()) {
            return;
        }
        session.update(MomentsSession.Kind.DIALOGUE, dialogueId, json -> {
            JsonArray stored = MJson.arr(json, "segments");
            labels.forEach((index, label) -> {
                if (index < stored.size() && stored.get(index).isJsonObject()) {
                    stored.get(index).getAsJsonObject().addProperty("sound", label);
                }
            });
        });
    }

    /** Filters the Whisper segments and fills in the text, language and confidence of a dialogue record */
    static void applyResponse(JsonObject record, JsonObject response, String model) {
        JsonArray kept = new JsonArray();
        JsonArray all = new JsonArray();
        StringBuilder text = new StringBuilder();
        double logprobSum = 0;
        double noSpeechSum = 0;
        int dropped = 0;
        int nonSpeech = 0;

        JsonArray segments = MJson.arr(response, "segments");
        // Word timings come either inside each segment or as one list beside them, as OpenAI's API gives them
        JsonArray looseWords = MJson.arr(response, "words");
        boolean[] wordTaken = new boolean[looseWords.size()];
        for (JsonElement element : segments) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject segment = element.getAsJsonObject();
            double avgLogprob = MJson.dbl(segment, "avg_logprob", 0);
            double noSpeechProb = MJson.dbl(segment, "no_speech_prob", 0);
            String segmentText = MJson.str(segment, "text", "").trim();
            boolean notSpeech = noSpeechProb >= MAX_NO_SPEECH_PROB;
            if (!notSpeech && (segmentText.isEmpty() || avgLogprob <= MIN_AVG_LOGPROB)) {
                dropped += 1;
                continue;
            }
            JsonObject json = new JsonObject();
            json.addProperty("start", MJson.dbl(segment, "start", 0));
            json.addProperty("end", MJson.dbl(segment, "end", 0));
            json.addProperty("text", segmentText);
            json.addProperty("avg_logprob", avgLogprob);
            json.addProperty("no_speech_prob", noSpeechProb);
            if (notSpeech) {
                // Laughter, a shout, a gasp: not words, so it stays out of the text, but when it happened is worth keeping
                json.addProperty("non_speech", true);
                all.add(json);
                nonSpeech += 1;
                continue;
            }
            JsonArray words = segmentWords(segment, looseWords, wordTaken);
            if (!words.isEmpty()) {
                json.add("words", words);
            }
            all.add(json);
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
        record.add("segments", all);
        // A segment that was all laughter is still "empty" of words, and says so
        record.addProperty("non_speech", text.isEmpty() && nonSpeech > 0);
        record.addProperty("whisper_model", model);
        record.addProperty("word_count", MJson.countWords(text.toString()));
        record.remove("error");
        JsonObject metadata = MJson.meta(record);
        metadata.addProperty("whisper_segments", segments.size());
        metadata.addProperty("dropped_segments", dropped);
        metadata.addProperty("non_speech_segments", nonSpeech);
        metadata.addProperty("raw_text", MJson.str(response, "text", "").trim());
        metadata.addProperty("transcribed_at", System.currentTimeMillis());
    }

    /**
     * The words of one kept segment, each with its start and end in seconds of the audio: the segment's own list when
     * it has one, else the words of the list beside the segments whose middle falls inside it, each used once. Empty
     * when the server gave no word timings; the transcript then spreads the words evenly over the segment.
     */
    static JsonArray segmentWords(JsonObject segment, JsonArray looseWords, boolean[] taken) {
        JsonArray words = new JsonArray();
        JsonArray own = MJson.arr(segment, "words");
        if (!own.isEmpty()) {
            for (JsonElement element : own) {
                addWord(words, element);
            }
            return words;
        }
        double start = MJson.dbl(segment, "start", 0);
        double end = MJson.dbl(segment, "end", start);
        for (int i = 0; i < looseWords.size(); i++) {
            if (taken[i] || !looseWords.get(i).isJsonObject()) {
                continue;
            }
            JsonObject word = looseWords.get(i).getAsJsonObject();
            double middle = (MJson.dbl(word, "start", 0) + MJson.dbl(word, "end", 0)) / 2.0;
            if (middle >= start - 0.05 && middle <= end + 0.05) {
                taken[i] = true;
                addWord(words, word);
            }
        }
        return words;
    }

    private static void addWord(JsonArray words, JsonElement element) {
        if (!element.isJsonObject()) {
            return;
        }
        JsonObject given = element.getAsJsonObject();
        String said = MJson.str(given, "word", MJson.str(given, "text", "")).trim();
        if (said.isEmpty()) {
            return;
        }
        double start = MJson.dbl(given, "start", 0);
        JsonObject word = new JsonObject();
        word.addProperty("start", MJson.round(start));
        word.addProperty("end", MJson.round(Math.max(start, MJson.dbl(given, "end", start))));
        word.addProperty("word", said);
        words.add(word);
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
