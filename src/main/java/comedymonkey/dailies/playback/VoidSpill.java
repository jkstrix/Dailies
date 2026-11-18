package comedymonkey.dailies.playback;

import comedymonkey.dailies.editor.EditorSession;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Whatever falls out of the bottom of the world is gone a tick later: a player who dies in the void drops everything
 * they carried where they are, hundreds of blocks down, and the game deletes it all at once, so the live game shows
 * nothing of it. Playback lets those items fly as the server threw them, scattering and tumbling down for a few
 * seconds before they go (the owner, 18 November: "for comedic effect ... your items should appear in playback
 * scattering and falling"). They are the recording's own items, each with the velocity it was dropped with. A look of
 * the scene ({@code void_spill}), on unless turned off; never while a jump runs.
 */
public final class VoidSpill {

    /** How long an item that fell out of the world is left to fall, in ticks */
    private static final int LINGER_TICKS = 60;

    /** Items left falling, by entity id, with the ticks each has left */
    private static final Int2IntOpenHashMap lingering = new Int2IntOpenHashMap();

    private VoidSpill() {
    }

    static void init() {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> tick());
        Playback.onClosing(lingering::clear);
    }

    private static boolean enabled() {
        if (!Playback.loaded() || Playback.skipping() || Boolean.getBoolean("dailies.playback.noVoidSpill")) {
            return false;
        }
        return !EditorSession.open() || EditorSession.scene().looks().voidSpill;
    }

    /**
     * Asked where the game would delete an entity for having fallen out of the world: true keeps it, an item that fell
     * out a moment ago, to go on falling for a while
     */
    public static boolean keeps(Entity entity) {
        if (!(entity instanceof ItemEntity) || !(entity.level() instanceof ClientLevel)) {
            return false;
        }
        if (lingering.containsKey(entity.getId())) {
            return true;
        }
        if (!enabled()) {
            return false;
        }
        lingering.put(entity.getId(), LINGER_TICKS);
        return true;
    }

    /** Whether an entity is an item playback is letting fall, which the live game no longer had. For the tests */
    public static boolean lingers(Entity entity) {
        return lingering.containsKey(entity.getId());
    }

    /**
     * The ids of a recorded removal the game is to take out now: those playback is letting fall go when their time is up.
     * An item already below the world when its removal comes is let fall too: the server can drop it and take it away
     * again before this game has ticked it down there, and then only its removal says it fell out
     */
    static IntList stillToRemove(IntList ids) {
        ClientLevel level = Minecraft.getInstance().level;
        IntArrayList left = new IntArrayList(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            int id = ids.getInt(i);
            if (lingering.containsKey(id)) {
                continue;
            }
            Entity entity = level == null ? null : level.getEntity(id);
            if (entity != null && entity.getY() < level.getMinBuildHeight() - 64 && keeps(entity)) {
                continue;
            }
            left.add(id);
        }
        return left;
    }

    private static void tick() {
        if (lingering.isEmpty()) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !Playback.loaded() || Playback.skipping()) {
            // A jump or a closed recording: the world they fell through is not there any more
            if (level != null) {
                lingering.keySet().forEach(id -> level.removeEntity(id, Entity.RemovalReason.DISCARDED));
            }
            lingering.clear();
            return;
        }
        IntArrayList done = new IntArrayList();
        for (Int2IntOpenHashMap.Entry entry : lingering.int2IntEntrySet()) {
            int left = entry.getIntValue() - 1;
            if (left <= 0) {
                done.add(entry.getIntKey());
            } else {
                entry.setValue(left);
            }
        }
        for (int i = 0; i < done.size(); i++) {
            lingering.remove(done.getInt(i));
            level.removeEntity(done.getInt(i), Entity.RemovalReason.DISCARDED);
        }
    }

}
