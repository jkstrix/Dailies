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

    /** The stored form of each chunk that has not changed since it was last described, by chunk position */
    private static final Map<Long, byte[]> CHUNKS = new HashMap<>();
    private static ClientPacketListener worldOf = null;
    private static byte[] world = null;

    private Snapshots() {
    }

    /** Whether a snapshot is being taken right now, on this thread */
    public static boolean taking() {
        return taking;
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

    /** Everything remembered about the world is out of date: a new world, a pause, a change of dimension */
    static void forget() {
        CHUNKS.clear();
        worldOf = null;
        world = null;
    }

    /** A packet is about to be applied. Any chunk it changes has to be described afresh in the next snapshot */
    static void applied(Packet<?> packet) {
        if (CHUNKS.isEmpty()) {
            if (packet instanceof ClientboundUpdateTagsPacket) {
                world = null;
            }
            return;
        }
        if (packet instanceof ClientboundBlockUpdatePacket update) {
            dirty(update.getPos());
        } else if (packet instanceof ClientboundSectionBlocksUpdatePacket updates) {
            updates.runUpdates((pos, state) -> dirty(pos));
        } else if (packet instanceof ClientboundBlockEntityDataPacket data) {
            dirty(data.getPos());
        } else if (packet instanceof ClientboundLightUpdatePacket light) {
            CHUNKS.remove(ChunkPos.asLong(light.getX(), light.getZ()));
        } else if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
            CHUNKS.remove(ChunkPos.asLong(chunk.getX(), chunk.getZ()));
        } else if (packet instanceof ClientboundForgetLevelChunkPacket forgotten) {
            CHUNKS.remove(forgotten.pos().toLong());
        } else if (packet instanceof ClientboundChunksBiomesPacket biomes) {
            biomes.chunkBiomeData().forEach(data -> CHUNKS.remove(data.pos().toLong()));
        } else if (packet instanceof ClientboundExplodePacket explosion) {
            explosion.getToBlow().forEach(Snapshots::dirty);
        } else if (packet instanceof ClientboundUpdateTagsPacket) {
            world = null;
        }
    }

    private static void dirty(BlockPos pos) {
        CHUNKS.remove(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
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
                for (int turn = 0; turn < 100_000 && !level.isLightUpdateQueueEmpty(); turn++) {
                    level.pollLightUpdates();
                }
                level.getChunkSource().getLightEngine().runLightUpdates();
            } catch (RuntimeException e) {
                warnOnce("light", e);
            }
            writeWorld(out, minecraft, listener, level, playback);
            int chunks = playback == null ? writeChunks(out, minecraft, listener, level, codec) : writePlaybackChunks(out, level, codec, playback);
            int entities = writeEntities(out, level, codec, playback);
            (playback == null ? SnapshotExtensions.all() : SnapshotExtensions.inPlayback()).forEach((id, extension) -> {
                try {
                    extension.write(out, codec);
                } catch (Throwable t) {
                    warnOnce("snapshot extension " + id, t);
                }
            });
            Host.LOGGER.debug("Dailies: snapshot of {} chunks and {} entities took {} ms", chunks, entities, (System.nanoTime() - started) / 1_000_000);
            if (playback == null) {
                lastMillis = (System.nanoTime() - started) / 1_000_000.0;
            }
        } finally {
            taking = false;
        }
    }

    /** Where a watching game would be drawing the recorder: set by the recorder just before each snapshot. Null when unknown */
    static double[] ownShown = null;

    /** Something to write straight after each entity's spawn, for mods that keep entities somewhere special. Null for none */
    public static java.util.function.BiConsumer<Recorder.Out, Entity> entityFollowUp = null;

    /** How long the last snapshot held the game up, for the tests that watch what recording costs */
    public static volatile double lastMillis = 0;

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

        ChunkPos centre = playback != null ? playback.centre() : minecraft.player.chunkPosition();
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
        minecraft.gui.getBossOverlay().events.values().forEach(bar -> out.packet(ClientboundBossEventPacket.createAddPacket(bar)));
        // What is drawn on each map the game has been sent. A server sends a map's picture once and then only what changes
        level.getAllMapData().forEach((id, map) -> {
            List<net.minecraft.world.level.saveddata.maps.MapDecoration> decorations = new ArrayList<>();
            map.getDecorations().forEach(decorations::add);
            out.packet(new ClientboundMapItemDataPacket(id, map.scale, map.locked, decorations,
                new net.minecraft.world.level.saveddata.maps.MapItemSavedData.MapPatch(0, 0, 128, 128, map.colors.clone())));
        });
    }

    /**
     * Lids that stand open. A chest or shulker box is told to open by a block event, once, and says nothing of it in
     * its own data, so a world rebuilt from its chunks would have every lid shut
     */
    private static void openLids(LevelChunk chunk, List<Packet<? super ClientGamePacketListener>> lids) {
        for (BlockEntity entity : chunk.getBlockEntities().values()) {
            boolean open = entity instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chest ? chest.chestLidController.shouldBeOpen
                : entity instanceof net.minecraft.world.level.block.entity.EnderChestBlockEntity ender ? ender.chestLidController.shouldBeOpen
                : entity instanceof net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity box
                && (box.getAnimationStatus() == net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity.AnimationStatus.OPENING
                || box.getAnimationStatus() == net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity.AnimationStatus.OPENED);
            if (open) {
                lids.add(new ClientboundBlockEventPacket(entity.getBlockPos(), entity.getBlockState().getBlock(), 1, 1));
            }
        }
    }

    private static int writeChunks(Recorder.Out out, Minecraft minecraft, ClientPacketListener listener, ClientLevel level, GameCodec codec) {
        ChunkPos centre = minecraft.player.chunkPosition();
        int radius = Math.max(minecraft.options.getEffectiveRenderDistance(), ((ClientPacketListenerAccess) listener).dailies$serverChunkRadius()) + 4;
        Set<Long> present = new HashSet<>();
        List<Packet<? super ClientGamePacketListener>> lids = new ArrayList<>();
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
                // A chunk with block entities is described afresh every time: mods change those in ways no packet shows
                byte[] stored = chunk.getBlockEntities().isEmpty() ? CHUNKS.get(key) : null;
                if (stored != null) {
                    out.sharedRef(Records.SHARED_PACKET, stored);
                    continue;
                }
                try {
                    byte[] encoded = codec.encode(new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null));
                    CHUNKS.put(key, out.sharedPacket(encoded));
                } catch (Throwable t) {
                    warnOnce("chunk", t);
                }
            }
        }
        CHUNKS.keySet().retainAll(present);
        lids.forEach(out::packet);
        return count;
    }

    /** The chunks of a playback world. One that nothing has touched since it was built is named again, not stored again */
    private static int writePlaybackChunks(Recorder.Out out, ClientLevel level, GameCodec codec, PlaybackWorld playback) {
        List<Packet<? super ClientGamePacketListener>> lids = new ArrayList<>();
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
                out.sharedRef(Records.SHARED_PACKET, stored);
                continue;
            }
            try {
                out.sharedPacket(codec.encode(new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null)));
            } catch (Throwable t) {
                warnOnce("chunk", t);
            }
        }
        lids.forEach(out::packet);
        return count;
    }

    private static int writeEntities(Recorder.Out out, ClientLevel level, GameCodec codec, @org.jetbrains.annotations.Nullable PlaybackWorld playback) {
        List<Entity> vehicles = new ArrayList<>();
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
            } catch (Throwable t) {
                warnOnce("entity " + BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()), t);
            }
        }
        // Riders are seated once everything that can be ridden exists
        for (Entity vehicle : vehicles) {
            out.packet(new ClientboundSetPassengersPacket(vehicle));
        }
        // And leashes are tied once both ends exist. A server says who holds a leash once, when it is tied
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof net.minecraft.world.entity.Leashable leashed && !entity.isRemoved() && leashed.getLeashHolder() != null
                && leashed.getLeashHolder().getId() != VIEWER_ENTITY_ID) {
                out.packet(new ClientboundSetEntityLinkPacket(entity, leashed.getLeashHolder()));
            }
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
