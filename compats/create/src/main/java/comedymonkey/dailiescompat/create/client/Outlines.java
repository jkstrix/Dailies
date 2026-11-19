package comedymonkey.dailiescompat.create.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllSpecialTextures;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBox;
import comedymonkey.dailies.api.DailiesApi;
import comedymonkey.dailiescompat.create.state.States;
import net.createmod.catnip.outliner.AABBOutline;
import net.createmod.catnip.outliner.BlockClusterOutline;
import net.createmod.catnip.outliner.ItemOutline;
import net.createmod.catnip.outliner.LineOutline;
import net.createmod.catnip.outliner.Outline;
import net.createmod.catnip.outliner.Outliner;
import net.createmod.catnip.render.BindableTexture;
import net.createmod.catnip.render.SuperRenderTypeBuffer;
import net.createmod.catnip.theme.Color;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The outlines Create draws into the world through Catnip's outliner, other than the value boxes ({@link ValueBoxes}):
 * the box a super glue selection is dragged out as and the glued areas shown while glue is held, the area the schematic
 * and quill selects, a block a linked controller is binding to, the targets of a mechanical arm, a weighted ejector or a
 * package port being set, a display link's source, the chain conveyor and track previews, a zapper's or the
 * worldshaper's reach, and the rest. What is recorded is what the recorder's outliner held at the end of each tick:
 * each outline's shape, colour, width, textures and how faded it was.
 * <p>
 * As with the value boxes, the overlay's drawing over the world only notes the outlines, so they follow the owner's
 * rule; the compat's own drawing into the world after the particles then draws them with Catnip's own outline classes,
 * while Dailies says overlays are shown. In playback the outlines Create works out from the playback world and its
 * camera are taken out of the outliner each tick, so only the recorder's are drawn.
 */
final class Outlines {

    static final String NAME = "outlines";

    private static @Nullable List<Outline> shown = null;
    private static byte @Nullable [] shownFrom = null;
    private static long drawnAt = Long.MIN_VALUE;

    private Outlines() {
    }

    // --- Recording ---

    static byte @Nullable [] capture() {
        if (Minecraft.getInstance().level == null) {
            return null;
        }
        List<States.Outline> outlines = new ArrayList<>();
        for (Map.Entry<Object, Outliner.OutlineEntry> entry : new ArrayList<>(Outliner.getInstance().getOutlines().entrySet())) {
            Outliner.OutlineEntry held = entry.getValue();
            if (held.getOutline() instanceof ValueBox) {
                continue;
            }
            // As Outliner.renderOutlines fades one Create has stopped showing
            float alpha = 1.0F;
            if (held.isFading()) {
                float left = 1.0F + held.getTicksTillRemoval() / (float) Outliner.OutlineEntry.FADE_TICKS;
                alpha = left * left * left;
                if (alpha < 1 / 8.0F) {
                    continue;
                }
            }
            States.Outline recorded = outline(held.getOutline(), alpha);
            if (recorded != null) {
                outlines.add(recorded);
            }
        }
        if (outlines.isEmpty()) {
            return null;
        }
        // The outliner keeps no order of its own: sorted, the same outlines write the same bytes
        outlines.sort(Comparator.comparingInt(States.Outline::kind).thenComparing((a, b) -> Arrays.compare(a.coords(), b.coords()))
            .thenComparing((a, b) -> Arrays.compare(a.blocks(), b.blocks())).thenComparingInt(States.Outline::colour));
        return States.outlines(outlines);
    }

    private static States.@Nullable Outline outline(Outline outline, float alpha) {
        int kind;
        double[] coords = new double[0];
        long[] blocks = new long[0];
        String item = "";
        if (outline instanceof AABBOutline box) {
            AABB bb = box.getBounds();
            kind = States.BOX;
            coords = new double[]{bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ};
        } else if (outline instanceof BlockClusterOutline) {
            long[] kept = CreateMarks.cluster(outline);
            if (kept == null) {
                return null;
            }
            kind = States.CLUSTER;
            blocks = kept;
        } else if (outline instanceof LineOutline.EndChasingLineOutline chased) {
            Vector3d start = Reflect.get(chased, "start");
            Vector3d end = Reflect.get(chased, "end");
            float progress = Reflect.get(chased, "progress");
            boolean lockStart = Reflect.get(chased, "lockStart");
            kind = States.CHASED_LINE;
            coords = new double[]{start.x, start.y, start.z, end.x, end.y, end.z, progress, lockStart ? 1 : 0};
        } else if (outline instanceof LineOutline line) {
            Vector3d start = Reflect.get(line, "start");
            Vector3d end = Reflect.get(line, "end");
            kind = States.LINE;
            coords = new double[]{start.x, start.y, start.z, end.x, end.y, end.z};
        } else if (outline instanceof ItemOutline floating) {
            Vec3 pos = Reflect.get(floating, "pos");
            kind = States.FLOATING_ITEM;
            coords = new double[]{pos.x, pos.y, pos.z};
            item = Texts.item(Reflect.get(floating, "stack"));
        } else {
            return null;
        }
        Outline.OutlineParams params = outline.getParams();
        Color rgb = Reflect.get(params, "rgb");
        float lineWidth = Reflect.get(params, "lineWidth");
        boolean fadeLineWidth = Reflect.get(params, "fadeLineWidth");
        boolean disableCull = Reflect.get(params, "disableCull");
        boolean disableNormals = Reflect.get(params, "disableLineNormals");
        Direction face = params.getHighlightedFace();
        return new States.Outline(kind, coords, blocks, item, rgb == null ? -1 : rgb.getRGB(), lineWidth, fadeLineWidth, disableCull, disableNormals,
            Reflect.getInt(params, "lightmap"), textureName(Reflect.get(params, "faceTexture")), textureName(Reflect.get(params, "highlightedFaceTexture")),
            face == null ? -1 : face.ordinal(), alpha);
    }

    private static String textureName(@Nullable BindableTexture texture) {
        return texture instanceof Enum<?> named ? named.name() : "";
    }

    private static @Nullable BindableTexture texture(String name) {
        for (AllSpecialTextures texture : AllSpecialTextures.values()) {
            if (texture.name().equals(name)) {
                return texture;
            }
        }
        return null;
    }

    // --- Playback ---

    /** The overlay's drawing over the world: notes the outlines for the world's next drawing, which is where they show */
    static void draw(byte[] state) {
        if (shownFrom == null || !Arrays.equals(shownFrom, state)) {
            shown = build(States.readOutlines(state));
            shownFrom = state;
        }
        drawnAt = InWorld.frame();
    }

    private static List<Outline> build(List<States.Outline> recorded) {
        List<Outline> outlines = new ArrayList<>(recorded.size());
        for (States.Outline each : recorded) {
            Outline built = switch (each.kind()) {
                case States.BOX -> new AABBOutline(new AABB(each.coord(0), each.coord(1), each.coord(2), each.coord(3), each.coord(4), each.coord(5)));
                case States.CLUSTER -> new BlockClusterOutline(Arrays.stream(each.blocks()).mapToObj(BlockPos::of).toList());
                case States.LINE -> new LineOutline().set(new Vec3(each.coord(0), each.coord(1), each.coord(2)), new Vec3(each.coord(3), each.coord(4), each.coord(5)));
                case States.CHASED_LINE -> {
                    LineOutline.EndChasingLineOutline chased = new LineOutline.EndChasingLineOutline(each.coord(7) == 1);
                    // Twice, so it is drawn as far as recorded rather than catching up from before
                    chased.setProgress((float) each.coord(6)).setProgress((float) each.coord(6));
                    chased.set(new Vec3(each.coord(0), each.coord(1), each.coord(2)), new Vec3(each.coord(3), each.coord(4), each.coord(5)));
                    yield chased;
                }
                case States.FLOATING_ITEM -> {
                    ItemStack stack = Texts.stack(each.item());
                    yield stack.isEmpty() ? null : new ItemOutline(new Vec3(each.coord(0), each.coord(1), each.coord(2)), stack);
                }
                default -> null;
            };
            if (built == null) {
                continue;
            }
            Outline.OutlineParams params = built.getParams();
            params.colored(new Color(each.colour(), true)).lineWidth(each.lineWidth()).lightmap(each.lightmap())
                .withFaceTextures(texture(each.faceTexture()), texture(each.highlightTexture()));
            Direction[] faces = Direction.values();
            if (each.highlightedFace() >= 0 && each.highlightedFace() < faces.length) {
                params.highlightFace(faces[each.highlightedFace()]);
            }
            if (each.disableCull()) {
                params.disableCull();
            }
            if (each.disableNormals()) {
                params.disableLineNormals();
            }
            Reflect.setBoolean(params, "fadeLineWidth", each.fadeLineWidth());
            Reflect.set(params, "alpha", each.alpha());
            outlines.add(built);
        }
        return outlines;
    }

    /** Draws the outlines last noted, into the world after the particles, while the overlay is drawn */
    static void drawWorld(PoseStack ms, SuperRenderTypeBuffer buffer, Vec3 camera, float partialTicks) {
        List<Outline> outlines = shown;
        if (outlines == null || !InWorld.noted(drawnAt)) {
            return;
        }
        for (Outline outline : outlines) {
            outline.render(ms, buffer, camera, partialTicks);
        }
    }

    /**
     * After Create's own work each tick in playback, the outlines it put in the outliner from the playback world are taken
     * out again (the value boxes go in {@link ValueBoxes#afterTick}): the recorder's are drawn from the recording instead
     */
    static void afterTick(ClientTickEvent.Post event) {
        if (!DailiesApi.isPlayback()) {
            return;
        }
        Outliner outliner = Outliner.getInstance();
        for (Map.Entry<Object, Outliner.OutlineEntry> entry : new ArrayList<>(outliner.getOutlines().entrySet())) {
            if (!(entry.getValue().getOutline() instanceof ValueBox)) {
                outliner.remove(entry.getKey());
            }
        }
    }

}
