package comedymonkey.dailies.compat.createaddons;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.capture.Snapshots;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.playback.PlaybackExtensions;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * ComedyMonkey's Create Addons (mod id comedymonkeysaddons), the owner's own mod. Two things of it need help:
 * <ul>
 * <li>The Fish Tank and the Monster Cage send a client a summary of their trough and tray, worked out from inventories
 * only the server holds. Asked to describe itself on a client, either works the summary out again from its own empty
 * inventories, and so sends nothing and forgets what it was told: in every snapshot, and in the live game the moment a
 * snapshot is taken. Here the summary the client was told is kept, put back, and written into the snapshot.</li>
 * <li>Trade Bot (and the Clockwork Juggernaut) play their animations on a clock of their own that follows how fast the
 * server's time arrives, eased over two seconds. In playback the time jumps with every jump and stops while paused,
 * so the clock ran slow for seconds after a jump back, and a bot that was kept across a jump went on from where it
 * was. In playback the clock is the recorded game time, exactly, and a jump makes each bot find his place in his
 * animation again from the recorded data, as on first sight.</li>
 * </ul>
 * Nothing is compiled against the addon: its classes and fields are found by name. If one is missing, that part does
 * nothing and one line is logged.
 */
public final class CreateAddonsCompat {

    private static final String ADDON = "com.comedymonkey.createaddons.";
    private static final String EXTENSION = "dailies:createaddons";

    /** A block entity that sends a summary: the fields it keeps the summary in, and the names it sends them under */
    private record Summary(Field shown, Field count, Field tray, Field used, String shownKey, String countKey) {
    }

    private static final Map<Class<?>, Optional<Summary>> SUMMARIES = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Optional<Field>> SEEN_CLIPS = new ConcurrentHashMap<>();
    private static Field clockNow, clockRate;
    private static boolean warned = false;

    private CreateAddonsCompat() {
    }

    public static void init() {
        if (!Host.get().isModLoaded("comedymonkeysaddons")) {
            return;
        }
        Snapshots.describeBlockEntities(CreateAddonsCompat::describe);
        try {
            Class<?> clock = Class.forName(ADDON + "content.tradebot.clip.ClipClock");
            clockNow = clock.getDeclaredField("now");
            clockNow.setAccessible(true);
            clockRate = clock.getDeclaredField("rate");
            clockRate.setAccessible(true);
            // After the addon's own tick of its clock, which runs at the usual priority
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, (ClientTickEvent.Pre event) -> pinClock());
        } catch (Throwable t) {
            Host.LOGGER.warn("Dailies: Trade Bot's animation clock was not found in this version of ComedyMonkey's Create Addons, so his animations may run a little slow just after a jump in playback", t);
        }
        PlaybackExtensions.register(EXTENSION, new PlaybackExtensions.Extension() {
            @Override
            public void play(byte[] data, int from, int length, boolean skipping) {
                // Nothing is written under this name
            }

            @Override
            public void arrived() {
                findPlacesAgain();
            }
        });
    }

    // --- The trough and the tray ---

    private static CompoundTag describe(BlockEntity blockEntity, HolderLookup.Provider registries, Supplier<CompoundTag> own) throws Exception {
        Optional<Summary> found = SUMMARIES.computeIfAbsent(blockEntity.getClass(), CreateAddonsCompat::summaryOf);
        if (found.isEmpty()) {
            return null;
        }
        Summary summary = found.get();
        ItemStack shown = (ItemStack) summary.shown.get(blockEntity);
        int count = summary.count.getInt(blockEntity);
        @SuppressWarnings("unchecked")
        List<ItemStack> tray = (List<ItemStack>) summary.tray.get(blockEntity);
        List<ItemStack> trayKept = new ArrayList<>(tray);
        int used = summary.used.getInt(blockEntity);
        CompoundTag tag;
        try {
            tag = own.get();
        } finally {
            // What the client was told, as it was, whatever working it out again did to it
            summary.shown.set(blockEntity, shown);
            summary.count.setInt(blockEntity, count);
            tray.clear();
            tray.addAll(trayKept);
            summary.used.setInt(blockEntity, used);
        }
        tag.remove(summary.shownKey);
        if (!shown.isEmpty()) {
            tag.put(summary.shownKey, shown.save(registries));
        }
        tag.putInt(summary.countKey, count);
        ListTag shownTray = new ListTag();
        for (ItemStack stack : trayKept) {
            if (!stack.isEmpty()) {
                shownTray.add(stack.save(registries));
            }
        }
        tag.put("Tray", shownTray);
        tag.putInt("TrayUsed", used);
        return tag;
    }

    private static Optional<Summary> summaryOf(Class<?> type) {
        String name = type.getName();
        try {
            if (name.equals(ADDON + "content.tank.FishTankBlockEntity")) {
                return Optional.of(new Summary(field(type, "shownFood"), field(type, "shownFoodCount"), field(type, "tray"), field(type, "trayUsed"), "ShownFood", "FoodCount"));
            }
            if (name.equals(ADDON + "content.cage.MonsterCageBlockEntity")) {
                return Optional.of(new Summary(field(type, "shownChow"), field(type, "shownChowCount"), field(type, "tray"), field(type, "trayUsed"), "ShownChow", "ChowCount"));
            }
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                Host.LOGGER.warn("Dailies: the trough of a Fish Tank or Monster Cage could not be read in this version of ComedyMonkey's Create Addons, so it may show empty after a jump in playback", t);
            }
        }
        return Optional.empty();
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    // --- Trade Bot's clock ---

    /** In playback his animations run on the recorded game time itself, which is what the server ran them on */
    private static void pinClock() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!Playback.loaded() || minecraft.level == null) {
            return;
        }
        try {
            clockNow.setDouble(null, (double) minecraft.level.getGameTime());
            clockRate.setFloat(null, 1.0F);
        } catch (Throwable t) {
            // Left to run as it does in the live game
        }
    }

    /** After a jump each bot works out again how far into his animation he is, from what was recorded */
    private static void findPlacesAgain() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        pinClock();
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            Optional<Field> seen = SEEN_CLIPS.computeIfAbsent(entity.getClass(), CreateAddonsCompat::seenClipOf);
            if (seen.isPresent()) {
                try {
                    seen.get().setInt(entity, -1);
                    // And what he was last seen playing is forgotten too: kept, a jump back would make him cross-fade from a
                    // time still to come and play every sound cue between, as if his animation had gone round again
                    Optional<Field[]> shown = PLAYBACKS.computeIfAbsent(entity.getClass(), CreateAddonsCompat::playbackOf);
                    if (shown.isPresent()) {
                        Object playback = shown.get()[0].get(entity);
                        if (playback != null) {
                            shown.get()[1].set(playback, null);
                            shown.get()[2].set(playback, null);
                        }
                    }
                } catch (IllegalAccessException e) {
                    // He finds his place when his animation next changes
                }
            }
        }
    }

    private static final Map<Class<?>, Optional<Field[]>> PLAYBACKS = new ConcurrentHashMap<>();

    /** His animation player, and the fields in it that say which animation it last saw start and which it fades from */
    private static Optional<Field[]> playbackOf(Class<?> type) {
        for (Class<?> at = type; at != null && at != Entity.class; at = at.getSuperclass()) {
            try {
                Field playback = at.getDeclaredField("playback");
                if (!playback.getType().getName().endsWith("ClipPlayback")) {
                    return Optional.empty();
                }
                playback.setAccessible(true);
                Field clip = playback.getType().getDeclaredField("clip");
                clip.setAccessible(true);
                Field fadeFrom = playback.getType().getDeclaredField("fadeFrom");
                fadeFrom.setAccessible(true);
                return Optional.of(new Field[]{playback, clip, fadeFrom});
            } catch (NoSuchFieldException notHere) {
                // Look further up
            }
        }
        return Optional.empty();
    }

    private static Optional<Field> seenClipOf(Class<?> type) {
        if (!type.getName().startsWith(ADDON)) {
            return Optional.empty();
        }
        for (Class<?> at = type; at != null && at != Entity.class; at = at.getSuperclass()) {
            try {
                Field field = at.getDeclaredField("seenClip");
                if (field.getType() == int.class) {
                    field.setAccessible(true);
                    return Optional.of(field);
                }
            } catch (NoSuchFieldException notHere) {
                // Look further up
            }
        }
        return Optional.empty();
    }

}
