package comedymonkey.dailiescompat.supplementaries.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import comedymonkey.dailies.api.DailiesApi;
import comedymonkey.dailies.api.compat.DailiesCompats;
import comedymonkey.dailiescompat.supplementaries.state.SuppStates;
import net.mehvahdjukaar.supplementaries.client.cannon.CannonController;
import net.mehvahdjukaar.supplementaries.client.cannon.CannonTrajectoryRenderer;
import net.mehvahdjukaar.supplementaries.common.block.cannon.BallisticTrajectory;
import net.mehvahdjukaar.supplementaries.common.block.cannon.ShootingMode;
import net.mehvahdjukaar.supplementaries.common.block.tiles.CannonBlockTile;
import net.mehvahdjukaar.supplementaries.configs.ClientConfigs;
import net.mehvahdjukaar.supplementaries.reg.ModEnchantments;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * What Supplementaries draws into the world during play, rather than over it: the trajectory of a cannon being aimed
 * (its arrows and the circle where it lands), and the outline a slingshot with Stasis draws round the far block it points
 * at. Each is recorded as an overlay (what was drawn, at the end of each tick: the trajectory as Supplementaries worked it
 * out on the recorder's game, the outlined block's place), and its drawing over the world in playback notes the frame;
 * its part in the world is drawn only while that note is fresh and Dailies still shows overlays, so it follows the
 * owner's rule like every other overlay. The trajectory is drawn by Supplementaries' own drawing, handed the recorded
 * trajectory and the playback world's cannon in place of the controller's own for that call alone.
 */
final class InWorld {

    static final String TRAJECTORY = "cannon_trajectory";
    static final String OUTLINE = "slingshot_outline";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean noOverlaysShown = false;
    private static boolean failedTrajectory = false;
    private static boolean failedOutline = false;

    private static SuppStates.@Nullable Trajectory trajectory = null;
    private static long trajectoryAt = Long.MIN_VALUE;
    private static SuppStates.@Nullable Outline outline = null;
    private static long outlineAt = Long.MIN_VALUE;

    private InWorld() {
    }

    static void frameStarts(RenderFrameEvent.Pre event) {
        Frames.next();
    }

    /** Whether a part noted this recently is to be drawn into the world */
    private static boolean noted(long notedAt) {
        return notedAt >= Frames.now() - 3 && DailiesApi.isPlayback() && overlaysShown();
    }

    /**
     * Whether Dailies draws overlays now. A Dailies from before {@code overlaysShown} (the first API jar of 1.1.0) has none,
     * so the parts drawn into the world are never shown with it, rather than shown against the owner's rule
     */
    private static boolean overlaysShown() {
        if (noOverlaysShown) {
            return false;
        }
        try {
            return DailiesCompats.overlaysShown();
        } catch (LinkageError tooOld) {
            noOverlaysShown = true;
            return false;
        }
    }

    // --- A cannon's trajectory ---

    static byte @Nullable [] captureTrajectory() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.options.hideGui || !CannonController.isActive()) {
            return null;
        }
        CannonBlockTile cannon = Reflect.getStatic(CannonController.class, "cannon");
        BallisticTrajectory path = Reflect.getStatic(CannonController.class, "trajectory");
        HitResult hit = Reflect.getStatic(CannonController.class, "hit");
        boolean shown = Reflect.getStatic(CannonController.class, "showsTrajectory");
        ShootingMode mode = Reflect.getStatic(CannonController.class, "shootingMode");
        // As CannonTrajectoryRenderer decides whether to draw it; a cannon carried by a boat has no place of its own to find again
        if (cannon == null || path == null || hit == null || !shown || minecraft.level.getBlockEntity(cannon.getBlockPos()) != cannon) {
            return null;
        }
        int kind = hit instanceof BlockHitResult ? SuppStates.HIT_BLOCK : SuppStates.HIT_OTHER;
        Vec3 at = hit.getLocation();
        int face = hit instanceof BlockHitResult block ? block.getDirection().get3DDataValue() : 0;
        long hitBlock = hit instanceof BlockHitResult block ? block.getBlockPos().asLong() : 0L;
        return SuppStates.trajectory(new SuppStates.Trajectory(cannon.getBlockPos().asLong(), path.pointHit().x, path.pointHit().y, path.pitch(), path.finalTime(),
            path.miss(), path.gravity(), path.drag(), path.v0x(), path.v0y(), mode == null ? 0 : mode.ordinal(), kind, at.x, at.y, at.z, face, hitBlock));
    }

    /** The overlay's drawing over the world: notes the trajectory for the world's next drawing, which is where it shows */
    static void noteTrajectory(byte[] state) {
        trajectory = SuppStates.readTrajectory(state);
        trajectoryAt = Frames.now();
    }

    /** By CannonTrajectoryRenderer itself, on the playback world's cannon, as the cannon's own drawing calls it */
    private static void drawTrajectory(PoseStack pose, MultiBufferSource.BufferSource buffers, Vec3 camera, float partialTick) {
        SuppStates.Trajectory recorded = trajectory;
        Minecraft minecraft = Minecraft.getInstance();
        if (recorded == null || !noted(trajectoryAt) || minecraft.level == null) {
            return;
        }
        BlockPos pos = BlockPos.of(recorded.cannon());
        if (!(minecraft.level.getBlockEntity(pos) instanceof CannonBlockTile cannon)) {
            return;
        }
        Vec3 at = new Vec3(recorded.hitX(), recorded.hitY(), recorded.hitZ());
        HitResult hit = recorded.hitKind() == SuppStates.HIT_BLOCK
            ? new BlockHitResult(at, Direction.from3DDataValue(recorded.face()), BlockPos.of(recorded.hitBlock()), false)
            : new HitResult(at) {
                @Override
                public Type getType() {
                    return Type.ENTITY;
                }
            };
        BallisticTrajectory path = new BallisticTrajectory(new Vec2(recorded.hitAcross(), recorded.hitDown()), recorded.pitch(), recorded.time(), recorded.miss(),
            recorded.gravity(), recorded.drag(), recorded.speedAcross(), recorded.speedUp());
        ShootingMode[] modes = ShootingMode.values();
        ShootingMode mode = recorded.shooting() >= 0 && recorded.shooting() < modes.length ? modes[recorded.shooting()] : ShootingMode.DOWN;
        Object wasCannon = Reflect.getStatic(CannonController.class, "cannon");
        Object wasPath = Reflect.getStatic(CannonController.class, "trajectory");
        Object wasHit = Reflect.getStatic(CannonController.class, "hit");
        Object wasShown = Reflect.getStatic(CannonController.class, "showsTrajectory");
        Object wasMode = Reflect.getStatic(CannonController.class, "shootingMode");
        pose.pushPose();
        try {
            Reflect.setStatic(CannonController.class, "cannon", cannon);
            Reflect.setStatic(CannonController.class, "trajectory", path);
            Reflect.setStatic(CannonController.class, "hit", hit);
            Reflect.setStatic(CannonController.class, "showsTrajectory", true);
            Reflect.setStatic(CannonController.class, "shootingMode", mode);
            pose.translate(pos.getX() - camera.x + 0.5, pos.getY() - camera.y + 0.5, pos.getZ() - camera.z + 0.5);
            CannonTrajectoryRenderer.render(cannon, pose, buffers, LevelRenderer.getLightColor(minecraft.level, pos), OverlayTexture.NO_OVERLAY, partialTick);
            buffers.endBatch();
        } finally {
            pose.popPose();
            Reflect.setStatic(CannonController.class, "cannon", wasCannon);
            Reflect.setStatic(CannonController.class, "trajectory", wasPath);
            Reflect.setStatic(CannonController.class, "hit", wasHit);
            Reflect.setStatic(CannonController.class, "showsTrajectory", wasShown);
            Reflect.setStatic(CannonController.class, "shootingMode", wasMode);
        }
    }

    // --- A slingshot's far outline ---

    static byte @Nullable [] captureOutline() {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.level == null || minecraft.options.hideGui || !ClientConfigs.Items.SLINGSHOT_OUTLINE.get()) {
            return null;
        }
        // As the slingshot's mark on the hotbar asks for it: a slingshot with Stasis in either hand
        if (!stasis(player.getMainHandItem()) && !stasis(player.getOffhandItem())) {
            return null;
        }
        // As SlingshotRendererHelper.grabNewLookPos finds the block: forty blocks along the look, past the player's reach
        Vec3 start = player.position().add(0.0, player.getEyeHeight(), 0.0);
        Vec3 end = start.add(player.getLookAngle().scale(40.0));
        BlockHitResult ray = minecraft.level.clip(new ClipContext(start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (ray.getType() != HitResult.Type.BLOCK || start.distanceToSqr(ray.getLocation()) <= Mth.square(player.getAttributeBaseValue(Attributes.BLOCK_INTERACTION_RANGE))) {
            return null;
        }
        BlockPos pos = ray.getBlockPos();
        if (minecraft.level.getBlockState(pos).isAir() || !minecraft.level.getWorldBorder().isWithinBounds(pos)) {
            return null;
        }
        return SuppStates.outline(new SuppStates.Outline(pos.asLong(), ClientConfigs.Items.SLINGSHOT_OUTLINE_COLOR.get()));
    }

    private static boolean stasis(ItemStack stack) {
        return stack.getItem() instanceof net.mehvahdjukaar.supplementaries.common.items.SlingshotItem
            && EnchantmentHelper.has(stack, ModEnchantments.PROJECTILE_NO_GRAVITY.get());
    }

    static void noteOutline(byte[] state) {
        outline = SuppStates.readOutline(state);
        outlineAt = Frames.now();
    }

    /** As SlingshotRendererHelper draws it: the block's shape in lines of the recorder's colour */
    private static void drawOutline(PoseStack pose, MultiBufferSource.BufferSource buffers, Vec3 camera) {
        SuppStates.Outline recorded = outline;
        Minecraft minecraft = Minecraft.getInstance();
        if (recorded == null || !noted(outlineAt) || minecraft.level == null) {
            return;
        }
        BlockPos pos = BlockPos.of(recorded.pos());
        BlockState block = minecraft.level.getBlockState(pos);
        if (block.isAir()) {
            return;
        }
        int colour = recorded.colour();
        float r = FastColor.ARGB32.red(colour) / 255.0F;
        float g = FastColor.ARGB32.green(colour) / 255.0F;
        float b = FastColor.ARGB32.blue(colour) / 255.0F;
        float a = FastColor.ARGB32.alpha(colour) / 255.0F;
        VoxelShape shape = block.getShape(minecraft.level, pos, CollisionContext.empty());
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        double x = pos.getX() - camera.x;
        double y = pos.getY() - camera.y;
        double z = pos.getZ() - camera.z;
        PoseStack.Pose last = pose.last();
        shape.forAllEdges((x1, y1, z1, x2, y2, z2) -> {
            float dx = (float) (x2 - x1);
            float dy = (float) (y2 - y1);
            float dz = (float) (z2 - z1);
            float length = Mth.sqrt(dx * dx + dy * dy + dz * dz);
            dx /= length;
            dy /= length;
            dz /= length;
            lines.addVertex(last.pose(), (float) (x1 + x), (float) (y1 + y), (float) (z1 + z)).setColor(r, g, b, a).setNormal(last, dx, dy, dz);
            lines.addVertex(last.pose(), (float) (x2 + x), (float) (y2 + y), (float) (z2 + z)).setColor(r, g, b, a).setNormal(last, dx, dy, dz);
        });
        buffers.endBatch(RenderType.lines());
    }

    // --- Drawing them ---

    static void draw(RenderLevelStageEvent event) {
        if (!DailiesApi.isPlayback() || event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Vec3 camera = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        if (!failedTrajectory) {
            try {
                drawTrajectory(event.getPoseStack(), buffers, camera, partialTick);
            } catch (Throwable t) {
                // Drawn inside the game's own drawing of the world, where a throw would stop the game: the part is left out
                // for the rest of the session instead, and said once
                failedTrajectory = true;
                LOGGER.warn("Dailies Supplementaries compat: a cannon's trajectory could not be drawn into the world in playback and is left out", t);
            }
        }
        if (!failedOutline) {
            event.getPoseStack().pushPose();
            try {
                drawOutline(event.getPoseStack(), buffers, camera);
            } catch (Throwable t) {
                failedOutline = true;
                LOGGER.warn("Dailies Supplementaries compat: a slingshot's outline could not be drawn into the world in playback and is left out", t);
            } finally {
                event.getPoseStack().popPose();
            }
        }
    }

}
