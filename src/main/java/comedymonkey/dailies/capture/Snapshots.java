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
            Host.LOGGER.warn("Dailies: a {} could not describe itself for a snapshot and is recorded without its own data. Said once per kind. ({})", what, problem.toString());
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

    static void write(Recorder.Out out, Minecraft minecraft, ClientPacketListener listener, GameCodec codec) {
        long started = System.nanoTime();
        taking = true;
        try {
            ClientLevel level = minecraft.level;
            writeWorld(out, minecraft, listener, level);
            int chunks = writeChunks(out, minecraft, listener, level, codec);
            int entities = writeEntities(out, level, codec);
            SnapshotExtensions.all().forEach((id, extension) -> {
                try {
                    extension.write(out, codec);
                } catch (Throwable t) {
                    warnOnce("snapshot extension " + id, t);
                }
            });
            Host.LOGGER.debug("Dailies: snapshot of {} chunks and {} entities took {} ms", chunks, entities, (System.nanoTime() - started) / 1_000_000);
            lastMillis = (System.nanoTime() - started) / 1_000_000.0;
        } finally {
            taking = false;
        }
    }

    /** How long the last snapshot held the game up, for the tests that watch what recording costs */
    public static volatile double lastMillis = 0;

    private static void writeWorld(Recorder.Out out, Minecraft minecraft, ClientPacketListener listener, ClientLevel level) {
        if (world == null || worldOf != listener) {
            world = WorldData.capture(listener).toBytes();
            worldOf = listener;
        }
        out.sharedRecord(Records.WORLD, world);

        ClientPacketListenerAccess distances = (ClientPacketListenerAccess) listener;
        boolean flat = level.getLevelData().getHorizonHeight(level) == level.getMinBuildHeight();
        out.packet(new ClientboundLoginPacket(VIEWER_ENTITY_ID, level.getLevelData().isHardcore(), listener.levels(), 20, distances.dailies$serverChunkRadius(),
            distances.dailies$serverSimulationDistance(), false, false, false,
            new CommonPlayerSpawnInfo(level.dimensionTypeRegistration(), level.dimension(), ((BiomeManagerAccess) level.getBiomeManager()).dailies$biomeZoomSeed(),
                GameType.SPECTATOR, null, level.isDebug(), flat, Optional.empty(), 0), false));

        ChunkPos centre = minecraft.player.chunkPosition();
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
    }

    private static int writeChunks(Recorder.Out out, Minecraft minecraft, ClientPacketListener listener, ClientLevel level, GameCodec codec) {
        ChunkPos centre = minecraft.player.chunkPosition();
        int radius = Math.max(minecraft.options.getEffectiveRenderDistance(), ((ClientPacketListenerAccess) listener).dailies$serverChunkRadius()) + 4;
        Set<Long> present = new HashSet<>();
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
        return count;
    }

    private static int writeEntities(Recorder.Out out, ClientLevel level, GameCodec codec) {
        List<Entity> vehicles = new ArrayList<>();
        int count = 0;
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof Marker || entity.isRemoved()) {
                continue;
            }
            try {
                writeEntity(out, entity, codec);
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
        return count;
    }

    private static void writeEntity(Recorder.Out out, Entity entity, GameCodec codec) {
        Packet<? super ClientGamePacketListener> spawn = spawnPacket(entity, codec);
        List<byte[]> extras = Capture.spawnExtras(entity.getId());
        byte[] built = null;
        if (extras == null && entity instanceof IEntityWithComplexSpawn) {
            // Its spawn was never seen (it was there before the game listened). Ask the entity itself, which may fail
            try {
                built = codec.encode(new AdvancedAddEntityPayload(entity).toVanillaClientbound());
            } catch (Throwable t) {
                warnOnce("entity " + BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()) + " (extra spawn data)", t);
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

    private static Packet<? super ClientGamePacketListener> spawnPacket(Entity entity, GameCodec codec) {
        if (entity instanceof ExperienceOrb orb) {
            RegistryFriendlyByteBuf buffer = codec.buffer();
            try {
                buffer.writeVarInt(orb.getId());
                buffer.writeDouble(orb.getX());
                buffer.writeDouble(orb.getY());
                buffer.writeDouble(orb.getZ());
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
        return new ClientboundAddEntityPacket(entity.getId(), entity.getUUID(), entity.getX(), entity.getY(), entity.getZ(), entity.getXRot(), entity.getYRot(),
            entity.getType(), data, entity.getDeltaMovement(), entity.getYHeadRot());
    }

}
