package comedymonkey.dailies.moments.track;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import comedymonkey.dailies.moments.MJson;
import comedymonkey.dailies.moments.Moments;
import comedymonkey.dailies.moments.MomentsConfig;
import comedymonkey.dailies.moments.TimeRef;
import comedymonkey.dailies.moments.storage.FirstsStore;
import comedymonkey.dailies.moments.storage.MomentsSession;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Feeds the positions of the players this client can see into {@link ProximityLogic} once per second and writes
 * the resulting meetings and group records to interactions.jsonl.
 */
public class InteractionTracker {

    private final MomentsSession session;
    private final FirstsStore firsts;
    private final ProximityLogic logic = new ProximityLogic();
    private final Map<String, String> names = new HashMap<>();

    public InteractionTracker(MomentsSession session, FirstsStore firsts) {
        this.session = session;
        this.firsts = firsts;
    }

    /** Client thread, once per second */
    public void second(ClientLevel level) {
        List<ProximityLogic.PlayerPos> positions = new ArrayList<>();
        for (AbstractClientPlayer player : level.players()) {
            if (player.isSpectator() || player.isRemoved()) {
                continue;
            }
            String uuid = player.getUUID().toString();
            synchronized (this.names) {
                this.names.put(uuid, player.getGameProfile().getName());
            }
            positions.add(new ProximityLogic.PlayerPos(uuid, player.getX(), player.getY(), player.getZ()));
        }

        List<ProximityLogic.Interaction> finished = this.logic.update(System.currentTimeMillis(), positions, Moments.now(),
            MomentsConfig.proximityRadius(), MomentsConfig.proximityMinSeconds() * 1000L);
        for (ProximityLogic.Interaction interaction : finished) {
            this.session.add(MomentsSession.Kind.INTERACTIONS, this.toJson(interaction, true));
        }
    }

    /** Meetings and groups that are still going on, as unsaved records with ongoing = true. Any thread */
    public List<JsonObject> ongoing() {
        List<JsonObject> list = new ArrayList<>();
        for (ProximityLogic.Interaction interaction : this.logic.snapshotOpen()) {
            JsonObject json = this.toJson(interaction, false);
            json.addProperty("id", "ongoing-" + interaction.type + "-" + interaction.start);
            list.add(json);
        }
        return list;
    }

    /** Session end: what is still open is written out, flagged as cut off rather than ended */
    public void finish() {
        for (ProximityLogic.Interaction interaction : this.logic.snapshotOpen()) {
            this.session.add(MomentsSession.Kind.INTERACTIONS, this.toJson(interaction, true));
        }
        this.logic.clear();
    }

    private JsonArray players(List<String> uuids) {
        JsonArray array = new JsonArray();
        synchronized (this.names) {
            for (String uuid : uuids) {
                array.add(MJson.player(uuid, this.names.getOrDefault(uuid, uuid)));
            }
        }
        return array;
    }

    private JsonObject toJson(ProximityLogic.Interaction interaction, boolean markFirsts) {
        TimeRef start = interaction.startRef instanceof TimeRef ref ? ref.withRealTime(interaction.start) : TimeRef.EMPTY.withRealTime(interaction.start);
        TimeRef end = interaction.endRef instanceof TimeRef ref ? ref.withRealTime(interaction.end) : TimeRef.EMPTY.withRealTime(interaction.end);

        JsonObject json = new JsonObject();
        json.addProperty("type", interaction.type);
        json.add("players", this.players(interaction.players));
        json.addProperty("real_time", interaction.start);
        start.applyTo(json, "start_");
        end.applyTo(json, "end_");
        json.addProperty("duration_seconds", MJson.round(interaction.durationSeconds()));
        json.addProperty("ongoing", interaction.ongoing);
        json.add("location", MJson.position(interaction.x, interaction.y, interaction.z));
        json.addProperty("dimension", end.dimension());
        json.addProperty("biome", end.biome());

        if (interaction.type.equals("meeting")) {
            String pair = interaction.players.get(0) + "|" + interaction.players.get(1);
            json.addProperty("first_meeting", markFirsts ? this.firsts.mark("pairs", pair) : !this.firsts.contains("pairs", pair));
        }
        if (interaction.groupId != null) {
            json.addProperty("group_id", interaction.groupId);
        }
        switch (interaction.type) {
            case "group_changed" -> {
                json.add("joined", this.players(interaction.joined));
                json.add("left", this.players(interaction.left));
            }
            case "group_dissolved" -> json.add("final_members", this.players(interaction.left));
            case "group_move" -> {
                json.addProperty("from_group_id", interaction.fromGroupId);
                json.add("from_members", this.players(interaction.fromMembers));
                json.add("to_members", this.players(interaction.joined));
            }
            default -> {}
        }
        return json;
    }

}
