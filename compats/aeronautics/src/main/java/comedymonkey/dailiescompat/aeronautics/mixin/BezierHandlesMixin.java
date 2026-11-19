package comedymonkey.dailiescompat.aeronautics.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import comedymonkey.dailiescompat.aeronautics.client.HandleMarks;
import comedymonkey.dailiescompat.aeronautics.state.AeroStates;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Simulated Coasters works out a track's handles each frame from its own drag and hover state, then draws them in five
 * lists, one for each kind of handle; the lists are noted as they are drawn, for the compat to record what was drawn.
 * Skipped without complaint when Simulated Coasters is absent or has changed.
 */
@Pseudo
@Mixin(targets = "dev.silvergold.simulatedcoasters.client.track.BezierHandleOverlay", remap = false)
public abstract class BezierHandlesMixin {

    @Inject(method = "flushArmBillboardsByTexture", at = @At("HEAD"), remap = false, require = 0)
    private static void dailiesAeronautics$noteArms(PoseStack ms, MultiBufferSource buffer, Vec3 camera, Matrix4f rotation, List<?> pending, CallbackInfo ci) {
        HandleMarks.drawn(AeroStates.ARM, pending);
    }

    @Inject(method = "flushTangentBillboardsByTexture", at = @At("HEAD"), remap = false, require = 0)
    private static void dailiesAeronautics$noteTangents(PoseStack ms, MultiBufferSource buffer, Vec3 camera, Matrix4f rotation, List<?> pending, CallbackInfo ci) {
        HandleMarks.drawn(AeroStates.TANGENT, pending);
    }

    @Inject(method = "flushOpenEndpointBillboardsByTexture", at = @At("HEAD"), remap = false, require = 0)
    private static void dailiesAeronautics$noteOpenEnds(PoseStack ms, MultiBufferSource buffer, Vec3 camera, Matrix4f rotation, List<?> pending, CallbackInfo ci) {
        HandleMarks.drawn(AeroStates.OPEN_END, pending);
    }

    @Inject(method = "flushLiftBillboardsByTexture", at = @At("HEAD"), remap = false, require = 0)
    private static void dailiesAeronautics$noteLifts(PoseStack ms, MultiBufferSource buffer, Vec3 camera, Matrix4f rotation, List<?> pending, CallbackInfo ci) {
        HandleMarks.drawn(AeroStates.LIFT, pending);
    }

    @Inject(method = "flushTiltHandlesByTexture", at = @At("HEAD"), remap = false, require = 0)
    private static void dailiesAeronautics$noteTilts(PoseStack ms, MultiBufferSource buffer, Vec3 camera, Matrix4f rotation, List<?> pending, CallbackInfo ci) {
        HandleMarks.drawn(AeroStates.TILT, pending);
    }

}
