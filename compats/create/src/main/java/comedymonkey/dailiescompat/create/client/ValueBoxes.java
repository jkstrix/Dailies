package comedymonkey.dailiescompat.create.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBox;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.INamedIconOptions;
import com.simibubi.create.foundation.gui.AllIcons;
import comedymonkey.dailies.api.DailiesApi;
import comedymonkey.dailies.api.compat.DailiesCompats;
import comedymonkey.dailiescompat.create.state.States;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.outliner.Outline;
import net.createmod.catnip.outliner.Outliner;
import net.createmod.catnip.render.DefaultSuperRenderTypeBuffer;
import net.createmod.catnip.render.SuperRenderTypeBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The value boxes Create shows on a block the player looks at: the box on each slot that sets something (a speed, a
 * mode, a filter and its amount), with what it is set to, and an outline round the one aimed at. Create draws them into
 * the world, through Catnip's outliner, not over it. What is recorded is what the recorder's outliner held at the end
 * of each tick: each box's block, where on it Create's slot put the box (as the transform from the block's corner, so
 * the playback world's own block is never asked), what it showed and whether it was outlined.
 * <p>
 * The overlay's drawing over the world only notes the boxes to draw, so they follow the owner's rule like every other
 * overlay; the compat's own drawing into the world, after the particles as Catnip draws its outlines, then draws them
 * with Create's own value box classes, while Dailies says overlays are shown. In playback the boxes Create would work out
 * for itself from the playback world and its camera are taken out of the outliner each tick, so only the recorder's are
 * ever drawn, and never in third person or flying free.
 */
final class ValueBoxes {

    static final String NAME = "value_box";

    private static final String WIDE = "VALUE_BOX_HOVER_6PX";

    /** The boxes the overlay last asked to be drawn, built, and the state they were built from */
    private static @Nullable List<ValueBox> shown = null;
    private static byte @Nullable [] shownFrom = null;
    /** Counts the world's drawings; the overlay notes the count it drew in, so boxes no longer held stop being drawn */
    private static long worldFrames = 0;
    private static long drawnAt = Long.MIN_VALUE;
    private static boolean oldDailies = false;

    private ValueBoxes() {
    }

    // --- Recording ---

    static byte @Nullable [] capture() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return null;
        }
        List<States.Box> boxes = new ArrayList<>();
        for (Map.Entry<Object, Outliner.OutlineEntry> entry : new ArrayList<>(Outliner.getInstance().getOutlines().entrySet())) {
            // A box fading out is one Create has stopped showing
            if (entry.getValue().getOutline() instanceof ValueBox box && entry.getValue().getTicksTillRemoval() >= 0) {
                States.Box recorded = box(box, minecraft.level);
                if (recorded != null) {
                    boxes.add(recorded);
                }
            }
        }
        if (boxes.isEmpty()) {
            return null;
        }
        boxes.sort(Comparator.comparingLong(States.Box::pos).thenComparingInt(States.Box::kind).thenComparing(States.Box::text));
        return States.boxes(boxes);
    }

    /** One box as Create would draw it now, or null when Create would not draw it */
    private static States.@Nullable Box box(ValueBox box, ClientLevel level) {
        BlockPos pos = Reflect.get(box, "pos");
        BlockState state = Reflect.get(box, "blockState");
        ValueBoxTransform transform = Reflect.get(box, "transform");
        WeakReference<LevelAccessor> held = Reflect.get(box, "level");
        LevelAccessor in = held == null || held.get() == null ? level : held.get();
        PoseStack placed = new PoseStack();
        float fontScale = 1 / 64.0F;
        int colour = -1;
        if (transform != null) {
            // As ValueBox.render places it
            Direction face = box.getParams().getHighlightedFace();
            if (transform instanceof ValueBoxTransform.Sided sided && face != null) {
                sided.fromSide(face);
            }
            if (!transform.shouldRender(in, pos, state)) {
                return null;
            }
            transform.transform(in, pos, state, placed);
            fontScale = transform.getFontScale();
            colour = transform.getOverrideColor();
        }
        float[] pose = new float[16];
        placed.last().pose().get(pose);
        int kind = States.PLAIN;
        String text = "";
        String icon = "";
        String item = "";
        String count = null;
        if (box instanceof ValueBox.TextValueBox) {
            kind = States.TEXT;
            text = Texts.json((Component) Reflect.get(box, "text"));
        } else if (box instanceof ValueBox.IconValueBox) {
            kind = States.ICON;
            icon = Texts.icon((AllIcons) Reflect.get(box, "icon"));
        } else if (box instanceof ValueBox.ItemValueBox) {
            kind = States.ITEM;
            item = Texts.item(Reflect.get(box, "stack"));
            MutableComponent amount = Reflect.get(box, "count");
            count = amount == null ? null : Texts.json(amount);
        }
        return new States.Box(pos.asLong(), pose, fontScale, colour, box.isPassive, Texts.icon(box.getOutline()), kind, text, icon, item, count);
    }

    // --- Playback ---

    /** The overlay's drawing over the world: notes the boxes for the world's next drawing, which is where they show */
    static void draw(byte[] state) {
        if (shownFrom == null || !Arrays.equals(shownFrom, state)) {
            shown = build(States.readBoxes(state));
            shownFrom = state;
        }
        drawnAt = worldFrames;
    }

    private static List<ValueBox> build(List<States.Box> recorded) {
        List<ValueBox> boxes = new ArrayList<>(recorded.size());
        Minecraft minecraft = Minecraft.getInstance();
        for (States.Box box : recorded) {
            BlockPos pos = BlockPos.of(box.pos());
            BlockState state = minecraft.level.getBlockState(pos);
            AABB none = new AABB(Vec3.ZERO, Vec3.ZERO);
            Component label = Component.empty();
            ValueBox built = switch (box.kind()) {
                case States.TEXT -> new ValueBox.TextValueBox(label, none, pos, state, Texts.read(box.text()));
                case States.ICON -> {
                    AllIcons icon = Texts.icon(box.icon());
                    yield new ValueBox.IconValueBox(label, new INamedIconOptions() {
                        @Override
                        public AllIcons getIcon() {
                            return icon != null ? icon : AllIcons.I_CONFIRM;
                        }

                        @Override
                        public String getTranslationKey() {
                            return "";
                        }
                    }, none, pos);
                }
                case States.ITEM -> new ValueBox.ItemValueBox(label, none, pos, Texts.stack(box.item()), box.count() == null ? null : Texts.read(box.count()));
                default -> new ValueBox(label, none, pos, state);
            };
            Matrix4f pose = new Matrix4f().set(box.pose());
            built.transform(new Placed(pose, box.fontScale(), box.overrideColor())).passive(box.passive());
            if (WIDE.equals(box.outline())) {
                built.wideOutline();
            }
            boxes.add(built);
        }
        return boxes;
    }

    /** The compat's drawing into the world, after the particles, as Catnip draws its outlines */
    static void drawWorld(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        worldFrames++;
        List<ValueBox> boxes = shown;
        // Drawn only while the overlay is: noted within the last few drawings, and Dailies still showing overlays
        if (boxes == null || drawnAt < worldFrames - 3 || !DailiesApi.isPlayback() || !overlaysShown()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        PoseStack ms = event.getPoseStack();
        ms.pushPose();
        try {
            SuperRenderTypeBuffer buffer = DefaultSuperRenderTypeBuffer.getInstance();
            Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
            float partialTicks = AnimationTickHolder.getPartialTicks();
            for (ValueBox box : boxes) {
                box.render(ms, buffer, camera, partialTicks);
            }
            buffer.draw();
        } finally {
            ms.popPose();
        }
    }

    private static boolean overlaysShown() {
        if (oldDailies) {
            return true;
        }
        try {
            return DailiesCompats.overlaysShown();
        } catch (LinkageError tooOld) {
            // A Dailies from before overlaysShown: the overlay's own drawing, noted above, is the rule then
            oldDailies = true;
            return true;
        }
    }

    /**
     * After Create's own work each tick in playback, the value boxes it put in the outliner from the playback world and
     * its camera are taken out again: the recorder's boxes are drawn from the recording instead
     */
    static void afterTick(ClientTickEvent.Post event) {
        if (!DailiesApi.isPlayback()) {
            return;
        }
        Outliner outliner = Outliner.getInstance();
        for (Map.Entry<Object, Outliner.OutlineEntry> entry : new ArrayList<>(outliner.getOutlines().entrySet())) {
            Outline outline = entry.getValue().getOutline();
            if (outline instanceof ValueBox) {
                outliner.remove(entry.getKey());
            }
        }
    }

    /** A value box's slot as the recording placed it: the recorded transform, whatever block the playback world has there */
    private static final class Placed extends ValueBoxTransform {
        private final Matrix4f pose;
        private final float fontScale;
        private final int colour;

        Placed(Matrix4f pose, float fontScale, int colour) {
            this.pose = pose;
            this.fontScale = fontScale;
            this.colour = colour;
        }

        @Override
        public Vec3 getLocalOffset(LevelAccessor level, BlockPos pos, BlockState state) {
            return Vec3.ZERO;
        }

        @Override
        public void rotate(LevelAccessor level, BlockPos pos, BlockState state, PoseStack ms) {
        }

        @Override
        public void transform(LevelAccessor level, BlockPos pos, BlockState state, PoseStack ms) {
            ms.mulPose(this.pose);
        }

        @Override
        public boolean shouldRender(LevelAccessor level, BlockPos pos, BlockState state) {
            return true;
        }

        @Override
        public float getFontScale() {
            return this.fontScale;
        }

        @Override
        public int getOverrideColor() {
            return this.colour;
        }
    }

}
