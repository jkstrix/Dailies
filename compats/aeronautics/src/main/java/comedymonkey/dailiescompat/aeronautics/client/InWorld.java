package comedymonkey.dailiescompat.aeronautics.client;

import com.mojang.logging.LogUtils;
import comedymonkey.dailies.api.DailiesApi;
import comedymonkey.dailies.api.compat.DailiesCompats;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.slf4j.Logger;

/**
 * The compat's drawing into the world in playback, for the overlays whose part shows in the world rather than over it:
 * the physics staff's locks ({@link StaffLocks}), after the see-through blocks as Simulated draws them, and a coaster
 * track's handles ({@link BezierHandles}), once the world is drawn as Simulated Coasters draws them. Each overlay's
 * drawing over the world notes the frame it was drawn in; its part in the world is drawn only while that note is fresh and
 * Dailies still shows overlays, so it follows the owner's rule like every other overlay.
 */
final class InWorld {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean noOverlaysShown = false;
    private static boolean failedLocks = false;
    private static boolean failedHandles = false;

    private InWorld() {
    }

    static long frame() {
        return Frames.now();
    }

    static void frameStarts(RenderFrameEvent.Pre event) {
        Frames.next();
    }

    /** Whether a part noted this recently is to be drawn into the world */
    static boolean noted(long notedAt) {
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

    static void draw(RenderLevelStageEvent event, boolean simulated, boolean coasters) {
        if (!DailiesApi.isPlayback()) {
            return;
        }
        RenderLevelStageEvent.Stage stage = event.getStage();
        boolean locks = simulated && stage == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS;
        boolean handles = coasters && stage == RenderLevelStageEvent.Stage.AFTER_LEVEL;
        if (!locks && !handles) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Vec3 camera = event.getCamera().getPosition();
        event.getPoseStack().pushPose();
        try {
            if (locks && !failedLocks) {
                StaffLocks.drawWorld(event.getPoseStack(), minecraft.renderBuffers().bufferSource(), camera);
            }
            if (handles && !failedHandles) {
                BezierHandles.drawWorld(event.getPoseStack(), minecraft.renderBuffers().bufferSource(), camera, event.getModelViewMatrix());
            }
        } catch (Throwable t) {
            // Drawn inside the game's own drawing of the world, where a throw would stop the game: the part is left out
            // for the rest of the session instead, and said once
            if (locks) {
                failedLocks = true;
            }
            if (handles) {
                failedHandles = true;
            }
            LOGGER.warn("Dailies Create Aeronautics compat: {} could not be drawn into the world in playback and is left out", locks ? "the physics staff's locks"
                : "a coaster track's handles", t);
        } finally {
            event.getPoseStack().popPose();
        }
    }

}
