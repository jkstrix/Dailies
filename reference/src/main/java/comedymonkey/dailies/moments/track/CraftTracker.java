package comedymonkey.dailies.moments.track;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.moments.MJson;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Craft and contraption moments as the player's own game sees them (plan 2.1): a craft assembled, boarded, flown for
 * the first time, an altitude or speed record aboard one, a craft lost, and a Create contraption assembled nearby.
 * Craft are found through {@link Host#crafts()} only, so this knows nothing of Sable itself and does nothing without it.
 * <p>
 * The companion on the server reports some of the same things with certainty; {@link #onServerEvent} merges its
 * report with the game's when both arrive within five seconds, so the event lists both sources. The rules and
 * numbers are in {@link CraftRules}. Client thread, except where said.
 */
public final class CraftTracker {

    /** How often craft are looked at, in ticks: four times a second is enough to measure speed and catch a removal */
    private static final int LOOK_EVERY_TICKS = 5;
    private static final String GAME = "game";
    private static final String SERVER = "server";

    private static final class Craft {
        final UUID id;
        String name;
        Vec3 centre;
        long seenAt;
        double speed;
        double volume;
        double peak;
        long nearAt;
        boolean lostLogged;

        Craft(UUID id, String name, AABB bounds, long now) {
            this.id = id;
            this.name = name;
            this.centre = bounds.getCenter();
            this.seenAt = now;
            this.volume = volume(bounds);
            this.peak = this.volume;
        }
    }

    private final GameplayTracker owner;
    private final SourceMerge merge = new SourceMerge();
    private final Map<UUID, Craft> crafts = new HashMap<>();
    private final Set<UUID> boarded = new HashSet<>();
    private final Set<UUID> contraptionsSeen = new HashSet<>();
    private @Nullable CraftRules.Best altitude = null;
    private @Nullable CraftRules.Best speed = null;
    private volatile long lastAssemblyAction = 0;
    private @Nullable ClientLevel level = null;
    private boolean firstLook = true;
    private int ticks = 0;
    private int contraptionTicks = 0;

    CraftTracker(GameplayTracker owner) {
        this.owner = owner;
    }

    // --- Hooks ---

    /** The player used a block. An assembler starts the five seconds in which a new craft counts as theirs */
    void onBlockUsed(String blockId) {
        if (CraftRules.isAssembler(blockId)) {
            this.lastAssemblyAction = System.currentTimeMillis();
        }
    }

    /** The player sent a command; /sable assemble starts the same five seconds. Any thread */
    public void onCommand(String command) {
        if (CraftRules.assemblesCraft(command)) {
            this.lastAssemblyAction = System.currentTimeMillis();
        }
    }

    /** The scripted test flies the same world on every run, so the records of the last run would stand in the way */
    public void forgetRecordsForSelfTest() {
        if (!Boolean.getBoolean("dailies.test")) {
            throw new IllegalStateException("Only the self test may forget the records");
        }
        this.owner.firsts().forgetRecords();
        this.altitude = null;
        this.speed = null;
    }

    // --- Watching ---

    void tick(Minecraft minecraft, LocalPlayer player, ClientLevel level) {
        if (level != this.level) {
            // Another world or dimension: the craft of the last one went with it, and that is no loss
            this.level = level;
            this.crafts.clear();
            this.contraptionsSeen.clear();
            this.firstLook = true;
        }
        this.ticks += 1;
        if (this.ticks % LOOK_EVERY_TICKS != 0) {
            return;
        }
        long now = System.currentTimeMillis();
        this.lookAtCraft(player, level, now);
        if (++this.contraptionTicks >= 20 / LOOK_EVERY_TICKS) {
            this.contraptionTicks = 0;
            this.lookAtContraptions(player, level);
        }
        this.firstLook = false;
    }

    private void lookAtCraft(LocalPlayer player, ClientLevel level, long now) {
        Host.Crafts host = Host.crafts();
        List<Host.CraftInfo> present;
        try {
            present = host.crafts(level);
        } catch (RuntimeException e) {
            return;
        }
        if (present.isEmpty() && this.crafts.isEmpty()) {
            return;
        }
        UUID aboard = this.aboard(host, player);
        Vec3 eyes = player.position();
        Set<UUID> still = new HashSet<>();
        for (Host.CraftInfo info : present) {
            if (info.uniqueId() == null || info.bounds() == null) {
                continue;
            }
            still.add(info.uniqueId());
            Craft craft = this.crafts.get(info.uniqueId());
            double distance = distance(info.bounds(), eyes);
            if (craft == null) {
                craft = new Craft(info.uniqueId(), info.name(), info.bounds(), now);
                this.crafts.put(craft.id, craft);
                // Craft already there when the session began, or when the player came into range, are not new
                if (!this.firstLook && CraftRules.assembledByPlayer(this.lastAssemblyAction, now, distance)) {
                    this.lastAssemblyAction = 0;
                    this.assembled(craft, "observed");
                }
            } else {
                Vec3 centre = info.bounds().getCenter();
                double seconds = Math.max(0.05, (now - craft.seenAt) / 1000.0);
                craft.speed = centre.distanceTo(craft.centre) / seconds;
                craft.centre = centre;
                craft.seenAt = now;
                craft.volume = volume(info.bounds());
                craft.peak = Math.max(craft.peak, craft.volume);
                craft.name = info.name();
            }
            boolean near = info.uniqueId().equals(aboard) || distance <= CraftRules.NEAR_RADIUS;
            if (near) {
                craft.nearAt = now;
            }
            if (!craft.lostLogged && near && CraftRules.lostBlocks(craft.volume, craft.peak)) {
                craft.lostLogged = true;
                this.lost(craft, "lost_blocks");
            }
            if (info.uniqueId().equals(aboard)) {
                this.aboard(craft, info.bounds(), player, level, now);
            }
        }
        Iterator<Craft> known = this.crafts.values().iterator();
        while (known.hasNext()) {
            Craft craft = known.next();
            if (still.contains(craft.id)) {
                continue;
            }
            known.remove();
            boolean wasNear = now - craft.nearAt <= CraftRules.NEAR_MEMORY_MILLIS;
            if (!craft.lostLogged && CraftRules.lost(true, craft.speed, 0.0, craft.peak, wasNear)) {
                this.lost(craft, "removed_while_moving");
            }
        }
    }

    /** The craft the player is on or riding, or null */
    private static @Nullable UUID aboard(Host.Crafts host, LocalPlayer player) {
        try {
            Object craft = host.getSubLevel(player);
            Entity vehicle = player.getVehicle();
            if (craft == null && vehicle != null) {
                craft = host.getSubLevel(vehicle);
            }
            return craft == null ? null : host.craftId(craft);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void aboard(Craft craft, AABB bounds, LocalPlayer player, ClientLevel level, long now) {
        if (this.boarded.add(craft.id)) {
            this.emit("craft_boarded", craft, event -> {});
        }
        double ground = groundBelow(level, bounds);
        double height = bounds.minY - ground;
        // Once per craft for good, so a craft flown in an earlier session does not fly "for the first time" again
        if (CraftRules.flying(height) && !this.owner.firsts().contains(this.owner.scope("craft_flown"), craft.id.toString())) {
            this.owner.firsts().mark(this.owner.scope("craft_flown"), craft.id.toString());
            this.emit("craft_first_flight", craft, event -> MJson.meta(event).addProperty("height", MJson.round(height)));
        }
        if (this.altitude == null) {
            this.altitude = new CraftRules.Best(true, this.owner.firsts().record(this.owner.scope("altitude")));
        }
        if (this.speed == null) {
            this.speed = new CraftRules.Best(false, this.owner.firsts().record(this.owner.scope("craft_speed")));
        }
        double y = player.getY();
        double before = this.altitude.best();
        if (this.altitude.offer(y, now)) {
            this.emit("altitude_record", craft, event -> {
                JsonObject metadata = MJson.meta(event);
                metadata.addProperty("altitude", MJson.round(y));
                metadata.addProperty("previous", MJson.round(before));
            });
        }
        if (this.altitude.best() != before) {
            this.owner.firsts().setRecord(this.owner.scope("altitude"), this.altitude.best());
        }
        double fastest = this.speed.best();
        double moving = craft.speed;
        if (this.speed.offer(moving, now)) {
            this.emit("speed_record", craft, event -> {
                JsonObject metadata = MJson.meta(event);
                metadata.addProperty("speed", MJson.round(moving));
                metadata.addProperty("previous", MJson.round(fastest));
            });
        }
        if (this.speed.best() != fastest) {
            this.owner.firsts().setRecord(this.owner.scope("craft_speed"), this.speed.best());
        }
    }

    private void lookAtContraptions(LocalPlayer player, ClientLevel level) {
        Set<UUID> present = new HashSet<>();
        for (Entity entity : level.entitiesForRendering()) {
            String type = GameplayTracker.id(entity.getType());
            if (!CraftRules.isContraption(type)) {
                continue;
            }
            present.add(entity.getUUID());
            if (!this.contraptionsSeen.add(entity.getUUID())) {
                continue;
            }
            if (!this.firstLook && entity.distanceTo(player) <= CraftRules.CONTRAPTION_RADIUS) {
                this.owner.emit("contraption_assembled", type, false, event -> {
                    event.add("position", MJson.position(entity.getX(), entity.getY(), entity.getZ()));
                    JsonObject metadata = MJson.meta(event);
                    metadata.addProperty("entity_uuid", entity.getUUID().toString());
                    metadata.addProperty("source", GAME);
                });
            }
        }
        this.contraptionsSeen.retainAll(present);
    }

    // --- Events, merged with the server's ---

    private void assembled(Craft craft, String how) {
        this.emit("craft_assembled", craft, event -> MJson.meta(event).addProperty("detection", how));
    }

    private void lost(Craft craft, String cause) {
        double speed = craft.speed;
        this.emit("craft_lost", craft, event -> {
            JsonObject metadata = MJson.meta(event);
            metadata.addProperty("cause", cause);
            metadata.addProperty("speed", MJson.round(speed));
        });
    }

    /** Logs a craft event the game saw, or adds the game as a source of the same event the server reported a moment ago */
    private void emit(String type, Craft craft, java.util.function.Consumer<JsonObject> details) {
        long now = System.currentTimeMillis();
        String id = craft.id.toString();
        SourceMerge.Entry earlier = CraftRules.SERVER_TYPES.contains(type) ? this.merge.find(type, id, GAME, now) : null;
        if (earlier != null) {
            this.merge.joined(earlier, GAME);
            this.owner.update(earlier.eventId(), event -> {
                JsonObject metadata = MJson.meta(event);
                GameplayTracker.addSource(metadata, GAME);
                if (!metadata.has("craft_id")) {
                    metadata.addProperty("craft_id", id);
                }
                JsonObject game = new JsonObject();
                game.add("metadata", new JsonObject());
                details.accept(game);
                for (Map.Entry<String, JsonElement> entry : MJson.meta(game).entrySet()) {
                    if (!metadata.has(entry.getKey())) {
                        metadata.add(entry.getKey(), entry.getValue());
                    }
                }
                event.add("metadata", metadata);
            });
            return;
        }
        JsonObject stored = this.owner.emit(type, "craft", false, event -> {
            JsonObject metadata = MJson.meta(event);
            metadata.addProperty("craft_id", id);
            if (craft.name != null && !craft.name.isBlank()) {
                metadata.addProperty("craft_name", craft.name);
            }
            metadata.addProperty("source", GAME);
            GameplayTracker.addSource(metadata, GAME);
            details.accept(event);
            event.add("metadata", metadata);
        });
        if (stored != null && CraftRules.SERVER_TYPES.contains(type)) {
            this.merge.remember(MJson.str(stored, "id"), type, id, GAME, now);
        }
    }

    /**
     * A craft or train event from the companion. It joins the game's report of the same thing from the last five
     * seconds, or becomes an event that a report from the game in the next five seconds will join.
     */
    JsonObject onServerEvent(String type, String label, @Nullable JsonObject data) {
        long now = System.currentTimeMillis();
        String craft = data == null ? null : MJson.str(data, "craft_id");
        SourceMerge.Entry earlier = this.merge.find(type, craft, SERVER, now);
        if (earlier != null) {
            this.merge.joined(earlier, SERVER);
            this.owner.update(earlier.eventId(), event -> {
                JsonObject metadata = MJson.meta(event);
                GameplayTracker.addSource(metadata, SERVER);
                copy(data, metadata);
                if (label != null && !label.isBlank()) {
                    metadata.addProperty("label", label);
                }
                event.add("metadata", metadata);
            });
            JsonObject joined = new JsonObject();
            joined.addProperty("id", earlier.eventId());
            joined.addProperty("merged", true);
            return joined;
        }
        String subject = type.startsWith("train_") ? "train" : "craft";
        JsonObject stored = this.owner.emit(type, subject, false, event -> {
            JsonObject metadata = MJson.meta(event);
            copy(data, metadata);
            metadata.addProperty("label", label == null || label.isBlank() ? type : label);
            metadata.addProperty("source", SERVER);
            GameplayTracker.addSource(metadata, SERVER);
            event.add("metadata", metadata);
        });
        if (stored == null) {
            return new JsonObject();
        }
        this.merge.remember(MJson.str(stored, "id"), type, craft, SERVER, now);
        return stored;
    }

    private static void copy(@Nullable JsonObject from, JsonObject to) {
        if (from == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : from.entrySet()) {
            if (!entry.getKey().equals("source") && !entry.getKey().equals("sources")) {
                to.add(entry.getKey(), entry.getValue());
            }
        }
    }

    // --- Measures ---

    private static double volume(AABB bounds) {
        return Math.max(0.0, bounds.getXsize()) * Math.max(0.0, bounds.getYsize()) * Math.max(0.0, bounds.getZsize());
    }

    private static double distance(AABB bounds, Vec3 point) {
        double dx = Math.max(0.0, Math.max(bounds.minX - point.x, point.x - bounds.maxX));
        double dy = Math.max(0.0, Math.max(bounds.minY - point.y, point.y - bounds.maxY));
        double dz = Math.max(0.0, Math.max(bounds.minZ - point.z, point.z - bounds.maxZ));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * The top of the ground under the middle of a craft. A craft's own blocks are kept elsewhere by Sable, so the
     * world's height map there is the real ground. Where the chunk is not loaded the craft is taken to be on the ground,
     * so that nothing flies by mistake
     */
    private static double groundBelow(ClientLevel level, AABB bounds) {
        Vec3 centre = bounds.getCenter();
        BlockPos column = BlockPos.containing(centre.x, bounds.minY, centre.z);
        if (!level.hasChunkAt(column)) {
            return bounds.minY;
        }
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING, column.getX(), column.getZ());
    }

}
