package comedymonkey.dailies.mixin.packs;

import com.mojang.blaze3d.systems.RenderSystem;
import comedymonkey.dailies.mixin.access.FontManagerAccess;
import net.minecraft.client.gui.GlyphSource;
import net.minecraft.client.gui.font.FontManager;
import net.minecraft.client.gui.font.glyphs.EffectGlyph;
import net.minecraft.network.chat.FontDescription;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The game's font keeps the glyphs it last looked up in one field, read and written without a lock. Text measured on
 * another thread (the download notice, a mod's search worker) while a resource reload swaps the fonts could leave the
 * old font there after the reload had closed it, or the stand-in for missing fonts, which the reload's own clearing
 * runs before; every character drawn after that would come out wrong, and a new one would crash the game. Dailies
 * reloads resources whenever a recording pushes or pops a server pack, so it matters here: lookups from any thread
 * but the render thread (which also applies the reload) neither read nor fill the shared field.
 */
@Mixin(targets = "net.minecraft.client.gui.font.FontManager$CachedFontProvider")
public abstract class FontCacheMixin {

    @Shadow
    @Final
    FontManager this$0;

    @Shadow
    protected abstract GlyphSource getGlyphSource(FontDescription description);

    @Inject(method = "glyphs", at = @At("HEAD"), cancellable = true)
    private void dailies$uncachedOffRenderThread(FontDescription description, CallbackInfoReturnable<GlyphSource> cir) {
        if (!RenderSystem.isOnRenderThread()) {
            cir.setReturnValue(this.getGlyphSource(description));
        }
    }

    @Inject(method = "effect", at = @At("HEAD"), cancellable = true)
    private void dailies$uncachedEffectOffRenderThread(CallbackInfoReturnable<EffectGlyph> cir) {
        if (!RenderSystem.isOnRenderThread()) {
            cir.setReturnValue(((FontManagerAccess) this.this$0).dailies$fontSetRaw(FontDescription.DEFAULT.id()).whiteGlyph());
        }
    }
}
