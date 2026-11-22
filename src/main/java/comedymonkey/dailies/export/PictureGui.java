package comedymonkey.dailies.export;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

/**
 * Draws interface things (text, plates) straight into the game's picture, outside the game's own interface pass and
 * after it: captions burned into an exported frame, the export's progress panel. What is painted is measured in pixels
 * of the picture, whatever the interface scale. It has an interface renderer of its own, so the game's own interface
 * for the frame is left as it was. Render thread
 */
public final class PictureGui {

    private static @Nullable GuiRenderState state = null;
    private static @Nullable GuiRenderer renderer = null;
    /** The picture being drawn into instead of the game's, while {@link #drawInto} runs; render thread */
    private static @Nullable RenderTarget into = null;

    private PictureGui() {
    }

    /** The picture {@link #drawInto} is drawing into just now, or null (for the mixin that points the renderer at it) */
    public static @Nullable RenderTarget drawingInto() {
        return into;
    }

    /**
     * Paints into a picture of Dailies' own, one unit a pixel of it, whatever size the game's picture is: for what is
     * shown in the window in its own pixels while an export draws the game at the video's size
     */
    public static void drawInto(RenderTarget target, Consumer<GuiGraphicsExtractor> painter) {
        Minecraft minecraft = Minecraft.getInstance();
        renderer(minecraft);
        GuiGraphicsExtractor graphics = new GuiGraphicsExtractor(minecraft, state, Integer.MIN_VALUE / 2, Integer.MIN_VALUE / 2);
        painter.accept(graphics);
        if (target.getDepthTexture() != null) {
            RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(target.getDepthTexture(), 0.0);
        }
        into = target;
        try {
            renderer.render();
        } finally {
            into = null;
        }
        renderer.endFrame();
    }

    private static void renderer(Minecraft minecraft) {
        if (renderer == null) {
            state = new GuiRenderState();
            renderer = new GuiRenderer(state, minecraft.gameRenderer.featureRenderDispatcher(), List.of());
        }
    }

    /** Paints into the game's picture now, on top of everything drawn so far this frame */
    public static void draw(Consumer<GuiGraphicsExtractor> painter) {
        Minecraft minecraft = Minecraft.getInstance();
        renderer(minecraft);
        GuiGraphicsExtractor graphics = new GuiGraphicsExtractor(minecraft, state, Integer.MIN_VALUE / 2, Integer.MIN_VALUE / 2);
        int guiScale = Math.max(1, minecraft.gameRenderer.gameRenderState().windowRenderState.guiScale);
        graphics.pose().pushMatrix();
        // One unit a pixel of the picture: the interface renderer draws in units of the interface scale
        graphics.pose().scale(1.0F / guiScale, 1.0F / guiScale);
        try {
            painter.accept(graphics);
        } finally {
            graphics.pose().popMatrix();
        }
        RenderTarget main = minecraft.gameRenderer.mainRenderTarget();
        if (main.getDepthTexture() != null) {
            // On top of whatever the frame drew, as the game's own interface is
            RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(main.getDepthTexture(), 0.0);
        }
        renderer.render();
        renderer.endFrame();
    }

}
