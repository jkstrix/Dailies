package comedymonkey.dailies.selftest;

import comedymonkey.dailies.capture.Recorder;
import comedymonkey.dailies.capture.Snapshots;
import comedymonkey.dailies.extras.HudLayer;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.playback.Viewer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Rotations;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.projectile.EvokerFangs;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ConduitBlockEntity;
import net.minecraft.world.level.block.entity.LidBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Everything a game shows at the end of one tick, written down so that the live game and playback can be compared:
 * each entity with its place and state, the blocks and block entities of the staging ground, the boss bars, the
 * weather and the recorder's HUD. It is taken at the very end of a client tick, where the live game at recording
 * tick T and playback standing at tick T have applied the same packets and run the same number of ticks.
 */
final class Census {

    record Thing(String type, String label, double x, double y, double z, float yaw, String state, String clocks) {
    }

    final int tick;
    String dimension = "";
    final Map<Integer, Thing> things = new HashMap<>();
    final Map<Long, String> blockEntities = new HashMap<>();
    String bars = "";
    String hud = "";
    long dayTime;
    float rain;
    float thunder;
    int particles;
    /** Every block of the watched boxes, kept on the ticks that are jumped to; else null */
    short[] blocks;
    /** The blocks that changed since the tick before; null when there is no tick before to compare with */
    List<String> changes;
    /** Block light and sky light at every watched block, sixteen times the one plus the other, where the blocks are kept */
    byte[] light;
    /** The same three ticks on in the live game, which takes light in a little at a time: either is right for playback */
    byte[] lightLater;
    int recorderId = Integer.MIN_VALUE;

    private Census(int tick) {
        this.tick = tick;
    }

    // --- What is watched ---

    /** The live game, tick by tick */
    static final ConcurrentSkipListMap<Integer, Census> live = new ConcurrentSkipListMap<>();
    /** Ticks that are jumped to, where the live game keeps every watched block */
    static final Set<Integer> keyTicks = ConcurrentHashMap.newKeySet();
    /** The boxes of blocks watched in each dimension, with the tick from which each is watched: x1 y1 z1 x2 y2 z2 */
    private static final List<Object[]> BOXES = new CopyOnWriteArrayList<>();
    /** What was heard, with the tick it was heard on: live, in play, and while a jump was skipping (which must stay empty) */
    static final List<String> liveSounds = new CopyOnWriteArrayList<>();
    static final List<String> playSounds = new CopyOnWriteArrayList<>();
    static final List<String> skippedSounds = new CopyOnWriteArrayList<>();
    /** How many particles playback had in the air at each tick of the play-through */
    static final Map<Integer, Integer> playParticles = new ConcurrentHashMap<>();

    enum Mode {
        OFF, LIVE, PLAY
    }

    static volatile Mode mode = Mode.OFF;
    static volatile Tally tally;
    private static boolean registered = false;
    private static Census previous = null;
    private static final Tracker TRACKER = new Tracker();
    private static int lastPlayed = -1;

    static void watch(String dimension, int fromTick, int x1, int y1, int z1, int x2, int y2, int z2) {
        BOXES.add(new Object[]{dimension, fromTick, new int[]{Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2), Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2)}});
    }

    private static List<int[]> boxesAt(String dimension, int tick) {
        List<int[]> boxes = new ArrayList<>();
        for (Object[] box : BOXES) {
            if (box[0].equals(dimension) && (Integer) box[1] <= tick) {
                boxes.add((int[]) box[2]);
            }
        }
        return boxes;
    }

    static synchronized void start(Mode wanted) {
        if (!registered) {
            registered = true;
            // Before the recorder counts the tick, so the tick read here is the one this state belongs to
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, (ClientTickEvent.Post event) -> endOfTick());
            NeoForge.EVENT_BUS.addListener((PlaySoundEvent event) -> heard(event));
            NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.RenderFrameEvent.Post event) -> drawn());
            // The recorder respawned: the live game's world is rebuilt on this tick, which playback only reaches by the next
            NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.Clone event) -> {
                if (mode == Mode.LIVE && comedymonkey.dailies.capture.Recorder.get().state() == comedymonkey.dailies.capture.Recorder.State.RECORDING) {
                    respawnTicks.add(comedymonkey.dailies.capture.Recorder.get().recordingTick());
                }
            });
        }
        if (wanted == Mode.LIVE) {
            respawnTicks.clear();
        }
        previous = null;
        lastPlayed = -1;
        TRACKER.forget();
        mode = wanted;
    }

    static void stop() {
        mode = Mode.OFF;
    }

    /** Frames drawn so far, for waiting a number of them */
    static volatile long frames = 0;
    /** Pictures of the screen to take once so many more frames have been drawn: the file, and the frames still to go */
    private static final List<Object[]> GRABS = new CopyOnWriteArrayList<>();
    /** Recording ticks at which the live game's screen is to be kept, with the file for each */
    static final Map<Integer, java.nio.file.Path> liveGrabs = new ConcurrentHashMap<>();

    /** Keeps the screen as it is drawn so many frames from now (0 is the frame being put together) */
    static void grab(java.nio.file.Path file, int framesOn) {
        GRABS.add(new Object[]{file, new int[]{framesOn}});
    }

    private static void drawn() {
        frames++;
        for (Object[] grab : GRABS) {
            int[] left = (int[]) grab[1];
            if (left[0]-- <= 0) {
                GRABS.remove(grab);
                try (com.mojang.blaze3d.platform.NativeImage image = net.minecraft.client.Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) {
                    image.writeToFile((java.nio.file.Path) grab[0]);
                } catch (Throwable t) {
                    comedymonkey.dailies.Host.LOGGER.warn("Dailies self test: a picture of the screen could not be kept", t);
                }
            }
        }
    }

    private static void heard(PlaySoundEvent event) {
        if (event.getOriginalSound() == null) {
            return;
        }
        String name = event.getOriginalSound().getLocation().toString();
        if (mode == Mode.LIVE && Recorder.get().state() == Recorder.State.RECORDING) {
            liveSounds.add(Recorder.get().recordingTick() + " " + name);
        } else if (Playback.loaded()) {
            (Playback.skipping() ? skippedSounds : playSounds).add(Playback.position() + " " + name);
        }
    }

    private static void endOfTick() {
        Minecraft minecraft = Minecraft.getInstance();
        try {
            if (mode == Mode.LIVE) {
                if (Recorder.get().state() != Recorder.State.RECORDING || minecraft.level == null || minecraft.player == null || minecraft.getConnection() == null || minecraft.isPaused()) {
                    previous = null;
                    return;
                }
                int tick = Recorder.get().recordingTick();
                Census census = take(minecraft, tick, true, previous, TRACKER, keyTicks.contains(tick) || tick % BestiarySteps.EVERY == 0);
                live.put(tick, census);
                previous = census;
                Census earlier = live.get(tick - 3);
                if (earlier != null && earlier.light != null && earlier.dimension.equals(census.dimension)) {
                    byte[] later = scanLight(minecraft.level, boxesAt(earlier.dimension, earlier.tick));
                    earlier.lightLater = later.length == earlier.light.length ? later : null;
                }
                java.nio.file.Path picture = liveGrabs.remove(tick);
                if (picture != null) {
                    grab(picture, 0);
                }
            } else if (mode == Mode.PLAY) {
                if (!Playback.loaded() || Playback.jumping() || Playback.skipping() || minecraft.level == null) {
                    return;
                }
                int tick = Playback.position();
                if (tick == lastPlayed) {
                    return;
                }
                if (tick != lastPlayed + 1) {
                    // The world was built again from a stored one in between: nothing leads up to this tick
                    TRACKER.forget();
                }
                lastPlayed = tick;
                Census then = live.get(tick);
                Census now = take(minecraft, tick, false, null, TRACKER, then != null && then.blocks != null);
                playParticles.put(tick, now.particles);
                if (then != null && tally != null) {
                    compare(then, now, "play", tally);
                }
                // Light in play is looked at three ticks on, as the live game's was a second time: both take a few frames over it
                Census earlier = live.get(tick - 3);
                if (earlier != null && earlier.light != null && tally != null && earlier.dimension.equals(now.dimension)) {
                    compareLight(earlier, scanLight(minecraft.level, boxesAt(earlier.dimension, earlier.tick)), "play", tally, false);
                }
                aim(tick + 1);
            }
        } catch (Throwable t) {
            comedymonkey.dailies.Host.LOGGER.error("Dailies self test: the census failed at a tick", t);
            if (tally != null) {
                tally.add("play", "census", "could not be taken: " + t, Playback.loaded() ? Playback.position() : -1, "", false);
            }
        }
    }

    /** Puts the viewer where the recorder's eyes were at a tick, a little behind them, so the same things are drawn */
    static void aim(int tick) {
        Census then = live.get(tick);
        Thing recorder = then == null ? null : then.things.get(then.recorderId);
        if (recorder != null) {
            double back = Math.toRadians(recorder.yaw);
            Viewer.set(recorder.x + Math.sin(back) * 1.5, recorder.y + 2.2, recorder.z - Math.cos(back) * 1.5, recorder.yaw, 20.0F);
        }
    }

    /** The world as playback shows it now, standing still after a jump, with every watched block */
    static Census ofPlayback(Minecraft minecraft) {
        return take(minecraft, Playback.position(), false, null, new Tracker(), true);
    }

    // --- Taking it ---

    /** Remembers the blocks of the tick before, to say what changed */
    private static final class Tracker {
        short[] before;
        String key = "";

        void forget() {
            this.before = null;
            this.key = "";
        }
    }

    private static Census take(Minecraft minecraft, int tick, boolean isLive, Census before, Tracker tracker, boolean keepBlocks) {
        ClientLevel level = minecraft.level;
        Census census = new Census(tick);
        census.dimension = level.dimension().location().toString();
        UUID recorder = isLive ? minecraft.player.getUUID() : UUID.fromString(Playback.manifest().recorderId);
        for (Entity entity : level.entitiesForRendering()) {
            if (entity.getId() == Snapshots.VIEWER_ENTITY_ID || (!isLive && entity == minecraft.player)) {
                continue;
            }
            // Items playback lets fall out of the world for a few seconds, for the fun of it, which the live game deleted at once
            if (!isLive && comedymonkey.dailies.playback.VoidSpill.lingers(entity)) {
                voidSpilled++;
                continue;
            }
            boolean own = entity.getUUID().equals(recorder);
            if (own) {
                census.recorderId = entity.getId();
            }
            StringBuilder state = new StringBuilder();
            StringBuilder clocks = new StringBuilder();
            try {
                describe(entity, own, state, clocks, level);
            } catch (Throwable t) {
                state.append("undescribed=").append(t.getClass().getSimpleName()).append(';');
            }
            String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
            String label = own ? "recorder" : entity.hasCustomName() ? entity.getCustomName().getString() : type;
            String text = state.toString();
            Thing old = before == null ? null : before.things.get(entity.getId());
            if (old != null && old.state.equals(text)) {
                text = old.state;
            }
            census.things.put(entity.getId(), new Thing(type, label, entity.getX(), entity.getY(), entity.getZ(), entity.getYRot(), text, clocks.toString()));
        }

        // Blocks and block entities of the watched boxes
        List<int[]> boxes = boxesAt(census.dimension, tick);
        String key = census.dimension + boxes.size();
        short[] now = scan(level, boxes);
        if (tracker.before != null && tracker.key.equals(key) && tracker.before.length == now.length) {
            census.changes = changes(tracker.before, now, boxes);
        }
        tracker.before = now;
        tracker.key = key;
        if (keepBlocks) {
            census.blocks = now;
            census.light = scanLight(level, boxes);
        }
        blockEntities(level, boxes, census, before);

        // The rest of what is on screen
        StringBuilder bars = new StringBuilder();
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<UUID, LerpingBossEvent> bar : minecraft.gui.getBossOverlay().events.entrySet()) {
            LerpingBossEvent event = bar.getValue();
            sorted.put(bar.getKey().toString(), event.getName().getString() + "|" + String.format("%.3f", target(event)) + "|" + event.getColor() + "|" + event.getOverlay()
                + "|darken=" + event.shouldDarkenScreen() + "|music=" + event.shouldPlayBossMusic() + "|fog=" + event.shouldCreateWorldFog());
        }
        sorted.values().forEach(line -> bars.append(line).append('\n'));
        census.bars = bars.toString();
        census.dayTime = level.getDayTime();
        census.rain = level.getRainLevel(1.0F);
        census.thunder = level.getThunderLevel(1.0F);
        try {
            census.particles = Integer.parseInt(minecraft.particleEngine.countParticles());
        } catch (NumberFormatException e) {
            census.particles = -1;
        }
        if (isLive) {
            census.hud = hud(minecraft.player.experienceLevel, minecraft.player.experienceProgress, minecraft.player.getHealth(), minecraft.player.getMainHandItem());
        } else {
            float[] numbers = HudLayer.recordedNumbers();
            census.hud = numbers == null ? "none" : hud((int) numbers[0], numbers[1], numbers[2], HudLayer.recordedHeld());
        }
        return census;
    }

    private static String hud(int level, float progress, float health, ItemStack held) {
        return "level=" + level + " progress=" + String.format("%.3f", progress) + " health=" + String.format("%.1f", health) + " held=" + (held == null || held.isEmpty() ? "nothing" : item(held));
    }

    private static java.lang.reflect.Field targetPercent;

    /** Where a boss bar is heading: what it shows is eased towards this by the clock on the wall, which no tick decides */
    private static float target(LerpingBossEvent event) {
        try {
            if (targetPercent == null) {
                targetPercent = LerpingBossEvent.class.getDeclaredField("targetPercent");
                targetPercent.setAccessible(true);
            }
            return targetPercent.getFloat(event);
        } catch (ReflectiveOperationException e) {
            return event.getProgress();
        }
    }

    private static final Set<Integer> OWN_DATA = Set.of(0, 8, 12, 14);

    /** More that a step wants written down about some entities: state that must match, and clocks that may differ by a set amount */
    interface Extra {
        void describe(Entity entity, StringBuilder state, StringBuilder clocks);
    }

    /** Added by a step for its own run and taken away after it */
    static final List<Extra> EXTRAS = new CopyOnWriteArrayList<>();
    /** How far the clocks a step adds may differ, by name */
    static final Map<String, Double> EXTRA_SLACK = new ConcurrentHashMap<>();

    private static void describe(Entity entity, boolean own, StringBuilder state, StringBuilder clocks, ClientLevel level) {
        if (entity.getVehicle() != null) {
            state.append("vehicle=").append(entity.getVehicle().getId()).append(';');
        }
        if (!entity.getPassengers().isEmpty()) {
            TreeSet<Integer> riders = new TreeSet<>();
            entity.getPassengers().forEach(rider -> riders.add(rider.getId()));
            state.append("riders=").append(riders).append(';');
        }
        state.append("pose=").append(entity.getPose()).append(';');
        if (entity instanceof LivingEntity living) {
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack stack = living.getItemBySlot(slot);
                if (!stack.isEmpty()) {
                    state.append("wears.").append(slot.getName()).append('=').append(item(stack)).append(';');
                }
            }
            // The recorder's own health comes to their game in a packet of its own that is not recorded: the recorded HUD holds it
            if (!own) {
                state.append("health=").append(String.format("%.1f", living.getHealth())).append(';');
            }
            if (living.isSleeping()) {
                state.append("asleep=true;");
            }
            if (living.isFallFlying()) {
                state.append("gliding=true;");
            }
            if (living.isAutoSpinAttack()) {
                state.append("spinning=true;");
            }
            if (living.isBlocking()) {
                state.append("blocking=true;");
            }
            if (living.deathTime > 0) {
                clocks.append("deathTime=").append(living.deathTime).append(';');
            }
            if (living.hurtTime > 0) {
                clocks.append("hurtTime=").append(living.hurtTime).append(';');
            }
        }
        if (entity instanceof Leashable leashable && leashable.getLeashHolder() != null) {
            state.append("leash=").append(leashable.getLeashHolder().getId()).append(';');
        }
        if (entity instanceof ExperienceOrb orb) {
            state.append("value=").append(orb.getValue()).append(';');
        } else if (entity instanceof ItemEntity item) {
            state.append("item=").append(item(item.getItem())).append(';');
        } else if (entity instanceof FallingBlockEntity falling) {
            state.append("block=").append(falling.getBlockState()).append(';');
        } else if (entity instanceof Projectile projectile) {
            state.append("owner=").append(projectile.getOwner() == null ? "none" : String.valueOf(projectile.getOwner().getId())).append(';');
        } else if (entity instanceof Creeper creeper) {
            state.append("charged=").append(creeper.isPowered()).append(";ignited=").append(creeper.isIgnited()).append(';');
            if (creeper.getSwelling(1.0F) > 0.0F) {
                clocks.append("swell=").append(String.format("%.3f", creeper.getSwelling(1.0F))).append(';');
            }
        } else if (entity instanceof Ghast ghast) {
            state.append("charging=").append(ghast.isCharging()).append(';');
        } else if (entity instanceof EnderDragon dragon) {
            state.append("phase=").append(dragon.getPhaseManager().getCurrentPhase().getPhase()).append(";parts=").append(dragon.getSubEntities().length).append(';');
            if (dragon.dragonDeathTime > 0) {
                clocks.append("dragonDeath=").append(dragon.dragonDeathTime).append(';');
            }
        } else if (entity instanceof EvokerFangs fangs) {
            clocks.append("fangs=").append(String.format("%.2f", fangs.getAnimationProgress(1.0F))).append(';');
        } else if (entity instanceof Guardian guardian && guardian.hasActiveAttackTarget()) {
            clocks.append("beam=").append(String.format("%.2f", guardian.getAttackAnimationScale(1.0F))).append(';');
        } else if (entity instanceof Warden warden && warden.emergeAnimationState.isStarted()) {
            clocks.append("emerge=").append(warden.emergeAnimationState.getAccumulatedTime()).append(';');
        } else if (entity instanceof Display display && display.renderState() != null) {
            float progress = display.calculateInterpolationProgress(1.0F);
            com.mojang.math.Transformation shown = display.renderState().transformation().get(progress);
            clocks.append("shownX=").append(String.format("%.2f", shown.getTranslation().x())).append(";shownY=").append(String.format("%.2f", shown.getTranslation().y()))
                .append(";shownZ=").append(String.format("%.2f", shown.getTranslation().z())).append(";shownScale=").append(String.format("%.2f", shown.getScale().x())).append(';');
        }
        if (entity instanceof net.minecraft.world.entity.decoration.ItemFrame frame) {
            MapId map = frame.getItem().get(net.minecraft.core.component.DataComponents.MAP_ID);
            if (map != null) {
                MapItemSavedData data = level.getMapData(map);
                state.append("map=").append(data == null ? "not known" : String.valueOf(Arrays.hashCode(data.colors))).append(';');
            }
        }
        for (Extra extra : EXTRAS) {
            extra.describe(entity, state, clocks);
        }
        List<SynchedEntityData.DataValue<?>> values = entity.getEntityData().getNonDefaultValues();
        if (values != null) {
            TreeMap<Integer, String> sorted = new TreeMap<>();
            for (SynchedEntityData.DataValue<?> value : values) {
                // A game holds values about its own player that it never tells anyone; of the recorder only what others are shown
                if (!own || OWN_DATA.contains(value.id())) {
                    sorted.put(value.id(), text(value.value()));
                }
            }
            sorted.forEach((id, value) -> state.append('d').append(id).append('=').append(value).append(';'));
        }
    }

    static String item(ItemStack stack) {
        if (stack.isEmpty()) {
            return "nothing";
        }
        String text = stack.getCount() + " " + BuiltInRegistries.ITEM.getKey(stack.getItem());
        return stack.getComponentsPatch().isEmpty() ? text : text + clean(String.valueOf(stack.getComponentsPatch()));
    }

    private static String clean(String text) {
        return text.replaceAll("@[0-9a-f]{4,8}", "").replace(';', ',');
    }

    private static String text(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Optional<?> optional) {
            return optional.map(inner -> "some(" + text(inner) + ")").orElse("none");
        }
        if (value instanceof Component component) {
            return clean(component.getString());
        }
        if (value instanceof ItemStack stack) {
            return item(stack);
        }
        if (value instanceof Rotations rotations) {
            return rotations.getX() + "," + rotations.getY() + "," + rotations.getZ();
        }
        if (value instanceof VillagerData data) {
            return BuiltInRegistries.VILLAGER_TYPE.getKey(data.getType()) + "/" + BuiltInRegistries.VILLAGER_PROFESSION.getKey(data.getProfession()) + "/" + data.getLevel();
        }
        if (value instanceof Holder<?> holder) {
            return holder.unwrapKey().map(key -> key.location().toString()).orElse("direct");
        }
        if (value instanceof ParticleOptions particle) {
            return String.valueOf(BuiltInRegistries.PARTICLE_TYPE.getKey(particle.getType()));
        }
        if (value instanceof List<?> list) {
            StringBuilder all = new StringBuilder("[");
            list.forEach(part -> all.append(text(part)).append(','));
            return all.append(']').toString();
        }
        if (value instanceof Float number) {
            return String.format("%.4f", number);
        }
        try {
            if (value.getClass().getMethod("toString").getDeclaringClass() == Object.class) {
                return value.getClass().getSimpleName();
            }
        } catch (NoSuchMethodException e) {
            return value.getClass().getSimpleName();
        }
        return clean(String.valueOf(value));
    }

    private static short[] scan(ClientLevel level, List<int[]> boxes) {
        int size = 0;
        for (int[] box : boxes) {
            size += (box[3] - box[0] + 1) * (box[4] - box[1] + 1) * (box[5] - box[2] + 1);
        }
        short[] blocks = new short[size];
        int offset = 0;
        for (int[] box : boxes) {
            int wide = box[3] - box[0] + 1, high = box[4] - box[1] + 1, deep = box[5] - box[2] + 1;
            for (int cx = box[0] >> 4; cx <= box[3] >> 4; cx++) {
                for (int cz = box[2] >> 4; cz <= box[5] >> 4; cz++) {
                    LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                    if (chunk == null) {
                        continue;
                    }
                    for (int y = box[1]; y <= box[4]; y++) {
                        int index = chunk.getSectionIndex(y);
                        if (index < 0 || index >= chunk.getSectionsCount()) {
                            continue;
                        }
                        LevelChunkSection section = chunk.getSection(index);
                        if (section.hasOnlyAir()) {
                            continue;
                        }
                        for (int x = Math.max(box[0], cx << 4); x <= Math.min(box[3], (cx << 4) + 15); x++) {
                            for (int z = Math.max(box[2], cz << 4); z <= Math.min(box[5], (cz << 4) + 15); z++) {
                                blocks[offset + ((y - box[1]) * deep + (z - box[2])) * wide + (x - box[0])] = (short) Block.getId(section.getBlockState(x & 15, y & 15, z & 15));
                            }
                        }
                    }
                }
            }
            offset += wide * high * deep;
        }
        return blocks;
    }

    static byte[] scanLight(ClientLevel level, List<int[]> boxes) {
        int size = 0;
        for (int[] box : boxes) {
            size += (box[3] - box[0] + 1) * (box[4] - box[1] + 1) * (box[5] - box[2] + 1);
        }
        byte[] light = new byte[size];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int at = 0;
        for (int[] box : boxes) {
            for (int y = box[1]; y <= box[4]; y++) {
                for (int z = box[2]; z <= box[5]; z++) {
                    for (int x = box[0]; x <= box[3]; x++) {
                        pos.set(x, y, z);
                        light[at++] = (byte) (level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, pos) << 4 | level.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos));
                    }
                }
            }
        }
        return light;
    }

    /** The light of the watched boxes as playback has it this instant */
    static byte[] lightNow(Minecraft minecraft, int tick) {
        return scanLight(minecraft.level, boxesAt(minecraft.level.dimension().location().toString(), tick));
    }

    /** Where light differs from the live game's at a tick, each as "x y z: block/sky live -> block/sky"; null when it cannot be compared */
    static List<String> lightDifferences(Census then, byte[] now) {
        if (then.light == null || now == null || then.light.length != now.length) {
            return null;
        }
        List<String> differ = new ArrayList<>();
        int offset = 0;
        for (int[] box : boxesAt(then.dimension, then.tick)) {
            int wide = box[3] - box[0] + 1, high = box[4] - box[1] + 1, deep = box[5] - box[2] + 1;
            for (int i = 0; i < wide * high * deep; i++) {
                byte was = then.light[offset + i], is = now[offset + i];
                if (was != is && (then.lightLater == null || then.lightLater[offset + i] != is)) {
                    differ.add((box[0] + i % wide) + " " + (box[1] + i / (wide * deep)) + " " + (box[2] + (i / wide) % deep) + ": " + ((was >> 4) & 15) + "/" + (was & 15) + " -> " + ((is >> 4) & 15) + "/" + (is & 15));
                }
            }
            offset += wide * high * deep;
        }
        return differ;
    }

    /** Compares light alone, for the frame a jump arrives on */
    static int compareLight(Census then, byte[] now, String mode, Tally tally, boolean warning) {
        List<String> differ = lightDifferences(then, now);
        if (differ != null && !differ.isEmpty()) {
            // By chunk, so it can be seen whether whole chunks or the edges of something are wrong
            TreeMap<String, Integer> chunks = new TreeMap<>();
            for (String place : differ) {
                String[] at = place.substring(0, place.indexOf(':')).split(" ");
                chunks.merge((Integer.parseInt(at[0]) >> 4) + "," + (Integer.parseInt(at[2]) >> 4), 1, Integer::sum);
            }
            tally.add(mode, "light", "differs", then.tick, differ.size() + " places at tick " + then.tick + ", block/sky live -> playback: " + few(differ) + "; by chunk " + chunks, warning);
            tally.lightPlaces.merge(mode, differ.size(), Integer::sum);
            tally.lightTicks.computeIfAbsent(mode, key -> new TreeMap<>()).put(then.tick, differ.size());
        }
        return differ == null ? -1 : differ.size();
    }

    /** The blocks that differ between two scans of the same boxes, each as "x y z: block -> block" */
    static List<String> changes(short[] before, short[] now, List<int[]> boxes) {
        List<String> changes = new ArrayList<>();
        if (Arrays.equals(before, now)) {
            return changes;
        }
        int offset = 0;
        for (int[] box : boxes) {
            int wide = box[3] - box[0] + 1, high = box[4] - box[1] + 1, deep = box[5] - box[2] + 1;
            for (int i = 0; i < wide * high * deep; i++) {
                if (before[offset + i] != now[offset + i]) {
                    int x = box[0] + i % wide, z = box[2] + (i / wide) % deep, y = box[1] + i / (wide * deep);
                    changes.add(x + " " + y + " " + z + ": " + name(before[offset + i]) + " -> " + name(now[offset + i]));
                }
            }
            offset += wide * high * deep;
        }
        return changes;
    }

    static List<String> blockDifferences(Census then, Census now) {
        return changes(then.blocks, now.blocks, boxesAt(then.dimension, then.tick));
    }

    private static String name(short id) {
        BlockState state = Block.stateById(id);
        return clean(state.toString().replace("Block{minecraft:", "").replace("}", ""));
    }

    private static void blockEntities(ClientLevel level, List<int[]> boxes, Census census, Census before) {
        for (int[] box : boxes) {
            for (int cx = box[0] >> 4; cx <= box[3] >> 4; cx++) {
                for (int cz = box[2] >> 4; cz <= box[5] >> 4; cz++) {
                    LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                    if (chunk == null) {
                        continue;
                    }
                    for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                        BlockPos pos = entry.getKey();
                        if (pos.getX() < box[0] || pos.getX() > box[3] || pos.getY() < box[1] || pos.getY() > box[4] || pos.getZ() < box[2] || pos.getZ() > box[5]) {
                            continue;
                        }
                        String text;
                        try {
                            text = describe(entry.getValue(), level);
                        } catch (Throwable t) {
                            text = "undescribed " + t.getClass().getSimpleName();
                        }
                        String old = before == null ? null : before.blockEntities.get(pos.asLong());
                        census.blockEntities.put(pos.asLong(), text.equals(old) ? old : text);
                    }
                }
            }
        }
    }

    private static String describe(BlockEntity entity, ClientLevel level) {
        StringBuilder text = new StringBuilder(String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType())));
        if (entity instanceof LidBlockEntity lid) {
            text.append(" open=").append(String.format("%.2f", lid.getOpenNess(1.0F)));
        }
        if (entity instanceof ShulkerBoxBlockEntity box) {
            text.append(" lid=").append(box.getAnimationStatus()).append(String.format("/%.2f", box.getProgress(1.0F)));
        } else if (entity instanceof BellBlockEntity bell) {
            text.append(" ringing=").append(bell.shaking ? bell.ticks : -1);
        } else if (entity instanceof PistonMovingBlockEntity piston) {
            text.append(" moved=").append(String.format("%.2f", piston.getProgress(1.0F))).append(" carrying=").append(name((short) Block.getId(piston.getMovedState())));
        } else if (entity instanceof BeaconBlockEntity beacon) {
            text.append(" beam=").append(beacon.getBeamSections().size());
        } else if (entity instanceof ConduitBlockEntity conduit) {
            text.append(" active=").append(conduit.isActive());
        }
        String namespace = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()) == null ? "" : BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()).getNamespace();
        if (!(entity instanceof PistonMovingBlockEntity)) {
            net.minecraft.nbt.CompoundTag saved = entity.saveWithoutMetadata(level.registryAccess());
            if (!namespace.equals("minecraft")) {
                // The components a game copies from the item in its own player's hand onto a block it places, which a
                // modded block entity's own updates never send or clear: no other game, playback included, ever holds them
                saved.remove("components");
            }
            text.append(' ').append(clean(String.valueOf(saved)));
        }
        if (!namespace.equals("minecraft")) {
            // A modded chest's lid, which is in no saved data
            Boolean lid = Snapshots.lidOpen(entity);
            if (lid != null) {
                text.append(" lid=").append(lid);
            }
        }
        if (namespace.equals("comedymonkeysaddons")) {
            // What the owner's machines were told, which their saved data on a client leaves out: troughs, the cuckoo's show, the bot's station
            text.append(" told=").append(clean(String.valueOf(Snapshots.updateTag(entity, level.registryAccess()))));
        }
        return text.toString();
    }

    // --- Comparing ---

    /** One kind of difference, with every tick it was seen and an example from each stretch of twenty */
    static final class Entry {
        final String mode, label, what;
        final boolean warning;
        final TreeSet<Integer> ticks = new TreeSet<>();
        /** The first detail stored in each band of twenty ticks, so a later stretch can quote its own */
        final TreeMap<Integer, String> examples = new TreeMap<>();

        Entry(String mode, String label, String what, boolean warning) {
            this.mode = mode;
            this.label = label;
            this.what = what;
            this.warning = warning;
        }

        /** How this difference reads for the ticks in question */
        String describe(java.util.SortedSet<Integer> span) {
            StringBuilder text = new StringBuilder();
            if (this.warning) {
                text.append("(noted) ");
            }
            text.append(this.mode).append(": ").append(this.label).append(' ').append(this.what);
            if (span.isEmpty()) {
                return text.toString();
            }
            String detail = "";
            for (Map.Entry<Integer, String> example : this.examples.entrySet()) {
                if (span.contains(example.getKey())) {
                    detail = example.getValue();
                    break;
                }
            }
            int found = span.size();
            text.append(", on ").append(found).append(found == 1 ? " tick" : " ticks").append(" from ").append(span.first());
            if (found > 1) {
                text.append(" to ").append(span.last());
            }
            if (!detail.isEmpty()) {
                text.append(" [").append(detail.length() > 420 ? detail.substring(0, 420) + "..." : detail).append(']');
            }
            return text.toString();
        }

        @Override
        public String toString() {
            return describe(this.ticks);
        }
    }

    static final class Tally {
        final Map<String, Entry> entries = new java.util.LinkedHashMap<>();
        /** The furthest anything of each label was from its live place, by mode */
        final Map<String, Double> furthest = new TreeMap<>();
        int compared = 0;
        int thingsCompared = 0;
        /** How many places had the wrong light, summed over the ticks looked at, by mode */
        final Map<String, Integer> lightPlaces = new TreeMap<>();
        /** And how many at each tick */
        final Map<String, TreeMap<Integer, Integer>> lightTicks = new TreeMap<>();

        synchronized void add(String mode, String label, String what, int tick, String detail, boolean warning) {
            Entry entry = this.entries.computeIfAbsent(mode + "|" + label + "|" + what, key -> new Entry(mode, label, what, warning));
            entry.ticks.add(tick);
            int band = Math.floorDiv(tick, 20) * 20;
            if (entry.examples.subMap(band, true, band + 19, true).isEmpty()) {
                entry.examples.put(tick, detail == null ? "" : detail);
            }
        }
    }

    /** Numbers that are allowed to differ a little, and by how much; the reason for each is given in BestiarySteps */
    private static final Map<String, Double> CLOCK_SLACK = Map.of("emerge", 300.0, "beam", 0.11, "swell", 0.001, "fangs", 0.051,
        "shownX", 0.011, "shownY", 0.011, "shownZ", 0.011, "shownScale", 0.011);

    /**
     * Whether a dead recorder is at the very end of dying, about to go in a puff of smoke. Their death reaches playback
     * with the recorded state of their own player, sometimes on the tick their own game took it and sometimes on the next,
     * so the body goes on the same tick or one apart: noted, not counted
     */
    private static boolean dyingOut(Thing thing) {
        java.util.regex.Matcher dying = java.util.regex.Pattern.compile("deathTime=(\\d+)").matcher(thing.clocks());
        return dying.find() && Integer.parseInt(dying.group(1)) >= 18;
    }

    /** The live ticks on which the recorder respawned */
    static final Set<Integer> respawnTicks = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Items seen left falling out of the world in playback (playback.VoidSpill), counted once a tick each */
    static int voidSpilled = 0;

    static void compare(Census then, Census now, String mode, Tally tally) {
        int tick = then.tick;
        tally.compared++;
        if (mode.equals("play") && respawnTicks.contains(tick)) {
            // A respawn begins a stored world of its own, as a change of dimension does, and play reaches it on the tick
            // after, as it does a change of dimension: the live game had rebuilt its world on this one. Noted, not counted
            tally.add(mode, "respawn", "is shown a tick later", tick, "the recorder respawned on this tick of the live game", true);
            return;
        }
        if (!then.dimension.equals(now.dimension)) {
            // On the tick a change of dimension is recorded, playback shows the old world for that one tick and builds
            // the new one before the next: the live game was already there. The same tick on a jump is the same lag.
            // Noted, not counted. A mismatch that lasts past that tick still counts.
            tally.add(mode, "dimension", "differs", tick, "live " + then.dimension + ", playback " + now.dimension, then.changes == null);
            return;
        }
        for (Map.Entry<Integer, Thing> entry : then.things.entrySet()) {
            Thing was = entry.getValue();
            Thing is = now.things.get(entry.getKey());
            boolean own = entry.getKey() == then.recorderId;
            if (is == null) {
                // A lightning bolt lasts as long as its own dice say, on each game: it must be there on its first tick, no more
                if (!was.type.equals("minecraft:lightning_bolt") || live.get(tick - 1) == null || !live.get(tick - 1).things.containsKey(entry.getKey())) {
                    tally.add(mode, was.label, "is missing in playback", tick, "entity " + entry.getKey() + " " + was.type, own && dyingOut(was));
                }
                continue;
            }
            tally.thingsCompared++;
            if (!is.type.equals(was.type)) {
                tally.add(mode, was.label, "is another kind of entity", tick, was.type + " live, " + is.type + " in playback", false);
                continue;
            }
            // A third of a block, plus one tick of the thing's own travel as the live game saw it. The recorder is shown as
            // every other player is, eased towards each reported place over three ticks, so three ticks of travel for them
            double speed = speed(entry.getKey(), tick, own ? 5 : 1);
            double allowed = 0.34 + (own ? 3.0 * speed : Math.min(1.2, speed));
            double off = Math.sqrt((was.x - is.x) * (was.x - is.x) + (was.y - is.y) * (was.y - is.y) + (was.z - is.z) * (was.z - is.z));
            tally.furthest.merge(mode + " " + was.label, off, Math::max);
            if (off > allowed) {
                tally.add(mode, was.label, "is in the wrong place", tick, String.format("%.2f blocks off, %.2f allowed: live %.2f %.2f %.2f, playback %.2f %.2f %.2f", off, allowed,
                    was.x, was.y, was.z, is.x, is.y, is.z), false);
            }
            float turned = Math.abs(net.minecraft.util.Mth.wrapDegrees(was.yaw - is.yaw));
            // An orb is turned at random by each game that is told of it
            if (turned > 25.0F && !own && !was.type.equals("minecraft:experience_orb")) {
                tally.add(mode, was.label, "faces another way", tick, String.format("%.0f degrees: live %.0f, playback %.0f", turned, was.yaw, is.yaw), true);
            }
            if (!was.state.equals(is.state)) {
                Map<String, String> one = split(was.state), two = split(is.state);
                Set<String> keys = new TreeSet<>(one.keySet());
                keys.addAll(two.keySet());
                for (String key : keys) {
                    String a = one.get(key), b = two.get(key);
                    if (java.util.Objects.equals(a, b) || (own && recently(then, key, b))) {
                        continue;
                    }
                    // Primed TNT counts its own fuse down between the server's corrections, and has been seen one out for a tick
                    if (key.equals("d8") && was.type.equals("minecraft:tnt") && a != null && b != null && Math.abs(Integer.parseInt(a) - Integer.parseInt(b)) <= 1) {
                        continue;
                    }
                    tally.add(mode, was.label, "state " + key + " differs", tick, "live " + a + ", playback " + b, false);
                }
            }
            if (!was.clocks.equals(is.clocks)) {
                Map<String, String> one = split(was.clocks), two = split(is.clocks);
                Set<String> keys = new TreeSet<>(one.keySet());
                keys.addAll(two.keySet());
                for (String key : keys) {
                    double a = one.containsKey(key) ? Double.parseDouble(one.get(key)) : 0.0, b = two.containsKey(key) ? Double.parseDouble(two.get(key)) : 0.0;
                    if (Math.abs(a - b) > CLOCK_SLACK.getOrDefault(key, EXTRA_SLACK.getOrDefault(key, 0.0)) + 1e-9 && !(own && recentClock(then, key, b))) {
                        tally.add(mode, was.label, "animation " + key + " differs", tick, "live " + one.get(key) + ", playback " + two.get(key), false);
                    }
                }
            }
        }
        for (Map.Entry<Integer, Thing> entry : now.things.entrySet()) {
            if (!then.things.containsKey(entry.getKey()) && !(entry.getValue().type.equals("minecraft:lightning_bolt") && lately(entry.getKey(), tick))) {
                tally.add(mode, entry.getValue().label, "is in playback and was not in the live game", tick, "entity " + entry.getKey() + " " + entry.getValue().type,
                    entry.getKey() == then.recorderId && dyingOut(entry.getValue()));
            }
        }

        // Blocks: the same changes on the same tick in play, the same blocks after a jump
        if (mode.equals("play") && then.changes != null && now.changes != null) {
            if (!then.changes.equals(now.changes)) {
                List<String> onlyLive = new ArrayList<>(then.changes), onlyPlayback = new ArrayList<>(now.changes);
                onlyLive.removeAll(now.changes);
                onlyPlayback.removeAll(then.changes);
                tally.add(mode, "blocks", "changed differently on a tick", tick, "only live " + few(onlyLive) + "; only in playback " + few(onlyPlayback), false);
            }
        } else if (then.blocks != null && now.blocks != null) {
            if (then.blocks.length != now.blocks.length) {
                tally.add(mode, "blocks", "could not be compared", tick, "", false);
            } else {
                List<String> differ = blockDifferences(then, now);
                if (!differ.isEmpty()) {
                    tally.add(mode, "blocks", "differ", tick, differ.size() + " blocks, live -> playback: " + few(differ), false);
                }
            }
        }
        Set<Long> places = new TreeSet<>(then.blockEntities.keySet());
        places.addAll(now.blockEntities.keySet());
        for (long place : places) {
            String a = then.blockEntities.get(place), b = now.blockEntities.get(place);
            if (!java.util.Objects.equals(a, b)) {
                String kind = (a != null ? a : b).split(" ")[0];
                tally.add(mode, "block entity " + kind, a == null ? "is in playback only" : b == null ? "is missing in playback" : "differs", tick,
                    BlockPos.of(place).toShortString() + ": live " + a + ", playback " + b, false);
            }
        }

        if (then.light != null && now.light != null && !mode.equals("play")) {
            compareLight(then, now.light, mode, tally, false);
        }
        if (!then.bars.equals(now.bars)) {
            tally.add(mode, "boss bar", "differs", tick, "live " + then.bars.replace('\n', '/') + ", playback " + now.bars.replace('\n', '/'), false);
        }
        if (Math.abs(then.rain - now.rain) > 0.011F || Math.abs(then.thunder - now.thunder) > 0.011F) {
            tally.add(mode, "weather", "differs", tick, "live rain " + then.rain + " thunder " + then.thunder + ", playback rain " + now.rain + " thunder " + now.thunder, false);
        }
        if (then.dayTime != now.dayTime) {
            tally.add(mode, "time of day", "differs", tick, "live " + then.dayTime + ", playback " + now.dayTime, Math.abs(then.dayTime - now.dayTime) <= 1);
        }
        // The HUD is written at the end of a tick and shown from the next, so it may be the tick before's
        boolean hudSeen = false;
        for (int back = 0; back <= 3 && !hudSeen; back++) {
            Census earlier = live.get(tick - back);
            hudSeen = earlier != null && earlier.hud.equals(now.hud);
        }
        if (!hudSeen) {
            tally.add(mode, "recorded HUD", "differs", tick, "live " + then.hud + ", playback " + now.hud, false);
        }
        Integer inPlay = playParticles.get(tick);
        if (!mode.equals("play") && inPlay != null && now.particles > inPlay + 20) {
            tally.add(mode, "particles", "piled up over a jump", tick, now.particles + " in the air after the jump, " + inPlay + " at the same tick in play", false);
        }
    }

    /** Whether the live game had an entity within the last ten ticks */
    private static boolean lately(int id, int tick) {
        for (int back = 1; back <= 10; back++) {
            Census earlier = live.get(tick - back);
            if (earlier != null && earlier.things.containsKey(id)) {
                return true;
            }
        }
        return false;
    }

    // --- Kept with a fixture (see TourFixtures), in the order read back ---

    static void writeLive(TourFixtures.Out out) throws java.io.IOException {
        out.name("boxes");
        out.json.beginArray();
        for (Object[] box : BOXES) {
            out.json.beginArray();
            out.string((String) box[0]);
            out.json.value((Integer) box[1]);
            for (int edge : (int[]) box[2]) {
                out.json.value(edge);
            }
            out.json.endArray();
        }
        out.json.endArray();
        out.name("sounds").strings(liveSounds);
        out.name("census");
        out.json.beginArray();
        for (Census census : live.values()) {
            out.json.beginArray();
            out.json.value(census.tick);
            out.string(census.dimension);
            out.json.value(census.recorderId);
            out.string(census.bars);
            out.string(census.hud);
            out.json.value(census.dayTime).value((double) census.rain).value((double) census.thunder).value(census.particles);
            out.json.beginArray();
            for (Map.Entry<Integer, Thing> entry : census.things.entrySet()) {
                Thing thing = entry.getValue();
                out.json.value(entry.getKey());
                out.string(thing.type);
                out.string(thing.label);
                out.json.value(thing.x).value(thing.y).value(thing.z).value((double) thing.yaw);
                out.string(thing.state);
                out.string(thing.clocks);
            }
            out.json.endArray();
            out.json.beginArray();
            for (Map.Entry<Long, String> entry : census.blockEntities.entrySet()) {
                out.json.value(entry.getKey());
                out.string(entry.getValue());
            }
            out.json.endArray();
            out.shorts(census.blocks);
            out.strings(census.changes);
            out.bytes(census.light);
            out.bytes(census.lightLater);
            out.json.endArray();
        }
        out.json.endArray();
    }

    static void readLive(TourFixtures.In in) throws java.io.IOException {
        in.name("boxes");
        in.json.beginArray();
        while (in.more()) {
            in.json.beginArray();
            String dimension = in.string();
            int from = in.integer();
            int[] edges = new int[6];
            for (int i = 0; i < 6; i++) {
                edges[i] = in.integer();
            }
            in.json.endArray();
            BOXES.add(new Object[]{dimension, from, edges});
        }
        in.json.endArray();
        liveSounds.addAll(in.name("sounds").strings());
        in.name("census");
        in.json.beginArray();
        while (in.more()) {
            in.json.beginArray();
            Census census = new Census(in.integer());
            census.dimension = in.string();
            census.recorderId = in.integer();
            census.bars = in.string();
            census.hud = in.string();
            census.dayTime = in.number();
            census.rain = (float) in.decimal();
            census.thunder = (float) in.decimal();
            census.particles = in.integer();
            in.json.beginArray();
            while (in.more()) {
                int id = in.integer();
                census.things.put(id, new Thing(in.string(), in.string(), in.decimal(), in.decimal(), in.decimal(), (float) in.decimal(), in.string(), in.string()));
            }
            in.json.endArray();
            in.json.beginArray();
            while (in.more()) {
                census.blockEntities.put(in.number(), in.string());
            }
            in.json.endArray();
            census.blocks = in.shorts();
            census.changes = in.strings();
            census.light = in.bytes();
            census.lightLater = in.bytes();
            in.json.endArray();
            live.put(census.tick, census);
        }
        in.json.endArray();
    }

    /** Forgets the live game, for notes that could not be read to the end */
    static void forgetLive() {
        live.clear();
        keyTicks.clear();
        BOXES.clear();
        liveSounds.clear();
        liveGrabs.clear();
    }

    private static String few(List<String> lines) {
        return lines.size() <= 6 ? lines.toString() : lines.subList(0, 6) + " and " + (lines.size() - 6) + " more";
    }

    private static Map<String, String> split(String state) {
        Map<String, String> parts = new HashMap<>();
        for (String part : state.split(";")) {
            int at = part.indexOf('=');
            if (at > 0) {
                parts.put(part.substring(0, at), part.substring(at + 1));
            }
        }
        return parts;
    }

    /** How far something moved in one tick of the live game, at most, over the ticks around one */
    private static double speed(int id, int tick, int span) {
        double fastest = 0.0;
        for (int at = tick - span; at <= tick + 1; at++) {
            Census one = live.get(at - 1), two = live.get(at);
            Thing a = one == null ? null : one.things.get(id), b = two == null ? null : two.things.get(id);
            if (a != null && b != null) {
                fastest = Math.max(fastest, Math.sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z)));
            }
        }
        return fastest;
    }

    /**
     * Whether the recorder had this state within five ticks of the live game, either way. A game changes its own player at
     * once and hears the same from the server a tick or two later, and it restarts its own hurt flash when its health is
     * sent, which is not recorded; playback shows the recorder from what the server said about them to everyone
     */
    private static boolean recently(Census then, String key, String value) {
        for (int back = -5; back <= 5; back++) {
            Census earlier = live.get(then.tick - back);
            Thing recorder = earlier == null ? null : earlier.things.get(earlier.recorderId);
            if (recorder != null && java.util.Objects.equals(split(recorder.state).get(key), value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean recentClock(Census then, String key, double value) {
        for (int back = -5; back <= 5; back++) {
            Census earlier = live.get(then.tick - back);
            Thing recorder = earlier == null ? null : earlier.things.get(earlier.recorderId);
            if (recorder != null) {
                String had = split(recorder.clocks).get(key);
                if (Math.abs((had == null ? 0.0 : Double.parseDouble(had)) - value) < 1e-9) {
                    return true;
                }
            }
        }
        return false;
    }

}
