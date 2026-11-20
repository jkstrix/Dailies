package comedymonkey.dailiescompat.create.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.content.equipment.symmetryWand.SymmetryWandItem;
import com.simibubi.create.content.equipment.symmetryWand.mirror.CrossPlaneMirror;
import com.simibubi.create.content.equipment.symmetryWand.mirror.EmptyMirror;
import com.simibubi.create.content.equipment.symmetryWand.mirror.PlaneMirror;
import com.simibubi.create.content.equipment.symmetryWand.mirror.SymmetryMirror;
import com.simibubi.create.content.equipment.symmetryWand.mirror.TriplePlaneMirror;
import comedymonkey.dailiescompat.create.state.States;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.ghostblock.GhostBlockParams;
import net.createmod.catnip.ghostblock.GhostBlockRenderer;
import net.createmod.catnip.ghostblock.GhostBlocks;
import net.createmod.catnip.placement.PlacementClient;
import net.createmod.catnip.render.SuperRenderTypeBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What Create and Catnip draw into the world for what the player holds, besides the outlines ({@link Outlines}): a
 * placement helper's ghost blocks (part of {@link PlayHud#PLACEMENT}) and the mirror a wand of symmetry in the hotbar
 * has placed ({@link #MIRROR}), which Create draws bobbing where it stands. Each overlay's drawing over the world only
 * notes what to draw; {@link InWorld} draws it into the world while that note is fresh, so it follows the same rule as
 * every overlay.
 */
final class WorldMarks {

    static final String MIRROR = "symmetry_mirror";

    private static byte @Nullable [] ghostsFrom = null;
    private static States.@Nullable Placement ghosts = null;
    private static long ghostsAt = Long.MIN_VALUE;
    private static byte @Nullable [] mirrorsFrom = null;
    private static List<SymmetryMirror> mirrors = List.of();
    private static long mirrorsAt = Long.MIN_VALUE;

    private WorldMarks() {
    }

    // --- Ghost blocks ---

    static void noteGhosts(byte[] state, States.Placement placement) {
        if (ghostsFrom == null || !Arrays.equals(ghostsFrom, state)) {
            ghostsFrom = state;
            ghosts = placement;
        }
        ghostsAt = InWorld.frame();
    }

    /** As GhostBlocks.renderAll draws each, with Catnip's fade put back to the recorded one for the moment */
    static void drawGhosts(PoseStack ms, SuperRenderTypeBuffer buffer, Vec3 camera) {
        States.Placement placement = ghosts;
        if (placement == null || placement.ghosts().isEmpty() || !InWorld.noted(ghostsAt)) {
            return;
        }
        int before = Reflect.getStatic(PlacementClient.class, "animationTick");
        try {
            Reflect.setStaticInt(PlacementClient.class, "animationTick", placement.fade());
            for (States.Ghost ghost : placement.ghosts()) {
                BlockState state = EditScreens.blockState(ghost.state());
                if (state == null) {
                    continue;
                }
                GhostBlockParams params = GhostBlockParams.of(state).at(BlockPos.of(ghost.pos()));
                if (ghost.breathing()) {
                    params.breathingAlpha();
                } else {
                    params.alpha(ghost.alpha());
                }
                (ghost.transparent() ? GhostBlockRenderer.transparent() : GhostBlockRenderer.standard()).render(ms, buffer, camera, params);
            }
        } finally {
            Reflect.setStaticInt(PlacementClient.class, "animationTick", before);
        }
    }

    // --- The wand of symmetry's mirror ---

    static byte @Nullable [] captureMirrors() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return null;
        }
        // As SymmetryHandler.onRenderWorld finds them: each enabled wand in the hotbar with a mirror placed
        List<States.Mirror> found = new ArrayList<>();
        for (int i = 0; i < Inventory.getSelectionSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!(stack.getItem() instanceof SymmetryWandItem) || !SymmetryWandItem.isEnabled(stack)) {
                continue;
            }
            SymmetryMirror mirror = SymmetryWandItem.getMirror(stack);
            if (mirror == null || mirror instanceof EmptyMirror) {
                continue;
            }
            int kind = mirror instanceof TriplePlaneMirror ? 2 : mirror instanceof CrossPlaneMirror ? 1 : 0;
            Vec3 at = mirror.getPosition();
            found.add(new States.Mirror(kind, at.x, at.y, at.z, mirror.getOrientationIndex()));
        }
        return found.isEmpty() ? null : States.mirrors(found);
    }

    /** Notes the mirrors for the world's next drawing: nothing of them is over the world */
    static void noteMirrors(GuiGraphics graphics, byte[] state) {
        if (mirrorsFrom == null || !Arrays.equals(mirrorsFrom, state)) {
            mirrorsFrom = state;
            List<SymmetryMirror> built = new ArrayList<>();
            for (States.Mirror recorded : States.readMirrors(state)) {
                Vec3 at = new Vec3(recorded.x(), recorded.y(), recorded.z());
                SymmetryMirror mirror = switch (recorded.kind()) {
                    case 1 -> new CrossPlaneMirror(at);
                    case 2 -> new TriplePlaneMirror(at);
                    default -> new PlaneMirror(at);
                };
                mirror.setOrientation(recorded.orientation());
                built.add(mirror);
            }
            mirrors = built;
        }
        mirrorsAt = InWorld.frame();
    }

    /** As SymmetryHandler.onRenderWorld draws each, bobbing where it stands */
    static void drawMirrors(PoseStack ms, Vec3 view) {
        if (mirrors.isEmpty() || !InWorld.noted(mirrorsAt)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        RandomSource random = RandomSource.create();
        MultiBufferSource.BufferSource buffer = minecraft.renderBuffers().bufferSource();
        for (SymmetryMirror mirror : mirrors) {
            BlockPos pos = BlockPos.containing(mirror.getPosition());
            float yShift = Mth.sin((float) (AnimationTickHolder.getRenderTime() * (1 / 16.0))) / 5.0F;
            ms.pushPose();
            try {
                ms.translate(pos.getX() - view.x(), pos.getY() - view.y(), pos.getZ() - view.z());
                ms.translate(0, yShift + 0.2F, 0);
                mirror.applyModelTransform(ms);
                // Flywheel's PartialModel, which the compat is not compiled against, holds the baked model
                BakedModel model = Reflect.call(Reflect.call(mirror, "getModel"), "get");
                VertexConsumer builder = buffer.getBuffer(RenderType.solid());
                minecraft.getBlockRenderer().getModelRenderer().tesselateBlock(minecraft.level, model, Blocks.AIR.defaultBlockState(), pos, ms, builder, true,
                    random, Mth.getSeed(pos), OverlayTexture.NO_OVERLAY, ModelData.EMPTY, RenderType.solid());
            } finally {
                ms.popPose();
            }
        }
        buffer.endBatch();
    }

}
