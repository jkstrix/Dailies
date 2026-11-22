package comedymonkey.dailies.mixin.export;

import com.mojang.blaze3d.pipeline.RenderTarget;
import comedymonkey.dailies.export.PictureGui;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Lets Dailies' own interface renderer draw into a picture of its own, at that picture's size in pixels, instead of
 * into the game's picture at the game's interface size: the export progress panel is drawn so, in the window's own
 * pixels whatever size the video is. Only while {@link PictureGui} asks for it; every other drawing is untouched.
 */
@Mixin(GuiRenderer.class)
public abstract class GuiRendererTargetMixin {

    @Redirect(method = "draw", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;mainRenderTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;"))
    private RenderTarget dailies$target(GameRenderer gameRenderer) {
        RenderTarget into = PictureGui.drawingInto();
        return into != null ? into : gameRenderer.mainRenderTarget();
    }

    @ModifyArgs(method = "draw", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/Projection;setupOrtho(FFFFZ)V"))
    private void dailies$size(Args args) {
        RenderTarget into = PictureGui.drawingInto();
        if (into != null) {
            // One unit a pixel of that picture
            args.set(2, (float) into.width);
            args.set(3, (float) into.height);
        }
    }
}
