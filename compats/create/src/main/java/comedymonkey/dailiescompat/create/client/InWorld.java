package comedymonkey.dailiescompat.create.client;

import com.mojang.blaze3d.vertex.PoseStack;
import comedymonkey.dailies.api.DailiesApi;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.render.DefaultSuperRenderTypeBuffer;
import net.createmod.catnip.render.SuperRenderTypeBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * The compat's drawing into the world in playback, after the particles as Catnip draws its outlines, for the overlays
 * whose part shows in the world rather than over it: the outlines ({@link Outlines}) and a held schematic's box
 * ({@link SchematicParts}). Each overlay's drawing over the world notes the frame it was drawn in; its part in the world
 * is drawn only while that note is fresh and Dailies still shows overlays, so it follows the owner's rule like every
 * other overlay. The value boxes keep a drawing of their own ({@link ValueBoxes#drawWorld}).
 */
final class InWorld {

    /** Counts the world's drawings */
    private static long frames = 0;

    private InWorld() {
    }

    static long frame() {
        return frames;
    }

    /** Whether a part noted in this drawing of the world is to be drawn into it */
    static boolean noted(long notedAt) {
        return notedAt >= frames - 3 && DailiesApi.isPlayback() && ValueBoxes.overlaysShown();
    }

    static void draw(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        frames++;
        if (!DailiesApi.isPlayback()) {
            return;
        }
        PoseStack ms = event.getPoseStack();
        ms.pushPose();
        try {
            SuperRenderTypeBuffer buffer = DefaultSuperRenderTypeBuffer.getInstance();
            Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            float partialTicks = AnimationTickHolder.getPartialTicks();
            Outlines.drawWorld(ms, buffer, camera, partialTicks);
            SchematicParts.drawWorld(ms, buffer, camera, partialTicks);
            buffer.draw();
        } finally {
            ms.popPose();
        }
    }

}
