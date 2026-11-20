package comedymonkey.dailiescompat.create.client;

import com.simibubi.create.AllDataComponents;
import com.simibubi.create.CreateClient;
import com.simibubi.create.content.equipment.zapper.PlacementPatterns;
import com.simibubi.create.content.equipment.zapper.terrainzapper.PlacementOptions;
import com.simibubi.create.content.equipment.zapper.terrainzapper.TerrainBrushes;
import com.simibubi.create.content.equipment.zapper.terrainzapper.TerrainTools;
import com.simibubi.create.content.equipment.zapper.terrainzapper.WorldshaperScreen;
import com.simibubi.create.content.schematics.client.SchematicEditScreen;
import com.simibubi.create.content.schematics.client.SchematicHandler;
import com.simibubi.create.foundation.gui.widget.ScrollInput;
import comedymonkey.dailiescompat.create.state.States;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Two of Create's screens where a tool is set, each only the player's game knows until it closes:
 * <ul>
 * <li>the worldshaper's ({@link #WORLDSHAPER}), opened by using it while sneaking: its pattern, brush and the brush's
 * numbers, the connectivity options, the tool and how it places, sent when the screen closes. Recorded as those, with
 * the block it places and its name, and drawn with Create's own screen on a worldshaper of its own, which never
 * sends;</li>
 * <li>the schematic's edit screen ({@link #SCHEMATIC_EDIT}), where a schematic held is placed by number: the
 * coordinates as typed, the rotation and mirror, applied to the schematic held when it closes. Recorded as those with
 * the schematic's name, and drawn with Create's own screen, which in playback changes nothing as it closes.</li>
 * </ul>
 * Create 6.0.10's only zapper is the worldshaper: its screen is the zapper screen, the one Create keeps for every
 * zapper.
 */
final class EditScreens {

    static final String WORLDSHAPER = "worldshaper";
    static final String SCHEMATIC_EDIT = "schematic_edit";

    private EditScreens() {
    }

    // --- The worldshaper's ---

    static byte[] captureWorldshaper(Screen screen) {
        ItemStack zapper = Reflect.get(screen, "zapper");
        Component title = Reflect.get(screen, "title");
        BlockState block = zapper.get(AllDataComponents.SHAPER_BLOCK_USED);
        PlacementPatterns pattern = Reflect.get(screen, "currentPattern");
        TerrainBrushes brush = Reflect.get(screen, "currentBrush");
        int[] params = Reflect.get(screen, "currentBrushParams");
        TerrainTools tool = Reflect.get(screen, "currentTool");
        PlacementOptions placement = Reflect.get(screen, "currentPlacement");
        return States.worldshaper(new States.Worldshaper(title == null ? "" : Texts.json(title), block == null ? "" : BlockStateParser.serialize(block),
            pattern == null ? "" : pattern.name(), brush == null ? "" : brush.name(), params == null ? new int[]{1, 1, 1} : params.clone(),
            (boolean) Reflect.get(screen, "currentFollowDiagonals"), (boolean) Reflect.get(screen, "currentAcrossMaterials"), tool == null ? "" : tool.name(),
            placement == null ? "" : placement.name()));
    }

    static Screen buildWorldshaper(byte[] state) {
        States.Worldshaper recorded = States.readWorldshaper(state);
        ItemStack zapper = Texts.stack("create:handheld_worldshaper");
        BlockState block = blockState(recorded.block());
        if (block != null) {
            zapper.set(AllDataComponents.SHAPER_BLOCK_USED, block);
        }
        return new RecordedWorldshaper(zapper, recorded);
    }

    static final class RecordedWorldshaper extends WorldshaperScreen implements RecordedScreen {
        RecordedWorldshaper(ItemStack zapper, States.Worldshaper recorded) {
            super(zapper, InteractionHand.MAIN_HAND);
            // Before the screen lays itself out from them
            Reflect.set(this, "title", Texts.read(recorded.title()));
            Reflect.set(this, "currentPattern", value(PlacementPatterns.class, recorded.pattern(), PlacementPatterns.Solid));
            Reflect.set(this, "currentBrush", value(TerrainBrushes.class, recorded.brush(), TerrainBrushes.Cuboid));
            Reflect.set(this, "currentBrushParams", new int[]{recorded.param(0), recorded.param(1), recorded.param(2)});
            Reflect.setBoolean(this, "currentFollowDiagonals", recorded.followDiagonals());
            Reflect.setBoolean(this, "currentAcrossMaterials", recorded.acrossMaterials());
            Reflect.set(this, "currentTool", value(TerrainTools.class, recorded.tool(), TerrainTools.Fill));
            Reflect.set(this, "currentPlacement", value(PlacementOptions.class, recorded.placement(), PlacementOptions.Merged));
        }

        @Override
        public void tick() {
        }

        @Override
        public void removed() {
            // Create's screen sends the settings to the server as it closes; this one never does
        }
    }

    private static <E extends Enum<E>> E value(Class<E> type, String name, E otherwise) {
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException unknown) {
            return otherwise;
        }
    }

    static @Nullable BlockState blockState(String written) {
        if (written == null || written.isEmpty()) {
            return null;
        }
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), written, false).blockState();
        } catch (Exception unknown) {
            return null;
        }
    }

    // --- The schematic's edit screen ---

    static byte[] captureSchematicEdit(Screen screen) {
        List<String> coords = new ArrayList<>(3);
        int focused = -1;
        int cursor = 0;
        String[] names = {"xInput", "yInput", "zInput"};
        for (int i = 0; i < names.length; i++) {
            EditBox box = Reflect.get(screen, names[i]);
            coords.add(box == null ? "" : box.getValue());
            if (box != null && box.isFocused()) {
                focused = i;
                cursor = box.getCursorPosition();
            }
        }
        ScrollInput rotation = Reflect.get(screen, "rotationArea");
        ScrollInput mirror = Reflect.get(screen, "mirrorArea");
        return States.schematicEdit(new States.SchematicEdit(CreateClient.SCHEMATIC_HANDLER.getCurrentSchematicName(), coords, focused, cursor,
            rotation == null ? 0 : rotation.getState(), mirror == null ? 0 : mirror.getState()));
    }

    static Screen buildSchematicEdit(byte[] state) {
        return new RecordedSchematicEdit(States.readSchematicEdit(state));
    }

    static final class RecordedSchematicEdit extends SchematicEditScreen implements RecordedScreen {
        private final States.SchematicEdit recorded;

        RecordedSchematicEdit(States.SchematicEdit recorded) {
            this.recorded = recorded;
        }

        @Override
        protected void init() {
            super.init();
            String[] names = {"xInput", "yInput", "zInput"};
            for (int i = 0; i < names.length; i++) {
                EditBox box = Reflect.get(this, names[i]);
                box.setValue(this.recorded.coord(i));
                box.setFocused(i == this.recorded.focused());
                if (i == this.recorded.focused()) {
                    box.setCursorPosition(this.recorded.cursor());
                    box.setHighlightPos(box.getCursorPosition());
                }
            }
            ((ScrollInput) Reflect.get(this, "rotationArea")).setState(this.recorded.rotation());
            ((ScrollInput) Reflect.get(this, "mirrorArea")).setState(this.recorded.mirror());
            // The labels beside them read the states only when told
            ((ScrollInput) Reflect.get(this, "rotationArea")).onChanged();
            ((ScrollInput) Reflect.get(this, "mirrorArea")).onChanged();
        }

        @Override
        protected void renderWindow(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            // The title is the name of the schematic the game's player holds: for the moment, the recorder's
            SchematicHandler handler = CreateClient.SCHEMATIC_HANDLER;
            String before = Reflect.get(handler, "displayedSchematic");
            try {
                Reflect.set(handler, "displayedSchematic", this.recorded.name());
                super.renderWindow(graphics, mouseX, mouseY, partialTicks);
            } finally {
                Reflect.set(handler, "displayedSchematic", before);
            }
        }

        @Override
        public void removed() {
            // Create's screen places the schematic held as it closes; this one changes nothing
        }
    }

}
