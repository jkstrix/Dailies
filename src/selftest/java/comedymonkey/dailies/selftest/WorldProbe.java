package comedymonkey.dailies.selftest;

import comedymonkey.dailies.capture.Snapshots;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;

/**
 * Everything about the world a game holds at one moment that the generated-terrain steps compare between the live
 * game and playback: which chunks are loaded and what is in each (blocks, biomes, light, block entities), every
 * entity, the sky, and whatever a step has asked to be watched closely. Taken on the client thread.
 * <p>
 * How exact each comparison is, and why:
 * <ul>
 * <li>Loaded chunks, blocks, biomes, block entities, the set of entities: exact. A recording holds every packet that
 * changed them, so nothing excuses a difference.</li>
 * <li>One kind of chunk is let off, and counted: a chunk the live game still held although the server had stopped
 * sending it. When a player is teleported the server moves the centre of their chunks first and lists the chunks to
 * forget afterwards; by then they are out of the game's reach and it keeps them, unseen, until the player comes back
 * that way or something else takes their place in its table. A snapshot describes the chunks within reach, so a jump
 * that starts from a snapshot taken elsewhere does not have these leftovers. They lie beyond the server's view distance
 * from the recorder, so they are drawn only by a game whose render distance is greater than that, and they are stale:
 * the server sends nothing more about them.</li>
 * <li>Blocks in the seven-by-seven chunks around the recorder are compared place by place; further out, by how many of
 * each block state every sixteen-block section holds, which misses only two blocks swapping places.</li>
 * <li>Light: exact, at a lattice of places in every chunk and at every block of a watched box.</li>
 * <li>A named entity's place: a third of a block (MOD-2) plus one tick of its own travel, because a jump runs one
 * more game tick after the last recorded packet than the live game had run when the note was taken.</li>
 * <li>The recorder's place: a third of a block plus two and a half ticks of travel. A watching game eases another
 * player towards each reported place over three ticks, so at speed they are drawn two ticks behind where the live
 * player stood; that is how every multiplayer game shows other players, and playback is one.</li>
 * <li>The time of day: two ticks. Rain and thunder: 0.03, three ticks of a change. Sky colour: 0.03 a channel.</li>
 * </ul>
 */
final class WorldProbe {

    record Tracked(String type, Vec3 at, double speed, String state) {
    }

    /** A box of blocks compared place by place, with its light when asked */
    record Region(String name, BlockPos min, BlockPos max, boolean light) {
        static Region around(String name, BlockPos centre, int across, int down, int up, boolean light) {
            return new Region(name, centre.offset(-across, -down, -across), centre.offset(across, up, across), light);
        }

        /** Where a block of the box is in the arrays a note keeps for it */
        int index(BlockPos pos) {
            int width = this.max.getX() - this.min.getX() + 1;
            int depth = this.max.getZ() - this.min.getZ() + 1;
            return ((pos.getY() - this.min.getY()) * depth + pos.getZ() - this.min.getZ()) * width + pos.getX() - this.min.getX();
        }

        int size() {
            return (this.max.getX() - this.min.getX() + 1) * (this.max.getY() - this.min.getY() + 1) * (this.max.getZ() - this.min.getZ() + 1);
        }
    }

    /** The columns of a chunk whose light is sampled, as x then z */
    private static final int[] LIGHT_COLUMNS = {1, 1, 8, 8, 14, 14, 3, 12, 12, 3};
    private static final int LIGHT_STEP = 24;
    /** How far from the recorder's chunk blocks are compared place by place */
    private static final int NEAR = 3;
    /** How far from the recorder's chunk every block entity's own data is compared */
    private static final int NEAR_DATA = 2;
    /** Further than any chunk a game can hold from its player */
    private static final int REACH = 40;

    int tick;
    String label;
    String dimension;
    int centreX, centreZ;
    /** How far, in chunks, the server sends chunks to this player */
    int sendDistance;
    long dayTime;
    float rain, thunder;
    /** Null while lightning lights the sky, which each game times for itself */
    double[] sky;
    String screen;
    float[] hud;
    Tracked recorder;
    final Map<Long, long[]> blocks = new HashMap<>();
    final Map<Long, Long> biomes = new HashMap<>();
    final Map<Long, byte[]> light = new HashMap<>();
    /** In playback, for a chunk whose light differs from the live game's: where the first such place is and what lights it */
    final Map<Long, String> lightSources = new HashMap<>();
    final Map<Long, String> blockEntities = new HashMap<>();
    final Map<String, String> blockEntityData = new TreeMap<>();
    final TreeMap<Integer, String> entities = new TreeMap<>();
    final Map<String, Tracked> named = new TreeMap<>();
    final Map<Region, int[]> regionBlocks = new LinkedHashMap<>();
    final Map<Region, byte[]> regionLight = new LinkedHashMap<>();
    final Map<String, Function<Minecraft, String>> askers = new LinkedHashMap<>();
    final Map<String, String> facts = new LinkedHashMap<>();
    long nanos;

    private WorldProbe() {
    }

    /**
     * @param like     the live note this is to be compared with, or null when this is the live note
     * @param recorder the recorder's player: the game's own player live, the recorded one in playback
     */
    static WorldProbe take(Minecraft minecraft, WorldProbe like, Entity recorder, int tick, String label, List<Region> regions, Map<String, Function<Minecraft, String>> askers) {
        long started = System.nanoTime();
        ClientLevel level = minecraft.level;
        WorldProbe probe = new WorldProbe();
        probe.tick = tick;
        probe.label = label;
        probe.dimension = level.dimension().location().toString();
        // What is waiting to be lit is lit first, as the next drawn frame would do: both games are read as they would be seen
        Snapshots.settleLight(level);

        ChunkPos centre = recorder != null ? recorder.chunkPosition() : new ChunkPos(0, 0);
        probe.centreX = like != null ? like.centreX : centre.x;
        probe.centreZ = like != null ? like.centreZ : centre.z;
        probe.sendDistance = like != null ? like.sendDistance
            : minecraft.getConnection() == null ? 12 : ((comedymonkey.dailies.mixin.access.ClientPacketListenerAccess) minecraft.getConnection()).dailies$serverChunkRadius();
        probe.dayTime = level.getDayTime();
        probe.rain = level.getRainLevel(1.0F);
        probe.thunder = level.getThunderLevel(1.0F);
        if (level.getSkyFlashTime() <= 0 && recorder != null) {
            Vec3 colour = level.getSkyColor(recorder.getEyePosition(), 1.0F);
            probe.sky = new double[]{colour.x, colour.y, colour.z};
        }
        probe.screen = minecraft.screen == null ? "none" : minecraft.screen.getClass().getName();
        if (recorder != null) {
            probe.recorder = tracked(recorder);
        }

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();
        for (int x = probe.centreX - REACH; x <= probe.centreX + REACH; x++) {
            for (int z = probe.centreZ - REACH; z <= probe.centreZ + REACH; z++) {
                LevelChunk chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk == null) {
                    continue;
                }
                long key = ChunkPos.asLong(x, z);
                boolean near = Math.abs(x - probe.centreX) <= NEAR && Math.abs(z - probe.centreZ) <= NEAR;
                LevelChunkSection[] sections = chunk.getSections();
                long[] hashes = new long[sections.length];
                long biomeHash = 7;
                for (int i = 0; i < sections.length; i++) {
                    LevelChunkSection section = sections[i];
                    hashes[i] = section.hasOnlyAir() ? 0 : near ? placeByPlace(section) : byCount(section);
                    for (int quart = 0; quart < 4; quart++) {
                        Holder<Biome> biome = section.getNoiseBiome(quart, quart ^ 1, 3 - quart);
                        biomeHash = biomeHash * 31 + biome.unwrapKey().map(name -> name.location().hashCode()).orElse(0);
                    }
                }
                probe.blocks.put(key, hashes);
                probe.biomes.put(key, biomeHash);

                int perColumn = (maxY - minY) / LIGHT_STEP + 1;
                byte[] lit = new byte[LIGHT_COLUMNS.length / 2 * perColumn];
                int index = 0;
                byte[] litLive = like == null ? null : like.light.get(key);
                for (int column = 0; column < LIGHT_COLUMNS.length; column += 2) {
                    int blockX = (x << 4) + LIGHT_COLUMNS[column];
                    int blockZ = (z << 4) + LIGHT_COLUMNS[column + 1];
                    for (int step = 0; step < perColumn; step++) {
                        pos.set(blockX, lightY(chunk, blockX, blockZ, step, minY, maxY), blockZ);
                        lit[index] = lightAt(level, pos);
                        if (litLive != null && litLive.length == lit.length && litLive[index] != lit[index] && !probe.lightSources.containsKey(key) && probe.lightSources.size() < 8) {
                            probe.lightSources.put(key, lightSources(level, pos.immutable()));
                        }
                        index++;
                    }
                }
                probe.light.put(key, lit);

                if (!chunk.getBlockEntities().isEmpty()) {
                    TreeMap<String, Integer> kinds = new TreeMap<>();
                    boolean data = Math.abs(x - probe.centreX) <= NEAR_DATA && Math.abs(z - probe.centreZ) <= NEAR_DATA;
                    for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                        String kind = String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()));
                        kinds.merge(kind, 1, Integer::sum);
                        if (data) {
                            String said;
                            try {
                                said = blockEntity.getUpdateTag(level.registryAccess()).toString();
                            } catch (Throwable t) {
                                said = "cannot say (" + t.getClass().getSimpleName() + ")";
                            }
                            probe.blockEntityData.put(kind + " at " + blockEntity.getBlockPos().toShortString(), said);
                        }
                    }
                    probe.blockEntities.put(key, kinds.toString());
                }
            }
        }

        for (Entity entity : level.entitiesForRendering()) {
            if (entity.getId() == Snapshots.VIEWER_ENTITY_ID || (like != null && entity == minecraft.player)) {
                // The person watching is not in the recording
                continue;
            }
            probe.entities.put(entity.getId(), BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath());
            if (entity.hasCustomName() && entity != recorder) {
                probe.named.put(entity.getCustomName().getString(), tracked(entity));
            }
        }

        for (Region region : regions) {
            int[] states = new int[region.size()];
            byte[] lit = region.light() ? new byte[region.size()] : null;
            int index = 0;
            for (int y = region.min().getY(); y <= region.max().getY(); y++) {
                for (int z = region.min().getZ(); z <= region.max().getZ(); z++) {
                    for (int x = region.min().getX(); x <= region.max().getX(); x++) {
                        pos.set(x, y, z);
                        states[index] = Block.getId(level.getBlockState(pos));
                        if (lit != null) {
                            lit[index] = lightAt(level, pos);
                        }
                        index++;
                    }
                }
            }
            probe.regionBlocks.put(region, states);
            if (lit != null) {
                probe.regionLight.put(region, lit);
            }
        }

        probe.askers.putAll(askers);
        probe.askers.forEach((what, asker) -> {
            String answer;
            try {
                answer = asker.apply(minecraft);
            } catch (Throwable t) {
                answer = "cannot say (" + t + ")";
            }
            probe.facts.put(what, answer);
        });
        probe.nanos = System.nanoTime() - started;
        return probe;
    }

    /** The heights a column's light is read at: just above the ground first, then a ladder from the bottom of the world */
    private static int lightY(LevelChunk chunk, int x, int z, int step, int minY, int maxY) {
        if (step == 0) {
            return Math.min(maxY - 1, chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15) + 1);
        }
        return Math.min(maxY - 1, minY + 8 + (step - 1) * LIGHT_STEP);
    }

    /**
     * For a place whose light differs: where it is, what is there, the block light round it in playback, and the
     * brightest things that give off light within reach, with how far each is in steps and what it could give here.
     * The blocks are the same in both games, so this says which light is missing or extra, and from which chunk
     */
    private static String lightSources(ClientLevel level, BlockPos at) {
        StringBuilder said = new StringBuilder("at ").append(at.toShortString()).append(' ').append(shortState(level.getBlockState(at)));
        said.append("; block light round it in playback:");
        for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
            said.append(' ').append(direction.getName().charAt(0)).append(level.getBrightness(LightLayer.BLOCK, at.relative(direction)));
        }
        List<long[]> found = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = -14; dx <= 14; dx++) {
            for (int dy = -14; dy <= 14; dy++) {
                for (int dz = -14; dz <= 14; dz++) {
                    int steps = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                    if (steps > 14) {
                        continue;
                    }
                    pos.set(at.getX() + dx, at.getY() + dy, at.getZ() + dz);
                    if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
                        continue;
                    }
                    int gives = level.getBlockState(pos).getLightEmission(level, pos);
                    if (gives - steps > 0) {
                        found.add(new long[]{gives - steps, pos.asLong(), steps, gives});
                    }
                }
            }
        }
        found.sort((a, b) -> Long.compare(b[0], a[0]));
        said.append("; brightest near it:");
        if (found.isEmpty()) {
            said.append(" nothing");
        }
        for (int i = 0; i < Math.min(3, found.size()); i++) {
            BlockPos source = BlockPos.of(found.get(i)[1]);
            said.append(i == 0 ? " " : ", ").append(shortState(level.getBlockState(source))).append(" at ").append(source.toShortString())
                .append(" in chunk ").append(source.getX() >> 4).append(',').append(source.getZ() >> 4)
                .append(", ").append(found.get(i)[2]).append(" steps, gives ").append(found.get(i)[3]).append(", at most ").append(found.get(i)[0]).append(" here");
        }
        return said.toString();
    }

    private static String shortState(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
    }

    private static byte lightAt(ClientLevel level, BlockPos pos) {
        return (byte) (level.getBrightness(LightLayer.SKY, pos) << 4 | level.getBrightness(LightLayer.BLOCK, pos));
    }

    private static long placeByPlace(LevelChunkSection section) {
        long hash = 1125899906842597L;
        BlockState last = null;
        int id = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState state = section.getBlockState(x, y, z);
                    if (state != last) {
                        last = state;
                        id = Block.getId(state);
                    }
                    hash = hash * 31 + id;
                }
            }
        }
        return hash == 0 ? 1 : hash;
    }

    private static long byCount(LevelChunkSection section) {
        long[] sum = {0};
        section.getStates().count((state, count) -> sum[0] += (Block.getId(state) + 1) * 0x9E3779B97F4A7C15L * count);
        return sum[0] == 0 ? 1 : sum[0];
    }

    private static Tracked tracked(Entity entity) {
        double speed = entity.position().distanceTo(new Vec3(entity.xo, entity.yo, entity.zo));
        return new Tracked(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath(), entity.position(), speed, state(entity));
    }

    /** What can be seen of an entity besides where it is: how it stands, what it holds, what holds it */
    private static String state(Entity entity) {
        StringBuilder state = new StringBuilder(entity.getPose().name().toLowerCase(java.util.Locale.ROOT));
        if (entity.isOnFire()) {
            state.append(" burning");
        }
        if (entity.isPassenger()) {
            state.append(" riding ").append(entity.getVehicle().getId());
        }
        if (entity instanceof Leashable leashed && leashed.getLeashHolder() != null) {
            state.append(" leashed to ").append(leashed.getLeashHolder().getId());
        }
        if (entity instanceof EnderMan enderman && enderman.getCarriedBlock() != null) {
            state.append(" carrying ").append(BuiltInRegistries.BLOCK.getKey(enderman.getCarriedBlock().getBlock()).getPath());
        }
        if (entity instanceof AgeableMob young && young.isBaby()) {
            state.append(" baby");
        }
        if (entity instanceof LivingEntity living) {
            if (living.isDeadOrDying()) {
                state.append(" dead");
            }
            if (living.isSleeping()) {
                state.append(" asleep");
            }
            if (living.isUsingItem()) {
                state.append(" using");
            }
            if (!living.getMainHandItem().isEmpty()) {
                state.append(" holding ").append(BuiltInRegistries.ITEM.getKey(living.getMainHandItem().getItem()).getPath());
            }
        }
        return state.toString();
    }

    /** The recorded player in a playback world */
    static Entity recorderIn(Minecraft minecraft, String recorderId) {
        return minecraft.level == null ? null : minecraft.level.getPlayerByUUID(UUID.fromString(recorderId));
    }

    // ------------------------------------------------------------------ Comparing

    /** What a playback world shows differently from the live note, one line each; empty when nothing does */
    static List<String> differences(WorldProbe live, WorldProbe played) {
        List<String> found = new ArrayList<>();
        if (!live.dimension.equals(played.dimension)) {
            found.add("dimension: live " + live.dimension + ", playback " + played.dimension);
            return found;
        }
        chunksHeld(live, played, found);
        chunkContents(live, played, found);
        blockEntityData(live, played, found);
        boxes(live, played, found);
        entities(live, played, found);
        named(live, played, found);
        recorder(live, played, found);
        sky(live, played, found);
        facts(live, played, found);
        hud(live, played, found);
        return found;
    }

    /** Chunks: none missing, none left over */
    private static void chunksHeld(WorldProbe live, WorldProbe played, List<String> found) {
        TreeSet<String> missing = new TreeSet<>();
        TreeSet<String> extra = new TreeSet<>();
        int leftovers = 0;
        for (long key : live.blocks.keySet()) {
            if (!played.blocks.containsKey(key)) {
                if (live.sends(ChunkPos.getX(key), ChunkPos.getZ(key))) {
                    missing.add(chunkName(key, live));
                } else {
                    leftovers++;
                }
            }
        }
        if (leftovers > 0) {
            found.add(LET_OFF + leftovers + " chunks the live game still held, beyond where the server was sending any, are not in playback");
        }
        for (long key : played.blocks.keySet()) {
            if (!live.blocks.containsKey(key)) {
                extra.add(chunkName(key, live));
            }
        }
        if (!missing.isEmpty()) {
            found.add("chunks: " + missing.size() + " of " + live.blocks.size() + " missing in playback: " + some(missing));
        }
        if (!extra.isEmpty()) {
            found.add("chunks: " + extra.size() + " in playback that the live game did not hold: " + some(extra));
        }
    }

    /** In each chunk both hold: its blocks section by section, its biomes, its light and the kinds of block entity in it */
    private static void chunkContents(WorldProbe live, WorldProbe played, List<String> found) {
        List<String> wrongBlocks = new ArrayList<>();
        List<String> wrongBiomes = new ArrayList<>();
        List<String> wrongLight = new ArrayList<>();
        List<String> wrongBlockEntities = new ArrayList<>();
        int perColumn = 0;
        for (Map.Entry<Long, long[]> entry : live.blocks.entrySet()) {
            long key = entry.getKey();
            long[] theirs = played.blocks.get(key);
            if (theirs == null) {
                continue;
            }
            long[] ours = entry.getValue();
            for (int i = 0; i < ours.length; i++) {
                if (ours[i] != theirs[i]) {
                    wrongBlocks.add(chunkName(key, live) + " section " + i);
                }
            }
            if (!live.biomes.get(key).equals(played.biomes.get(key))) {
                wrongBiomes.add(chunkName(key, live));
            }
            byte[] lit = live.light.get(key);
            byte[] litThere = played.light.get(key);
            perColumn = lit.length / (LIGHT_COLUMNS.length / 2);
            for (int i = 0; i < lit.length && live.sends(ChunkPos.getX(key), ChunkPos.getZ(key)); i++) {
                if (lit[i] != litThere[i]) {
                    wrongLight.add(chunkName(key, live) + " column " + i / perColumn + (i % perColumn == 0 ? " above ground" : " rung " + i % perColumn)
                        + " live sky " + (lit[i] >> 4 & 15) + " block " + (lit[i] & 15) + ", playback sky " + (litThere[i] >> 4 & 15) + " block " + (litThere[i] & 15)
                        + (played.lightSources.containsKey(key) ? " (" + played.lightSources.get(key) + ")" : ""));
                    break;
                }
            }
            String kinds = live.blockEntities.getOrDefault(key, "{}");
            String kindsThere = played.blockEntities.getOrDefault(key, "{}");
            if (!kinds.equals(kindsThere)) {
                wrongBlockEntities.add(chunkName(key, live) + " live " + kinds + ", playback " + kindsThere);
            }
        }
        if (!wrongBlocks.isEmpty()) {
            found.add("blocks: " + wrongBlocks.size() + " sections differ: " + some(wrongBlocks));
        }
        if (!wrongBiomes.isEmpty()) {
            found.add("biomes: " + wrongBiomes.size() + " chunks differ: " + some(wrongBiomes));
        }
        if (!wrongLight.isEmpty()) {
            found.add("light: " + wrongLight.size() + " chunks differ: " + some(wrongLight));
        }
        if (!wrongBlockEntities.isEmpty()) {
            found.add("block entities: " + wrongBlockEntities.size() + " chunks differ: " + some(wrongBlockEntities));
        }
    }

    /** The data each block entity near the recorder holds, where both games hold it */
    private static void blockEntityData(WorldProbe live, WorldProbe played, List<String> found) {
        List<String> wrongData = new ArrayList<>();
        live.blockEntityData.forEach((what, said) -> {
            String there = played.blockEntityData.get(what);
            if (there != null && !steady(there).equals(steady(said))) {
                wrongData.add(what + " live " + shortened(said) + ", playback " + shortened(there));
            }
        });
        if (!wrongData.isEmpty()) {
            found.add("block entity data: " + wrongData.size() + " differ: " + some(wrongData));
        }
    }

    /** Watched boxes, place by place */
    private static void boxes(WorldProbe live, WorldProbe played, List<String> found) {
        for (Map.Entry<Region, int[]> entry : live.regionBlocks.entrySet()) {
            Region region = entry.getKey();
            int[] ours = entry.getValue();
            int[] theirs = played.regionBlocks.get(region);
            byte[] lit = live.regionLight.get(region);
            byte[] litThere = played.regionLight.get(region);
            List<String> wrong = new ArrayList<>();
            List<String> wrongLit = new ArrayList<>();
            int width = region.max().getX() - region.min().getX() + 1;
            int depth = region.max().getZ() - region.min().getZ() + 1;
            for (int i = 0; i < ours.length; i++) {
                String at = (region.min().getX() + i % width) + " " + (region.min().getY() + i / (width * depth)) + " " + (region.min().getZ() + i / width % depth);
                if (theirs == null || ours[i] != theirs[i]) {
                    wrong.add(at + " live " + stateName(ours[i]) + ", playback " + (theirs == null ? "not looked at" : stateName(theirs[i])));
                }
                if (lit != null && litThere != null && lit[i] != litThere[i]) {
                    wrongLit.add(at + " live sky " + (lit[i] >> 4 & 15) + " block " + (lit[i] & 15) + ", playback sky " + (litThere[i] >> 4 & 15) + " block " + (litThere[i] & 15));
                }
            }
            if (!wrong.isEmpty()) {
                found.add("box '" + region.name() + "': " + wrong.size() + " of " + ours.length + " blocks differ: " + some(wrong));
            }
            if (!wrongLit.isEmpty()) {
                found.add("box '" + region.name() + "': light differs at " + wrongLit.size() + " of " + ours.length + " places: " + some(wrongLit));
            }
        }
    }

    /** Entities: the same ones, of the same kinds */
    private static void entities(WorldProbe live, WorldProbe played, List<String> found) {
        List<String> wrongEntities = new ArrayList<>();
        live.entities.forEach((id, kind) -> {
            String there = played.entities.get(id);
            if (!kind.equals(there)) {
                wrongEntities.add(kind + " " + id + (there == null ? " missing in playback" : " is a " + there + " in playback"));
            }
        });
        played.entities.forEach((id, kind) -> {
            if (!live.entities.containsKey(id)) {
                wrongEntities.add(kind + " " + id + " only in playback");
            }
        });
        if (!wrongEntities.isEmpty()) {
            found.add("entities: " + wrongEntities.size() + " differ (live " + live.entities.size() + ", playback " + played.entities.size() + "): " + some(wrongEntities));
        }
    }

    /** The named entities being followed: each there, near its live place, in the same state */
    private static void named(WorldProbe live, WorldProbe played, List<String> found) {
        live.named.forEach((name, ours) -> {
            Tracked theirs = played.named.get(name);
            if (theirs == null) {
                found.add("'" + name + "' is missing in playback");
                return;
            }
            double off = ours.at().distanceTo(theirs.at());
            double allowed = 0.34 + Math.max(ours.speed(), theirs.speed());
            if (off > allowed) {
                found.add(String.format("'%s' is %.2f blocks from its live place (allowed %.2f): live %s, playback %s", name, off, allowed, place(ours.at()), place(theirs.at())));
            }
            if (!ours.state().equals(theirs.state())) {
                found.add("'" + name + "' live: " + ours.state() + "; playback: " + theirs.state());
            }
        });
    }

    /** The recorder: there, near their live place, in the same state */
    private static void recorder(WorldProbe live, WorldProbe played, List<String> found) {
        if (live.recorder != null) {
            if (played.recorder == null) {
                found.add("the recorder is missing in playback");
            } else {
                double off = live.recorder.at().distanceTo(played.recorder.at());
                double allowed = 0.34 + 2.5 * Math.max(live.recorder.speed(), played.recorder.speed());
                if (off > allowed) {
                    found.add(String.format("the recorder is %.2f blocks from their live place (allowed %.2f): live %s, playback %s", off, allowed,
                        place(live.recorder.at()), place(played.recorder.at())));
                }
                if (!live.recorder.state().equals(played.recorder.state())) {
                    found.add("the recorder live: " + live.recorder.state() + "; playback: " + played.recorder.state());
                }
            }
        }
    }

    /** The sky: the time of day, the weather and the sky's colour */
    private static void sky(WorldProbe live, WorldProbe played, List<String> found) {
        if (Math.abs(live.dayTime - played.dayTime) > 2) {
            found.add("time of day: live " + live.dayTime + ", playback " + played.dayTime);
        }
        if (Math.abs(live.rain - played.rain) > 0.03F || Math.abs(live.thunder - played.thunder) > 0.03F) {
            found.add("weather: live rain " + live.rain + " thunder " + live.thunder + ", playback rain " + played.rain + " thunder " + played.thunder);
        }
        if (live.sky != null && played.sky != null) {
            for (int i = 0; i < 3; i++) {
                if (Math.abs(live.sky[i] - played.sky[i]) > 0.03) {
                    found.add(String.format("sky colour: live %.3f %.3f %.3f, playback %.3f %.3f %.3f", live.sky[0], live.sky[1], live.sky[2], played.sky[0], played.sky[1], played.sky[2]));
                    break;
                }
            }
        }
    }

    /** The answers the steps asked of the game, such as the cracks in a block being mined */
    private static void facts(WorldProbe live, WorldProbe played, List<String> found) {
        live.facts.forEach((what, ours) -> {
            String theirs = played.facts.get(what);
            if (!ours.equals(theirs)) {
                found.add(what + ": live " + ours + ", playback " + theirs);
            }
        });
    }

    /** The HUD's numbers: health, hunger, air, experience and the game mode */
    private static void hud(WorldProbe live, WorldProbe played, List<String> found) {
        if (live.hud != null) {
            if (played.hud == null) {
                found.add("HUD: playback has none");
            } else if (!java.util.Arrays.equals(live.hud, played.hud)) {
                found.add("HUD (health, most, absorption, hunger, air, most, level, progress, mode): live " + java.util.Arrays.toString(live.hud)
                    + ", playback " + java.util.Arrays.toString(played.hud));
            }
        }
    }

    /** A spawner counts down to its next mob on every tick of each game for itself; the count is left out */
    private static String steady(String said) {
        return said.replaceAll("Delay:-?[0-9]+s", "Delay");
    }

    /** Begins a line that is counted and logged but is not a fault: see the note on leftover chunks above */
    static final String LET_OFF = "(let off) ";

    /**
     * Whether the server sends this chunk to a player whose chunks are centred where the recorder is, by the game's own
     * rule for it. A leftover chunk has had its light taken away in the live game and is given light again by a snapshot, so its light is not compared either
     */
    private boolean sends(int x, int z) {
        int across = Math.max(0, Math.abs(x - this.centreX) - 1);
        int along = Math.max(0, Math.abs(z - this.centreZ) - 1);
        long far = Math.max(0, Math.max(across, along) - 1);
        long near = Math.min(across, along);
        return near * near + far * far < (long) this.sendDistance * this.sendDistance;
    }

    private static String chunkName(long key, WorldProbe live) {
        int x = ChunkPos.getX(key);
        int z = ChunkPos.getZ(key);
        return "chunk " + x + "," + z + " (" + Math.max(Math.abs(x - live.centreX), Math.abs(z - live.centreZ)) + " from the recorder)";
    }

    private static String stateName(int id) {
        return String.valueOf(Block.stateById(id)).replace("Block{minecraft:", "{");
    }

    private static String place(Vec3 at) {
        return String.format("%.2f %.2f %.2f", at.x, at.y, at.z);
    }

    private static String shortened(String text) {
        return text.length() > 160 ? text.substring(0, 160) + "..." : text;
    }

    private static String some(java.util.Collection<String> lines) {
        StringBuilder text = new StringBuilder();
        int count = 0;
        for (String line : lines) {
            if (count++ == 4) {
                text.append("; ...");
                break;
            }
            text.append(count == 1 ? "" : "; ").append(line);
        }
        return text.toString();
    }

    // ------------------------------------------------------------------ Kept with a fixture (see TourFixtures), in the order read back

    void write(TourFixtures.Out out) throws java.io.IOException {
        com.google.gson.stream.JsonWriter json = out.json;
        json.beginArray();
        json.value(this.tick);
        out.string(this.label);
        out.string(this.dimension);
        json.value(this.centreX).value(this.centreZ).value(this.sendDistance).value(this.dayTime).value((double) this.rain).value((double) this.thunder);
        if (this.sky == null) {
            json.nullValue();
        } else {
            json.beginArray().value(this.sky[0]).value(this.sky[1]).value(this.sky[2]).endArray();
        }
        out.string(this.screen);
        if (this.hud == null) {
            json.nullValue();
        } else {
            json.beginArray();
            for (float number : this.hud) {
                json.value((double) number);
            }
            json.endArray();
        }
        writeTracked(out, this.recorder);
        json.beginArray();
        for (Map.Entry<Long, long[]> entry : this.blocks.entrySet()) {
            json.value(entry.getKey());
            out.longs(entry.getValue());
            json.value(this.biomes.get(entry.getKey()));
            out.bytes(this.light.get(entry.getKey()));
        }
        json.endArray();
        json.beginArray();
        for (Map.Entry<Long, String> entry : this.lightSources.entrySet()) {
            json.value(entry.getKey());
            out.string(entry.getValue());
        }
        json.endArray();
        json.beginArray();
        for (Map.Entry<Long, String> entry : this.blockEntities.entrySet()) {
            json.value(entry.getKey());
            out.string(entry.getValue());
        }
        json.endArray();
        json.beginArray();
        for (Map.Entry<String, String> entry : this.blockEntityData.entrySet()) {
            out.string(entry.getKey());
            out.string(entry.getValue());
        }
        json.endArray();
        json.beginArray();
        for (Map.Entry<Integer, String> entry : this.entities.entrySet()) {
            json.value(entry.getKey());
            out.string(entry.getValue());
        }
        json.endArray();
        json.beginArray();
        for (Map.Entry<String, Tracked> entry : this.named.entrySet()) {
            out.string(entry.getKey());
            writeTracked(out, entry.getValue());
        }
        json.endArray();
        json.beginArray();
        for (Map.Entry<Region, int[]> entry : this.regionBlocks.entrySet()) {
            Region region = entry.getKey();
            out.string(region.name());
            json.value(region.min().getX()).value(region.min().getY()).value(region.min().getZ());
            json.value(region.max().getX()).value(region.max().getY()).value(region.max().getZ());
            json.value(region.light());
            out.ints(entry.getValue());
            out.bytes(this.regionLight.get(region));
        }
        json.endArray();
        json.beginArray();
        for (Map.Entry<String, Function<Minecraft, String>> entry : this.askers.entrySet()) {
            if (!(entry.getValue() instanceof RealWorldSteps.Asker asker)) {
                throw new java.io.IOException("the question '" + entry.getKey() + "' cannot be kept: it is not a RealWorldSteps.Asker");
            }
            out.string(entry.getKey());
            out.string(asker.kind());
            json.value(asker.pos().asLong());
        }
        json.endArray();
        json.beginArray();
        for (Map.Entry<String, String> entry : this.facts.entrySet()) {
            out.string(entry.getKey());
            out.string(entry.getValue());
        }
        json.endArray();
        json.value(this.nanos);
        json.endArray();
    }

    static WorldProbe read(TourFixtures.In in) throws java.io.IOException {
        com.google.gson.stream.JsonReader json = in.json;
        WorldProbe probe = new WorldProbe();
        json.beginArray();
        probe.tick = in.integer();
        probe.label = in.string();
        probe.dimension = in.string();
        probe.centreX = in.integer();
        probe.centreZ = in.integer();
        probe.sendDistance = in.integer();
        probe.dayTime = in.number();
        probe.rain = (float) in.decimal();
        probe.thunder = (float) in.decimal();
        if (json.peek() == com.google.gson.stream.JsonToken.NULL) {
            json.nextNull();
        } else {
            json.beginArray();
            probe.sky = new double[]{in.decimal(), in.decimal(), in.decimal()};
            json.endArray();
        }
        probe.screen = in.string();
        if (json.peek() == com.google.gson.stream.JsonToken.NULL) {
            json.nextNull();
        } else {
            List<Float> numbers = new ArrayList<>();
            json.beginArray();
            while (in.more()) {
                numbers.add((float) in.decimal());
            }
            json.endArray();
            probe.hud = new float[numbers.size()];
            for (int i = 0; i < numbers.size(); i++) {
                probe.hud[i] = numbers.get(i);
            }
        }
        probe.recorder = readTracked(in);
        json.beginArray();
        while (in.more()) {
            long key = in.number();
            probe.blocks.put(key, in.longs());
            probe.biomes.put(key, in.number());
            probe.light.put(key, in.bytes());
        }
        json.endArray();
        json.beginArray();
        while (in.more()) {
            probe.lightSources.put(in.number(), in.string());
        }
        json.endArray();
        json.beginArray();
        while (in.more()) {
            probe.blockEntities.put(in.number(), in.string());
        }
        json.endArray();
        json.beginArray();
        while (in.more()) {
            probe.blockEntityData.put(in.string(), in.string());
        }
        json.endArray();
        json.beginArray();
        while (in.more()) {
            probe.entities.put(in.integer(), in.string());
        }
        json.endArray();
        json.beginArray();
        while (in.more()) {
            probe.named.put(in.string(), readTracked(in));
        }
        json.endArray();
        json.beginArray();
        while (in.more()) {
            Region region = new Region(in.string(), new BlockPos(in.integer(), in.integer(), in.integer()), new BlockPos(in.integer(), in.integer(), in.integer()), in.truth());
            probe.regionBlocks.put(region, in.ints());
            byte[] lit = in.bytes();
            if (lit != null) {
                probe.regionLight.put(region, lit);
            }
        }
        json.endArray();
        json.beginArray();
        while (in.more()) {
            probe.askers.put(in.string(), new RealWorldSteps.Asker(in.string(), BlockPos.of(in.number())));
        }
        json.endArray();
        json.beginArray();
        while (in.more()) {
            probe.facts.put(in.string(), in.string());
        }
        json.endArray();
        probe.nanos = in.number();
        json.endArray();
        return probe;
    }

    private static void writeTracked(TourFixtures.Out out, Tracked tracked) throws java.io.IOException {
        if (tracked == null) {
            out.json.nullValue();
            return;
        }
        out.json.beginArray();
        out.string(tracked.type());
        out.json.value(tracked.at().x).value(tracked.at().y).value(tracked.at().z).value(tracked.speed());
        out.string(tracked.state());
        out.json.endArray();
    }

    private static Tracked readTracked(TourFixtures.In in) throws java.io.IOException {
        if (in.json.peek() == com.google.gson.stream.JsonToken.NULL) {
            in.json.nextNull();
            return null;
        }
        in.json.beginArray();
        Tracked tracked = new Tracked(in.string(), new Vec3(in.decimal(), in.decimal(), in.decimal()), in.decimal(), in.string());
        in.json.endArray();
        return tracked;
    }

    /** A line for the log: what this note took in */
    String summary() {
        return this.blocks.size() + " chunks, " + this.blockEntities.size() + " with block entities, " + this.entities.size() + " entities, " + this.named.size() + " named";
    }

}
