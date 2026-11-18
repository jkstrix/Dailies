package comedymonkey.dailies.playback;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.capture.PlayerInfoPackets;
import comedymonkey.dailies.capture.WorldData;
import comedymonkey.dailies.file.RecordingFile;
import comedymonkey.dailies.file.RecordingManifest;
import comedymonkey.dailies.file.Records;
import net.minecraft.client.Minecraft;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * Reads a recording's records in order and gives each to the game at its tick. It knows two things about where it
 * is: how many ticks of the recording have been played (the position), and how many more the record just read says
 * may pass before anything else happens.
 */
final class Feed {

    private final RecordingFile file;
    private final List<RecordingManifest.Segment> segments;
    private Session session;
    private Records.Reader reader;
    private int segmentIndex = -1;
    private int position = 0;
    private int ticksOwed = 0;
    private List<Packet<? super ClientGamePacketListener>> bundle = null;
    /** Set when play runs into a segment that does not follow on from the one before */
    private int reloadWanted = -1;
    private final Set<String> warned = new HashSet<>();
    boolean skipping = false;

    // --- Going back without rebuilding the world ---
    // A jump back used to throw the whole world away and build it again from the snapshot, which blanks the screen and
    // makes the game draw every chunk afresh. Now the world is kept: everything that moves is taken out and put back as
    // the snapshot has it, and only the chunks that have changed since they were last built are built again.

    /** For each chunk, the name of the stored chunk it was built from, for as long as nothing has changed it since */
    private final java.util.Map<Long, String> cleanChunks = new java.util.HashMap<>();
    /**
     * The same, for as long as nothing has changed its blocks, light or biomes since: what its block entities hold may
     * have changed (machines send theirs all the time), but what the game has drawn of it has not
     */
    private final java.util.Map<Long, String> sameBlocks = new java.util.HashMap<>();
    /**
     * Whether the snapshot being read is for someone watching (not an export or a tool), so that a chunk whose blocks
     * are what the kept world already has is left drawn as it is and only its block entities are told what they held
     */
    private boolean softChunks = false;
    private int chunksSoft = 0;
    /** Every chunk the world holds */
    private final Set<Long> loadedChunks = new HashSet<>();
    /** Which chunk each stored chunk is, learned the first time it is read */
    private final java.util.Map<String, Long> chunkOfStored = new java.util.HashMap<>();
    /** True while a snapshot is being read by {@link #load} */
    private boolean loading = false;
    /** The world that was open when the snapshot began can be kept, if the snapshot turns out to be of the same place */
    private boolean mayRewind = false;
    /** The world has been kept, and emptied of everything that moves */
    private boolean rewound = false;
    /** The chunks the snapshot being read has, when the world is being kept */
    private Set<Long> snapshotChunks = null;
    /** The name of the stored packet being applied right now, or null */
    private String storedNow = null;

    // --- What a snapshot taken from this world needs to know (a trim that cuts inside a segment) ---

    /** The chunk the recording last said its chunks are centred on */
    private int centreX = 0, centreZ = 0;
    /** The mod messages that came bundled with each entity's spawn, by entity id: what the live game keeps in Capture */
    private final java.util.Map<Integer, List<byte[]>> spawnExtras = new java.util.HashMap<>();
    private int bundleSpawn = Integer.MIN_VALUE;
    private List<byte[]> bundleExtras = null;

    /** This world as the snapshot writer needs it described */
    comedymonkey.dailies.capture.Snapshots.PlaybackWorld world() {
        Feed feed = this;
        return new comedymonkey.dailies.capture.Snapshots.PlaybackWorld() {
            @Override
            public byte[] worldName() {
                return HexFormat.of().parseHex(feed.session.worldName);
            }

            @Override
            public net.minecraft.world.level.ChunkPos centre() {
                return new net.minecraft.world.level.ChunkPos(feed.centreX, feed.centreZ);
            }

            @Override
            public Iterable<Long> chunks() {
                return new java.util.TreeSet<>(feed.loadedChunks);
            }

            @Override
            public byte[] storedChunk(long chunk) {
                String stored = feed.cleanChunks.get(chunk);
                return stored == null ? null : HexFormat.of().parseHex(stored);
            }

            @Override
            public List<byte[]> spawnExtras(int entityId) {
                return feed.spawnExtras.get(entityId);
            }

            @Override
            public java.util.UUID viewerProfile() {
                return Session.VIEWER.getId();
            }
        };
    }

    RecordingFile file() {
        return this.file;
    }

    Feed(RecordingFile file) {
        this.file = file;
        this.segments = file.segments();
        comedymonkey.dailies.capture.LightPasses.reset();
    }

    Session session() {
        return this.session;
    }

    int position() {
        return this.position;
    }

    /** The index of a segment the world has to be rebuilt from before play can go on, or -1 */
    int reloadWanted() {
        return this.reloadWanted;
    }

    int segmentIndexAt(int tick) {
        int found = 0;
        for (int i = 0; i < this.segments.size(); i++) {
            if (this.segments.get(i).startTick <= tick) {
                found = i;
            }
        }
        return found;
    }

    int segmentStart(int index) {
        return this.segments.get(index).startTick;
    }

    int segmentIndex() {
        return this.segmentIndex;
    }

    private final java.util.Map<Integer, Boolean> continues = new java.util.HashMap<>();

    /** Whether a segment follows on from the one before it, so that play can run from one into the other */
    boolean continuesFromPrevious(int index) {
        if (index <= 0) {
            return false;
        }
        return this.continues.computeIfAbsent(index, key -> {
            try (Records.Reader start = new Records.Reader(this.file.openSegment(this.segments.get(key).number))) {
                // A segment that does not follow on says so straight after the start of its snapshot
                return start.next() && start.kind() == Records.SNAPSHOT_BEGIN && start.next() && start.kind() != Records.DISCONTINUITY;
            } catch (IOException e) {
                return false;
            }
        });
    }

    /**
     * Rebuilds the world from a segment's snapshot. Must not be called from inside a game tick: the game draws a
     * frame of its own while it changes worlds.
     */
    void load(int index, Minecraft minecraft, boolean strict) throws IOException {
        this.closeReader();
        this.reloadWanted = -1;
        this.bundle = null;
        this.segmentIndex = index;
        RecordingManifest.Segment segment = this.segments.get(index);
        this.position = segment.startTick;
        this.ticksOwed = 0;
        this.reader = new Records.Reader(this.file.openSegment(segment.number));

        long started = System.nanoTime();
        this.chunksKept = 0;
        this.chunksBuilt = 0;
        this.chunksLetGo = 0;
        this.chunksSoft = 0;
        boolean plain = minecraft.level != null && this.plainWorld(minecraft) && this.plainSnapshot(segment);
        this.mayRewind = minecraft.level != null && this.session != null && !Boolean.getBoolean("dailies.playback.alwaysRebuild") && (plain || !strict);
        this.skipChunks = this.mayRewind && plain;
        this.softChunks = this.mayRewind && !strict && !Boolean.getBoolean("dailies.playback.noSoftChunks");
        this.keptModded = false;
        PlaybackExtensions.resetAll();
        clearCracks(minecraft);
        this.lightSettled = false;
        this.rewound = false;
        this.snapshotChunks = null;
        if (!this.mayRewind) {
            this.rebuild(minecraft);
        }
        // Everything up to the first tick record is the snapshot and whatever happened on its own tick
        this.loading = true;
        boolean inOrder = !Boolean.getBoolean("dailies.playback.lightOutOfOrder");
        boolean settled = false;
        try {
            while (this.reader.next()) {
                if (this.reader.kind() == Records.TICK) {
                    this.ticksOwed += this.reader.number();
                    break;
                }
                if (this.reader.kind() == Records.SNAPSHOT_END) {
                    this.dropChunksNotInSnapshot();
                    // The light that came with the snapshot's chunks is taken in now, before anything that happened after
                    // it, as the live game had taken in all of its own when it described its world. The game queues such
                    // light and takes it a little each frame; left waiting, it would be applied after the block changes
                    // of the ticks a jump then runs through, and put the old light back over them: the pit an explosion
                    // dug stayed as dark as the earth that had filled it, and the shadow of a wall outlived the wall
                    if (inOrder) {
                        Playback.settleLight(minecraft);
                        settled = true;
                    }
                }
                this.handle(minecraft);
            }
            this.dropChunksNotInSnapshot();
            // What arrived after the snapshot on its own tick waits, as it did live, when the recording says when the
            // live game took its light in (capture.LightPasses)
            if (inOrder && (!settled || !comedymonkey.dailies.capture.LightPasses.paced())) {
                Playback.settleLight(minecraft);
            }
        } finally {
            this.loading = false;
            this.mayRewind = false;
            this.rewound = false;
            this.snapshotChunks = null;
        }
        if (this.session == null) {
            throw new IOException("Segment " + segment.number + " of the recording does not start with a description of its world");
        }
        this.lastLoad = new Load((System.nanoTime() - started) / 1_000_000.0, this.chunksKept, this.chunksBuilt, this.chunksLetGo, segment.startTick, this.chunksSoft);
    }

    /**
     * What reading one snapshot into the world cost: how long it took, how many chunks were left as they were, how many
     * built, and how many the world held that the snapshot does not and were let go; the tick the snapshot is of; and
     * how many of those left as they were had only their block entities told what they held
     */
    record Load(double millis, int chunksKept, int chunksBuilt, int chunksLetGo, int tick, int chunksSoft) {
    }

    private int chunksLetGo = 0;

    /** Whether a chunk is on the list of those the world holds, for the tests that look for chunks left behind */
    boolean lists(int x, int z) {
        return this.loadedChunks.contains(net.minecraft.world.level.ChunkPos.asLong(x, z));
    }

    private Load lastLoad = null;
    private int chunksKept = 0;
    private int chunksBuilt = 0;

    Load lastLoad() {
        return this.lastLoad;
    }

    // Keeping the world is proven for what the game itself puts in it. With machines from mods about (a turning
    // contraption, a physics craft), a mob they carry loose has been seen to land half a block out after a jump into a
    // kept world, about one run in three, and never after the world was built from nothing. Why is not yet understood.
    // So a jump that has to be exact (an export, a tool) builds such a world from nothing, and a jump made by someone
    // watching keeps it, with every chunk applied again, because the wait and the blank screen are worse than the risk.

    private boolean keptModded = false;

    /** Whether the last snapshot was read into a kept world that has mod machines in it */
    boolean keptModded() {
        return this.keptModded;
    }

    /** Whether chunks that have not changed are left as they are by the snapshot being read */
    private boolean skipChunks = false;

    private boolean plainWorld(Minecraft minecraft) {
        if (PlaybackExtensions.anyNeedsNewWorld()) {
            return false;
        }
        for (net.minecraft.world.entity.Entity entity : minecraft.level.entitiesForRendering()) {
            if (!net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getNamespace().equals("minecraft")) {
                return false;
            }
        }
        return true;
    }

    /** No mod has put a message of its own in the snapshot: a mod's entity arrives with one, and so does a craft */
    private boolean plainSnapshot(RecordingManifest.Segment segment) {
        try (Records.Reader start = new Records.Reader(this.file.openSegment(segment.number))) {
            while (start.next()) {
                int kind = start.kind();
                if (kind == Records.TICK || kind == Records.SNAPSHOT_END) {
                    return true;
                }
                if (kind == Records.PAYLOAD) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Throws the world away, to be built again from nothing by the snapshot being read */
    private void rebuild(Minecraft minecraft) {
        if (minecraft.level != null) {
            minecraft.clearClientLevel(new net.minecraft.client.gui.screens.GenericMessageScreen(net.minecraft.network.chat.Component.translatable("dailies.playback.jumping")));
        }
        if (this.session != null) {
            this.session.close();
            this.session = null;
        }
        this.mayRewind = false;
        this.rewound = false;
        this.snapshotChunks = null;
        this.cleanChunks.clear();
        this.sameBlocks.clear();
        this.loadedChunks.clear();
        this.spawnExtras.clear();
    }

    /**
     * The snapshot's first packet, the one that makes a world. When the world that is open is the one it describes, the
     * world is kept and emptied instead.
     *
     * @return true when the world was kept, and the packet must not be applied
     */
    private boolean keepWorld(ClientboundLoginPacket login, Minecraft minecraft) {
        if (!this.loading) {
            return false;
        }
        if (this.mayRewind && minecraft.level != null && minecraft.player != null
            && minecraft.level.dimension().equals(login.commonPlayerSpawnInfo().dimension())) {
            try {
                this.emptyWorld(minecraft);
                this.rewound = true;
                this.keptModded = !this.skipChunks;
                this.snapshotChunks = new HashSet<>();
                return true;
            } catch (Throwable t) {
                this.warnOnce("going back without rebuilding the world", t);
            }
        }
        if (minecraft.level != null) {
            // Another dimension, or the world could not be emptied: build from nothing, with a connection of its own
            String name = this.session.worldName;
            this.rebuild(minecraft);
            try {
                this.session = Session.create(minecraft, name, WorldData.fromBytes(this.file.shared(name)));
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
        return false;
    }

    /** Takes out of the kept world everything a snapshot puts in: whatever is left afterwards would be there twice */
    private void emptyWorld(Minecraft minecraft) {
        net.minecraft.client.multiplayer.ClientLevel level = minecraft.level;
        net.minecraft.client.multiplayer.ClientPacketListener listener = this.session.listener;
        it.unimi.dsi.fastutil.ints.IntArrayList ids = new it.unimi.dsi.fastutil.ints.IntArrayList();
        for (net.minecraft.world.entity.Entity entity : level.entitiesForRendering()) {
            if (entity != minecraft.player) {
                ids.add(entity.getId());
            }
        }
        if (!ids.isEmpty()) {
            new net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket(ids).handle(listener);
        }
        this.spawnExtras.clear();
        List<java.util.UUID> listed = new ArrayList<>();
        for (net.minecraft.client.multiplayer.PlayerInfo info : listener.getOnlinePlayers()) {
            if (!info.getProfile().getId().equals(Session.VIEWER.getId())) {
                listed.add(info.getProfile().getId());
            }
        }
        if (!listed.isEmpty()) {
            new net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket(listed).handle(listener);
        }
        net.minecraft.world.scores.Scoreboard scoreboard = level.getScoreboard();
        for (net.minecraft.world.scores.Objective objective : new ArrayList<>(scoreboard.getObjectives())) {
            scoreboard.removeObjective(objective);
        }
        for (net.minecraft.world.scores.PlayerTeam team : new ArrayList<>(scoreboard.getPlayerTeams())) {
            scoreboard.removePlayerTeam(team);
        }
        minecraft.gui.getBossOverlay().reset();
        minecraft.gui.getChat().clearMessages(false);
        new net.minecraft.network.protocol.game.ClientboundClearTitlesPacket(true).handle(listener);
        // The snapshot says how hard it is raining, and that it has begun when it has; never that it has stopped
        level.getLevelData().setRaining(false);
    }

    /** At the end of a snapshot read into a kept world: chunks the world holds that the snapshot does not have go */
    private void dropChunksNotInSnapshot() {
        if (!this.rewound || this.snapshotChunks == null || this.session == null) {
            return;
        }
        Set<Long> kept = this.snapshotChunks;
        this.snapshotChunks = null;
        net.minecraft.client.multiplayer.ClientLevel level = this.session.listener.getLevel();
        boolean moved = false;
        // What the game really holds, as well as what the list says: a chunk the list lost track of would otherwise stay
        Set<Long> held = new java.util.LinkedHashSet<>(this.loadedChunks);
        if (level != null) {
            held.addAll(heldChunks(level));
        }
        for (long key : held) {
            if (!kept.contains(key)) {
                this.chunksLetGo++;
                try {
                    net.minecraft.world.level.ChunkPos pos = new net.minecraft.world.level.ChunkPos(key);
                    if (level != null) {
                        // A game lets go of a chunk only within reach of the place its chunks are centred on, and the
                        // snapshot has just moved that place. A chunk left behind by a long journey would otherwise stay,
                        // unseen, and show itself as it was at the later time when the recorder next came that way
                        level.getChunkSource().updateViewCenter(pos.x, pos.z);
                        moved = true;
                    }
                    new net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket(pos).handle(this.session.listener);
                } catch (Throwable t) {
                    this.warnOnce("dropping a chunk when going back", t);
                }
                this.loadedChunks.remove(key);
                this.cleanChunks.remove(key);
                this.sameBlocks.remove(key);
            }
        }
        if (moved) {
            level.getChunkSource().updateViewCenter(this.centreX, this.centreZ);
        }
    }

    /**
     * Every chunk the game holds, within reach of the place its chunks are centred on or not. The game keeps a chunk it
     * was told to forget while it was out of reach, unseen, until another chunk takes its place in its table or the centre
     * comes back that way and shows it again
     */
    private static Set<Long> heldChunks(net.minecraft.client.multiplayer.ClientLevel level) {
        Set<Long> held = new HashSet<>();
        java.util.concurrent.atomic.AtomicReferenceArray<net.minecraft.world.level.chunk.LevelChunk> chunks = level.getChunkSource().storage.chunks;
        for (int i = 0; i < chunks.length(); i++) {
            net.minecraft.world.level.chunk.LevelChunk chunk = chunks.get(i);
            if (chunk != null) {
                held.add(chunk.getPos().toLong());
            }
        }
        return held;
    }

    /**
     * Takes away the cracks on every block someone was mining. A snapshot has none (whoever is still mining is heard of
     * again just after it), and the game keeps cracks with whoever made them, not with the world: changing worlds leaves
     * them, and so does taking the miner out. After a jump back the log the recorder was about to mine stood cracked
     */
    private static void clearCracks(Minecraft minecraft) {
        net.minecraft.client.renderer.LevelRenderer renderer = minecraft.levelRenderer;
        if (renderer == null) {
            return;
        }
        for (int breaker : new it.unimi.dsi.fastutil.ints.IntArrayList(renderer.destroyingBlocks.keySet())) {
            renderer.destroyBlockProgress(breaker, net.minecraft.core.BlockPos.ZERO, -1);
        }
    }

    /** Whether the game holds this chunk, in reach or not */
    private static boolean holds(net.minecraft.client.multiplayer.ClientLevel level, int x, int z) {
        net.minecraft.client.multiplayer.ClientChunkCache.Storage storage = level.getChunkSource().storage;
        net.minecraft.world.level.chunk.LevelChunk chunk = storage.chunks.get(storage.getIndex(x, z));
        return chunk != null && chunk.getPos().x == x && chunk.getPos().z == z;
    }

    /**
     * After a chunk arrived or was to be forgotten: the list follows what the game did with it, not what it should have
     * done. The game takes a chunk only within reach of its centre and lets one go only within reach, and its reach and
     * centre are its own; a chunk it kept that the list had let go would never be taken out by a jump back
     */
    private void followGame(Packet<?> packet) {
        if (packet instanceof ClientboundBundlePacket bundle) {
            bundle.subPackets().forEach(this::followGame);
            return;
        }
        net.minecraft.client.multiplayer.ClientLevel level = this.session == null ? null : this.session.listener.getLevel();
        if (level == null) {
            return;
        }
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket forgotten) {
            long key = forgotten.pos().toLong();
            if (holds(level, forgotten.pos().x, forgotten.pos().z)) {
                this.loadedChunks.add(key);
            } else {
                this.loadedChunks.remove(key);
            }
        } else if (packet instanceof net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket chunk) {
            if (!holds(level, chunk.getX(), chunk.getZ())) {
                this.loadedChunks.remove(net.minecraft.world.level.ChunkPos.asLong(chunk.getX(), chunk.getZ()));
            }
        }
    }

    /** Whether a stored chunk in a snapshot is what the kept world already has in that place, untouched */
    private boolean alreadyThere(String stored, Minecraft minecraft) {
        if (!this.rewound || this.snapshotChunks == null || (!this.skipChunks && !this.softChunks) || minecraft.level == null) {
            return false;
        }
        Long key = this.chunkOfStored.get(stored);
        if (key == null) {
            return false;
        }
        net.minecraft.world.level.chunk.LevelChunk chunk = minecraft.level.getChunkSource().getChunk(net.minecraft.world.level.ChunkPos.getX(key),
            net.minecraft.world.level.ChunkPos.getZ(key), net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false);
        if (chunk == null) {
            return false;
        }
        // Machines change in ways no packet shows, so a chunk with any in it is built again for an exact jump
        if (this.skipChunks && stored.equals(this.cleanChunks.get(key)) && chunk.getBlockEntities().isEmpty()) {
            this.snapshotChunks.add(key);
            this.chunksKept++;
            return true;
        }
        // For someone watching: blocks as they were drawn, so the chunk stays drawn and its block entities are told what
        // they held, as the game tells them from a chunk it is sent, in place. Building it again would have the game draw
        // every such chunk afresh at every jump, which is most of what made dragging the playhead back stutter
        if (this.softChunks && stored.equals(this.sameBlocks.get(key)) && this.retell(stored, chunk, minecraft)) {
            this.snapshotChunks.add(key);
            this.chunksKept++;
            this.chunksSoft++;
            return true;
        }
        return false;
    }

    /**
     * Gives each block entity of a chunk what the stored chunk says it held, as the game does with a chunk it is sent.
     * False, with nothing changed, when the chunk's block entities are not the ones the stored chunk speaks of: then it
     * is built again as usual
     */
    private boolean retell(String stored, net.minecraft.world.level.chunk.LevelChunk chunk, Minecraft minecraft) {
        net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket packet;
        try {
            byte[] shared = this.file.shared(stored);
            if (!(this.session.codec.decode(shared, 0, shared.length) instanceof net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket decoded)) {
                return false;
            }
            packet = decoded;
        } catch (Throwable t) {
            return false;
        }
        java.util.List<Object[]> told = new ArrayList<>();
        boolean[] matches = {true};
        packet.getChunkData().getBlockEntitiesTagsConsumer(packet.getX(), packet.getZ()).accept((pos, type, tag) -> {
            net.minecraft.world.level.block.entity.BlockEntity entity = chunk.getBlockEntity(pos, net.minecraft.world.level.chunk.LevelChunk.EntityCreationType.CHECK);
            if (entity == null || entity.getType() != type) {
                matches[0] = false;
            } else if (tag != null) {
                told.add(new Object[]{entity, tag});
            }
        });
        if (!matches[0]) {
            return false;
        }
        net.minecraft.core.HolderLookup.Provider registries = minecraft.level.registryAccess();
        for (Object[] each : told) {
            try {
                ((net.minecraft.world.level.block.entity.BlockEntity) each[0]).handleUpdateTag((net.minecraft.nbt.CompoundTag) each[1], registries);
            } catch (Throwable t) {
                this.warnOnce("telling a block entity what it held when going back", t);
            }
        }
        return true;
    }

    /** Keeps count of which chunks the world holds and which have changed since they were built */
    private void track(Packet<?> packet, Minecraft minecraft) {
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket centre) {
            this.centreX = centre.getX();
            this.centreZ = centre.getZ();
        } else if (packet instanceof net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket removed) {
            for (int id : removed.getEntityIds()) {
                this.spawnExtras.remove(id);
            }
        }
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket chunk) {
            if (minecraft.level != null && comedymonkey.dailies.Host.crafts().isInPlot(minecraft.level, chunk.getX() * 16.0 + 8.0, chunk.getZ() * 16.0 + 8.0)) {
                // Part of a physics craft, which is taken out and put back whole
                return;
            }
            long key = net.minecraft.world.level.ChunkPos.asLong(chunk.getX(), chunk.getZ());
            this.loadedChunks.add(key);
            if (this.loading) {
                this.chunksBuilt++;
            }
            if (this.storedNow != null) {
                this.cleanChunks.put(key, this.storedNow);
                this.sameBlocks.put(key, this.storedNow);
                this.chunkOfStored.put(this.storedNow, key);
            } else {
                this.cleanChunks.remove(key);
                this.sameBlocks.remove(key);
            }
            if (this.snapshotChunks != null) {
                this.snapshotChunks.add(key);
            }
        } else if (this.cleanChunks.isEmpty() && this.sameBlocks.isEmpty()) {
            if (packet instanceof net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket forgotten && this.letsGoOf(forgotten.pos())) {
                this.loadedChunks.remove(forgotten.pos().toLong());
            }
        } else if (packet instanceof net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket update) {
            this.changed(update.getPos(), true);
        } else if (packet instanceof net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket updates) {
            updates.runUpdates((pos, state) -> this.changed(pos, true));
        } else if (packet instanceof net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket data) {
            this.changed(data.getPos(), false);
        } else if (packet instanceof net.minecraft.network.protocol.game.ClientboundBlockEventPacket event) {
            this.changed(event.getPos(), false);
        } else if (packet instanceof net.minecraft.network.protocol.game.ClientboundLightUpdatePacket light) {
            this.cleanChunks.remove(net.minecraft.world.level.ChunkPos.asLong(light.getX(), light.getZ()));
            this.sameBlocks.remove(net.minecraft.world.level.ChunkPos.asLong(light.getX(), light.getZ()));
        } else if (packet instanceof net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket forgotten) {
            if (this.letsGoOf(forgotten.pos())) {
                this.loadedChunks.remove(forgotten.pos().toLong());
            }
            this.cleanChunks.remove(forgotten.pos().toLong());
            this.sameBlocks.remove(forgotten.pos().toLong());
        } else if (packet instanceof net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket biomes) {
            biomes.chunkBiomeData().forEach(data -> {
                this.cleanChunks.remove(data.pos().toLong());
                this.sameBlocks.remove(data.pos().toLong());
            });
        } else if (packet instanceof net.minecraft.network.protocol.game.ClientboundExplodePacket explosion) {
            explosion.getToBlow().forEach(pos -> this.changed(pos, true));
        } else if (packet instanceof ClientboundBundlePacket bundle) {
            bundle.subPackets().forEach(part -> this.track(part, minecraft));
        }
    }

    /**
     * Whether the game will act on being told to forget a chunk. It only does within reach of the place its chunks are
     * centred on. After a teleport the server moves that place first and then lists the chunks left behind, which are by
     * then out of reach: the game keeps every one of them, unseen. They must stay on the list here, or the next jump back
     * would not know to take them out, and they would show themselves again when the recorder came back that way.
     */
    private boolean letsGoOf(net.minecraft.world.level.ChunkPos pos) {
        if (this.session == null) {
            return true;
        }
        int reach = Math.max(2, ((comedymonkey.dailies.mixin.access.ClientPacketListenerAccess) this.session.listener).dailies$serverChunkRadius()) + 3;
        return Math.abs(pos.x - this.centreX) <= reach && Math.abs(pos.z - this.centreZ) <= reach;
    }

    /**
     * @param spreads whether the change can alter the light, which reaches into the chunks next door
     */
    private void changed(net.minecraft.core.BlockPos pos, boolean spreads) {
        int x = pos.getX() >> 4;
        int z = pos.getZ() >> 4;
        if (!spreads) {
            // What a block entity holds, or a block's event: nothing drawn of the blocks has changed
            this.cleanChunks.remove(net.minecraft.world.level.ChunkPos.asLong(x, z));
            return;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                this.cleanChunks.remove(net.minecraft.world.level.ChunkPos.asLong(x + dx, z + dz));
                this.sameBlocks.remove(net.minecraft.world.level.ChunkPos.asLong(x + dx, z + dz));
            }
        }
    }

    /**
     * Moves the recording on by one tick and applies everything recorded on that tick.
     *
     * @return false at the end of the recording, or when the world has to be rebuilt first (see {@link #reloadWanted})
     */
    boolean advance(Minecraft minecraft) throws IOException {
        if (this.reloadWanted >= 0) {
            return false;
        }
        while (this.ticksOwed == 0) {
            if (!this.nextRecord()) {
                return false;
            }
            if (this.reader.kind() == Records.TICK) {
                this.ticksOwed += this.reader.number();
            } else {
                this.handle(minecraft);
            }
        }
        this.ticksOwed--;
        this.position++;
        while (this.ticksOwed == 0) {
            if (!this.nextRecord()) {
                break;
            }
            if (this.reader.kind() == Records.TICK) {
                this.ticksOwed += this.reader.number();
            } else {
                this.handle(minecraft);
            }
        }
        return true;
    }

    /** The next record, moving into the next segment when this one is finished */
    private boolean nextRecord() throws IOException {
        while (true) {
            if (this.reader.next()) {
                return true;
            }
            if (this.segmentIndex + 1 >= this.segments.size()) {
                return false;
            }
            this.closeReader();
            this.segmentIndex++;
            this.reader = new Records.Reader(this.file.openSegment(this.segments.get(this.segmentIndex).number));
            if (comedymonkey.dailies.capture.LightPasses.paced()) {
                // The live game took in all of its light as it described its world for this segment
                Playback.settleLight(Minecraft.getInstance());
            }
            // The world is already as the next snapshot describes it, unless the recording says otherwise
            boolean inSnapshot = false;
            while (this.reader.next()) {
                int kind = this.reader.kind();
                if (kind == Records.SNAPSHOT_BEGIN) {
                    inSnapshot = true;
                } else if (kind == Records.DISCONTINUITY && inSnapshot) {
                    this.reloadWanted = this.segmentIndex;
                    return false;
                } else if (kind == Records.SNAPSHOT_END) {
                    break;
                } else if (!inSnapshot) {
                    return true;
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void handle(Minecraft minecraft) throws IOException {
        switch (this.reader.kind()) {
            case Records.PACKET -> this.packet(this.reader.payload(), 0, this.reader.length(), minecraft);
            case Records.SHARED_PACKET -> {
                String stored = HexFormat.of().formatHex(this.reader.payload(), 0, this.reader.length());
                if (this.alreadyThere(stored, minecraft)) {
                    return;
                }
                byte[] shared = this.file.shared(stored);
                this.storedNow = this.bundle == null ? stored : null;
                try {
                    this.packet(shared, 0, shared.length, minecraft);
                } finally {
                    this.storedNow = null;
                }
            }
            case Records.PAYLOAD -> {
                String id = this.reader.name();
                if (this.bundle != null && this.bundleSpawn != Integer.MIN_VALUE) {
                    // Kept as it was recorded, whether or not this game can read it: a snapshot written from here hands it on
                    if (this.bundleExtras == null) {
                        this.bundleExtras = new ArrayList<>();
                    }
                    this.bundleExtras.add(java.util.Arrays.copyOfRange(this.reader.payload(), this.reader.bodyStart(), this.reader.length()));
                }
                if (PayloadRules.ignored(id) || this.session == null) {
                    return;
                }
                // A message whose mod is not installed here is dropped without a word (PLAY-12)
                ResourceLocation location = ResourceLocation.tryParse(id);
                if (location == null || !NetworkRegistry.hasChannel(this.session.connection, ConnectionProtocol.PLAY, location)) {
                    return;
                }
                int from = this.reader.bodyStart();
                this.packet(this.reader.payload(), from, this.reader.length() - from, minecraft);
            }
            case Records.WORLD -> {
                String name = HexFormat.of().formatHex(this.reader.payload(), 0, this.reader.length());
                if (this.session == null || !this.session.worldName.equals(name)) {
                    if (this.loading) {
                        // Other registries: nothing of the world that is open can be kept
                        this.rebuild(minecraft);
                    } else if (this.session != null) {
                        this.session.close();
                    }
                    this.session = Session.create(minecraft, name, WorldData.fromBytes(this.file.shared(name)));
                }
            }
            case Records.BUNDLE_BEGIN -> {
                this.bundle = new ArrayList<>();
                this.bundleSpawn = Integer.MIN_VALUE;
                this.bundleExtras = null;
            }
            case Records.BUNDLE_END -> {
                List<Packet<? super ClientGamePacketListener>> parts = this.bundle;
                this.bundle = null;
                if (this.bundleSpawn != Integer.MIN_VALUE && this.bundleExtras != null) {
                    this.spawnExtras.put(this.bundleSpawn, this.bundleExtras);
                }
                this.bundleSpawn = Integer.MIN_VALUE;
                this.bundleExtras = null;
                if (parts != null && !parts.isEmpty()) {
                    this.apply(new ClientboundBundlePacket(parts), minecraft);
                }
            }
            case Records.EXTENSION -> {
                String id = this.reader.name();
                PlaybackExtensions.Extension extension = PlaybackExtensions.find(id);
                if (extension != null) {
                    try {
                        int from = this.reader.bodyStart();
                        extension.play(this.reader.payload(), from, this.reader.length() - from, this.skipping);
                    } catch (Throwable t) {
                        this.warnOnce("extension " + id, t);
                    }
                }
            }
            default -> {
                // Snapshot markers, and kinds this version does not know
            }
        }
    }

    private void packet(byte[] bytes, int from, int length, Minecraft minecraft) {
        if (this.session == null) {
            return;
        }
        Packet<? super ClientGamePacketListener> packet;
        try {
            packet = this.session.codec.decode(bytes, from, length);
        } catch (Throwable t) {
            // Something in it this game does not have: another mod set, usually (PLAY-5)
            this.warnOnce("a packet that cannot be read", t);
            return;
        }
        if (this.bundle != null) {
            if (packet instanceof net.minecraft.network.protocol.game.ClientboundAddEntityPacket spawn) {
                this.bundleSpawn = spawn.getId();
            }
            this.bundle.add(packet);
        } else {
            this.apply(packet, minecraft);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void apply(Packet<? super ClientGamePacketListener> packet, Minecraft minecraft) {
        if (packet instanceof ClientboundLoginPacket login && this.keepWorld(login, minecraft)) {
            return;
        }
        try {
            if (this.rewound && packet instanceof net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket chunk && this.lightLeftBehind(chunk, minecraft)) {
                // Only forgetting the chunk makes the game drop light the packet does not speak of; it is built again at once
                new net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket(new net.minecraft.world.level.ChunkPos(chunk.getX(), chunk.getZ())).handle(this.session.listener);
            }
            this.track(packet, minecraft);
            // What a recorded packet says in the chat is the recording's. Not while a world opens: mods speak up then of
            // their own accord, about playback, not the recording (playback.PlaybackChat)
            boolean speaks = !(packet instanceof ClientboundLoginPacket) && !(packet instanceof net.minecraft.network.protocol.game.ClientboundRespawnPacket);
            if (speaks) {
                PlaybackChat.enterRecorded();
            }
            try {
                ((Packet) packet).handle(this.session.listener);
            } finally {
                if (speaks) {
                    PlaybackChat.leaveRecorded();
                }
            }
            this.followGame(packet);
            if (packet instanceof ClientboundLoginPacket) {
                // The viewer is a spectator who is in nobody's tab list
                ClientboundPlayerInfoUpdatePacket viewer = PlayerInfoPackets.build(this.session.codec, PlayerInfoPackets.allButChat(),
                    List.of(new ClientboundPlayerInfoUpdatePacket.Entry(Session.VIEWER.getId(), Session.VIEWER, false, 0, GameType.SPECTATOR, null, null)));
                viewer.handle(this.session.listener);
                Playback.viewerJoined(minecraft);
            }
        } catch (Throwable t) {
            this.warnOnce(packet.getClass().getSimpleName(), t);
        }
    }

    /**
     * Whether a chunk of a kept world holds light that the snapshot's packet for it would leave standing. A chunk
     * packet gives the light of the sections that had any and names the ones that were dark; of a section the server
     * kept no light for (empty air, well clear of everything) it says nothing, and the game then leaves whatever it has
     * there. In a world built from nothing that is nothing. In a kept world it may be the glow of a fire or a torch
     * from later in the recording, or the shadow of a block that is not there yet
     */
    private boolean lightLeftBehind(net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket packet, Minecraft minecraft) {
        net.minecraft.client.multiplayer.ClientLevel level = minecraft.level;
        // Written from reading the game, and never shown to be needed by a test: off unless asked for, until one shows it
        if (level == null || !Boolean.getBoolean("dailies.playback.forgetStaleLight") || !level.getChunkSource().hasChunk(packet.getX(), packet.getZ())) {
            return false;
        }
        net.minecraft.world.level.lighting.LevelLightEngine engine = level.getLightEngine();
        if (!this.lightSettled) {
            // What the game holds is only known once everything it has been sent has been taken in
            this.lightSettled = true;
            comedymonkey.dailies.capture.LightPasses.settle(level);
        }
        net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData light = packet.getLightData();
        for (int index = 0; index < engine.getLightSectionCount(); index++) {
            net.minecraft.core.SectionPos section = net.minecraft.core.SectionPos.of(packet.getX(), engine.getMinLightSection() + index, packet.getZ());
            if (!light.getBlockYMask().get(index) && !light.getEmptyBlockYMask().get(index) && engine.getLayerListener(net.minecraft.world.level.LightLayer.BLOCK).getDataLayerData(section) != null) {
                return true;
            }
            if (!light.getSkyYMask().get(index) && !light.getEmptySkyYMask().get(index) && engine.getLayerListener(net.minecraft.world.level.LightLayer.SKY).getDataLayerData(section) != null) {
                return true;
            }
        }
        return false;
    }

    /** Whether the light of the kept world has been brought up to date for the snapshot being read */
    private boolean lightSettled = false;

    private void warnOnce(String what, Throwable problem) {
        if (this.warned.add(what)) {
            Host.LOGGER.warn("Dailies: playback could not show {} and carried on without it. Said once per kind", what, problem);
        }
    }

    /** What could not be shown so far, for the list a person is given once (PLAY-5) */
    Set<String> problems() {
        return this.warned;
    }

    private void closeReader() {
        if (this.reader != null) {
            try {
                this.reader.close();
            } catch (IOException ignored) {
                // Nothing to save
            }
            this.reader = null;
        }
    }

    void close() {
        this.closeReader();
        if (this.session != null) {
            this.session.close();
            this.session = null;
        }
    }

}
