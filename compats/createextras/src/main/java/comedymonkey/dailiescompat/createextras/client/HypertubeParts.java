package comedymonkey.dailiescompat.createextras.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.pedrorok.hypertube.blocks.HypertubeBlock;
import com.pedrorok.hypertube.core.camera.DetachedCameraController;
import com.pedrorok.hypertube.core.connection.SimpleConnection;
import com.pedrorok.hypertube.core.escape.TubeEscapeHandler;
import com.pedrorok.hypertube.core.placement.TubePlacement;
import com.pedrorok.hypertube.core.travel.client.ClientTravelPathRender;
import com.pedrorok.hypertube.registry.ModDataComponent;
import com.pedrorok.hypertube.utils.ModColors;
import com.pedrorok.hypertube.utils.RenderUtils;
import com.simibubi.create.content.trains.track.TrackBlockOutline;
import comedymonkey.dailiescompat.createextras.state.ExtrasStates;
import net.createmod.catnip.math.AngleHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * Create: Hypertubes' overlays, from a sweep of its jar:
 * <ul>
 * <li>the arrow under the crosshair while riding a tube ({@link #ARROW}): which way the ride will go, white when the way
 * can be changed and orange when not; drawn with Hypertubes' own arrow;</li>
 * <li>the bar shown while the key to climb out of a tube is held ({@link #ESCAPE}), recorded as drawn ({@link Page});</li>
 * <li>the outline round the tube a placement starts from, while a tube is held to be joined on ({@link #OUTLINE}), green
 * when the tube can be placed and red when not, drawn into the world by the compat as Hypertubes draws it, only while
 * overlays show ({@link InWorld}).</li>
 * </ul>
 * The curve of a tube being placed is drawn through Catnip's outliner, which the Create compat records. Left out: the
 * highlight round a tube looked at (as the game's own highlight round a block is), the pulses along tubes and the
 * travellers in them (how the world looks, drawn by playback from its own world), the camera's ride, and the mod's
 * settings.
 */
final class HypertubeParts {

    static final String ARROW = "ride_arrow";
    static final String ESCAPE = "escape_bar";
    static final String OUTLINE = "placement_outline";

    private static final String TUBE = "create_hypertube:hypertube";
    /** The outline's state drawn over the last frame, and the frame */
    private static byte @Nullable [] outline = null;
    private static long outlineAt = Long.MIN_VALUE;

    private HypertubeParts() {
    }

    // --- The arrow ---

    /** As ClientTravelPathRender.renderOverlay decides whether to draw, and works out the arrow's turn */
    static byte @Nullable [] captureArrow() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gameMode == null || minecraft.options.hideGui || minecraft.gameMode.getPlayerMode() == GameType.SPECTATOR) {
            return null;
        }
        boolean traveling = Reflect.getStatic(ClientTravelPathRender.class, "isTraveling");
        Entity camera = minecraft.getCameraEntity();
        if (!traveling || camera == null || minecraft.player == null || !minecraft.player.getPersistentData().getBoolean("hypertube_travel")) {
            return null;
        }
        Direction way = Reflect.getStatic(ClientTravelPathRender.class, "lastValidDirection");
        if (way == null) {
            return null;
        }
        boolean canTurn = Reflect.getStatic(ClientTravelPathRender.class, "canChangeDirection");
        float step = 22.5F;
        float difference = AngleHelper.getShortestAngleDiff(DetachedCameraController.get().getYaw(), way.toYRot());
        if (Math.abs(difference) < 60.0F) {
            difference = 0.0F;
        }
        float angle = step * Math.round(difference / step) % 360.0F;
        return ExtrasStates.arrow(new ExtrasStates.Arrow(canTurn, angle));
    }

    /** As ClientTravelPathRender.renderOverlay draws it */
    static void drawArrow(GuiGraphics graphics, byte[] state) {
        ExtrasStates.Arrow arrow = ExtrasStates.readArrow(state);
        PoseStack pose = graphics.pose();
        pose.pushPose();
        try {
            pose.translate(graphics.guiWidth() / 2.0 - 91.0, graphics.guiHeight() / 1.5, 0.0);
            pose.translate(91.0F, -9.0F, 0.0F);
            pose.scale(0.925F, 0.925F, 1.0F);
            RenderUtils.directionArrow(pose, 0.0F, 0.0F, 1.0F, arrow.canTurn() ? 0xFF_FFFF : ModColors.ORANGE, arrow.angle());
        } finally {
            pose.popPose();
        }
    }

    // --- The escape bar ---

    static byte @Nullable [] captureEscape() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || minecraft.player == null) {
            return null;
        }
        Page page = new Page();
        TubeEscapeHandler.onRenderGuiOverlay(page, minecraft.getTimer());
        return page.state();
    }

    // --- The placement outline ---

    /** As TubePlacement.drawCustomBlockSelection decides whether to draw */
    static byte @Nullable [] captureOutline() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || minecraft.options.hideGui) {
            return null;
        }
        ItemStack held = minecraft.player.getMainHandItem();
        if (!TUBE.equals(Texts.item(held)) || !held.hasFoil()) {
            return null;
        }
        SimpleConnection from = held.get(ModDataComponent.TUBE_CONNECTING_FROM);
        if (from == null) {
            return null;
        }
        BlockState block = minecraft.level.getBlockState(from.pos());
        if (!(block.getBlock() instanceof HypertubeBlock)) {
            return null;
        }
        boolean canPlace = Reflect.getStatic(TubePlacement.class, "canPlace");
        return ExtrasStates.outline(new ExtrasStates.Outline(from.pos().asLong(), Texts.block(block), canPlace));
    }

    static void noteOutline(byte[] state) {
        outline = state;
        outlineAt = InWorld.frame();
    }

    /** Into the world, after the particles, as TubePlacement.drawCustomBlockSelection draws it */
    static void drawOutline(PoseStack pose, MultiBufferSource.BufferSource buffers, Vec3 camera) {
        if (outline == null || !InWorld.noted(outlineAt)) {
            return;
        }
        ExtrasStates.Outline recorded = ExtrasStates.readOutline(outline);
        BlockState block = Texts.block(recorded.block());
        if (block == null) {
            return;
        }
        BlockPos pos = BlockPos.of(recorded.pos());
        VoxelShape shape = shape(block);
        pose.pushPose();
        try {
            pose.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
            TrackBlockOutline.renderShape(shape, pose, buffers.getBuffer(RenderType.lines()), recorded.canPlace());
        } finally {
            pose.popPose();
        }
        buffers.endBatch(RenderType.lines());
    }

    /** The tube's own shape for a state, as Hypertubes outlines it */
    private static VoxelShape shape(BlockState block) {
        try {
            return Reflect.call(block.getBlock(), "getShape", new Class<?>[]{BlockState.class}, block);
        } catch (IllegalStateException notThere) {
            return block.getShape(Minecraft.getInstance().level, BlockPos.ZERO);
        }
    }

}
