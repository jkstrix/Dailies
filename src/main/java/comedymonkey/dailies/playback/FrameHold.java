package comedymonkey.dailies.playback;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.renderpearl.api.textures.GpuTexture;
import comedymonkey.dailies.Host;
import net.minecraft.client.Minecraft;

/**
 * Keeps the world still on screen while a jump is under way, as a video player holds its last frame while it seeks
 * (the owner, 18 November: dragging the playhead should feel like scrubbing a video, never going back and then running
 * forward in front of you). A jump back starts from the stored world before the place asked for and runs the game
 * forward to it, over several frames; drawn as it went, the world was seen to snap back and race forward at every step
 * of a drag. Now the picture of the world as it last stood, whole, is kept at the end of every frame drawn outside a
 * jump, and while a jump runs it is put back in place of whatever the world drew, before the panels and the playhead
 * are drawn over it, so they move on as ever. Not during an export, which draws every frame itself.
 */
public final class FrameHold {

    private static RenderTarget held = null;
    /** Whether {@link #held} holds a whole picture of the world at the window's present size */
    private static boolean whole = false;
    private static boolean failed = false;

    private FrameHold() {
    }

    /** Whether the world drawn this frame is to be covered by the picture kept from before the jump */
    private static boolean holding() {
        return Playback.loaded() && Playback.jumping() && !comedymonkey.dailies.export.Export.active() && !Boolean.getBoolean("dailies.playback.noFrameHold");
    }

    /** At the end of drawing the world, every frame: keep the picture, or put the kept one back */
    public static void worldDrawn() {
        if (failed) {
            return;
        }
        try {
            Minecraft minecraft = Minecraft.getInstance();
            RenderTarget main = minecraft.gameRenderer.mainRenderTarget();
            if (!Playback.loaded() || comedymonkey.dailies.export.Export.active()) {
                whole = false;
                return;
            }
            if (holding()) {
                if (whole && held != null && held.width == main.width && held.height == main.height) {
                    copy(held, main);
                }
                return;
            }
            GpuTexture colour = main.getColorTexture();
            if (colour == null) {
                return;
            }
            if (held == null) {
                // The same kind of picture as the window's, so one can be copied straight into the other
                held = new TextureTarget("Dailies held frame", main.width, main.height, colour.getFormat(), null);
            } else if (held.width != main.width || held.height != main.height) {
                held.resize(main.width, main.height);
            }
            copy(main, held);
            whole = true;
        } catch (Throwable t) {
            failed = true;
            whole = false;
            Host.LOGGER.warn("Dailies: the world could not be held still on screen during jumps; jumps are shown as they run. Said once", t);
        }
    }

    /** When playback closes: the kept picture belongs to the recording that was open */
    static void forget() {
        whole = false;
        if (held != null) {
            held.destroyBuffers();
            held = null;
        }
    }

    /** The colour of one picture into the other, on the graphics card, in order with the drawing around it */
    private static void copy(RenderTarget from, RenderTarget to) {
        to.copyColorFrom(from);
    }

}
