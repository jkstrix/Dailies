package comedymonkey.dailies.moments.track;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import comedymonkey.dailies.moments.MJson;
import comedymonkey.dailies.moments.Moments;
import comedymonkey.dailies.moments.MomentsConfig;
import comedymonkey.dailies.moments.TimeRef;
import comedymonkey.dailies.moments.storage.FirstsStore;
import comedymonkey.dailies.moments.storage.MomentsSession;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.advancements.AdvancementNode;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.advancements.AdvancementTree;
import net.minecraft.advancements.AdvancementType;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Detects the gameplay events of one session on the client and writes them to events.jsonl. Runs on the client thread.
 * <p>
 * The client is never told outright who killed what, so kills and deaths are pieced together from three sources,
 * recorded in metadata.attribution:
 * <ul>
 * <li>death_message: the structured arguments of the game's death messages (chat, and the death screen for the local player)</li>
 * <li>stats: the player's own statistics, polled from the server and compared with the previous answer</li>
 * <li>heuristic: the player hit something and it died shortly after (heuristic_unconfirmed once the statistics had
 *     their chance and did not confirm it), or a death that was only seen happening</li>
 * </ul>
 */
public class GameplayTracker {

    // --- Tables ---

    /** Experience levels that count as a milestone when first reached */
    private static final int[] LEVEL_MILESTONES = {10, 20, 30};
    /** A milestone for every this many minutes of play in the session */
    private static final int PLAYTIME_MILESTONE_MINUTES = 30;
    /** Crafting an item whose id path starts with the prefix gives the milestone (once per world): prefix, milestone id, label */
    private static final String[][] CRAFT_MILESTONES = {
        {"diamond_", "first_diamond_item", "First diamond item crafted"},
        {"enchanting_table", "first_enchanting_table", "First enchanting table crafted"},
    };
    /** Advancements that mean the player is at (or dealing with) a structure */
    private static final Map<String, String> STRUCTURE_ADVANCEMENTS = Map.ofEntries(
        Map.entry("minecraft:story/follow_ender_eye", "stronghold"),
        Map.entry("minecraft:nether/find_fortress", "fortress"),
        Map.entry("minecraft:nether/find_bastion", "bastion_remnant"),
        Map.entry("minecraft:nether/loot_bastion", "bastion_remnant"),
        Map.entry("minecraft:end/find_end_city", "end_city"),
        Map.entry("minecraft:adventure/minecraft_trials_edition", "trial_chambers"),
        Map.entry("minecraft:adventure/under_lock_and_key", "trial_chambers"),
        Map.entry("minecraft:adventure/revaulting", "trial_chambers"),
        Map.entry("minecraft:adventure/hero_of_the_village", "village"),
        Map.entry("minecraft:adventure/voluntary_exile", "pillager_outpost_or_patrol")
    );
    /** What a structure marker block usually belongs to. Only a hint */
    private static final Map<String, String> STRUCTURE_BLOCK_HINTS = Map.of(
        "minecraft:spawner", "dungeon_mineshaft_fortress_or_stronghold",
        "minecraft:trial_spawner", "trial_chambers",
        "minecraft:vault", "trial_chambers",
        "minecraft:end_portal_frame", "stronghold",
        "minecraft:reinforced_deepslate", "ancient_city",
        "minecraft:suspicious_sand", "desert_pyramid_desert_well_or_ocean_ruin",
        "minecraft:suspicious_gravel", "trail_ruins_or_ocean_ruin"
    );

    /** An entity counts as killed by the player when it dies within this long after the player's last hit */
    private static final long ATTACK_WINDOW_MILLIS = 5_000;
    /** A heuristic kill the statistics did not confirm within this long is marked heuristic_unconfirmed */
    private static final long STATS_CONFIRM_MILLIS = 15_000;
    /** The observation, the death screen and the chat message of one death arrive within this long of each other */
    private static final long DEATH_MERGE_MILLIS = 10_000;
    private static final long RECENT_MILLIS = 60_000;

    private record Attacked(Entity entity, long time, @Nullable String weapon) {}

    private static class Recent {
        final String id, type;
        @Nullable final String subject;
        final long time;
        boolean confirmed;

        Recent(String id, String type, @Nullable String subject, long time) {
            this.id = id;
            this.type = type;
            this.subject = subject;
            this.time = time;
        }
    }

    private static class PendingKill {
        final String eventId, entityType;
        final long time;
        boolean markedUnconfirmed;

        PendingKill(String eventId, String entityType, long time) {
            this.eventId = eventId;
            this.entityType = entityType;
            this.time = time;
        }
    }

    private record DeathEntry(String eventId, long time) {}

    private final MomentsSession session;
    private final FirstsStore firsts;
    private final UUID localId;
    private final String localUuid;
    private final String localName;
    /** Whether the first statistics answer is known to contain every statistic (the session started right at login) */
    private final boolean statsBaselineComplete;
    private final BossTracker bosses = new BossTracker(this);
    private final CraftTracker crafts = new CraftTracker(this);

    private int ticks = 0;
    private final long sessionStart = System.currentTimeMillis();
    private String lastDimension = null;
    private final Set<String> biomesThisSession = new HashSet<>();
    private boolean wasDead = false;
    private final Set<UUID> deadPlayers = new HashSet<>();
    private int lastXpLevel = -1;
    private int playtimeMilestones = 0;
    private boolean nightSeen = false;
    private final Set<String> advancementsDone = new HashSet<>();

    private final Map<Integer, Attacked> attacked = new HashMap<>();
    private final ArrayDeque<Recent> recent = new ArrayDeque<>();
    private final List<PendingKill> pendingKills = new ArrayList<>();
    private final Map<String, DeathEntry> recentDeaths = new HashMap<>();
    private final Map<String, DeathEntry> recentPlayerKills = new HashMap<>();
    @Nullable private String unconfirmedLocalDeath = null;

    private boolean statsBaselineDone = false;
    private int ticksSinceStatsRequest = 0;
    private int statsAnswers = 0;
    private final JsonObject statsTotals = new JsonObject();

    public GameplayTracker(MomentsSession session, FirstsStore firsts, UUID localId, String localName, boolean statsBaselineComplete) {
        this.session = session;
        this.firsts = firsts;
        this.localId = localId;
        this.localUuid = localId.toString();
        this.localName = localName;
        this.statsBaselineComplete = statsBaselineComplete;
    }

    // --- Building and storing events ---

    String scope(String type) {
        return this.localUuid + "|" + type;
    }

    JsonObject base(String type, @Nullable String subject) {
        TimeRef ref = Moments.now();
        JsonObject event = new JsonObject();
        event.addProperty("type", type);
        event.addProperty("subject", subject);
        event.addProperty("first", false);
        event.add("player", MJson.player(this.localUuid, this.localName));
        ref.applyTo(event);
        event.add("position", MJson.position(ref.x(), ref.y(), ref.z()));
        event.add("metadata", new JsonObject());
        return event;
    }

    // --- Laughter and shouting (plan 2.9) ---

    private final Map<Long, String> burstEvents = new java.util.HashMap<>();

    /**
     * A voice burst, or the same one grown by a speaker. The event is dated to when the burst began, which is a
     * second and a half before it could be told apart from ordinary talk.
     */
    public void onVoiceBurst(long burstId, TimeRef start, com.google.gson.JsonArray speakers, double peakDb) {
        String known = this.burstEvents.get(burstId);
        if (known != null) {
            this.update(known, event -> {
                JsonObject metadata = MJson.meta(event);
                metadata.add("speakers", speakers);
                metadata.addProperty("peak_db", MJson.round(peakDb));
                event.add("metadata", metadata);
            });
            JsonObject updated = this.session.get(MomentsSession.Kind.EVENTS, known);
            if (updated != null) {
                // Three voices score what one does not: the picture is decided again
                comedymonkey.dailies.moments.EventPictures.consider(this.session, updated);
            }
            return;
        }
        JsonObject event = new JsonObject();
        event.addProperty("type", "voice_burst");
        event.addProperty("subject", comedymonkey.dailies.moments.voice.BurstClassifier.UNCLASSIFIED);
        event.addProperty("first", false);
        event.add("player", MJson.player(this.localUuid, this.localName));
        start.applyTo(event);
        event.add("position", MJson.position(start.x(), start.y(), start.z()));
        JsonObject metadata = new JsonObject();
        metadata.add("speakers", speakers);
        metadata.addProperty("peak_db", MJson.round(peakDb));
        metadata.addProperty("source", "voice");
        event.add("metadata", metadata);
        this.burstEvents.put(burstId, MJson.str(this.store(event), "id"));
    }

    /** What the classifier made of a burst: laughter, cheering, screaming or shouting. It becomes the event's subject */
    public void onVoiceBurstLabel(long burstId, String label) {
        String known = this.burstEvents.get(burstId);
        if (known != null) {
            this.update(known, event -> event.addProperty("subject", label));
            JsonObject updated = this.session.get(MomentsSession.Kind.EVENTS, known);
            if (updated != null) {
                // Laughter scores above shouting, so the picture is decided again
                comedymonkey.dailies.moments.EventPictures.consider(this.session, updated);
            }
        }
    }

    JsonObject store(JsonObject event) {
        JsonObject stored = this.session.add(MomentsSession.Kind.EVENTS, event);
        comedymonkey.dailies.moments.EventPictures.consider(this.session, stored);
        this.recent.addLast(new Recent(MJson.str(stored, "id"), MJson.str(stored, "type", ""), MJson.str(stored, "subject"), MJson.time(stored)));
        while (this.recent.size() > 300 || (!this.recent.isEmpty() && MJson.time(stored) - this.recent.peekFirst().time > RECENT_MILLIS * 10)) {
            this.recent.removeFirst();
        }
        return stored;
    }

    void update(String eventId, Consumer<JsonObject> change) {
        this.session.update(MomentsSession.Kind.EVENTS, eventId, change);
    }

    /**
     * Store an event whose "first" flag comes from the per-world firsts.
     * @param onlyFirsts drop the event when it is not a first (unless momentsLogRepeats is on)
     */
    @Nullable
    JsonObject emit(String type, String subject, boolean onlyFirsts, @Nullable Consumer<JsonObject> change) {
        boolean first = this.firsts.mark(this.scope(type), subject);
        if (!first && onlyFirsts && !MomentsConfig.logRepeats()) {
            return null;
        }
        JsonObject event = this.base(type, subject);
        event.addProperty("first", first);
        if (change != null) {
            change.accept(event);
        }
        return this.store(event);
    }

    @Nullable
    private Recent findRecent(String type, @Nullable String subject, long withinMillis, boolean unconfirmedOnly) {
        long now = System.currentTimeMillis();
        Iterator<Recent> iterator = this.recent.descendingIterator();
        while (iterator.hasNext()) {
            Recent candidate = iterator.next();
            if (now - candidate.time > withinMillis) {
                break;
            }
            if (candidate.type.equals(type) && (subject == null || subject.equals(candidate.subject)) && !(unconfirmedOnly && candidate.confirmed)) {
                return candidate;
            }
        }
        return null;
    }

    /** The craft and contraption moments of this session */
    public CraftTracker crafts() {
        return this.crafts;
    }

    String localUuid() {
        return this.localUuid;
    }

    FirstsStore firsts() {
        return this.firsts;
    }

    static String id(EntityType<?> type) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
    }

    static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    static String id(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }

    public synchronized JsonObject statsTotals() {
        JsonObject json = this.statsTotals.deepCopy();
        json.addProperty("answers_received", this.statsAnswers);
        return json;
    }

    // --- Ticking ---

    public void tick(Minecraft minecraft, LocalPlayer player, ClientLevel level, @Nullable String biome) {
        this.ticks += 1;

        String dimension = level.dimension().location().toString();
        if (!dimension.equals(this.lastDimension)) {
            if (this.lastDimension == null) {
                // Where the session starts is not an "entry", but it is no longer new either
                this.firsts.mark(this.scope("dimension_entered"), dimension);
            } else {
                String from = this.lastDimension;
                this.emit("dimension_entered", dimension, false, event -> MJson.meta(event).addProperty("from", from));
            }
            this.lastDimension = dimension;
        }

        boolean dead = player.isDeadOrDying();
        if (dead && !this.wasDead) {
            this.nightSeen = false;
            this.recordDeath(new DeathMessages.Party(this.localName, this.localId, "minecraft:player", true), null, "observed", player.position());
        }
        this.wasDead = dead;

        for (AbstractClientPlayer other : level.players()) {
            if (other == player) {
                continue;
            }
            if (other.isDeadOrDying()) {
                if (this.deadPlayers.add(other.getUUID())) {
                    this.recordDeath(new DeathMessages.Party(other.getGameProfile().getName(), other.getUUID(), "minecraft:player", true),
                        null, "observed", other.position());
                }
            } else {
                this.deadPlayers.remove(other.getUUID());
            }
        }

        if (this.lastXpLevel >= 0 && player.experienceLevel > this.lastXpLevel) {
            for (int milestone : LEVEL_MILESTONES) {
                if (this.lastXpLevel < milestone && player.experienceLevel >= milestone) {
                    this.milestone("level_" + milestone, "Reached experience level " + milestone, false);
                }
            }
        }
        this.lastXpLevel = player.experienceLevel;

        if (!this.attacked.isEmpty()) {
            long now = System.currentTimeMillis();
            Iterator<Attacked> iterator = this.attacked.values().iterator();
            while (iterator.hasNext()) {
                Attacked attack = iterator.next();
                if (attack.entity() instanceof LivingEntity living && living.isDeadOrDying()) {
                    iterator.remove();
                    this.onAttackedEntityDied(attack, living);
                } else if (attack.entity().isRemoved() || now - attack.time() > ATTACK_WINDOW_MILLIS) {
                    iterator.remove();
                }
            }
        }

        this.bosses.tick();
        this.crafts.tick(minecraft, player, level);

        // The player's own statistics. Not while dead: the answer would only arrive after respawning anyway
        int pollSeconds = MomentsConfig.statsPollSeconds();
        this.ticksSinceStatsRequest += 1;
        if (pollSeconds > 0 && !dead && (this.ticksSinceStatsRequest >= pollSeconds * 20 || (this.statsAnswers == 0 && this.ticks == 20))) {
            ClientPacketListener connection = minecraft.getConnection();
            if (connection != null) {
                this.ticksSinceStatsRequest = 0;
                connection.send(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.REQUEST_STATS));
            }
        }

        if (this.ticks % 20 == 0) {
            this.second(minecraft, player, level, biome);
        }
    }

    private void second(Minecraft minecraft, LocalPlayer player, ClientLevel level, @Nullable String biome) {
        long now = System.currentTimeMillis();

        if (biome != null && this.biomesThisSession.add(biome)) {
            // Once per session and biome; "first" still means first in this world
            this.emit("biome_discovered", biome, false, null);
        }

        int playtime = (int) ((now - this.sessionStart) / 60_000 / PLAYTIME_MILESTONE_MINUTES);
        if (playtime > this.playtimeMilestones) {
            this.playtimeMilestones = playtime;
            int minutes = playtime * PLAYTIME_MILESTONE_MINUTES;
            this.milestone("playtime_" + minutes + "_minutes", "Played for " + minutes + " minutes this session", true);
        }

        // First night survived: saw the night and then the morning without dying in between
        long timeOfDay = level.getDayTime() % 24000;
        if (timeOfDay >= 13000 && timeOfDay < 23000) {
            this.nightSeen = true;
        } else if (this.nightSeen && timeOfDay < 12000) {
            this.nightSeen = false;
            this.milestone("first_night_survived", "Survived the first night", false);
        }

        if (MomentsConfig.statsPollSeconds() > 0) {
            Iterator<PendingKill> iterator = this.pendingKills.iterator();
            while (iterator.hasNext()) {
                PendingKill pending = iterator.next();
                if (!pending.markedUnconfirmed && now - pending.time > STATS_CONFIRM_MILLIS) {
                    pending.markedUnconfirmed = true;
                    this.update(pending.eventId, event -> {
                        JsonObject metadata = MJson.meta(event);
                        if ("heuristic".equals(MJson.str(metadata, "attribution"))) {
                            metadata.addProperty("attribution", "heuristic_unconfirmed");
                        }
                    });
                }
                // A late answer may still confirm it for a while
                if (now - pending.time > RECENT_MILLIS) {
                    iterator.remove();
                }
            }
        }

        this.deadPlayers.removeIf(uuid -> level.getPlayerByUUID(uuid) == null);
        this.recentDeaths.values().removeIf(entry -> now - entry.time() > RECENT_MILLIS);
        this.recentPlayerKills.values().removeIf(entry -> now - entry.time() > RECENT_MILLIS);

        this.bosses.second(minecraft, player, level);
    }

    /**
     * @param repeatable milestones that happen again (playtime marks) are always logged; the others only the first time in a world
     */
    private void milestone(String id, String label, boolean repeatable) {
        boolean first = this.firsts.mark(this.scope("milestone"), id);
        if (!first && !repeatable) {
            return;
        }
        JsonObject event = this.base("milestone", id);
        event.addProperty("first", first && !repeatable);
        MJson.meta(event).addProperty("label", label);
        this.store(event);
    }

    // --- Hooks: attacking, blocks, crafting ---

    public void onAttack(LocalPlayer player, Entity target) {
        ItemStack held = player.getMainHandItem();
        this.attacked.put(target.getId(), new Attacked(target, System.currentTimeMillis(), held.isEmpty() ? null : id(held.getItem())));
        this.toolUse(held, "attack");
    }

    boolean wasRecentlyAttacked(int entityId) {
        return this.attacked.containsKey(entityId);
    }

    private void toolUse(ItemStack stack, String action) {
        if (!stack.isEmpty() && stack.isDamageableItem()) {
            this.emit("tool_first_use", id(stack.getItem()), true, event -> MJson.meta(event).addProperty("action", action));
        }
    }

    private void onAttackedEntityDied(Attacked attack, LivingEntity entity) {
        if (entity instanceof Player victim) {
            String key = victim.getUUID().toString();
            DeathEntry existing = this.recentPlayerKills.get(key);
            if (existing != null && System.currentTimeMillis() - existing.time() < DEATH_MERGE_MILLIS) {
                return; // the death message already said so
            }
            DeathMessages.Party party = new DeathMessages.Party(victim.getGameProfile().getName(), victim.getUUID(), "minecraft:player", true);
            JsonObject event = this.base("player_kill", party.name());
            JsonObject metadata = MJson.meta(event);
            metadata.addProperty("attribution", "heuristic");
            metadata.add("victim", party.toJson());
            metadata.addProperty("weapon", attack.weapon());
            event.add("position", MJson.position(victim.getX(), victim.getY(), victim.getZ()));
            JsonObject stored = this.store(event);
            this.recentPlayerKills.put(key, new DeathEntry(MJson.str(stored, "id"), System.currentTimeMillis()));
            this.pendingKills.add(new PendingKill(MJson.str(stored, "id"), "minecraft:player", System.currentTimeMillis()));
            return;
        }

        String type = id(entity.getType());
        boolean boss = this.bosses.isNotable(entity);
        JsonObject stored = this.emit("mob_kill", type, false, event -> {
            JsonObject metadata = MJson.meta(event);
            metadata.addProperty("attribution", "heuristic");
            metadata.addProperty("entity_uuid", entity.getUUID().toString());
            if (entity.hasCustomName()) {
                metadata.addProperty("entity_name", entity.getName().getString());
            }
            metadata.addProperty("weapon", attack.weapon());
            if (boss) {
                metadata.addProperty("boss", true);
            }
            event.add("position", MJson.position(entity.getX(), entity.getY(), entity.getZ()));
        });
        this.pendingKills.add(new PendingKill(MJson.str(stored, "id"), type, System.currentTimeMillis()));
    }

    public void onBlockMined(LocalPlayer player, BlockPos pos, BlockState state) {
        String block = id(state.getBlock());
        this.emit("block_mined", block, true, event -> event.add("position", MJson.position(pos.getX(), pos.getY(), pos.getZ())));
        this.structureBlock(block, pos, "break");
        this.toolUse(player.getMainHandItem(), "mine");
    }

    public void onUseItemOn(ClientLevel level, ItemStack heldBefore, BlockState clickedState, BlockPos clickedPos, Direction face, InteractionResult result) {
        this.structureBlock(id(clickedState.getBlock()), clickedPos, "use");
        this.crafts.onBlockUsed(id(clickedState.getBlock()));

        if (result.consumesAction() && heldBefore.getItem() instanceof BlockItem blockItem) {
            Block block = blockItem.getBlock();
            // The block either replaced what was clicked (grass, snow, ...) or went next to it
            BlockPos placedPos;
            if (level.getBlockState(clickedPos.relative(face)).is(block)) {
                placedPos = clickedPos.relative(face);
            } else if (level.getBlockState(clickedPos).is(block) && !clickedState.is(block)) {
                placedPos = clickedPos;
            } else {
                return;
            }
            this.emit("block_placed", id(block), true, event -> {
                event.add("position", MJson.position(placedPos.getX(), placedPos.getY(), placedPos.getZ()));
                MJson.meta(event).addProperty("item", id(blockItem));
            });
        }
    }

    private void structureBlock(String block, BlockPos pos, String action) {
        if (!MomentsConfig.structureBlocks().contains(block)) {
            return;
        }
        this.emit("structure_interaction", block, true, event -> {
            event.add("position", MJson.position(pos.getX(), pos.getY(), pos.getZ()));
            JsonObject metadata = MJson.meta(event);
            metadata.addProperty("action", action);
            metadata.addProperty("detection", "marker_block");
            String hint = STRUCTURE_BLOCK_HINTS.get(block);
            if (hint != null) {
                metadata.addProperty("structure", hint);
            }
        });
    }

    public void onItemCrafted(ItemStack crafted) {
        if (crafted.isEmpty()) {
            return;
        }
        String item = id(crafted.getItem());
        this.emit("item_crafted", item, true, event -> MJson.meta(event).addProperty("count", crafted.getCount()));

        String path = item.substring(item.indexOf(':') + 1);
        for (String[] rule : CRAFT_MILESTONES) {
            if (path.startsWith(rule[0])) {
                this.milestone(rule[1], rule[2] + " (" + item + ")", false);
            }
        }
    }

    // --- Hooks: advancements, markers, custom events ---

    public void onAdvancements(ClientboundUpdateAdvancementsPacket packet, AdvancementTree tree) {
        if (packet.shouldReset()) {
            // The full list sent on login: things earned earlier, not now
            return;
        }
        for (Map.Entry<ResourceLocation, AdvancementProgress> entry : packet.getProgress().entrySet()) {
            AdvancementNode node = tree.get(entry.getKey());
            if (node == null || !entry.getValue().isDone() || node.advancement().display().isEmpty()) {
                continue; // recipe unlocks have no display
            }
            String advancementId = entry.getKey().toString();
            if (!this.advancementsDone.add(advancementId)) {
                continue;
            }
            DisplayInfo display = node.advancement().display().get();
            this.emit("advancement", advancementId, false, event -> {
                JsonObject metadata = MJson.meta(event);
                metadata.addProperty("title", display.getTitle().getString());
                metadata.addProperty("description", display.getDescription().getString());
                metadata.addProperty("frame", display.getType().getSerializedName());
                if (display.getType() == AdvancementType.CHALLENGE) {
                    metadata.addProperty("rare", true);
                }
                String structure = STRUCTURE_ADVANCEMENTS.get(advancementId);
                if (structure != null) {
                    metadata.addProperty("structure", structure);
                }
            });
        }
    }

    /**
     * A custom event sent by the server (Dailies Companion). Stored like one added over MCP, except that the first
     * event of each server-chosen type in this world is flagged as a first.
     */
    public synchronized JsonObject addServerEvent(String customType, String label, @Nullable JsonObject data) {
        if (customType != null && CraftRules.SERVER_TYPES.contains(customType)) {
            // The companion's craft and train events are moments of their own type, merged with what the game saw (plan 2.1)
            return this.crafts.onServerEvent(customType, label, data);
        }
        String subject = customType == null || customType.isBlank() ? "server_event" : customType;
        return this.emit("custom", subject, false, event -> {
            JsonObject metadata = MJson.meta(event);
            if (data != null) {
                for (Map.Entry<String, JsonElement> entry : data.entrySet()) {
                    metadata.add(entry.getKey(), entry.getValue());
                }
            }
            metadata.addProperty("label", label == null || label.isBlank() ? subject : label);
            metadata.addProperty("source", "server");
        });
    }

    // --- McpCraft's job board ---

    private static final Map<String, String> JOB_EVENT_TYPES = Map.of(
        "posted", "job_posted",
        "help_asked", "job_help_asked",
        "claimed", "job_claimed",
        "dropped", "job_dropped",
        "delivered", "job_delivered",
        "materials_complete", "job_materials_complete",
        "finished", "job_finished",
        "removed", "job_removed"
    );
    /** Deliveries to one job this close together are one event: emptying an inventory into a store is many of them */
    private static final long JOB_DELIVERY_MERGE_MILLIS = 60_000;

    private record JobDelivery(String eventId, long time) {}
    private final Map<String, JobDelivery> jobDeliveries = new java.util.HashMap<>();

    /**
     * A job board event the local player has a part in (McpCraft only sends those). The subject is the kind of job
     * (track, structure, supply, custom), so "first" means the first time this happened with a job of that kind.
     */
    public synchronized void onJobBoardEvent(String jobEvent, JsonObject data) {
        String type = JOB_EVENT_TYPES.get(jobEvent);
        if (type == null) {
            return;
        }
        String jobId = MJson.str(data, "job_id", "");
        String kind = MJson.str(data, "kind", "");
        String jobKind = kind.isBlank() ? "job" : kind;
        String deliveryKey = jobId + "|" + MJson.str(data, "player", "");

        if (type.equals("job_delivered")) {
            String item = MJson.str(data, "item", "unknown");
            long count = MJson.lng(data, "count", 0);
            long now = System.currentTimeMillis();
            JobDelivery previous = this.jobDeliveries.get(deliveryKey);
            if (previous != null && now - previous.time <= JOB_DELIVERY_MERGE_MILLIS) {
                this.update(previous.eventId, event -> {
                    JsonObject metadata = MJson.meta(event);
                    metadata.addProperty("count", MJson.lng(metadata, "count", 0) + count);
                    JsonObject items = metadata.has("items") && metadata.get("items").isJsonObject() ? metadata.getAsJsonObject("items") : new JsonObject();
                    items.addProperty(item, MJson.lng(items, item, 0) + count);
                    metadata.add("items", items);
                });
                this.jobDeliveries.put(deliveryKey, new JobDelivery(previous.eventId, now));
                return;
            }
        }

        JsonObject stored = this.emit(type, jobKind, false, event -> {
            JsonObject metadata = MJson.meta(event);
            for (Map.Entry<String, JsonElement> entry : data.entrySet()) {
                if (!entry.getKey().equals("event")) {
                    metadata.add(entry.getKey(), entry.getValue());
                }
            }
            metadata.addProperty("source", "mcpcraft");
            if (type.equals("job_delivered")) {
                JsonObject items = new JsonObject();
                items.addProperty(MJson.str(data, "item", "unknown"), MJson.lng(data, "count", 0));
                metadata.add("items", items);
            }
        });
        if (stored != null && type.equals("job_delivered")) {
            this.jobDeliveries.put(deliveryKey, new JobDelivery(MJson.str(stored, "id"), System.currentTimeMillis()));
        }
    }

    public void onMarker(@Nullable String description, int colour) {
        JsonObject event = this.base("marker", description == null ? "marker" : description);
        JsonObject metadata = MJson.meta(event);
        metadata.addProperty("label", description == null ? "Marker" : description);
        metadata.addProperty("colour", String.format("#%06X", colour & 0xFFFFFF));
        this.store(event);
    }

    public JsonObject addCustom(@Nullable String customType, String label, @Nullable JsonObject extraMetadata) {
        JsonObject event = this.base("custom", customType == null ? label : customType);
        JsonObject metadata = MJson.meta(event);
        if (extraMetadata != null) {
            for (Map.Entry<String, JsonElement> entry : extraMetadata.entrySet()) {
                metadata.add(entry.getKey(), entry.getValue());
            }
        }
        metadata.addProperty("label", label);
        metadata.addProperty("source", "mcp");
        return this.store(event);
    }

    // --- Deaths ---

    private boolean isLocal(@Nullable DeathMessages.Party party) {
        return party != null && (this.localId.equals(party.uuid()) || (party.uuid() == null && this.localName.equals(party.name())));
    }

    private static String partyKey(DeathMessages.Party party) {
        return party.uuid() != null ? party.uuid().toString() : "name:" + party.name();
    }

    static void addSource(JsonObject metadata, String source) {
        JsonArray sources = MJson.arr(metadata, "sources");
        for (JsonElement element : sources) {
            if (element.getAsString().equals(source)) {
                return;
            }
        }
        sources.add(source);
        metadata.add("sources", sources);
    }

    /** System chat: death messages and other players' advancements */
    public void onSystemChat(Component message) {
        DeathMessages.DeathInfo death = DeathMessages.parseDeath(message);
        if (death != null) {
            this.onDeathMessage(death, "chat");
            return;
        }

        DeathMessages.AdvancementInfo advancement = DeathMessages.parseAdvancement(message);
        if (advancement != null && !this.isLocal(advancement.player())) {
            String frame = advancement.key().substring(advancement.key().lastIndexOf('.') + 1);
            JsonObject event = this.base("advancement_other", advancement.title());
            DeathMessages.Party who = advancement.player();
            event.add("player", MJson.player(who.uuid() == null ? null : who.uuid().toString(), who.name()));
            JsonObject metadata = MJson.meta(event);
            metadata.addProperty("title", advancement.title());
            metadata.addProperty("frame", frame);
            metadata.addProperty("message", advancement.text());
            metadata.addProperty("source", "chat");
            metadata.addProperty("position_source", "observer");
            if (frame.equals("challenge")) {
                metadata.addProperty("rare", true);
            }
            this.store(event);
        }
    }

    /** The death screen message, which the local player gets even when chat is hidden */
    public void onCombatKill(Component message) {
        DeathMessages.DeathInfo death = DeathMessages.parseDeath(message);
        if (death != null && this.isLocal(death.victim())) {
            this.onDeathMessage(death, "combat_kill_packet");
        } else {
            this.recordDeath(new DeathMessages.Party(this.localName, this.localId, "minecraft:player", true), null, "combat_kill_packet", null);
        }
    }

    private void onDeathMessage(DeathMessages.DeathInfo death, String source) {
        DeathMessages.Party victim = death.victim();
        if (!victim.isPlayer()) {
            // Only sent for the player's own tamed animals
            String key = "pet:" + partyKey(victim);
            if (this.recentDeaths.containsKey(key)) {
                return;
            }
            JsonObject event = this.base("pet_death", victim.type() != null ? victim.type() : victim.name());
            JsonObject metadata = MJson.meta(event);
            death.applyTo(metadata);
            metadata.addProperty("attribution", "death_message");
            addSource(metadata, source);
            JsonObject stored = this.store(event);
            this.recentDeaths.put(key, new DeathEntry(MJson.str(stored, "id"), System.currentTimeMillis()));
            return;
        }

        this.recordDeath(victim, death, source, null);

        String key = partyKey(victim);
        DeathEntry kill = this.recentPlayerKills.get(key);
        boolean recentKill = kill != null && System.currentTimeMillis() - kill.time() < DEATH_MERGE_MILLIS;
        if (this.isLocal(death.killer()) && !this.isLocal(victim)) {
            if (recentKill) {
                // The hit-then-died guess was right
                this.update(kill.eventId(), event -> {
                    JsonObject metadata = MJson.meta(event);
                    death.applyTo(metadata);
                    metadata.addProperty("attribution", "death_message");
                });
            } else {
                JsonObject event = this.base("player_kill", victim.name());
                JsonObject metadata = MJson.meta(event);
                death.applyTo(metadata);
                metadata.addProperty("attribution", "death_message");
                JsonObject stored = this.store(event);
                this.recentPlayerKills.put(key, new DeathEntry(MJson.str(stored, "id"), System.currentTimeMillis()));
                // The statistics can still confirm it
                this.pendingKills.add(new PendingKill(MJson.str(stored, "id"), "minecraft:player", System.currentTimeMillis()));
            }
        } else if (recentKill) {
            // The player hit them, but the game credits someone or something else
            this.update(kill.eventId(), event -> {
                JsonObject metadata = MJson.meta(event);
                if ("heuristic".equals(MJson.str(metadata, "attribution"))) {
                    metadata.addProperty("attribution", "heuristic_unconfirmed");
                    metadata.addProperty("death_key", death.key());
                    metadata.addProperty("message", death.text());
                    if (death.killer() != null) {
                        metadata.add("credited_killer", death.killer().toJson());
                    }
                }
            });
        }
    }

    /**
     * One death of a player, from any of its sources. The first source creates the event, the others add to it.
     * @param death the parsed death message, when the source has one
     * @param observedAt where the player was seen dying, when the source is the client watching the entity
     */
    private void recordDeath(DeathMessages.Party victim, @Nullable DeathMessages.DeathInfo death, String source, @Nullable Vec3 observedAt) {
        long now = System.currentTimeMillis();
        boolean local = this.isLocal(victim);
        String key = partyKey(victim);

        DeathEntry existing = this.recentDeaths.get(key);
        if (existing != null && now - existing.time() < DEATH_MERGE_MILLIS) {
            this.update(existing.eventId(), event -> {
                JsonObject metadata = MJson.meta(event);
                addSource(metadata, source);
                if (death != null && !metadata.has("death_key")) {
                    death.applyTo(metadata);
                    metadata.addProperty("attribution", "death_message");
                    event.addProperty("subject", death.key());
                }
                if (observedAt != null) {
                    event.add("position", MJson.position(observedAt.x, observedAt.y, observedAt.z));
                    metadata.addProperty("observed", true);
                    metadata.remove("position_source");
                }
            });
            return;
        }

        JsonObject event = this.base(local ? "player_death" : "nearby_player_death", death == null ? null : death.key());
        event.add("player", MJson.player(victim.uuid() == null ? null : victim.uuid().toString(), victim.name()));
        JsonObject metadata = MJson.meta(event);
        if (death != null) {
            death.applyTo(metadata);
            metadata.addProperty("attribution", "death_message");
        } else {
            metadata.add("victim", victim.toJson());
            metadata.addProperty("attribution", "heuristic");
        }
        addSource(metadata, source);
        if (observedAt != null) {
            event.add("position", MJson.position(observedAt.x, observedAt.y, observedAt.z));
            metadata.addProperty("observed", true);
        } else if (!local) {
            // Only heard about it: the position is where this client's player was
            metadata.addProperty("observed", false);
            metadata.addProperty("position_source", "observer");
        }
        JsonObject stored = this.store(event);
        this.recentDeaths.put(key, new DeathEntry(MJson.str(stored, "id"), now));
        if (local) {
            this.unconfirmedLocalDeath = MJson.str(stored, "id");
        }
    }

    // --- Statistics ---

    /** Called for every statistics packet, whether it answers our request or the vanilla statistics screen's */
    public void onStats(Object2IntMap<Stat<?>> stats) {
        this.statsAnswers += 1;
        boolean baseline = !this.statsBaselineDone;
        this.statsBaselineDone = true;

        // Who killed the player has to be known before the death itself is handled
        String killedBy = null;
        List<StatChange> changes = new ArrayList<>();
        for (Object2IntMap.Entry<Stat<?>> entry : stats.object2IntEntrySet()) {
            Stat<?> stat = entry.getKey();
            int value = entry.getIntValue();
            if (baseline) {
                this.seed(stat, value);
                continue;
            }
            Integer previous = this.previousValues.put(stat, value);
            if (previous != null && value <= previous) {
                continue;
            }
            if (stat.getType() == Stats.ENTITY_KILLED_BY && stat.getValue() instanceof EntityType<?> type) {
                killedBy = id(type);
            }
            changes.add(new StatChange(stat, value, previous));
        }

        for (StatChange change : changes) {
            if (change.previous() != null) {
                this.applyStat(change.stat(), change.value() - change.previous(), true, killedBy);
            } else if (this.statsBaselineComplete || change.value() == 1) {
                this.applyStat(change.stat(), change.value(), true, killedBy);
            } else {
                // A statistic the (partial) baseline did not have: its history is unknown, so only confirm, never invent
                this.seed(change.stat(), change.value());
                this.applyStat(change.stat(), change.value(), false, killedBy);
            }
        }
        this.firsts.save();
    }

    private record StatChange(Stat<?> stat, int value, @Nullable Integer previous) {}

    /** The last known value of every statistic the server has sent */
    private final Map<Stat<?>, Integer> previousValues = new HashMap<>();

    /** Things the statistics say were already done before: they are not firsts anymore */
    private void seed(Stat<?> stat, int value) {
        this.previousValues.put(stat, value);
        if (value <= 0) {
            return;
        }
        Object subject = stat.getValue();
        if (stat.getType() == Stats.ENTITY_KILLED && subject instanceof EntityType<?> type) {
            this.firsts.markQuiet(this.scope("mob_kill"), id(type));
        } else if (stat.getType() == Stats.ITEM_CRAFTED && subject instanceof Item item) {
            this.firsts.markQuiet(this.scope("item_crafted"), id(item));
        } else if (stat.getType() == Stats.BLOCK_MINED && subject instanceof Block block) {
            this.firsts.markQuiet(this.scope("block_mined"), id(block));
        } else if (stat.getType() == Stats.ITEM_USED && subject instanceof Item item) {
            if (item instanceof BlockItem blockItem) {
                this.firsts.markQuiet(this.scope("block_placed"), id(blockItem.getBlock()));
            } else if (item.getDefaultInstance().isDamageableItem()) {
                this.firsts.markQuiet(this.scope("tool_first_use"), id(item));
            }
        }
    }

    private void applyStat(Stat<?> stat, int delta, boolean backfill, @Nullable String killedBy) {
        Object subject = stat.getValue();
        if (stat.getType() == Stats.ENTITY_KILLED && subject instanceof EntityType<?> type) {
            this.confirmKills(id(type), delta, backfill);
        } else if (stat.getType() == Stats.ITEM_CRAFTED && subject instanceof Item item) {
            this.confirmOrBackfill("item_crafted", id(item), backfill, "Counted by the statistics; may also be a smelted or traded item");
        } else if (stat.getType() == Stats.BLOCK_MINED && subject instanceof Block block) {
            this.confirmOrBackfill("block_mined", id(block), backfill, null);
        } else if (stat.getType() == Stats.ITEM_USED && subject instanceof Item item) {
            if (item instanceof BlockItem blockItem) {
                this.confirmOrBackfill("block_placed", id(blockItem.getBlock()), backfill, null);
            } else if (item.getDefaultInstance().isDamageableItem()) {
                this.confirmOrBackfill("tool_first_use", id(item), backfill, null);
            }
        } else if (stat.getType() == Stats.CUSTOM && subject instanceof ResourceLocation custom) {
            if (custom.equals(Stats.DEATHS)) {
                this.addTotal("deaths", delta);
                this.confirmLocalDeath(backfill, killedBy);
            } else if (custom.equals(Stats.MOB_KILLS)) {
                this.addTotal("mob_kills", delta);
            } else if (custom.equals(Stats.PLAYER_KILLS)) {
                this.addTotal("player_kills", delta);
            }
        }
    }

    /** Session counters from the statistics, shown by moments_status */
    private synchronized void addTotal(String key, int delta) {
        this.statsTotals.addProperty(key, MJson.lng(this.statsTotals, key, 0) + delta);
    }

    private void confirmKills(String entityType, int count, boolean backfill) {
        boolean isPlayer = entityType.equals("minecraft:player");
        int unmatched = 0;
        for (int i = 0; i < count; i++) {
            PendingKill match = null;
            for (PendingKill pending : this.pendingKills) {
                if (pending.entityType.equals(entityType)) {
                    match = pending;
                    break;
                }
            }
            if (match == null) {
                unmatched += 1;
                continue;
            }
            this.pendingKills.remove(match);
            this.update(match.eventId, event -> {
                JsonObject metadata = MJson.meta(event);
                metadata.addProperty("confirmed_by_stats", true);
                // A death message already is exact, and says more
                if (!"death_message".equals(MJson.str(metadata, "attribution"))) {
                    metadata.addProperty("attribution", "stats");
                }
            });
        }

        // The statistics also tell whether the player got the credit for a boss that was seen dying
        Recent bossDefeat = this.findRecent("boss_defeated", entityType, RECENT_MILLIS, true);
        if (bossDefeat != null) {
            bossDefeat.confirmed = true;
            this.update(bossDefeat.id, event -> {
                JsonObject metadata = MJson.meta(event);
                metadata.addProperty("attribution", "stats");
                metadata.addProperty("killed_by_local_player", true);
            });
        }

        if (unmatched > 0 && backfill) {
            // Kills nothing on the client hinted at: arrows, tridents, pets, explosions, ...
            int finalUnmatched = unmatched;
            if (isPlayer) {
                JsonObject event = this.base("player_kill", null);
                JsonObject metadata = MJson.meta(event);
                metadata.addProperty("attribution", "stats");
                metadata.addProperty("timing", "approximate");
                metadata.addProperty("count", finalUnmatched);
                this.store(event);
            } else {
                this.emit("mob_kill", entityType, false, event -> {
                    JsonObject metadata = MJson.meta(event);
                    metadata.addProperty("attribution", "stats");
                    metadata.addProperty("timing", "approximate");
                    metadata.addProperty("count", finalUnmatched);
                    metadata.addProperty("confirmed_by_stats", true);
                });
            }
        }
    }

    private void confirmOrBackfill(String type, String subject, boolean backfill, @Nullable String note) {
        Recent match = this.findRecent(type, subject, RECENT_MILLIS / 2, true);
        if (match != null) {
            match.confirmed = true;
            this.update(match.id, event -> MJson.meta(event).addProperty("confirmed_by_stats", true));
            return;
        }
        if (!backfill) {
            return;
        }
        // Something the client-side hooks did not see. These types are only logged as firsts (unless repeats are on)
        this.emit(type, subject, true, event -> {
            JsonObject metadata = MJson.meta(event);
            metadata.addProperty("source", "stats");
            metadata.addProperty("timing", "approximate");
            metadata.addProperty("confirmed_by_stats", true);
            if (note != null) {
                metadata.addProperty("note", note);
            }
        });
        Recent stored = this.recent.peekLast();
        if (stored != null && stored.type.equals(type)) {
            stored.confirmed = true;
        }
    }

    private void confirmLocalDeath(boolean backfill, @Nullable String killedBy) {
        if (this.unconfirmedLocalDeath != null) {
            String eventId = this.unconfirmedLocalDeath;
            this.unconfirmedLocalDeath = null;
            this.update(eventId, event -> {
                JsonObject metadata = MJson.meta(event);
                metadata.addProperty("confirmed_by_stats", true);
                if ("heuristic".equals(MJson.str(metadata, "attribution"))) {
                    metadata.addProperty("attribution", "stats");
                }
                if (killedBy != null && !metadata.has("killer")) {
                    JsonObject killer = new JsonObject();
                    killer.addProperty("type", killedBy);
                    killer.addProperty("is_player", killedBy.equals("minecraft:player"));
                    metadata.add("killer", killer);
                    metadata.addProperty("killer_is_player", killedBy.equals("minecraft:player"));
                }
            });
        } else if (backfill) {
            JsonObject event = this.base("player_death", null);
            JsonObject metadata = MJson.meta(event);
            metadata.addProperty("attribution", "stats");
            metadata.addProperty("timing", "approximate");
            metadata.addProperty("confirmed_by_stats", true);
            metadata.add("victim", new DeathMessages.Party(this.localName, this.localId, "minecraft:player", true).toJson());
            if (killedBy != null) {
                JsonObject killer = new JsonObject();
                killer.addProperty("type", killedBy);
                killer.addProperty("is_player", killedBy.equals("minecraft:player"));
                metadata.add("killer", killer);
            }
            this.store(event);
        }
    }

}
