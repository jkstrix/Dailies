package comedymonkey.dailies.capture;

import com.mojang.datafixers.util.Pair;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.file.Records;
import comedymonkey.dailies.mixin.access.BiomeManagerAccess;
import comedymonkey.dailies.mixin.access.ClientPacketListenerAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;
import net.neoforged.neoforge.network.payload.AdvancedAddEntityPayload;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Describes the whole world as the recorder's game holds it, at the start of a segment (specification REC-20 to 23).
 * A game has no server to ask, so it describes what it holds, as the packets a server would send to a player who had
 * just joined. Playback then needs nothing special: a snapshot is more packets.
 */
public final class Snapshots {

    /** The entity number playback gives the person watching. No real entity has it */
    public static final int VIEWER_ENTITY_ID = Integer.MAX_VALUE - 77;

    private static boolean taking = false;
    private static final Set<String> WARNED = new HashSet<>();

    /**
     * What is remembered of a chunk whose stored form is still good: the name of the stored bytes, and what its block
     * entities said of themselves when it was stored. A chunk kept exactly as the server sent it has not been asked yet
     */
    /**
     * A chunk's stored form: its name, which the recorder's writer fills in once it has encoded and stored the chunk off
     * the game's thread (null until then, when the chunk is simply described again)
     */
    private record Stored(java.util.concurrent.atomic.AtomicReference<byte[]> name, boolean asked, long said, boolean lightChanged,
                          java.util.concurrent.atomic.AtomicReference<byte[]> light) {
        Stored(java.util.concurrent.atomic.AtomicReference<byte[]> name, boolean asked, long said) {
            this(name, asked, said, false, null);
        }

        byte[] known() {
            return this.name.get();
        }

        /** The same chunk, whose blocks are as stored, but whose light may have changed since because a block next door did */
        Stored withLightChanged() {
            return new Stored(this.name, this.asked, this.said, true, this.light);
        }
    }

    /** The stored form of each chunk that no packet has changed since it was stored, by chunk position */
    private static final Map<Long, Stored> CHUNKS = new HashMap<>();
    private static ClientPacketListener worldOf = null;
    private static byte[] world = null;

    private Snapshots() {
    }

    /** Whether a snapshot is being taken right now, on this thread */
    public static boolean taking() {
        return taking;
    }

    /** Knows how to finish the description of a block entity that could not give its own on a client */
    public interface BlockEntityHelper {
        /**
         * @param tag the block entity's plain saved data, to add to
         * @return whether the tag now holds all a game needs to show the block entity as it is
         */
        boolean complete(BlockEntity blockEntity, net.minecraft.nbt.CompoundTag tag);
    }

    private static final java.util.List<BlockEntityHelper> HELPERS = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** For the parts of the mod that know another mod's block entities: asked when one fails to describe itself */
    public static void helpBlockEntities(BlockEntityHelper helper) {
        HELPERS.add(helper);
    }

    /** Whether some helper made the plain saved data of a block entity whole */
    public static boolean completeBlockEntity(BlockEntity blockEntity, net.minecraft.nbt.CompoundTag tag) {
        for (BlockEntityHelper helper : HELPERS) {
            try {
                if (helper.complete(blockEntity, tag)) {
                    return true;
                }
            } catch (Throwable t) {
                // The next one may know it
            }
        }
        return false;
    }

    /**
     * Describes a block entity for a snapshot in place of its own update data, for kinds that answer wrongly when asked
     * on a client: some work out what they send from inventories only a server holds, and overwrite what the client was
     * told with nothing as they do so, both in the snapshot and in the live game.
     */
    public interface BlockEntityDescriber {
        /**
         * @param own the block entity's own answer, to start from
         * @return the description, or null when this is not a kind this describer knows
         */
        @org.jetbrains.annotations.Nullable
        net.minecraft.nbt.CompoundTag describe(BlockEntity blockEntity, net.minecraft.core.HolderLookup.Provider registries,
                                               java.util.function.Supplier<net.minecraft.nbt.CompoundTag> own) throws Exception;
    }

    private static final java.util.List<BlockEntityDescriber> DESCRIBERS = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** For the parts of the mod that know another mod's block entities: asked first, whenever a client one is described */
    public static void describeBlockEntities(BlockEntityDescriber describer) {
        DESCRIBERS.add(describer);
    }

    /** What a block entity of this game would send of itself, as a snapshot (and the tests) describe it */
    public static net.minecraft.nbt.CompoundTag updateTag(BlockEntity blockEntity, net.minecraft.core.HolderLookup.Provider registries,
                                                          java.util.function.Supplier<net.minecraft.nbt.CompoundTag> own) {
        // Only block entities of a client: a server building its own chunk packets meanwhile is answered as it always was
        if (!DESCRIBERS.isEmpty() && blockEntity.getLevel() != null && blockEntity.getLevel().isClientSide()) {
            for (BlockEntityDescriber describer : DESCRIBERS) {
                try {
                    net.minecraft.nbt.CompoundTag described = describer.describe(blockEntity, registries, own);
                    if (described != null) {
                        return described;
                    }
                } catch (Throwable t) {
                    warnOnce("describer of block entity " + BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()), t);
                }
            }
        }
        return own.get();
    }

    /** The same, with the block entity's own answer as the start */
    public static net.minecraft.nbt.CompoundTag updateTag(BlockEntity blockEntity, net.minecraft.core.HolderLookup.Provider registries) {
        return updateTag(blockEntity, registries, () -> blockEntity.getUpdateTag(registries));
    }

    /** Lid controllers of modded block entities that open like a chest's but are not one, by class; empty for none */
    private static final Map<Class<?>, Optional<java.lang.reflect.Field>> LIDS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Whether a block entity's lid is meant to stand open: a vanilla chest's, or a modded container's that keeps a chest's
     * lid controller of its own and opens it with the same block event. Null when it has no such lid
     */
    @org.jetbrains.annotations.Nullable
    public static Boolean lidOpen(BlockEntity entity) {
        if (entity instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chest) {
            return chest.chestLidController.shouldBeOpen;
        }
        Optional<java.lang.reflect.Field> field = LIDS.computeIfAbsent(entity.getClass(), Snapshots::lidField);
        if (field.isEmpty()) {
            return null;
        }
        try {
            Object controller = field.get().get(entity);
            return controller instanceof net.minecraft.world.level.block.entity.ChestLidController lid ? lid.shouldBeOpen : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static Optional<java.lang.reflect.Field> lidField(Class<?> type) {
        for (Class<?> at = type; at != null && at != BlockEntity.class; at = at.getSuperclass()) {
            for (java.lang.reflect.Field field : at.getDeclaredFields()) {
                if (field.getType() == net.minecraft.world.level.block.entity.ChestLidController.class && !java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    try {
                        field.setAccessible(true);
                        return Optional.of(field);
                    } catch (RuntimeException e) {
                        return Optional.empty();
                    }
                }
            }
        }
        return Optional.empty();
    }

    public static void blockEntityFailed(BlockEntity blockEntity, Throwable problem) {
        warnOnce("block entity " + BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()), problem);
    }

    private static void warnOnce(String what, Throwable problem) {
        if (WARNED.add(what)) {
            Host.LOGGER.warn("Dailies: a {} could not describe itself for a snapshot and is recorded with what could be had. Its state may reset after a jump to a later part. Said once per kind. ({})",
                what, problem.getClass().getSimpleName());
        }
    }

    /** Every chunk remembered is out of date: a new world, a change of dimension, a respawn */
    static void forget() {
        CHUNKS.clear();
    }

    /**
     * Nothing remembered can be trusted: a new recording. The registries are described again too. A change of dimension
     * or a respawn does not change them, and describing them takes ten milliseconds, so those keep what is known; a new
     * connection is noticed by itself, and new tags as they arrive.
     */
    static void forgetAll() {
        CHUNKS.clear();
        WHY.clear();
        REASONS.clear();
        worldOf = null;
        world = null;
    }

    /**
     * A whole chunk has arrived and been stored in the recording exactly as the server sent it, in its pieces. Until a
     * packet changes it, the snapshots that follow name it instead of describing the chunk again: travelling brings
     * hundreds of new chunks between two snapshots, and describing each afresh held the game for a tenth of a second.
     */
    static void arrived(int x, int z, byte[] name) {
        CHUNKS.put(ChunkPos.asLong(x, z), new Stored(new java.util.concurrent.atomic.AtomicReference<>(name), false, 0));
    }

    /** Why each chunk has to be described afresh at the next snapshot, the first reason seen: for the log of what storing costs */
    private static final Map<Long, String> WHY = new HashMap<>();
    /** How many chunks were described afresh for each reason, over the recording being made */
    public static final Map<String, Integer> REASONS = new java.util.TreeMap<>();

    private static void because(net.minecraft.core.BlockPos pos, String reason) {
        WHY.putIfAbsent(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4), reason);
    }

    /** A packet is about to be applied. Any chunk it changes has to be described afresh in the next snapshot */
    static void applied(Packet<?> packet) {
        if (packet instanceof ClientboundUpdateTagsPacket) {
            world = null;
            return;
        }
        if (CHUNKS.isEmpty()) {
            return;
        }
        if (packet instanceof ClientboundBlockUpdatePacket update) {
            changed(update.getPos(), update.getBlockState());
            because(update.getPos(), "blocks");
        } else if (packet instanceof ClientboundSectionBlocksUpdatePacket updates) {
            updates.runUpdates((pos, state) -> {
                changed(pos, state);
                because(pos, "blocks");
            });
        } else if (packet instanceof ClientboundBlockEntityDataPacket data) {
            dirty(data.getPos());
            because(data.getPos(), "block entity data");
        } else if (packet instanceof ClientboundBlockEventPacket event) {
            // A piston's push is done by each game for itself when it is told of the event: blocks move with no packet of their own
            changed(event.getPos(), null);
            because(event.getPos(), "block events");
        } else if (packet instanceof ClientboundLightUpdatePacket light) {
            // New light and nothing else: the chunk's blocks are as stored, so it keeps its stored form and the next snapshot
            // adds its light as it is then, as for a chunk whose neighbour changed. Forgetting it had the chunks the server
            // sends light for, the ones round any change, described whole again
            CHUNKS.computeIfPresent(ChunkPos.asLong(light.getX(), light.getZ()), (key, stored) -> stored.withLightChanged());
        } else if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
            CHUNKS.remove(ChunkPos.asLong(chunk.getX(), chunk.getZ()));
        } else if (packet instanceof ClientboundForgetLevelChunkPacket forgotten) {
            CHUNKS.remove(forgotten.pos().toLong());
        } else if (packet instanceof ClientboundChunksBiomesPacket biomes) {
            biomes.chunkBiomeData().forEach(data -> CHUNKS.remove(data.pos().toLong()));
        } else if (packet instanceof ClientboundExplodePacket explosion) {
            explosion.getToBlow().forEach(pos -> changed(pos, null));
        }
    }

    /**
     * A block has changed. Its chunk has to be described afresh, and so have the eight round it: light reaches up to
     * fifteen blocks, the game works that out for itself with no packet to say so, and a chunk is stored with its light.
     * A neighbour stored while lava glowed next door would otherwise keep that glow in every later snapshot
     */
    private static void dirty(BlockPos pos) {
        int x = pos.getX() >> 4, z = pos.getZ() >> 4;
        CHUNKS.remove(ChunkPos.asLong(x, z));
        lightNextDoor(x, z);
    }

    /**
     * The eight chunks round one that changed may have different light now, and nothing else: their own blocks are as
     * stored, or a packet of their own would say otherwise and they would be described afresh for it. So each keeps its
     * stored form and the next snapshot adds its light as it is then, which is a fraction of what describing the whole
     * chunk again cost (one block mined or placed had nine chunks described afresh)
     */
    private static void lightNextDoor(int x, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 || dz != 0) {
                    CHUNKS.computeIfPresent(ChunkPos.asLong(x + dx, z + dz), (key, stored) -> stored.withLightChanged());
                }
            }
        }
    }

    /**
     * A block is about to become something else. Its own chunk has changed; so, when the change can alter the light, have
     * the eight chunks round it, because light reaches up to fifteen blocks and a game works out for itself what a
     * changed block does to the light of the chunks next door. No packet says so: a server sends light only for the
     * chunks at the edge of what a player can see. Without this a lava flow or a torch near the edge of a chunk left
     * the chunk next door stored with its old light, and a jump showed it so.
     *
     * @param to what the block becomes, or null when that is not known and the worst is assumed
     */
    private static void changed(BlockPos pos, @org.jetbrains.annotations.Nullable net.minecraft.world.level.block.state.BlockState to) {
        int x = pos.getX() >> 4;
        int z = pos.getZ() >> 4;
        CHUNKS.remove(ChunkPos.asLong(x, z));
        ClientLevel level = Minecraft.getInstance().level;
        if (to != null && level != null && !lightMayChange(level, pos, level.getBlockState(pos), to)) {
            return;
        }
        lightNextDoor(x, z);
    }

    /** Whether one block in place of another gives off, stops or shapes light differently: the game's own test, less cautious about blocks that only shape it */
    private static boolean lightMayChange(ClientLevel level, BlockPos pos, net.minecraft.world.level.block.state.BlockState from, net.minecraft.world.level.block.state.BlockState to) {
        if (from == to) {
            return false;
        }
        if (from.getLightBlock(level, pos) != to.getLightBlock(level, pos) || from.getLightEmission(level, pos) != to.getLightEmission(level, pos)) {
            return true;
        }
        return (from.useShapeForLightOcclusion() || to.useShapeForLightOcclusion()) && from.getOcclusionShape(level, pos) != to.getOcclusionShape(level, pos);
    }

    /**
     * What playback knows about the world it is showing that a live game finds out for itself. With this the same
     * writer describes a playback world, for the snapshot a trimmed recording needs where a cut falls inside a segment
     * (specification FILE-10).
     */
    public interface PlaybackWorld {
        /** The name of the shared data the registries came from */
        byte[] worldName();

        /** The chunk the recorded game last said its chunks are centred on */
        ChunkPos centre();

        /** Every chunk the world holds, craft apart */
        Iterable<Long> chunks();

        /** The name of the stored chunk a chunk was built from, while nothing has changed it since; else null */
        @org.jetbrains.annotations.Nullable byte[] storedChunk(long chunk);

        /**
         * The name of the stored light a snapshot put on top of that stored chunk, which a chunk whose light changed
         * because a block next door did is given straight after it; null when the chunk has the stored chunk's own light
         */
        default @org.jetbrains.annotations.Nullable byte[] storedLight(long chunk) {
            return null;
        }

        /** The mod messages that came with an entity's spawn, each as a whole packet, or null */
        @org.jetbrains.annotations.Nullable List<byte[]> spawnExtras(int entityId);

        /** The viewer's own profile, which is in the tab list of a playback world and is not part of the recording */
        java.util.UUID viewerProfile();
    }

    /**
     * Describes a world that is being played back, exactly as {@link #write} describes a live one. The viewer is left
     * out, in the world and in the tab list. Client thread, between ticks.
     */
    public static void writeFromPlayback(Recorder.Out out, Minecraft minecraft, ClientPacketListener listener, GameCodec codec, PlaybackWorld playback) {
        write(out, minecraft, listener, codec, playback);
    }

    static void write(Recorder.Out out, Minecraft minecraft, ClientPacketListener listener, GameCodec codec) {
        write(out, minecraft, listener, codec, null);
    }

    private static void write(Recorder.Out out, Minecraft minecraft, ClientPacketListener listener, GameCodec codec, @org.jetbrains.annotations.Nullable PlaybackWorld playback) {
        long started = System.nanoTime();
        taking = true;
        try {
            ClientLevel level = minecraft.level;
            // Chunks are described with the light the game holds for them, and it holds light it has been sent in a queue
            // that it takes a little from each frame. Straight after a change of dimension most of it is still waiting
            // there: the chunks went into the snapshot dark, and stayed dark in playback for the rest of the recording
            try {
                LightPasses.settle(level);
            } catch (RuntimeException e) {
                warnOnce("light", e);
            }
            writeWorld(out, minecraft, listener, level, playback);
            long worldDone = System.nanoTime();
            encoded = 0;
            // Light that has arrived, or that the game has yet to work out from a block that changed, is settled first. The
            // game does that as it draws, a few chunks a frame, so there is nearly always some waiting; a chunk described
            // meanwhile would be stored with its old light, or none, and named again that way by every later snapshot
            settleLight(level);
            int chunks = playback == null ? writeChunks(out, minecraft, listener, level, codec) : writePlaybackChunks(out, level, codec, playback);
            long chunksDone = System.nanoTime();
            SnapshotExtensions.beforeEntities().forEach((id, extension) -> {
                if (playback != null && !SnapshotExtensions.describesPlayback(id)) {
                    return;
                }
                try {
                    extension.write(out, codec);
                } catch (Throwable t) {
                    warnOnce("snapshot extension " + id, t);
                }
            });
            int entities = writeEntities(out, level, codec, playback);
            long entitiesDone = System.nanoTime();
            (playback == null ? SnapshotExtensions.all() : SnapshotExtensions.inPlayback()).forEach((id, extension) -> {
                try {
                    extension.write(out, codec);
                } catch (Throwable t) {
                    warnOnce("snapshot extension " + id, t);
                }
            });
            Host.LOGGER.debug("Dailies: snapshot of {} chunks and {} entities took {} ms", chunks, entities, (System.nanoTime() - started) / 1_000_000);
            if (playback == null) {
                long ended = System.nanoTime();
                lastMillis = (ended - started) / 1_000_000.0;
                last = new Cost(lastMillis, (worldDone - started) / 1_000_000.0, (chunksDone - worldDone) / 1_000_000.0, (entitiesDone - chunksDone) / 1_000_000.0,
                    (ended - entitiesDone) / 1_000_000.0, chunks, encoded, entities);
            }
        } finally {
            taking = false;
        }
    }

    /**
     * Brings a world's light up to date with everything it has been sent, as the next drawn frame would in a game that
     * had nothing else to draw. Playback uses it too: it applies recorded ticks without drawing between them, and a
     * block that changes in a chunk whose light is still waiting is lit wrongly for good.
     */
    public static void settleLight(ClientLevel level) {
        LightPasses.settle(level);
    }

    /** Where a watching game would be drawing the recorder: set by the recorder just before each snapshot. Null when unknown */
    static double[] ownShown = null;

    /** Something to write straight after each entity's spawn, for mods that keep entities somewhere special. Null for none */
    public static java.util.function.BiConsumer<Recorder.Out, Entity> entityFollowUp = null;

    /** How long the last snapshot held the game up, for the tests that watch what recording costs */
    public static volatile double lastMillis = 0;

    /**
     * Where the time of one snapshot of the live game went, in milliseconds, and how much it described: the chunks it
     * named, how many of those had to be written out afresh, and the entities
     */
    public record Cost(double millis, double world, double chunkMillis, double entityMillis, double extensions, int chunks, int encoded, int entities) {
    }

    /** The cost of the last snapshot of the live game; a new object each time, so a watcher can tell one from the next */
    public static volatile Cost last = null;
    private static int encoded = 0;

    private static void writeWorld(Recorder.Out out, Minecraft minecraft, ClientPacketListener listener, ClientLevel level, @org.jetbrains.annotations.Nullable PlaybackWorld playback) {
        if (playback != null) {
            // The registries a playback world was built from are already in the recording, under this name
            out.sharedRef(Records.WORLD, playback.worldName());
        } else {
            if (world == null || worldOf != listener) {
                world = WorldData.capture(listener).toBytes();
                worldOf = listener;
            }
            out.sharedRecord(Records.WORLD, world);
        }

        ClientPacketListenerAccess distances = (ClientPacketListenerAccess) listener;
        boolean flat = level.getLevelData().getHorizonHeight(level) == level.getMinBuildHeight();
        out.packet(new ClientboundLoginPacket(VIEWER_ENTITY_ID, level.getLevelData().isHardcore(), listener.levels(), 20, distances.dailies$serverChunkRadius(),
            distances.dailies$serverSimulationDistance(), false, false, false,
            new CommonPlayerSpawnInfo(level.dimensionTypeRegistration(), level.dimension(), ((BiomeManagerAccess) level.getBiomeManager()).dailies$biomeZoomSeed(),
                GameType.SPECTATOR, null, level.isDebug(), flat, Optional.empty(), 0), false));

        // Where the game's chunks are centred, which is where the server last said, not always the chunk the player stands
        // in: the server says so a moment after the player crosses into a chunk. Playback has to take and let go of chunks
        // from the same place the game did, or it keeps some the game let go of
        ChunkPos centre = playback != null ? playback.centre() : chunkCentre(level);
        out.packet(new ClientboundSetChunkCacheCenterPacket(centre.x, centre.z));
        out.packet(new ClientboundSetChunkCacheRadiusPacket(distances.dailies$serverChunkRadius()));
        out.packet(new ClientboundSetSimulationDistancePacket(distances.dailies$serverSimulationDistance()));
        out.packet(new ClientboundChangeDifficultyPacket(level.getDifficulty(), level.getLevelData().isDifficultyLocked()));
        out.packet(new ClientboundSetDefaultSpawnPositionPacket(level.getSharedSpawnPos(), level.getSharedSpawnAngle()));
        out.packet(new ClientboundSetTimePacket(level.getGameTime(), level.getDayTime(), level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)));
        out.packet(new ClientboundInitializeBorderPacket(level.getWorldBorder()));
        if (level.getRainLevel(1.0F) > 0.0F) {
            out.packet(new ClientboundGameEventPacket(ClientboundGameEventPacket.START_RAINING, 0.0F));
        }
        out.packet(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, level.getRainLevel(1.0F)));
        out.packet(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, level.getThunderLevel(1.0F)));

        // Everyone in the tab list, with the skins and names this game knew them by (REC-14)
        List<ClientboundPlayerInfoUpdatePacket.Entry> players = new ArrayList<>();
        Set<PlayerInfo> listed = new HashSet<>(listener.getListedOnlinePlayers());
        for (PlayerInfo info : listener.getOnlinePlayers()) {
            if (playback != null && info.getProfile().getId().equals(playback.viewerProfile())) {
                continue;
            }
            players.add(new ClientboundPlayerInfoUpdatePacket.Entry(info.getProfile().getId(), info.getProfile(), listed.contains(info), info.getLatency(),
                info.getGameMode(), info.getTabListDisplayName(), null));
        }
        if (!players.isEmpty()) {
            out.packet(PlayerInfoPackets.build(new GameCodec(listener.registryAccess(), listener.getConnectionType()), PlayerInfoPackets.allButChat(), players));
        }

        Scoreboard scoreboard = level.getScoreboard();
        for (Objective objective : scoreboard.getObjectives()) {
            out.packet(new ClientboundSetObjectivePacket(objective, ClientboundSetObjectivePacket.METHOD_ADD));
            for (PlayerScoreEntry score : scoreboard.listPlayerScores(objective)) {
                out.packet(new ClientboundSetScorePacket(score.owner(), objective.getName(), score.value(), Optional.ofNullable(score.display()),
                    Optional.ofNullable(score.numberFormatOverride())));
            }
        }
        for (DisplaySlot slot : DisplaySlot.values()) {
            Objective shown = scoreboard.getDisplayObjective(slot);
            if (shown != null) {
                out.packet(new ClientboundSetDisplayObjectivePacket(slot, shown));
            }
        }
        for (PlayerTeam team : scoreboard.getPlayerTeams()) {
            out.packet(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, true));
        }
        minecraft.gui.getBossOverlay().events.values().forEach(bar -> out.packet(addBar(bar)));
    }

    /**
     * A boss bar as the last packet left it. What a bar shows is eased by the clock on the wall towards the progress it
     * was last told, over a tenth of a second, and the game's own add packet takes what it shows: a bar told it had
     * emptied just before a snapshot was written down as it was before (the bestiary's dying dragon: 0.64 in the snapshot
     * at 1444, live 0, and no packet after it to empty it, so a jump back to 1500 or 1516 showed 0.64)
     */
    static ClientboundBossEventPacket addBar(net.minecraft.client.gui.components.LerpingBossEvent bar) {
        net.minecraft.world.BossEvent told = new net.minecraft.world.BossEvent(bar.getId(), bar.getName(), bar.getColor(), bar.getOverlay()) {
        };
        told.setProgress(bar.targetPercent);
        told.setDarkenScreen(bar.shouldDarkenScreen());
        told.setPlayBossMusic(bar.shouldPlayBossMusic());
        told.setCreateWorldFog(bar.shouldCreateWorldFog());
        return ClientboundBossEventPacket.createAddPacket(told);
    }

    /**
     * Lids that stand open. A chest or shulker box is told to open by a block event, once, and says nothing of it in
     * its own data, so a world rebuilt from its chunks would have every lid shut
     */
    private static void openLids(LevelChunk chunk, List<Packet<? super ClientGamePacketListener>> lids) {
        for (BlockEntity entity : chunk.getBlockEntities().values()) {
            // A modded chest with a vanilla chest's lid (an upgraded chest, say) is opened by the same event, so it is told the same
            boolean open = entity instanceof net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity box
                ? box.getAnimationStatus() == net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity.AnimationStatus.OPENING
                || box.getAnimationStatus() == net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity.AnimationStatus.OPENED
                : Boolean.TRUE.equals(lidOpen(entity));
            if (open) {
                lids.add(new ClientboundBlockEventPacket(entity.getBlockPos(), entity.getBlockState().getBlock(), 1, 1));
            }
        }
    }

    /** The chunk the game's chunks are centred on: every chunk it shows is within reach of this one */
    private static ChunkPos chunkCentre(ClientLevel level) {
        net.minecraft.client.multiplayer.ClientChunkCache.Storage storage = level.getChunkSource().storage;
        return new ChunkPos(storage.viewCenterX, storage.viewCenterZ);
    }

    private static int writeChunks(Recorder.Out out, Minecraft minecraft, ClientPacketListener listener, ClientLevel level, GameCodec codec) {
        ChunkPos centre = chunkCentre(level);
        int radius = Math.max(minecraft.options.getEffectiveRenderDistance(), ((ClientPacketListenerAccess) listener).dailies$serverChunkRadius()) + 4;
        Set<Long> present = new HashSet<>();
        List<Packet<? super ClientGamePacketListener>> lids = new ArrayList<>();
        // The chunks to describe afresh: their packets are made here, from the world as it is on this tick, and turned into
        // bytes, cut up by section, named and stored by the recorder's writer, off the game's thread (Recorder.Out.chunks).
        // A chunk described afresh costs the recording only the sections, light and block entities that are not as stored
        // before: the rest keeps its name
        List<Packet<? super ClientGamePacketListener>> fresh = new ArrayList<>();
        List<java.util.concurrent.atomic.AtomicReference<byte[]>> freshNames = new ArrayList<>();
        // Every chunk the snapshot names, by the names it has or will have: the listing the recording keeps for the snapshot
        List<Recorder.Out.NamedChunk> named = new ArrayList<>();
        int count = 0;
        for (int x = centre.x - radius; x <= centre.x + radius; x++) {
            for (int z = centre.z - radius; z <= centre.z + radius; z++) {
                LevelChunk chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue;
                }
                long key = ChunkPos.asLong(x, z);
                present.add(key);
                count++;
                openLids(chunk, lids);
                Stored stored = CHUNKS.get(key);
                long said = 0;
                if (!chunk.getBlockEntities().isEmpty()) {
                    // Mods change block entities in ways no packet shows. So a chunk that has any is named again only while
                    // every one of them still says of itself exactly what it said when the chunk was stored; each is asked,
                    // every time, which costs far less than describing the whole chunk every time did
                    said = whatBlockEntitiesSay(chunk, level);
                    if (stored != null && (!stored.asked() || stored.said() != said)) {
                        stored = null;
                    }
                }
                byte[] known = stored == null ? null : stored.known();
                if (known != null) {
                    byte[] light = stored.light() == null ? null : stored.light().get();
                    boolean lightWanted = stored.lightChanged() || (stored.light() != null && light == null);
                    if (!lightWanted) {
                        // With the light it was given at an earlier snapshot, if any, still as it is
                        named.add(new Recorder.Out.NamedChunk(key, new java.util.concurrent.atomic.AtomicReference<>(known),
                            light == null ? null : new java.util.concurrent.atomic.AtomicReference<>(light)));
                        continue;
                    }
                    // Its blocks as stored, then its light as it is now, stored by the writer with the chunks below
                    try {
                        java.util.concurrent.atomic.AtomicReference<byte[]> lightName = new java.util.concurrent.atomic.AtomicReference<>();
                        net.minecraft.network.protocol.game.ClientboundLightUpdatePacket lightPacket = new net.minecraft.network.protocol.game.ClientboundLightUpdatePacket(chunk.getPos(), level.getLightEngine(), null, null);
                        // A section with no block light keeps none, and the packet leaves it out; the stored chunk it goes on
                        // may have had some there, from a light that has gone, and that stayed (a dried-out laboratory left
                        // block light 2 in playback where the live game had none). Said outright to be dark, it is cleared.
                        // Sky light is left as it is: a section without it is lit from above, not dark
                        java.util.BitSet blocks = lightPacket.getLightData().getBlockYMask();
                        java.util.BitSet dark = lightPacket.getLightData().getEmptyBlockYMask();
                        for (int section = 0; section < level.getLightEngine().getLightSectionCount(); section++) {
                            if (!blocks.get(section)) {
                                dark.set(section);
                            }
                        }
                        fresh.add(lightPacket);
                        freshNames.add(lightName);
                        named.add(new Recorder.Out.NamedChunk(key, new java.util.concurrent.atomic.AtomicReference<>(known), lightName));
                        CHUNKS.put(key, new Stored(stored.name(), stored.asked(), stored.said(), false, lightName));
                        REASONS.merge("only its light, from a block next door or a light packet", 1, Integer::sum);
                    } catch (Throwable t) {
                        warnOnce("chunk light", t);
                        named.add(new Recorder.Out.NamedChunk(key, new java.util.concurrent.atomic.AtomicReference<>(known), null));
                    }
                    continue;
                }
                String why = stored != null ? "not yet stored by the writer" : !chunk.getBlockEntities().isEmpty() && CHUNKS.containsKey(key) ? "what its block entities say"
                    : WHY.getOrDefault(key, CHUNKS.containsKey(key) ? "what its block entities say" : "new, or changed by a packet not told apart");
                REASONS.merge(why, 1, Integer::sum);
                try {
                    java.util.concurrent.atomic.AtomicReference<byte[]> name = new java.util.concurrent.atomic.AtomicReference<>();
                    fresh.add(ownHeightmaps(new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null)));
                    freshNames.add(name);
                    named.add(new Recorder.Out.NamedChunk(key, name, null));
                    CHUNKS.put(key, new Stored(name, true, said));
                    encoded++;
                } catch (Throwable t) {
                    warnOnce("chunk", t);
                }
            }
        }
        WHY.clear();
        // Every snapshot has its listing, one that names no chunks too, so each listing follows on from the one before
        out.chunks(named, fresh, freshNames, codec);
        CHUNKS.keySet().retainAll(present);
        lids.forEach(out::packet);
        return count;
    }

    /**
     * The packet copies a chunk's blocks and light as it is made, but its heightmaps are the chunk's own arrays, which the
     * game goes on changing. It is turned into bytes later, on the recorder's writer, so the heightmaps are copied here,
     * on the game's thread, or the recording could hold them as they were some ticks on, or half changed (rain and snow
     * falling in the wrong columns in playback). About forty numbers each
     */
    private static ClientboundLevelChunkWithLightPacket ownHeightmaps(ClientboundLevelChunkWithLightPacket packet) {
        net.minecraft.nbt.CompoundTag heightmaps = packet.getChunkData().getHeightmaps();
        for (String key : new ArrayList<>(heightmaps.getAllKeys())) {
            net.minecraft.nbt.Tag tag = heightmaps.get(key);
            if (tag != null) {
                heightmaps.put(key, tag.copy());
            }
        }
        return packet;
    }

    private static long cannotSay = 0;

    /**
     * Everything a chunk's block entities would say of themselves in a description of the chunk, as one number: the same
     * number means the same block entities saying the same things. One that cannot answer makes the number new each time,
     * so its chunk is described afresh, as every chunk with block entities used to be.
     */
    private static long whatBlockEntitiesSay(LevelChunk chunk, ClientLevel level) {
        long sum = chunk.getBlockEntities().size();
        for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
            long hash;
            try {
                HASHER.reset();
                net.minecraft.nbt.NbtIo.write(updateTag(blockEntity, level.registryAccess()), HASHER_OUT);
                hash = HASHER.hash;
            } catch (Throwable t) {
                hash = ++cannotSay * 0x632BE59BD9B4E019L;
            }
            hash ^= blockEntity.getBlockPos().asLong() * 0x9E3779B97F4A7C15L;
            hash ^= BuiltInRegistries.BLOCK_ENTITY_TYPE.getId(blockEntity.getType()) * 0xC2B2AE3D27D4EB4FL;
            // Stirred before it is added, so that two block entities cannot cancel each other out
            hash = (hash ^ hash >>> 30) * 0xBF58476D1CE4E5B9L;
            hash = (hash ^ hash >>> 27) * 0x94D049BB133111EBL;
            sum += hash ^ hash >>> 31;
        }
        return sum;
    }

    /** Sixty-four bits of everything written to it (FNV-1a): data goes in, nothing is kept */
    private static final class Hasher extends java.io.OutputStream {
        long hash;

        void reset() {
            this.hash = 0xCBF29CE484222325L;
        }

        @Override
        public void write(int value) {
            this.hash = (this.hash ^ (value & 0xFF)) * 0x100000001B3L;
        }

        @Override
        public void write(byte[] bytes, int from, int length) {
            long hash = this.hash;
            for (int i = from; i < from + length; i++) {
                hash = (hash ^ (bytes[i] & 0xFF)) * 0x100000001B3L;
            }
            this.hash = hash;
        }
    }

    private static final Hasher HASHER = new Hasher();
    private static final java.io.DataOutputStream HASHER_OUT = new java.io.DataOutputStream(HASHER);

    /** The chunks of a playback world. One that nothing has touched since it was built is named again, not stored again */
    private static int writePlaybackChunks(Recorder.Out out, ClientLevel level, GameCodec codec, PlaybackWorld playback) {
        List<Packet<? super ClientGamePacketListener>> lids = new ArrayList<>();
        List<Packet<? super ClientGamePacketListener>> fresh = new ArrayList<>();
        List<java.util.concurrent.atomic.AtomicReference<byte[]>> freshNames = new ArrayList<>();
        List<Recorder.Out.NamedChunk> named = new ArrayList<>();
        int count = 0;
        for (long key : playback.chunks()) {
            LevelChunk chunk = level.getChunkSource().getChunk(ChunkPos.getX(key), ChunkPos.getZ(key), ChunkStatus.FULL, false);
            if (chunk == null) {
                continue;
            }
            count++;
            openLids(chunk, lids);
            // As in a live snapshot, a chunk with block entities is described afresh: mods change those in ways no packet shows
            byte[] stored = chunk.getBlockEntities().isEmpty() ? playback.storedChunk(key) : null;
            if (stored != null) {
                // With its light as it is now on top, as a live snapshot names such a chunk
                byte[] light = playback.storedLight(key);
                named.add(new Recorder.Out.NamedChunk(key, new java.util.concurrent.atomic.AtomicReference<>(stored),
                    light == null ? null : new java.util.concurrent.atomic.AtomicReference<>(light)));
                continue;
            }
            try {
                java.util.concurrent.atomic.AtomicReference<byte[]> name = new java.util.concurrent.atomic.AtomicReference<>();
                fresh.add(new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null));
                freshNames.add(name);
                named.add(new Recorder.Out.NamedChunk(key, name, null));
            } catch (Throwable t) {
                warnOnce("chunk", t);
            }
        }
        out.chunks(named, fresh, freshNames, codec);
        lids.forEach(out::packet);
        return count;
    }

    private static int writeEntities(Recorder.Out out, ClientLevel level, GameCodec codec, @org.jetbrains.annotations.Nullable PlaybackWorld playback) {
        List<Entity> vehicles = new ArrayList<>();
        List<Entity> leashed = new ArrayList<>();
        int count = 0;
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof Marker || entity.isRemoved()) {
                continue;
            }
            if (playback != null && (entity.getId() == VIEWER_ENTITY_ID || entity == Minecraft.getInstance().player)) {
                // The person watching is not in the recording
                continue;
            }
            try {
                writeEntity(out, entity, codec, playback);
                count++;
                if (!entity.getPassengers().isEmpty()) {
                    vehicles.add(entity);
                }
                if (entity instanceof Leashable held && held.getLeashHolder() != null) {
                    leashed.add(entity);
                }
            } catch (Throwable t) {
                warnOnce("entity " + BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()), t);
            }
        }
        // Riders are seated once everything that can be ridden exists
        for (Entity vehicle : vehicles) {
            out.packet(new ClientboundSetPassengersPacket(vehicle));
        }
        // And leads are tied once both ends exist: a server says who holds a lead only when it is taken up
        for (Entity entity : leashed) {
            out.packet(new ClientboundSetEntityLinkPacket(entity, ((Leashable) entity).getLeashHolder()));
        }
        return count;
    }

    private static void writeEntity(Recorder.Out out, Entity entity, GameCodec codec, @org.jetbrains.annotations.Nullable PlaybackWorld playback) {
        boolean eased = entity.lerpTargetX() != entity.getX() || entity.lerpTargetY() != entity.getY() || entity.lerpTargetZ() != entity.getZ()
            || entity.lerpTargetYRot() != entity.getYRot() || entity.lerpTargetXRot() != entity.getXRot();
        // A server says where most things are as steps from where it last said they were, and between its reports the game
        // moves a fireball, an arrow, an orb or a dropped item along by itself. Such a thing is spawned where the server last
        // put it, so that the steps that follow still add up, and then set down where the game had really got it to
        net.minecraft.world.phys.Vec3 told = entity.getPositionCodec().getBase();
        boolean drifted = !eased && entity != net.minecraft.client.Minecraft.getInstance().player && !(entity instanceof HangingEntity) && !(entity instanceof LeashFenceKnotEntity)
            && (told.x != entity.getX() || told.y != entity.getY() || told.z != entity.getZ());
        Packet<? super ClientGamePacketListener> spawn = spawnPacket(entity, codec, drifted ? told : null);
        List<byte[]> extras = playback != null ? playback.spawnExtras(entity.getId()) : Capture.spawnExtras(entity.getId());
        byte[] built = null;
        if (entity instanceof IEntityWithComplexSpawn) {
            // Ask the entity to describe itself as it is now: a contraption that has turned since it spawned must come back
            // at the angle it has reached, not the one it started at. Here the entity is real and can answer. If it cannot,
            // what came with its spawn is used instead
            try {
                built = codec.encode(new AdvancedAddEntityPayload(entity).toVanillaClientbound());
                extras = null;
            } catch (Throwable t) {
                if (extras == null) {
                    warnOnce("entity " + BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()) + " (extra spawn data)", t);
                }
            }
        }
        if (extras != null || built != null) {
            // The extra data travels in one bundle with the spawn, exactly as it first arrived (REC-16)
            out.bundleBegin();
            out.packet(spawn);
            if (extras != null) {
                for (byte[] extra : extras) {
                    out.payloadFrame(AdvancedAddEntityPayload.TYPE.id().toString(), extra);
                }
            } else {
                out.payloadFrame(AdvancedAddEntityPayload.TYPE.id().toString(), built);
            }
            out.bundleEnd();
        } else {
            out.packet(spawn);
        }

        if (drifted) {
            java.nio.ByteBuffer snap = java.nio.ByteBuffer.allocate(4 + 24 + 8);
            snap.putInt(entity.getId()).putDouble(entity.getX()).putDouble(entity.getY()).putDouble(entity.getZ()).putFloat(entity.getYRot()).putFloat(entity.getXRot());
            out.extension(Recorder.SNAP, snap.array());
        }
        if (entity != net.minecraft.client.Minecraft.getInstance().player && eased) {
            // Caught part way through easing towards where it was last told it is: it is placed where it is drawn, then told again
            RegistryFriendlyByteBuf buffer = codec.buffer();
            try {
                buffer.writeVarInt(entity.getId());
                buffer.writeDouble(entity.lerpTargetX());
                buffer.writeDouble(entity.lerpTargetY());
                buffer.writeDouble(entity.lerpTargetZ());
                buffer.writeByte((byte) net.minecraft.util.Mth.floor(entity.lerpTargetYRot() * 256.0F / 360.0F));
                buffer.writeByte((byte) net.minecraft.util.Mth.floor(entity.lerpTargetXRot() * 256.0F / 360.0F));
                buffer.writeBoolean(entity.onGround());
                // Other mods add fields of their own to this packet (Sable adds a flag). Zeroes stand for "nothing special"
                buffer.writeZero(16);
                out.packet(ClientboundTeleportEntityPacket.STREAM_CODEC.decode(buffer));
            } finally {
                buffer.release();
            }
        }
        if (entity instanceof ExperienceOrb) {
            // The game gives a new orb a random push of its own; say how it is really moving
            out.packet(new ClientboundSetEntityMotionPacket(entity));
        }
        if (entityFollowUp != null) {
            entityFollowUp.accept(out, entity);
        }
        var values = entity.getEntityData().getNonDefaultValues();
        if (values != null && !values.isEmpty()) {
            out.packet(new ClientboundSetEntityDataPacket(entity.getId(), values));
        }
        if (entity instanceof LivingEntity living) {
            var attributes = living.getAttributes().getSyncableAttributes();
            if (!attributes.isEmpty()) {
                out.packet(new ClientboundUpdateAttributesPacket(entity.getId(), attributes));
            }
            List<Pair<EquipmentSlot, ItemStack>> equipment = new ArrayList<>();
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack stack = living.getItemBySlot(slot);
                if (!stack.isEmpty()) {
                    equipment.add(Pair.of(slot, stack.copy()));
                }
            }
            if (!equipment.isEmpty()) {
                out.packet(new ClientboundSetEquipmentPacket(entity.getId(), equipment));
            }
        }
    }

    /**
     * @param told where the server last said the entity is, when the game has moved it on from there by itself; else null
     */
    private static Packet<? super ClientGamePacketListener> spawnPacket(Entity entity, GameCodec codec, @org.jetbrains.annotations.Nullable net.minecraft.world.phys.Vec3 told) {
        double x = told != null ? told.x : entity.getX(), y = told != null ? told.y : entity.getY(), z = told != null ? told.z : entity.getZ();
        if (entity instanceof ExperienceOrb orb) {
            RegistryFriendlyByteBuf buffer = codec.buffer();
            try {
                buffer.writeVarInt(orb.getId());
                buffer.writeDouble(x);
                buffer.writeDouble(y);
                buffer.writeDouble(z);
                buffer.writeShort(orb.getValue());
                return ClientboundAddExperienceOrbPacket.STREAM_CODEC.decode(buffer);
            } finally {
                buffer.release();
            }
        }
        if (entity instanceof HangingEntity hanging) {
            return new ClientboundAddEntityPacket(entity, hanging.getDirection().get3DDataValue(), hanging.getPos());
        }
        if (entity instanceof LeashFenceKnotEntity knot) {
            return new ClientboundAddEntityPacket(entity, 0, knot.getPos());
        }
        int data = 0;
        if (entity instanceof FallingBlockEntity falling) {
            data = Block.getId(falling.getBlockState());
        } else if (entity instanceof FishingHook hook) {
            data = hook.getPlayerOwner() == null ? hook.getId() : hook.getPlayerOwner().getId();
        } else if (entity instanceof Projectile projectile) {
            data = projectile.getOwner() == null ? 0 : projectile.getOwner().getId();
        } else if (entity instanceof Warden warden) {
            data = warden.hasPose(Pose.EMERGING) ? 1 : 0;
        }
        // A game eases each entity towards where it was last told it is. The snapshot records where it was told, not how
        // far the easing has got, or an entity caught mid-step would stay there for good
        double[] shown = entity == net.minecraft.client.Minecraft.getInstance().player ? ownShown : null;
        if (shown != null) {
            return new ClientboundAddEntityPacket(entity.getId(), entity.getUUID(), shown[0], shown[1], shown[2], (float) shown[4], (float) shown[3],
                entity.getType(), data, entity.getDeltaMovement(), entity.getYHeadRot());
        }
        return new ClientboundAddEntityPacket(entity.getId(), entity.getUUID(), x, y, z, entity.getXRot(), entity.getYRot(),
            entity.getType(), data, entity.getDeltaMovement(), entity.getYHeadRot());
    }

}
