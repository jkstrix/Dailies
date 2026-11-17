package comedymonkey.dailies.moments;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

/**
 * Where and when something happened: real time, game time, place and (while a replay is being recorded)
 * the position inside that replay. A fresh one is captured every client tick, see {@link Moments#now()}.
 */
public record TimeRef(long realTime, long gameTime, long dayTime, @Nullable String dimension, @Nullable String biome,
                      double x, double y, double z, @Nullable String replayUuid, int replayTick) {

    public static final TimeRef EMPTY = new TimeRef(0, -1, -1, null, null, 0, 0, 0, null, -1);

    public TimeRef withRealTime(long realTime) {
        return new TimeRef(realTime, this.gameTime, this.dayTime, this.dimension, this.biome, this.x, this.y, this.z, this.replayUuid, this.replayTick);
    }

    /** Fields of a record that happens at a single moment */
    public void applyTo(JsonObject json) {
        json.addProperty("real_time", this.realTime);
        json.addProperty("real_time_iso", MJson.iso(this.realTime));
        json.addProperty("game_time", this.gameTime);
        json.addProperty("day_time", this.dayTime);
        json.addProperty("dimension", this.dimension);
        json.addProperty("biome", this.biome);
        if (this.replayUuid != null) {
            json.addProperty("replay_uuid", this.replayUuid);
            json.addProperty("replay_tick", this.replayTick);
        }
    }

    /** Fields for one end of a record with a duration, eg. start_real_time, start_game_time, start_replay_tick */
    public void applyTo(JsonObject json, String prefix) {
        json.addProperty(prefix + "real_time", this.realTime);
        json.addProperty(prefix + "real_time_iso", MJson.iso(this.realTime));
        json.addProperty(prefix + "game_time", this.gameTime);
        if (this.replayUuid != null) {
            json.addProperty(prefix + "replay_uuid", this.replayUuid);
            json.addProperty(prefix + "replay_tick", this.replayTick);
        }
    }

}
