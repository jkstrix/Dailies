package comedymonkey.dailies.effects;

import com.google.common.base.MoreObjects;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import comedymonkey.dailies.capture.Recorder;
import comedymonkey.dailies.capture.SnapshotExtensions;
import comedymonkey.dailies.mixin.access.ItemInHandRendererAccess;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.playback.PlaybackExtensions;
import comedymonkey.dailies.playback.Viewer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;

/**
 * The player's arms and what they hold, seen through their eyes in playback: the main hand and the off hand, a shield
 * raised, food eaten, a bow drawn, a map held, swings, and each hand dropping and rising as items are swapped or used.
 * Drawn as the game draws its own player's hands, from the one the view rides with, in first person with Show HUD on
 * (as F1 hides them in the game), unless the held items look is off.
 * <p>
 * How far up each hand is comes from the recording: the game works that out only for its own player (attacks, swaps,
 * an item used, hands busy rowing), so the recorder's is written each tick it changes. Anyone else ridden with has
 * their hands up.
 */
public final class RecorderHands {

    /** The extension record of how far up the recorder's hands are: a byte each, main then off, 255 fully up */
    public static final String HANDS = "dailies:own_hands";

    // As recorded for the recorder
    private static float recordedMain = 1.0F;
    private static float recordedOff = 1.0F;
    // While recording: what was last written, as bytes
    private static int writtenMain = -1;
    private static int writtenOff = -1;

    // Kept a tick at a time for the one ridden with, as the game keeps them for its own player
    private static int keptFor = -1;
    private static int keptAtTick = -1;
    private static float main = 1.0F;
    private static float mainBefore = 1.0F;
    private static float off = 1.0F;
    private static float offBefore = 1.0F;
    private static float xBob;
    private static float xBobBefore;
    private static float yBob;
    private static float yBobBefore;
    private static ItemStack mainItem = ItemStack.EMPTY;
    private static ItemStack offItem = ItemStack.EMPTY;

    /** Whose hands are being drawn this moment, for the parts of the game's hand drawing that ask for its own player */
    private static @Nullable AbstractClientPlayer drawing = null;

    /** What the hands were last drawn holding, for the self test */
    public record Shown(long frame, String mainHand, String offHand, boolean usingItem, @Nullable InteractionHand usedHand,
                        int useRemaining, boolean blocking, float mainHeight, float offHeight) {
        static final Shown NONE = new Shown(-1, "", "", false, null, 0, false, 0.0F, 0.0F);
    }

    private static volatile Shown shown = Shown.NONE;
    private static volatile long drawnCount = 0;

    private RecorderHands() {
    }

    public static void init() {
        Recorder.everyRecordedTick(RecorderHands::record);
        SnapshotExtensions.register(HANDS, (out, codec) -> {
            float[] now = ownHeights();
            out.extension(HANDS, encode(now[0], now[1]));
        });
        PlaybackExtensions.register(HANDS, new PlaybackExtensions.Extension() {
            @Override
            public void play(byte[] data, int from, int length, boolean skipping) {
                if (length >= 2) {
                    recordedMain = (data[from] & 0xFF) / 255.0F;
                    recordedOff = (data[from + 1] & 0xFF) / 255.0F;
                }
            }

            @Override
            public void reset() {
                recordedMain = 1.0F;
                recordedOff = 1.0F;
                keptFor = -1;
            }

            @Override
            public void describe(java.util.function.Consumer<byte[]> out) {
                out.accept(encode(recordedMain, recordedOff));
            }
        });
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> tick());
    }

    // ------------------------------------------------------------------ While recording

    private static float[] ownHeights() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.gameRenderer == null) {
            return new float[]{1.0F, 1.0F};
        }
        ItemInHandRendererAccess hands = (ItemInHandRendererAccess) minecraft.gameRenderer.itemInHandRenderer;
        return new float[]{hands.dailies$mainHandHeight(), hands.dailies$offHandHeight()};
    }

    private static void record() {
        if (Minecraft.getInstance().player == null) {
            return;
        }
        float[] now = ownHeights();
        int mainByte = toByte(now[0]);
        int offByte = toByte(now[1]);
        if (mainByte != writtenMain || offByte != writtenOff) {
            writtenMain = mainByte;
            writtenOff = offByte;
            Recorder.get().writeExtension(HANDS, encode(now[0], now[1]));
        }
    }

    private static int toByte(float height) {
        return Math.round(Mth.clamp(height, 0.0F, 1.0F) * 255.0F);
    }

    private static byte[] encode(float mainHeight, float offHeight) {
        return new byte[]{(byte) toByte(mainHeight), (byte) toByte(offHeight)};
    }

    // ------------------------------------------------------------------ Kept a tick at a time in playback

    private static void tick() {
        if (!Playback.loaded()) {
            keptFor = -1;
            return;
        }
        if (!(Viewer.riding() instanceof Player player)) {
            keptFor = -1;
            return;
        }
        if (player.getId() != keptFor) {
            // Someone new, or the world just rebuilt: everything as it is now, nothing easing in from before
            keptFor = player.getId();
            keptAtTick = player.tickCount;
            main = mainBefore = heightOf(player, true);
            off = offBefore = heightOf(player, false);
            xBob = xBobBefore = player.getXRot();
            yBob = yBobBefore = player.getYRot();
            mainItem = player.getMainHandItem();
            offItem = player.getOffhandItem();
            return;
        }
        if (player.tickCount == keptAtTick) {
            // Paused, or between ticks: nothing moves on
            return;
        }
        keptAtTick = player.tickCount;
        mainBefore = main;
        offBefore = off;
        main = heightOf(player, true);
        off = heightOf(player, false);
        // The hands lag a little behind where the player looks, as the game's own do
        xBobBefore = xBob;
        yBobBefore = yBob;
        xBob += (player.getXRot() - xBob) * 0.5F;
        yBob += (player.getYRot() - yBob) * 0.5F;
        // The item shown changes once its hand is down, or at once when no swap shows (the game's own rule)
        ItemStack nextMain = player.getMainHandItem();
        ItemStack nextOff = player.getOffhandItem();
        if (main < 0.1F || replacesAtOnce(mainItem, nextMain) || !isRecorder(player)) {
            mainItem = nextMain;
        }
        if (off < 0.1F || replacesAtOnce(offItem, nextOff) || !isRecorder(player)) {
            offItem = nextOff;
        }
    }

    private static boolean replacesAtOnce(ItemStack shownNow, ItemStack next) {
        return ItemStack.matches(shownNow, next) || !net.neoforged.neoforge.client.ClientHooks.shouldCauseReequipAnimation(shownNow, next, -1);
    }

    private static float heightOf(Player player, boolean mainHand) {
        if (!isRecorder(player)) {
            return 1.0F;
        }
        return mainHand ? recordedMain : recordedOff;
    }

    private static boolean isRecorder(Entity entity) {
        try {
            var manifest = Playback.manifest();
            return manifest != null && manifest.recorderId != null && entity.getUUID().toString().equals(manifest.recorderId);
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ The frame

    /**
     * The player whose hands are drawn just now: the one ridden with through their eyes with Show HUD on and the HUD not
     * hidden. Null otherwise, and outside playback
     */
    public static @Nullable AbstractClientPlayer subject() {
        return RecorderEyes.subject() instanceof AbstractClientPlayer player ? player : null;
    }

    /** The player whose hands the game's hand drawing is drawing this moment, when it is not the game's own */
    public static @Nullable AbstractClientPlayer drawing() {
        return drawing;
    }

    /**
     * Draws the hands of the one the view rides with, as the game draws its own player's (in place of the viewer's, who
     * as a spectator has none): their arms, what each hand holds and how far up it is, the swing, the item in use
     */
    public static void renderHands(ItemInHandRenderer renderer, AbstractClientPlayer player, float partialTicks, PoseStack poseStack,
                                   MultiBufferSource.BufferSource buffers) {
        int light = Minecraft.getInstance().getEntityRenderDispatcher().getPackedLightCoords(player, partialTicks);
        float attack = player.getAttackAnim(partialTicks);
        InteractionHand attackHand = MoreObjects.firstNonNull(player.swingingArm, InteractionHand.MAIN_HAND);
        float xRot = Mth.lerp(partialTicks, player.xRotO, player.getXRot());
        boolean[] which = whichHands(player);
        float bobX = Mth.lerp(partialTicks, xBobBefore, xBob);
        float bobY = Mth.lerp(partialTicks, yBobBefore, yBob);
        poseStack.mulPose(Axis.XP.rotationDegrees((player.getViewXRot(partialTicks) - bobX) * 0.1F));
        poseStack.mulPose(Axis.YP.rotationDegrees((player.getViewYRot(partialTicks) - bobY) * 0.1F));
        noteDrawn(player);
        ItemInHandRendererAccess access = (ItemInHandRendererAccess) renderer;
        drawing = player;
        try {
            if (which[0]) {
                float swing = attackHand == InteractionHand.MAIN_HAND ? attack : 0.0F;
                float lowered = 1.0F - Mth.lerp(partialTicks, mainBefore, main);
                if (!net.neoforged.neoforge.client.ClientHooks.renderSpecificFirstPersonHand(InteractionHand.MAIN_HAND, poseStack, buffers, light, partialTicks,
                    xRot, swing, lowered, mainItem)) {
                    access.dailies$renderArmWithItem(player, partialTicks, xRot, InteractionHand.MAIN_HAND, swing, mainItem, lowered, poseStack, buffers, light);
                }
            }
            if (which[1]) {
                float swing = attackHand == InteractionHand.OFF_HAND ? attack : 0.0F;
                float lowered = 1.0F - Mth.lerp(partialTicks, offBefore, off);
                if (!net.neoforged.neoforge.client.ClientHooks.renderSpecificFirstPersonHand(InteractionHand.OFF_HAND, poseStack, buffers, light, partialTicks,
                    xRot, swing, lowered, offItem)) {
                    access.dailies$renderArmWithItem(player, partialTicks, xRot, InteractionHand.OFF_HAND, swing, offItem, lowered, poseStack, buffers, light);
                }
            }
        } finally {
            drawing = null;
        }
        buffers.endBatch();
    }

    /** Which hands show, main then off: a drawn bow or crossbow takes its hand alone, as the game decides for its own player */
    private static boolean[] whichHands(Player player) {
        ItemStack mainHand = player.getMainHandItem();
        ItemStack offHand = player.getOffhandItem();
        boolean bow = mainHand.is(Items.BOW) || offHand.is(Items.BOW);
        boolean crossbow = mainHand.is(Items.CROSSBOW) || offHand.is(Items.CROSSBOW);
        if (!bow && !crossbow) {
            return new boolean[]{true, true};
        }
        if (!player.isUsingItem()) {
            return chargedCrossbow(mainHand) ? new boolean[]{true, false} : new boolean[]{true, true};
        }
        ItemStack used = player.getUseItem();
        InteractionHand usedHand = player.getUsedItemHand();
        if (!used.is(Items.BOW) && !used.is(Items.CROSSBOW)) {
            return usedHand == InteractionHand.MAIN_HAND && chargedCrossbow(offHand) ? new boolean[]{true, false} : new boolean[]{true, true};
        }
        return usedHand == InteractionHand.MAIN_HAND ? new boolean[]{true, false} : new boolean[]{false, true};
    }

    private static boolean chargedCrossbow(ItemStack item) {
        return item.is(Items.CROSSBOW) && CrossbowItem.isCharged(item);
    }

    // ------------------------------------------------------------------ For the self test

    private static void noteDrawn(Player player) {
        if (!comedymonkey.dailies.looks.LooksRenderer.showsHeldItems()) {
            // With the held items look off the game is handed the hands and then told not to draw them
            return;
        }
        drawnCount++;
        shown = new Shown(drawnCount, key(mainItem), key(offItem), player.isUsingItem(), player.isUsingItem() ? player.getUsedItemHand() : null,
            player.getUseItemRemainingTicks(), player.isBlocking(), main, off);
    }

    private static String key(ItemStack item) {
        return item.isEmpty() ? "" : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item.getItem()).toString();
    }

    public static Shown shown() {
        return shown;
    }

    /** How many frames the hands have been drawn through someone's eyes since the game started */
    public static long drawnCount() {
        return drawnCount;
    }
}
