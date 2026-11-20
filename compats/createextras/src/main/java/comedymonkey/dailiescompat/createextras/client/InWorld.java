package comedymonkey.dailiescompat.createextras.client;

import com.mojang.logging.LogUtils;
import comedymonkey.dailies.api.DailiesApi;
import comedymonkey.dailies.api.compat.DailiesCompats;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.slf4j.Logger;

/**
 * The compat's part in the drawing of the world in playback, for the overlays that show in the world rather than over it:
 * Create Deep Seas' console as the recorder worked it ({@link DeepSeasParts#putConsole}), put in for the console's own
 * drawing once the sky is drawn and taken out again once the world is, and Create: Hypertubes' placement outline
 * ({@link HypertubeParts#drawOutline}), after the particles as Hypertubes draws it. Each overlay's drawing over the world
 * notes the frame it was drawn in; its part in the world is drawn only while that note is fresh and Dailies still shows
 * overlays, so it follows the owner's rule like every other overlay.
 */
final class InWorld {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean noOverlaysShown = false;
    private static boolean failedConsole = false;
    private static boolean failedOutline = false;

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

    static void draw(RenderLevelStageEvent event, boolean deepSeas, boolean hypertube) {
        if (!DailiesApi.isPlayback()) {
            return;
        }
        RenderLevelStageEvent.Stage stage = event.getStage();
        try {
            if (deepSeas && !failedConsole && stage == RenderLevelStageEvent.Stage.AFTER_SKY) {
                DeepSeasParts.putConsole();
            } else if (deepSeas && !failedConsole && stage == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
                DeepSeasParts.takeConsoleBack();
            }
        } catch (Throwable t) {
            // Inside the game's own drawing of the world, where a throw would stop the game: left out for the session instead
            failedConsole = true;
            LOGGER.warn("Dailies Create add-ons compat: Create Deep Seas' console could not be drawn as recorded in playback and is left out", t);
        }
        if (hypertube && !failedOutline && stage == RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            event.getPoseStack().pushPose();
            try {
                HypertubeParts.drawOutline(event.getPoseStack(), Minecraft.getInstance().renderBuffers().bufferSource(), event.getCamera().getPosition());
            } catch (Throwable t) {
                failedOutline = true;
                LOGGER.warn("Dailies Create add-ons compat: Create: Hypertubes' placement outline could not be drawn into the world in playback and is left out", t);
            } finally {
                event.getPoseStack().popPose();
            }
        }
    }

}
