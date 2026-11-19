package comedymonkey.dailiescompat.extras.mixin;

import comedymonkey.dailiescompat.extras.client.ExtrasMarks;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Nature's Compass draws its lines over play one string at a time, each from its left end or its right end; each is noted as it
 * is drawn, for the compat to record what was drawn while the compass's layer was drawn. Skipped without complaint when
 * Nature's Compass is absent or has changed.
 */
@Pseudo
@Mixin(targets = "com.chaosthedude.naturescompass.util.RenderUtils", remap = false)
public abstract class NaturesCompassLinesMixin {

    @Inject(method = "drawStringLeft", at = @At("HEAD"), remap = false, require = 0)
    private static void dailiesExtras$noteLeft(GuiGraphics graphics, String text, Font font, int x, int y, int colour, CallbackInfo ci) {
        ExtrasMarks.compassLine(ExtrasMarks.NATURES, text, x, y, colour, false);
    }

    @Inject(method = "drawStringRight", at = @At("HEAD"), remap = false, require = 0)
    private static void dailiesExtras$noteRight(GuiGraphics graphics, String text, Font font, int x, int y, int colour, CallbackInfo ci) {
        ExtrasMarks.compassLine(ExtrasMarks.NATURES, text, x, y, colour, true);
    }

}
