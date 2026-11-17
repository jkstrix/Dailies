package comedymonkey.dailies.edit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

/**
 * Another recording of the same stretch of play that a scene can show (PLAY-19): usually a friend's, made on the same
 * server at the same time. It is named by its unique id, so it is found again however the file is renamed or moved;
 * the name and the recorder are only for showing it. Ticks are never stored here: the core lines the two recordings up
 * by their time index whenever it needs to.
 */
public record LinkedRecording(String recordingId, String name, String recorder) {

    public LinkedRecording {
        if (recordingId == null || recordingId.isBlank()) {
            throw new IllegalArgumentException("'recording_id' must be a string that is not empty");
        }
        recordingId = recordingId.trim();
        name = name == null ? "" : name;
        recorder = recorder == null ? "" : recorder;
    }

    /** What a person reads: the recorder's name, or the recording's when there is none */
    public String label() {
        return !this.recorder.isBlank() ? this.recorder : !this.name.isBlank() ? this.name : this.recordingId;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("recording_id", this.recordingId);
        json.addProperty("name", this.name);
        json.addProperty("recorder", this.recorder);
        return json;
    }

    /** Null for anything that is not a linked recording, so that one bad entry does not cost the scene */
    public static @Nullable LinkedRecording fromJson(@Nullable JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject json = element.getAsJsonObject();
        try {
            return new LinkedRecording(text(json, "recording_id"), text(json, "name"), text(json, "recorder"));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String text(JsonObject json, String field) {
        JsonElement value = json.get(field);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

}
