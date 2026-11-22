package comedymonkey.dailies.mixin.packs;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.font.FontManager;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/**
 * Stops a resource reload from leaving the game drawing text with a font the reload has closed.
 * <p>
 * The game remembers the font it last looked up in one field, read and written with no lock, and a reload empties
 * that field just before it closes the old fonts and makes new ones. Text is not only measured on the game's own
 * thread: the game's download notice for a server's pack measures its words on the download thread, and Create's
 * Ponder measures a tooltip line on the worker thread that rebuilds the creative search after every reload. A lookup
 * from such a thread that lands in the middle of the swap finds the field empty, reads the old font out of the table
 * that is about to be cleared, and writes it back into the field after the reload has emptied it. From then on every
 * text is drawn with the closed font; characters it had already drawn still work, and the first new one crashes the
 * game with "Image is not allocated". Recording made that easy to meet, because its timer shows a new digit every
 * second, and playing a recording with server resource packs reloads the game's resources as a matter of course.
 * <p>
 * So a lookup from any thread but the game's own no longer touches that field, or the table the reload is rewriting:
 * it is answered from a copy of the table taken when the last reload finished. While a reload swaps the fonts that copy
 * is emptied, as the old fonts in it are being closed, and such a lookup gets the game's missing font, which no reload
 * closes: text measured off the game's thread in that moment may be a little out, and nothing crashes.
 */
@Mixin(FontManager.class)
public abstract class FontManagerMixin {

    @Shadow
    @Final
    private Map<ResourceLocation, FontSet> fontSets;

    @Shadow
    @Final
    private FontSet missingFontSet;

    @Unique
    private volatile Map<ResourceLocation, FontSet> dailies$current = Map.of();

    @Inject(method = "apply", at = @At("HEAD"))
    private void dailies$applying(CallbackInfo ci) {
        this.dailies$current = Map.of();
    }

    @Inject(method = "apply", at = @At("TAIL"))
    private void dailies$applied(CallbackInfo ci) {
        this.dailies$current = Map.copyOf(this.fontSets);
    }

    @Inject(method = "getFontSetCached", at = @At("HEAD"), cancellable = true)
    private void dailies$fromAnotherThread(ResourceLocation name, CallbackInfoReturnable<FontSet> cir) {
        if (!RenderSystem.isOnRenderThread()) {
            cir.setReturnValue(this.dailies$current.getOrDefault(name, this.missingFontSet));
        }
    }

}
