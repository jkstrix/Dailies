package comedymonkey.dailies.mixin.renderers;

import comedymonkey.dailies.export.Export;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Shader packs animate water, wind, clouds and much else by Iris's frame timer (the frameTimeCounter and frameTime
 * uniforms), which Iris feeds from the real clock at the start of every frame. An export takes as long over a frame
 * as it needs, so by the real clock its water would race or crawl. While an export holds the clock, the timer is fed
 * the export's own clock instead, exactly one frame's length per exported frame (specification REN-4). The frame
 * counter is left alone: shader packs that build their picture up over several frames count on it going up with every
 * frame drawn, the thrown-away ones of {@code throwawayFrames} included.
 * <p>
 * After an export the timer carries on from where the export left it rather than jumping back to the real clock, so
 * the live picture does not see time run backwards. Skipped without complaint when Iris (or Oculus, which keeps the
 * same timer under its older name) is absent or has changed.
 */
@Pseudo
@Mixin(targets = {"net.irisshaders.iris.uniforms.SystemTimeUniforms$Timer", "net.coderbot.iris.uniforms.SystemTimeUniforms$Timer"}, remap = false)
public abstract class IrisTimerMixin {

    @Unique
    private static long dailies$offset = 0L;
    @Unique
    private static boolean dailies$exporting = false;
    @Unique
    private static long dailies$lastGiven = 0L;
    @Unique
    private static long dailies$lastReal = 0L;

    @ModifyVariable(method = "beginFrame(J)V", at = @At("HEAD"), argsOnly = true, remap = false, require = 0)
    private long dailies$exportClock(long real) {
        if (Export.holdsClock()) {
            dailies$exporting = true;
            dailies$lastGiven = Export.frameClockNanos() + dailies$offset;
            dailies$lastReal = real;
            return dailies$lastGiven;
        }
        if (dailies$exporting) {
            dailies$exporting = false;
            dailies$offset = dailies$lastGiven - dailies$lastReal;
        }
        return real + dailies$offset;
    }

}
