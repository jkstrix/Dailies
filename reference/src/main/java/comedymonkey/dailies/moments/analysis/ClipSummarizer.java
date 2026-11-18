package comedymonkey.dailies.moments.analysis;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.moments.MJson;
import comedymonkey.dailies.moments.MomentsConfig;
import comedymonkey.dailies.moments.MomentsHttp;
import comedymonkey.dailies.moments.storage.MomentsSession;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Asks the configured Ollama model for a first-pass title and summary of clip candidates, one clip at a time on a
 * background thread. The heuristic text stays in place until (and unless) the model answers with usable JSON.
 */
public class ClipSummarizer {

    private static final int MAX_EVENTS_IN_PROMPT = 20;
    private static final int MAX_TRANSCRIPT_CHARS = 3000;
    private static final int MAX_TITLE_CHARS = 120;
    private static final int MAX_SUMMARY_CHARS = 600;

    private static final String SYSTEM_PROMPT = "You write titles and summaries for gameplay clips from a Minecraft multiplayer session. "
        + "Use only the events, players and voice chat transcript you are given. Do not invent events, names or dialogue. "
        + "Answer with a JSON object {\"title\": \"...\", \"summary\": \"...\"}: the title is at most 8 words, "
        + "the summary is one or two factual sentences.";

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Dailies-Moments-Ollama");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger pending = new AtomicInteger();
    private volatile String lastError = null;
    private volatile boolean unreachableLogged = false;

    public int pending() {
        return this.pending.get();
    }

    public String lastError() {
        return this.lastError;
    }

    /** Queue the clips (which must already be stored in the session, marked summary_pending) */
    public void summarize(MomentsSession session, List<String> clipIds) {
        for (String clipId : clipIds) {
            this.pending.incrementAndGet();
            this.executor.execute(() -> {
                try {
                    this.summarizeOne(session, clipId);
                } catch (Throwable t) {
                    Host.LOGGER.error("Moments: summarising clip {} failed unexpectedly", clipId, t);
                } finally {
                    this.pending.decrementAndGet();
                }
            });
        }
    }

    private void summarizeOne(MomentsSession session, String clipId) {
        JsonObject clip = null;
        for (JsonObject candidate : session.clips()) {
            if (clipId.equals(MJson.str(candidate, "id"))) {
                clip = candidate;
            }
        }
        if (clip == null || !MJson.bool(clip, "summary_pending")) {
            // Replaced by a newer clips_generate call in the meantime
            return;
        }

        String model = MomentsConfig.ollamaModel();
        boolean unreachable = false;
        String error;
        String title = null;
        String summary = null;
        try {
            JsonObject request = new JsonObject();
            request.addProperty("model", model);
            request.addProperty("stream", false);
            request.addProperty("format", "json");
            // Thinking models otherwise spend seconds on reasoning that is thrown away, and sometimes break the JSON format
            request.addProperty("think", false);
            JsonArray messages = new JsonArray();
            messages.add(message("system", SYSTEM_PROMPT));
            messages.add(message("user", buildPrompt(clip)));
            request.add("messages", messages);
            JsonObject options = new JsonObject();
            options.addProperty("temperature", 0.2);
            request.add("options", options);

            MomentsHttp.Result result = MomentsHttp.postJson(MomentsConfig.ollamaUrl() + "/api/chat", MJson.GSON.toJson(request), Duration.ofSeconds(120));
            if (!result.ok()) {
                String body = result.body() == null ? "" : result.body();
                error = "HTTP " + result.status() + " " + body.substring(0, Math.min(300, body.length()));
            } else {
                // Only message.content counts; an empty or non-JSON answer is a failure
                JsonObject response = JsonParser.parseString(result.body()).getAsJsonObject();
                String content = MJson.str(MJson.obj(response, "message"), "content", "").trim();
                JsonObject answer = JsonParser.parseString(content.isEmpty() ? "null" : content).getAsJsonObject();
                title = clean(MJson.str(answer, "title"), MAX_TITLE_CHARS);
                summary = clean(MJson.str(answer, "summary"), MAX_SUMMARY_CHARS);
                error = title == null || summary == null ? "Model answered without a title or summary" : null;
            }
        } catch (Exception e) {
            error = e.toString();
            unreachable = MomentsHttp.isConnectFailure(e);
        }

        this.lastError = error;
        String finalError = error;
        String finalTitle = title;
        String finalSummary = summary;
        session.updateClip(clipId, json -> {
            if (!MJson.bool(json, "summary_pending")) {
                return;
            }
            json.addProperty("summary_pending", false);
            if (finalError == null) {
                json.addProperty("title", finalTitle);
                json.addProperty("summary", finalSummary);
                json.addProperty("summary_source", "ollama");
                json.addProperty("summary_model", model);
                json.remove("summary_error");
            } else {
                json.addProperty("summary_error", finalError);
            }
        });
        if (error == null) {
            this.unreachableLogged = false;
        } else if (!unreachable) {
            Host.LOGGER.warn("Moments: Ollama summary for clip {} failed, keeping the built-in text: {}", clipId, error);
        } else if (!this.unreachableLogged) {
            // Once, not once per clip: a server that is switched off is a normal state
            this.unreachableLogged = true;
            Host.LOGGER.info("Moments: the Ollama server at {} cannot be reached, clips keep their built-in titles and summaries", MomentsConfig.ollamaUrl());
        }
    }

    private static String clean(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        text = text.replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) {
            return null;
        }
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }

    private static JsonObject message(String role, String content) {
        JsonObject json = new JsonObject();
        json.addProperty("role", role);
        json.addProperty("content", content);
        return json;
    }

    /** Short and factual: who was there, what was logged, what was said */
    static String buildPrompt(JsonObject clip) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Clip length: ").append(Math.round(MJson.dbl(clip, "duration_seconds", 0))).append(" seconds\n");

        StringBuilder players = new StringBuilder();
        for (JsonElement element : MJson.arr(clip, "players")) {
            if (!players.isEmpty()) players.append(", ");
            players.append(element.getAsString());
        }
        prompt.append("Players: ").append(players.isEmpty() ? "unknown" : players).append("\n");

        prompt.append("Events (seconds from the start of the clip):\n");
        int events = 0;
        for (JsonElement element : MJson.arr(clip, "events")) {
            JsonObject event = element.getAsJsonObject();
            if (events++ >= MAX_EVENTS_IN_PROMPT) {
                prompt.append("- ...\n");
                break;
            }
            prompt.append("- ").append(Math.round(MJson.dbl(event, "offset_seconds", 0))).append("s: ").append(MJson.str(event, "description", "")).append("\n");
        }
        if (events == 0) {
            prompt.append("- none\n");
        }

        prompt.append("Voice chat transcript:\n");
        int transcriptStart = prompt.length();
        for (JsonElement element : MJson.arr(clip, "transcript")) {
            JsonObject line = element.getAsJsonObject();
            String text = MJson.str(line, "text", "");
            if (text.isBlank()) {
                continue;
            }
            prompt.append(MJson.str(line, "speaker", "Unknown")).append(": ").append(text).append("\n");
            if (prompt.length() - transcriptStart > MAX_TRANSCRIPT_CHARS) {
                prompt.setLength(transcriptStart + MAX_TRANSCRIPT_CHARS);
                prompt.append("...\n");
                break;
            }
        }
        if (prompt.length() == transcriptStart) {
            prompt.append("(nothing was said)\n");
        }
        return prompt.toString();
    }

}
