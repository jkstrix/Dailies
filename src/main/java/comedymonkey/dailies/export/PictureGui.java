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

    private PictureGui() {
    }

    /** Paints into the game's picture now, on top of everything drawn so far this frame */
    public static void draw(Consumer<GuiGraphicsExtractor> painter) {
        Minecraft minecraft = Minecraft.getInstance();
        if (renderer == null) {
            state = new GuiRenderState();
            renderer = new GuiRenderer(state, minecraft.gameRenderer.featureRenderDispatcher(), List.of());
        }
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
