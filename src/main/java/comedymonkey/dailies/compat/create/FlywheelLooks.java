package comedymonkey.dailies.compat.create;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.looks.LooksRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Makes a scene's looks reach what Flywheel draws. Create hands its moving parts (shafts, cogs, belts, a bearing's
 * top, and whole contraptions) to Flywheel, which draws them all together by a road of its own that passes none of
 * the game's places where a block entity or an entity can be turned away. So while a look hides blocks, entities or
 * single entities, the things hidden are taken out of Flywheel's own lists, and put back when the look lets them be
 * seen again.
 * <p>
 * Where the line falls: a block entity belongs to the blocks ("show_blocks"), wherever it is drawn. A contraption is
 * an entity, and the blocks it carries are drawn as part of it, so an assembled contraption follows
 * "show_entities", the entity filter and the changes made to single entities.
 * <p>
 * Only Flywheel's public lists are used ({@code VisualizationManager.get(level).blockEntities()} and
 * {@code entities()}, with {@code queueAdd}, {@code queueRemove} and {@code visualCount}), found by name so that
 * nothing is compiled against it. Without Flywheel, or with a version that has no such lists, nothing here runs.
 */
public final class FlywheelLooks {

    private static final String MANAGER = "dev.engine_room.flywheel.api.visualization.VisualizationManager";
    private static final String LIST = "dev.engine_room.flywheel.api.visualization.VisualManager";

    private static boolean looked = false;
    private static @Nullable MethodHandle get, blockEntities, entities, queueAdd, queueRemove, visualCount;
    private static boolean failed = false;

    /** What has been taken out of Flywheel's lists, held weakly: a jump throws the whole world away */
    private static final Set<BlockEntity> hiddenBlockEntities = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<Entity> hiddenEntities = Collections.newSetFromMap(new WeakHashMap<>());

    private FlywheelLooks() {
    }

    private static boolean ready() {
        if (!looked) {
            looked = true;
            if (Host.get().isModLoaded("create") || Host.get().isModLoaded("flywheel")) {
                try {
                    Class<?> manager = Class.forName(MANAGER);
                    Class<?> list = Class.forName(LIST);
                    MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                    get = lookup.findStatic(manager, "get", MethodType.methodType(manager, LevelAccessor.class));
                    blockEntities = lookup.findVirtual(manager, "blockEntities", MethodType.methodType(list));
                    entities = lookup.findVirtual(manager, "entities", MethodType.methodType(list));
                    queueAdd = lookup.findVirtual(list, "queueAdd", MethodType.methodType(void.class, Object.class));
                    queueRemove = lookup.findVirtual(list, "queueRemove", MethodType.methodType(void.class, Object.class));
                    visualCount = lookup.findVirtual(list, "visualCount", MethodType.methodType(int.class));
                } catch (Throwable t) {
                    get = null;
                    Host.LOGGER.warn("Dailies: this version of Flywheel has not the lists Dailies knows, so what it draws for Create stays in the picture when a scene hides blocks or entities", t);
                }
            }
        }
        return get != null && !failed;
    }

    private static void fail(Throwable problem) {
        if (!failed) {
            failed = true;
            Host.LOGGER.error("Dailies: Flywheel's lists could not be changed, so what it draws for Create stays in the picture when a scene hides blocks or entities. Said once", problem);
        }
    }

    /**
     * Once per drawn frame, before the picture, with the scene's looks already taken up.
     *
     * @param active whether a recording is on screen; when not, everything is put back
     */
    public static void frame(boolean active) {
        if (!ready()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        try {
            Object manager = level == null ? null : get.invoke((LevelAccessor) level);
            if (manager == null) {
                hiddenBlockEntities.clear();
                hiddenEntities.clear();
                return;
            }
            Object blockList = blockEntities.invoke(manager);
            Object entityList = entities.invoke(manager);

            boolean hideBlocks = active && LooksRenderer.hidesBlocks();
            if (hideBlocks) {
                // Every frame while they are hidden: chunks arrive, and a jump puts a whole new world in
                int reach = minecraft.options.getEffectiveRenderDistance() + 1;
                int centreX = minecraft.gameRenderer.mainCamera().blockPosition().getX() >> 4;
                int centreZ = minecraft.gameRenderer.mainCamera().blockPosition().getZ() >> 4;
                // Flywheel puts a block entity back by itself when its block changes. While it draws none, only those
                // not taken out yet need asking for; while it still draws any, every one is asked for again
                boolean noneDrawn = (int) visualCount.invoke(blockList) == 0;
                for (int x = centreX - reach; x <= centreX + reach; x++) {
                    for (int z = centreZ - reach; z <= centreZ + reach; z++) {
                        LevelChunk chunk = level.getChunkSource().getChunk(x, z, false);
                        if (chunk == null) {
                            continue;
                        }
                        for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                            if (hiddenBlockEntities.add(blockEntity) || !noneDrawn) {
                                queueRemove.invoke(blockList, (Object) blockEntity);
                            }
                        }
                    }
                }
            } else if (!hiddenBlockEntities.isEmpty()) {
                for (BlockEntity blockEntity : hiddenBlockEntities) {
                    if (!blockEntity.isRemoved() && blockEntity.getLevel() == level) {
                        queueAdd.invoke(blockList, (Object) blockEntity);
                    }
                }
                hiddenBlockEntities.clear();
            }

            if (active && LooksRenderer.hidesAnyEntity()) {
                Set<Entity> now = Collections.newSetFromMap(new IdentityHashMap<>());
                for (Entity entity : level.entitiesForRendering()) {
                    if (LooksRenderer.hidesEntity(entity)) {
                        now.add(entity);
                        hiddenEntities.add(entity);
                        queueRemove.invoke(entityList, (Object) entity);
                    }
                }
                for (Iterator<Entity> hidden = hiddenEntities.iterator(); hidden.hasNext(); ) {
                    Entity entity = hidden.next();
                    if (!now.contains(entity)) {
                        hidden.remove();
                        if (!entity.isRemoved() && entity.level() == level) {
                            queueAdd.invoke(entityList, (Object) entity);
                        }
                    }
                }
            } else if (!hiddenEntities.isEmpty()) {
                for (Entity entity : hiddenEntities) {
                    if (!entity.isRemoved() && entity.level() == level) {
                        queueAdd.invoke(entityList, (Object) entity);
                    }
                }
                hiddenEntities.clear();
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    /**
     * How many things Flywheel is drawing in the world on screen: {block entities, entities}, or null when Flywheel
     * is not there. For the scripted test, and for anyone chasing a look that seems not to reach Create's parts.
     */
    public static int @Nullable [] drawn() {
        if (!ready()) {
            return null;
        }
        try {
            ClientLevel level = Minecraft.getInstance().level;
            Object manager = level == null ? null : get.invoke((LevelAccessor) level);
            if (manager == null) {
                return null;
            }
            return new int[]{(int) visualCount.invoke(blockEntities.invoke(manager)), (int) visualCount.invoke(entities.invoke(manager))};
        } catch (Throwable t) {
            return null;
        }
    }

}
