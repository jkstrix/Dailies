package comedymonkey.dailiescompat.create.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.simibubi.create.AllKeys;
import com.simibubi.create.AllSpecialTextures;
import com.simibubi.create.CreateClient;
import com.simibubi.create.content.schematics.client.SchematicHandler;
import com.simibubi.create.content.schematics.client.SchematicHotbarSlotOverlay;
import com.simibubi.create.content.schematics.client.SchematicPromptScreen;
import com.simibubi.create.content.schematics.client.SchematicTransformation;
import com.simibubi.create.content.schematics.client.ToolSelectionScreen;
import com.simibubi.create.content.schematics.client.tools.DeployTool;
import com.simibubi.create.content.schematics.client.tools.ISchematicTool;
import com.simibubi.create.content.schematics.client.tools.SchematicToolBase;
import com.simibubi.create.content.schematics.client.tools.ToolType;
import comedymonkey.dailies.api.DailiesApi;
import comedymonkey.dailiescompat.create.state.States;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.outliner.AABBOutline;
import net.createmod.catnip.render.SuperRenderTypeBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Create's schematics as the player uses them, besides the schematic table and the schematicannon (server windows,
 * which Dailies draws itself) and the area the schematic and quill selects (an outline, {@link Outlines}):
 * <ul>
 * <li>a schematic held ({@link #HELD}): the tool bar over the hotbar (the tools, the one picked, whether it is held open)
 * and the schematic's slot marked in the hotbar; and in the world, the schematic's box where it is placed, with the face
 * picked, and the deploy tool's preview box where it would go;</li>
 * <li>the screen naming a schematic saved with the schematic and quill ({@link #NAMING}).</li>
 * </ul>
 * Once placed, the schematic's blocks are drawn inside its box too: the schematic's file is stored once in the recording
 * and its state names it ({@link SchematicBlocks}).
 */
final class SchematicParts {

    static final String HELD = "schematic";
    static final String NAMING = "schematic_name";

    private static final int BOX_COLOUR = 0x6886c5;

    /** The state last drawn and what was built from it, and when it was drawn */
    private static byte @Nullable [] shownFrom = null;
    private static States.@Nullable Schematic shown = null;
    private static @Nullable ToolSelectionScreen toolBar = null;
    private static long drawnAt = Long.MIN_VALUE;

    private SchematicParts() {
    }

    // --- A schematic held ---

    static byte @Nullable [] captureHeld() {
        Minecraft minecraft = Minecraft.getInstance();
        SchematicHandler handler = CreateClient.SCHEMATIC_HANDLER;
        // As SchematicHandler.render decides whether to draw
        if (minecraft.options.hideGui || !handler.isActive()) {
            return null;
        }
        ItemStack held = handler.getActiveSchematicItem();
        ToolSelectionScreen bar = Reflect.get(handler, "selectionScreen");
        List<ToolType> offered = Reflect.get(bar, "tools");
        List<String> tools = new ArrayList<>(offered.size());
        for (ToolType tool : offered) {
            tools.add(tool.name());
        }
        float rise = Reflect.get(bar, "yOffset");
        AABB bounds = handler.getBounds();
        double[] size = bounds == null ? new double[3] : new double[]{bounds.getXsize(), bounds.getYsize(), bounds.getZsize()};
        float[] placed = new float[0];
        float[] preview = new float[0];
        int face = -1;
        boolean faceHighlighted = false;
        ToolType current = Reflect.get(handler, "currentTool");
        if (held != null && bounds != null && current != null) {
            ISchematicTool tool = current.getTool();
            SchematicTransformation transformation = handler.getTransformation();
            if (handler.isDeployed()) {
                // From the world to the schematic, as SchematicHandler.render places it (its camera taken out)
                PoseStack ms = new PoseStack();
                transformation.applyTransformations(ms, Vec3.ZERO);
                placed = new float[16];
                ms.last().pose().get(placed);
                if (tool instanceof SchematicToolBase base && (boolean) Reflect.get(base, "renderSelectedFace")) {
                    Direction selected = Reflect.get(base, "selectedFace");
                    face = selected == null ? -1 : selected.ordinal();
                    faceHighlighted = AllKeys.ctrlDown();
                }
            }
            if (tool instanceof DeployTool deploy && Reflect.get(deploy, "selectedPos") != null) {
                preview = deployPreview(Reflect.get(deploy, "chasingSelectedPos"), bounds, transformation);
            }
        }
        String blob = "";
        int mirror = 0;
        if (held != null && handler.isDeployed()) {
            // Its blocks, from its file; and the copy Create draws, as SchematicHandler.render picks it
            blob = SchematicBlocks.handle(Reflect.get(handler, "displayedSchematic"));
            SchematicTransformation transformation = handler.getTransformation();
            float pt = AnimationTickHolder.getPartialTicks();
            boolean leftRight = transformation.getScaleLR().getValue(pt) < 0;
            boolean frontBack = transformation.getScaleFB().getValue(pt) < 0;
            mirror = leftRight && !frontBack ? 2 : frontBack && !leftRight ? 1 : 0;
        }
        return States.schematic(new States.Schematic(held == null ? -1 : Reflect.getInt(handler, "activeHotbarSlot"), tools,
            Reflect.getInt(bar, "selection"), bar.focused, rise, size, handler.isDeployed(), placed, preview, face, faceHighlighted, blob, mirror));
    }

    /** Where the deploy tool shows the schematic would go, as DeployTool.renderTool places it (its camera taken out) */
    private static float[] deployPreview(Vec3 at, AABB bounds, SchematicTransformation transformation) {
        Vec3 center = bounds.getCenter();
        Vec3 rotationOffset = transformation.getRotationOffset(true);
        Vec3 origin = new Vec3(bounds.getXsize() / 2.0F, 0, bounds.getZsize() / 2.0F);
        PoseStack ms = new PoseStack();
        ms.translate(at.x - (int) center.x, at.y, at.z - (int) center.z);
        ms.translate(origin.x, origin.y, origin.z);
        ms.translate(rotationOffset.x, rotationOffset.y, rotationOffset.z);
        ms.mulPose(Axis.YP.rotationDegrees(transformation.getCurrentRotation()));
        ms.translate(-rotationOffset.x, -rotationOffset.y, -rotationOffset.z);
        ms.translate(-origin.x, -origin.y, -origin.z);
        float[] pose = new float[16];
        ms.last().pose().get(pose);
        return pose;
    }

    /** The tool bar and the hotbar's mark, as SchematicHandler draws them; notes the boxes for the world's next drawing */
    static void drawHeld(GuiGraphics graphics, byte[] state, float partialTick) {
        if (shownFrom == null || !Arrays.equals(shownFrom, state)) {
            shown = States.readSchematic(state);
            shownFrom = state;
            toolBar = toolBar(shown);
        }
        drawnAt = InWorld.frame();
        States.Schematic schematic = shown;
        if (schematic.hotbarSlot() >= 0) {
            new SchematicHotbarSlotOverlay().renderOn(graphics, schematic.hotbarSlot());
        }
        if (toolBar != null) {
            toolBar.renderPassive(graphics, partialTick);
        }
    }

    private static @Nullable ToolSelectionScreen toolBar(States.Schematic schematic) {
        List<ToolType> tools = new ArrayList<>();
        for (String name : schematic.tools()) {
            for (ToolType tool : ToolType.values()) {
                if (tool.name().equals(name)) {
                    tools.add(tool);
                }
            }
        }
        if (tools.isEmpty()) {
            return null;
        }
        // Built with nothing to call: the bar only tells its owner which tool was picked
        ToolSelectionScreen bar = new ToolSelectionScreen(tools, picked -> {
        });
        Reflect.setInt(bar, "selection", Math.max(0, Math.min(tools.size() - 1, schematic.selection())));
        bar.focused = schematic.focused();
        Reflect.set(bar, "yOffset", schematic.rise());
        return bar;
    }

    /** The schematic's boxes, into the world after the particles, while the overlay is drawn */
    static void drawWorld(PoseStack ms, SuperRenderTypeBuffer buffer, Vec3 camera, float partialTicks) {
        States.Schematic schematic = shown;
        if (schematic == null || !InWorld.noted(drawnAt)) {
            if (!DailiesApi.isPlayback()) {
                SchematicBlocks.forget();
            }
            return;
        }
        AABB bounds = new AABB(0, 0, 0, schematic.size()[0], schematic.size()[1], schematic.size()[2]);
        if (schematic.placed().length == 16) {
            // As SchematicToolBase.renderOnSchematic draws it
            AABBOutline outline = new AABBOutline(bounds);
            outline.getParams().colored(BOX_COLOUR).lineWidth(1 / 16.0F);
            Direction[] faces = Direction.values();
            if (schematic.face() >= 0 && schematic.face() < faces.length) {
                outline.getParams().highlightFace(faces[schematic.face()]).withFaceTextures(AllSpecialTextures.CHECKERED,
                    schematic.faceHighlighted() ? AllSpecialTextures.HIGHLIGHT_CHECKERED : AllSpecialTextures.CHECKERED);
            }
            outline.getParams().withFaceTexture(AllSpecialTextures.CHECKERED);
            drawBox(ms, buffer, camera, partialTicks, outline, schematic.placed(), schematic);
        }
        if (schematic.preview().length == 16) {
            AABBOutline outline = new AABBOutline(bounds);
            outline.getParams().colored(BOX_COLOUR).lineWidth(1 / 16.0F);
            drawBox(ms, buffer, camera, partialTicks, outline, schematic.preview(), null);
        }
    }

    /** A box placed by its transform, with the schematic's blocks inside it first when they are to be drawn, as SchematicHandler.render does */
    private static void drawBox(PoseStack ms, SuperRenderTypeBuffer buffer, Vec3 camera, float partialTicks, AABBOutline outline, float[] placed,
                                States.@Nullable Schematic blocks) {
        ms.pushPose();
        try {
            ms.translate(-camera.x, -camera.y, -camera.z);
            ms.mulPose(new Matrix4f().set(placed));
            if (blocks != null && blocks.deployed()) {
                SchematicBlocks.draw(ms, buffer, blocks.blob(), blocks.mirror());
            }
            outline.render(ms, buffer, Vec3.ZERO, partialTicks);
        } finally {
            ms.popPose();
        }
    }

    // --- Naming a schematic ---

    static byte[] captureNaming(Screen screen) {
        EditBox name = Reflect.get(screen, "nameField");
        return States.schematicName(new States.SchematicName(name == null ? "" : name.getValue(), name == null ? 0 : name.getCursorPosition()));
    }

    /** Built as Create builds it, with the name as typed: it saves only when confirmed, which playback never does */
    static Screen buildNaming(byte[] state) {
        return new RecordedNaming(States.readSchematicName(state));
    }

    static final class RecordedNaming extends SchematicPromptScreen implements RecordedScreen {
        private final States.SchematicName recorded;

        RecordedNaming(States.SchematicName recorded) {
            this.recorded = recorded;
        }

        @Override
        public void init() {
            super.init();
            EditBox name = Reflect.get(this, "nameField");
            name.setValue(this.recorded.name());
            name.setCursorPosition(this.recorded.cursor());
            name.setHighlightPos(name.getCursorPosition());
        }
    }

}
