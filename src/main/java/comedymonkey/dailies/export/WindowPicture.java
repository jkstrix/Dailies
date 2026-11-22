package comedymonkey.dailies.export;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.RenderPipelines;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

/**
 * Puts a picture in the window scaled to fit and centred on a plain colour, in the window's own pixels whatever size
 * the game draws at. The game itself hands its picture to the window pixel for pixel from a corner, which while an
 * export draws at its own size would show a corner of a huge picture or a postage stamp of a small one. Render thread
 */
public final class WindowPicture {

    private static @Nullable GpuTexture texture = null;
    private static @Nullable GpuTextureView view = null;

    private WindowPicture() {
    }

    /** The window's size in pixels, as its surface is set up; null when it is not */
    public static int @Nullable [] size(GpuSurface surface) {
        return surface.currentConfiguration().map(config -> new int[]{config.width(), config.height()}).orElse(null);
    }

    /** Where a picture goes in a window when scaled to fit and centred: left, top, width and height, in pixels */
    public static int[] fitted(int pictureWidth, int pictureHeight, int windowWidth, int windowHeight) {
        double scale = Math.min((double) windowWidth / Math.max(1, pictureWidth), (double) windowHeight / Math.max(1, pictureHeight));
        int width = Math.max(1, Math.min(windowWidth, (int) Math.round(pictureWidth * scale)));
        int height = Math.max(1, Math.min(windowHeight, (int) Math.round(pictureHeight * scale)));
        return new int[]{(windowWidth - width) / 2, (windowHeight - height) / 2, width, height};
    }

    /** Hands the picture to the window scaled to fit, the rest of the window in the colour given (0xRRGGBB) */
    public static void show(GpuSurface surface, CommandEncoder encoder, GpuTextureView picture, int background) {
        int[] window = size(surface);
        if (window == null || window[0] <= 0 || window[1] <= 0) {
            surface.blitFromTexture(encoder, picture);
            return;
        }
        GpuTextureView into = target(window[0], window[1]);
        int[] at = fitted(picture.getWidth(0), picture.getHeight(0), window[0], window[1]);
        Vector4f clear = new Vector4f((background >> 16 & 0xFF) / 255.0F, (background >> 8 & 0xFF) / 255.0F, (background & 0xFF) / 255.0F, 1.0F);
        try (RenderPass pass = encoder.createRenderPass(() -> "Dailies picture in the window", into, Optional.of(clear))) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.TRACY_BLIT));
            RenderSystem.bindDefaultUniforms(pass);
            pass.setViewport(at[0], at[1], at[2], at[3]);
            pass.setUniform("InSampler", picture, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.draw(3, 1, 0, 0);
        }
        surface.blitFromTexture(encoder, into);
    }

    /** A picture the size of the window to draw into, made again when the window's size changes */
    private static GpuTextureView target(int width, int height) {
        if (texture == null || view == null || texture.getWidth(0) != width || texture.getHeight(0) != height) {
            if (view != null) {
                view.close();
            }
            if (texture != null) {
                texture.close();
            }
            GpuDevice device = RenderSystem.getDevice();
            texture = device.createTexture("Dailies window picture", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA8_UNORM, width, height, 1, 1);
            view = device.createTextureView(texture);
        }
        return view;
    }

}
