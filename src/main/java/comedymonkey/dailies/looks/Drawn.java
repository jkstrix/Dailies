package comedymonkey.dailies.looks;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * A count of what went into the last finished frame: entities, players, layers of terrain, block entities, and what
 * a scene's looks kept out of it. Nothing is drawn from here. It is how the scripted test, and anyone chasing a look
 * that seems not to work beside another renderer mod, can tell that a look really reached the picture without
 * reading pixels. Render thread for the counting, any thread for the asking.
 */
public final class Drawn {

    private static int entities;
    private static int players;
    private static int terrainLayers;
    private static int terrainLayersHidden;
    private static int blockEntities;
    private static int blockEntitiesHidden;
    private static int beams;
    private static int beamsHidden;

    private static volatile int[] last = new int[8];
    private static volatile long frames = 0;
    /** The part of the screen the frame leaves clear, in the interface's own measure, and whether the bars are solid */
    private static volatile double[] clear = null;
    private static volatile boolean solidBars = false;
    private static double[] barsNow = null;
    private static boolean solidNow = false;

    private Drawn() {
    }

    /** Once per frame, before anything is drawn: what was counted belongs to the frame that has just ended */
    static void frame() {
        last = new int[]{entities, players, terrainLayers, terrainLayersHidden, blockEntities, blockEntitiesHidden, beams, beamsHidden};
        frames++;
        entities = players = terrainLayers = terrainLayersHidden = blockEntities = blockEntitiesHidden = beams = beamsHidden = 0;
        armourLast = new int[]{armour, armourTrimmed, armourOnInvisible};
        armour = armourTrimmed = armourOnInvisible = 0;
        clear = barsNow;
        solidBars = solidNow;
        barsNow = null;
        solidNow = false;
    }

    public static void entity(Entity entity) {
        if (entity instanceof Player) {
            players++;
        } else {
            entities++;
        }
    }

    // Armour drawn this frame and the last: pieces, pieces with a trim, and pieces on entities invisible to the viewer
    private static int armour, armourTrimmed, armourOnInvisible;
    private static int[] armourLast = new int[3];

    /** The armour of one entity about to be drawn. Render thread */
    public static void armour(net.minecraft.world.entity.LivingEntity entity) {
        net.minecraft.client.player.LocalPlayer viewer = net.minecraft.client.Minecraft.getInstance().player;
        boolean invisible = viewer != null && entity.isInvisibleTo(viewer);
        for (net.minecraft.world.entity.EquipmentSlot slot : new net.minecraft.world.entity.EquipmentSlot[]{net.minecraft.world.entity.EquipmentSlot.HEAD,
            net.minecraft.world.entity.EquipmentSlot.CHEST, net.minecraft.world.entity.EquipmentSlot.LEGS, net.minecraft.world.entity.EquipmentSlot.FEET}) {
            net.minecraft.world.item.ItemStack piece = entity.getItemBySlot(slot);
            if (piece.isEmpty() || !(piece.getItem() instanceof net.minecraft.world.item.ArmorItem)) {
                continue;
            }
            armour++;
            if (piece.has(net.minecraft.core.component.DataComponents.TRIM)) {
                armourTrimmed++;
            }
            if (invisible) {
                armourOnInvisible++;
            }
        }
    }

    /** Armour pieces drawn in the last whole frame */
    public static int armourDrawn() {
        return armourLast[0];
    }

    /** Of those, the pieces with a trim */
    public static int armourTrimmedDrawn() {
        return armourLast[1];
    }

    /** Of those, the pieces worn by entities invisible to the viewer, which the game still draws */
    public static int armourOnInvisibleDrawn() {
        return armourLast[2];
    }

    public static void terrainLayer(boolean hidden) {
        if (hidden) {
            terrainLayersHidden++;
        } else {
            terrainLayers++;
        }
    }

    public static void blockEntity(boolean hidden) {
        if (hidden) {
            blockEntitiesHidden++;
        } else {
            blockEntities++;
        }
    }

    /** A beam of a beacon, an end gateway or a mod's block that borrows the game's beam */
    public static void beam(boolean hidden) {
        if (hidden) {
            beamsHidden++;
        } else {
            beams++;
        }
    }

    static void bars(double left, double top, double right, double bottom, boolean solid) {
        barsNow = new double[]{left, top, right, bottom};
        solidNow = solid;
    }

    /** How many frames have been counted; two more than when a look was changed and the counts below are of the new look */
    public static long frames() {
        return frames;
    }

    /** Entities that are not players drawn in the last frame */
    public static int entities() {
        return last[0];
    }

    public static int players() {
        return last[1];
    }

    /** Layers of terrain handed to the renderer in the last frame, and those a look turned away */
    public static int terrainLayers() {
        return last[2];
    }

    public static int terrainLayersHidden() {
        return last[3];
    }

    public static int blockEntities() {
        return last[4];
    }

    public static int blockEntitiesHidden() {
        return last[5];
    }

    /** Beams drawn in the last frame, and those a look turned away */
    public static int beams() {
        return last[6];
    }

    public static int beamsHidden() {
        return last[7];
    }

    /** {left, top, right, bottom} of what the frame bars of the last frame left clear, or null when none were drawn in it */
    public static double[] clearFrame() {
        double[] now = clear;
        return now == null ? null : now.clone();
    }

    /** Whether the bars drawn last were solid black (the picture's true frame) and not a dimmed guide */
    public static boolean solidBars() {
        return solidBars;
    }

}
