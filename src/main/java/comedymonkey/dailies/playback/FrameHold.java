package comedymonkey.dailies.playback;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import comedymonkey.dailies.Host;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL30;

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
            if (held == null) {
                held = new TextureTarget(main.width, main.height, false, Minecraft.ON_OSX);
            } else if (held.width != main.width || held.height != main.height) {
                held.resize(main.width, main.height, Minecraft.ON_OSX);
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

    private static void copy(RenderTarget from, RenderTarget to) {
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, from.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, to.frameBufferId);
        GL30.glBlitFramebuffer(0, 0, from.width, from.height, 0, 0, to.width, to.height, GL30.GL_COLOR_BUFFER_BIT, GL30.GL_NEAREST);
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        // The game draws the panels and the HUD into the window's picture next, and expects it to be the one bound
        Minecraft.getInstance().gameRenderer.mainRenderTarget().bindWrite(false);
    }

}
