package comedymonkey.dailies.mixin.effects;

import comedymonkey.dailies.effects.RecorderEyes;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The warp of the portal and of nausea, and nausea's own overlay, as the game draws them from its own player, drawn in
 * playback from the one whose eyes the view is through ({@link RecorderEyes}), and none where nobody's should show. The
 * game reads the warp's intensity from its player's spinning effect, whether nausea is what draws it, and its own clock
 * for the spin; each is answered from the ridden player. A totem going off is drawn only through someone's eyes.
 */
@Mixin(GameRenderer.class)
public abstract class WarpMixin {

    @Shadow
    private ItemStack itemActivationItem;

    @Shadow
    private int itemActivationTicks;

    @Shadow
    private int confusionAnimationTick;

    // Each frame starts with nothing of the world's screen effects shown, and the warp of the one whose eyes the view is through
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void dailies$frameStarts(DeltaTracker deltaTracker, CallbackInfo ci) {
        try {
            RecorderEyes.beginFrame(deltaTracker.getGameTimeDeltaPartialTick(true));
        } catch (RuntimeException | LinkageError e) {
            comedymonkey.dailies.effects.RecorderOverlays.failOnce("the warp", e);
        }
    }

    @Redirect(method = {"render", "renderLevel"}, at = @At(value = "FIELD", target = "Lnet/minecraft/client/player/LocalPlayer;oSpinningEffectIntensity:F",
            opcode = Opcodes.GETFIELD))
    private float dailies$theirSpinBefore(LocalPlayer player) {
        if (!RecorderEyes.inPlayback()) {
            return player.oSpinningEffectIntensity;
        }
        return RecorderEyes.subject() != null ? RecorderEyes.spinBefore() : 0.0F;
    }

    @Redirect(method = {"render", "renderLevel"}, at = @At(value = "FIELD", target = "Lnet/minecraft/client/player/LocalPlayer;spinningEffectIntensity:F",
            opcode = Opcodes.GETFIELD))
    private float dailies$theirSpin(LocalPlayer player) {
        if (!RecorderEyes.inPlayback()) {
            return player.spinningEffectIntensity;
        }
        return RecorderEyes.subject() != null ? RecorderEyes.spinNow() : 0.0F;
    }

    // Whether nausea is what draws the warp (slower, and with nausea's overlay rather than the portal's)
    @Redirect(method = {"render", "renderLevel"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;hasEffect(Lnet/minecraft/core/Holder;)Z"))
    private boolean dailies$theirNausea(LocalPlayer player, Holder<MobEffect> effect) {
        if (!RecorderEyes.inPlayback()) {
            return player.hasEffect(effect);
        }
        LivingEntity subject = RecorderEyes.subject();
        return subject != null && subject.hasEffect(effect);
    }

    // The spin's clock moves on only as the ridden player is ticked, so a paused recording does not spin on
    @Redirect(method = "renderLevel", at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/GameRenderer;confusionAnimationTick:I",
            opcode = Opcodes.GETFIELD))
    private int dailies$theirSpinClock(GameRenderer renderer) {
        if (!RecorderEyes.inPlayback()) {
            return this.confusionAnimationTick;
        }
        return RecorderEyes.spinTicks();
    }

    // A totem going off, shown only through someone's eyes
    @Inject(method = "renderItemActivationAnimation", at = @At("HEAD"), cancellable = true)
    private void dailies$theirTotem(GuiGraphics graphics, float partialTicks, CallbackInfo ci) {
        if (!RecorderEyes.inPlayback()) {
            return;
        }
        if (RecorderEyes.subject() == null) {
            ci.cancel();
        } else if (this.itemActivationItem != null && this.itemActivationTicks > 0) {
            RecorderEyes.shownTotem();
        }
    }
}
