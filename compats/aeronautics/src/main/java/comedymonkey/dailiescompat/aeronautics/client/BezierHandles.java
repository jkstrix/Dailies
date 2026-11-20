package comedymonkey.dailiescompat.aeronautics.client;

import com.mojang.blaze3d.vertex.PoseStack;
import comedymonkey.dailiescompat.aeronautics.state.AeroStates;
import dev.silvergold.simulatedcoasters.client.track.BezierHandleOverlay;
import dev.silvergold.simulatedcoasters.client.track.BezierHandleOverlayRenderTypes;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * The handles Simulated Coasters draws into the world while a track is shaped with the wrench: the arms out to each
 * curve's tangent handles, the handle at an open end, and the joint's lift and tilt handles, each lit as hovered or held.
 * Recorded as Simulated Coasters drew them (noted by the compat's mixin, {@link HandleMarks}): each handle's points in
 * the world, texture, colour and alpha, never worked out again from the playback world, where nobody holds a wrench.
 * Drawn in playback by Simulated Coasters' own drawing, handed the recorded handles, once the world is drawn, as the
 * overlay's part in the world, so it follows the owner's rule like every other overlay. Simulated Coasters' outlines (a
 * track's placement preview, a cart's, a chain's, a rivet's) go through Catnip's outliner and are recorded by the Create
 * compat with Create's.
 */
final class BezierHandles {

    static final String NAME = "track_handles";

    /** Simulated Coasters' drawing of each kind of handle, by the kinds' order in the state */
    private static final String[] FLUSHES = {"flushArmBillboardsByTexture", "flushTangentBillboardsByTexture", "flushOpenEndpointBillboardsByTexture",
        "flushLiftBillboardsByTexture", "flushTiltHandlesByTexture"};
    private static final String[] RECORDS = {"PendingArmBillboard", "PendingTangentBillboard", "PendingOpenEndpointBillboard", "PendingLiftBillboard",
        "PendingTiltBillboard"};

    private static @Nullable List<List<Object>> shown = null;
    private static byte @Nullable [] shownFrom = null;
    private static long drawnAt = Long.MIN_VALUE;

    private BezierHandles() {
    }

    static byte @Nullable [] capture() {
        List<AeroStates.Handle> handles = HandleMarks.recent();
        return handles.isEmpty() ? null : AeroStates.handles(new AeroStates.Handles(handles));
    }

    /** The overlay's drawing over the world: notes the handles for the world's next drawing, which is where they show */
    static void note(byte[] state) {
        if (shownFrom == null || !java.util.Arrays.equals(shownFrom, state)) {
            shown = build(AeroStates.readHandles(state));
            shownFrom = state;
        }
        drawnAt = InWorld.frame();
    }

    /** Simulated Coasters' own records of each kind, made again from the recorded handles */
    private static List<List<Object>> build(AeroStates.Handles recorded) {
        List<List<Object>> lists = new ArrayList<>();
        for (int i = 0; i < RECORDS.length; i++) {
            lists.add(new ArrayList<>());
        }
        for (AeroStates.Handle handle : recorded.handles()) {
            if (handle.kind() < 0 || handle.kind() >= RECORDS.length) {
                continue;
            }
            Object made = record(handle);
            if (made != null) {
                lists.get(handle.kind()).add(made);
            }
        }
        return lists;
    }

    /** One record, its parts filled in their own order: points from the handle's points, the texture, the colour, the flag */
    private static @Nullable Object record(AeroStates.Handle handle) {
        try {
            Class<?> type = Class.forName(BezierHandleOverlay.class.getName() + "$" + RECORDS[handle.kind()]);
            Constructor<?> made = type.getDeclaredConstructors()[0];
            made.setAccessible(true);
            Class<?>[] parts = made.getParameterTypes();
            Object[] values = new Object[parts.length];
            int coord = 0;
            int colour = 0;
            float[] colours = {handle.r(), handle.g(), handle.b(), handle.a()};
            for (int i = 0; i < parts.length; i++) {
                if (parts[i] == Vec3.class) {
                    values[i] = new Vec3(handle.coord(coord), handle.coord(coord + 1), handle.coord(coord + 2));
                    coord += 3;
                } else if (parts[i] == ResourceLocation.class) {
                    values[i] = ResourceLocation.parse(handle.texture());
                } else if (parts[i] == float.class) {
                    values[i] = colour < 4 ? colours[colour++] : 0.0F;
                } else if (parts[i] == boolean.class) {
                    values[i] = handle.flag();
                } else {
                    return null;
                }
            }
            return made.newInstance(values);
        } catch (ReflectiveOperationException | RuntimeException unmade) {
            return null;
        }
    }

    /** Draws the handles last noted, as BezierHandleOverlay.renderWrenchRangeCurves draws its lists, then ends their batches */
    static void drawWorld(PoseStack ms, MultiBufferSource.BufferSource buffers, Vec3 camera, Matrix4f cameraRotation) {
        List<List<Object>> lists = shown;
        if (lists == null || !InWorld.noted(drawnAt)) {
            return;
        }
        for (int kind = 0; kind < FLUSHES.length; kind++) {
            Method flush = Reflect.method(BezierHandleOverlay.class, FLUSHES[kind], PoseStack.class, MultiBufferSource.class, Vec3.class, Matrix4f.class,
                List.class);
            try {
                flush.invoke(null, ms, buffers, camera, cameraRotation, lists.get(kind));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        buffers.endBatch(BezierHandleOverlayRenderTypes.SEE_THROUGH_LINES);
        BezierHandleOverlayRenderTypes.endTangentHandleBillboardBatches(buffers);
    }

}
