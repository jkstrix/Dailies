package comedymonkey.dailies.moments.track;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import comedymonkey.dailies.moments.MJson;
import comedymonkey.dailies.moments.MomentsConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Boss encounters and defeats. A boss is either a boss bar on screen or a nearby "notable" mob: one in the c:bosses
 * entity tag, in the configured list, or with enough max health. A defeat is that mob dying while it is being tracked.
 */
class BossTracker {

    private static final double ENCOUNTER_RANGE = 64.0;
    private static final double PARTICIPANT_RANGE = 48.0;
    /** The same boss name is not announced again within this long (boss bar and entity describe the same fight) */
    private static final long SAME_BOSS_MILLIS = 60_000;

    private record Tracked(LivingEntity entity, String type, String name) {}

    private static class Bar {
        String name;
        float progress;
    }

    private final GameplayTracker tracker;
    private final Map<Integer, Tracked> tracked = new HashMap<>();
    private final Map<UUID, Bar> bars = new HashMap<>();
    private final Map<String, Long> encounters = new HashMap<>();
    private final Map<String, Long> defeats = new HashMap<>();

    BossTracker(GameplayTracker tracker) {
        this.tracker = tracker;
    }

    boolean isNotable(LivingEntity entity) {
        if (entity instanceof Player) {
            return false;
        }
        return entity.typeHolder().is(Tags.EntityTypes.BOSSES)
            || MomentsConfig.notableEntities().contains(GameplayTracker.id(entity.getType()))
            || entity.getMaxHealth() >= MomentsConfig.bossHealthThreshold();
    }

    private static boolean recently(Map<String, Long> map, String name) {
        Long time = map.get(name);
        return time != null && System.currentTimeMillis() - time < SAME_BOSS_MILLIS;
    }

    private static JsonArray participants(ClientLevel level, Vec3 around) {
        JsonArray array = new JsonArray();
        for (AbstractClientPlayer player : level.players()) {
            if (player.position().distanceToSqr(around) <= PARTICIPANT_RANGE * PARTICIPANT_RANGE) {
                array.add(MJson.player(player.getUUID().toString(), player.getGameProfile().name()));
            }
        }
        return array;
    }

    /** Every tick: only looks at the few mobs that are being tracked */
    void tick() {
        if (this.tracked.isEmpty()) {
            return;
        }
        Iterator<Tracked> iterator = this.tracked.values().iterator();
        while (iterator.hasNext()) {
            Tracked boss = iterator.next();
            LivingEntity entity = boss.entity();
            if (entity.isDeadOrDying()) {
                iterator.remove();
                if (recently(this.defeats, boss.name())) {
                    continue;
                }
                this.defeats.put(boss.name(), System.currentTimeMillis());
                JsonObject event = this.tracker.base("boss_defeated", boss.type());
                event.add("position", MJson.position(entity.getX(), entity.getY(), entity.getZ()));
                JsonObject metadata = MJson.meta(event);
                metadata.addProperty("name", boss.name());
                metadata.addProperty("boss", true);
                metadata.addProperty("source", "entity");
                // Seen dying. Who gets the credit is not known here; the statistics may add that
                metadata.addProperty("attribution", "heuristic");
                metadata.addProperty("attacked_by_local_player", this.tracker.wasRecentlyAttacked(entity.getId()));
                metadata.addProperty("max_health", entity.getMaxHealth());
                if (entity.level() instanceof ClientLevel level) {
                    metadata.add("participants", participants(level, entity.position()));
                }
                this.tracker.store(event);
            } else if (entity.isRemoved()) {
                // Unloaded or despawned, not defeated
                iterator.remove();
            }
        }
    }

    /** Once per second: one cheap pass over the loaded entities, and the boss bars */
    void second(Minecraft minecraft, LocalPlayer player, ClientLevel level) {
        long now = System.currentTimeMillis();

        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || entity instanceof Player || this.tracked.containsKey(entity.getId())) {
                continue;
            }
            if (!living.isAlive() || !this.isNotable(living) || living.distanceToSqr(player) > ENCOUNTER_RANGE * ENCOUNTER_RANGE) {
                continue;
            }
            String type = GameplayTracker.id(living.getType());
            String name = living.getName().getString();
            this.tracked.put(living.getId(), new Tracked(living, type, name));
            if (recently(this.encounters, name)) {
                continue;
            }
            this.encounters.put(name, now);
            JsonObject event = this.tracker.base("boss_encounter", type);
            event.add("position", MJson.position(living.getX(), living.getY(), living.getZ()));
            JsonObject metadata = MJson.meta(event);
            metadata.addProperty("name", name);
            metadata.addProperty("boss", true);
            metadata.addProperty("source", "entity");
            metadata.addProperty("health", living.getHealth());
            metadata.addProperty("max_health", living.getMaxHealth());
            metadata.add("participants", participants(level, living.position()));
            this.tracker.store(event);
        }

        Map<UUID, LerpingBossEvent> shown = minecraft.gui.hud.getBossOverlay().events;
        for (Map.Entry<UUID, LerpingBossEvent> entry : shown.entrySet()) {
            Bar bar = this.bars.get(entry.getKey());
            String name = entry.getValue().getName().getString();
            if (bar == null) {
                bar = new Bar();
                this.bars.put(entry.getKey(), bar);
                if (!recently(this.encounters, name)) {
                    this.encounters.put(name, now);
                    JsonObject event = this.tracker.base("boss_encounter", name);
                    JsonObject metadata = MJson.meta(event);
                    metadata.addProperty("name", name);
                    metadata.addProperty("boss", true);
                    metadata.addProperty("source", "boss_bar");
                    metadata.addProperty("progress", entry.getValue().getProgress());
                    metadata.add("participants", participants(level, player.position()));
                    this.tracker.store(event);
                }
            }
            bar.name = name;
            bar.progress = entry.getValue().getProgress();
        }

        Iterator<Map.Entry<UUID, Bar>> iterator = this.bars.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Bar> entry = iterator.next();
            if (shown.containsKey(entry.getKey())) {
                continue;
            }
            iterator.remove();
            Bar bar = entry.getValue();
            // A bar that was (nearly) empty when it went away. Walking out of range removes it with health left
            if (bar.progress <= 0.03f && !recently(this.defeats, bar.name)) {
                this.defeats.put(bar.name, now);
                JsonObject event = this.tracker.base("boss_defeated", bar.name);
                JsonObject metadata = MJson.meta(event);
                metadata.addProperty("name", bar.name);
                metadata.addProperty("boss", true);
                metadata.addProperty("source", "boss_bar");
                metadata.addProperty("attribution", "heuristic");
                metadata.add("participants", participants(level, player.position()));
                this.tracker.store(event);
            }
        }
    }

}
