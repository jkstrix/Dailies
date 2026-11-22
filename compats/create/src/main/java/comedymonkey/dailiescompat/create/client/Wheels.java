package comedymonkey.dailiescompat.create.client;

import com.simibubi.create.content.contraptions.wrench.RadialWrenchMenu;
import com.simibubi.create.content.equipment.toolbox.RadialToolboxMenu;
import com.simibubi.create.content.equipment.toolbox.ToolboxBlockEntity;
import com.simibubi.create.content.equipment.toolbox.ToolboxInventory;
import comedymonkey.dailiescompat.create.state.States;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Create's two selection wheels, each shown while its key is held and each a screen only the player's game knows:
 * <ul>
 * <li>the toolbox's ({@link #TOOLBOX}): a toolbox to pick from, or a slot of the toolbox picked, the held item to put
 * back, or only putting it back when the toolbox is out of reach. Recorded as what it is picking, the toolboxes and the
 * items in the slots as drawn, the segment the pointer is on and the slot scrolled to; drawn with Create's own wheel
 * on toolboxes of its own, in no world, holding those items, with the pointer put on the recorded segment. Create's
 * wheel sends the choice as it closes: the one built for playback never closes and sends nothing;</li>
 * <li>the wrench's ({@link #WRENCH}): the states of one property of the block looked at, round a wheel, to turn it to.
 * Recorded as the block, the property, the states offered and the one highlighted, with where the pointer was and how
 * the player was turned; drawn with Create's own wheel made for the playback world's block, which sends only when a
 * key or button is let go, and is given none.</li>
 * </ul>
 */
final class Wheels {

    static final String TOOLBOX = "toolbox_wheel";
    static final String WRENCH = "wrench_wheel";

    /** How far the wheels fade in, in ticks */
    private static final int FADED_IN = 20;

    private Wheels() {
    }

    // --- The toolbox's ---

    static byte[] captureToolbox(Screen screen) {
        RadialToolboxMenu.State state = Reflect.get(screen, "state");
        List<ToolboxBlockEntity> offered = Reflect.get(screen, "toolboxes");
        List<States.Toolbox> boxes = new ArrayList<>();
        for (ToolboxBlockEntity box : offered == null ? List.<ToolboxBlockEntity>of() : offered) {
            if (boxes.size() < 8) {
                boxes.add(toolbox(box));
            }
        }
        ToolboxBlockEntity from = Reflect.get(screen, "selectedBox");
        List<String> items = new ArrayList<>(8);
        int stocked = 0;
        if (from != null) {
            ToolboxInventory inventory = Reflect.get(from, "inventory");
            List<ItemStack> filters = Reflect.get(inventory, "filters");
            for (int slot = 0; slot < 8; slot++) {
                items.add(Texts.item(slot < filters.size() ? filters.get(slot) : ItemStack.EMPTY));
                if (!inventory.getStackInSlot(slot * ToolboxInventory.STACKS_PER_COMPARTMENT).isEmpty()) {
                    stocked |= 1 << slot;
                }
            }
        }
        Minecraft minecraft = Minecraft.getInstance();
        String held = minecraft.player == null ? "" : Texts.json(minecraft.player.getMainHandItem().getHoverName());
        return States.toolboxWheel(new States.ToolboxWheel(state.name(), boxes, from != null, from == null ? new States.Toolbox("", "") : toolbox(from), items,
            stocked, Reflect.getInt(screen, "hoveredSlot"), (boolean) Reflect.get(screen, "scrollMode"), Reflect.getInt(screen, "scrollSlot"),
            Math.min(FADED_IN, Reflect.getInt(screen, "ticksOpen")), held));
    }

    private static States.Toolbox toolbox(ToolboxBlockEntity box) {
        Component name = Reflect.get(box, "customName");
        return new States.Toolbox(box.getColor().getName(), name == null ? "" : Texts.json(name));
    }

    static @Nullable Screen buildToolbox(byte[] data) {
        States.ToolboxWheel recorded = States.readToolboxWheel(data);
        RadialToolboxMenu.State state;
        try {
            state = RadialToolboxMenu.State.valueOf(recorded.state());
        } catch (IllegalArgumentException unknown) {
            return null;
        }
        List<ToolboxBlockEntity> boxes = new ArrayList<>();
        for (States.Toolbox box : recorded.toolboxes()) {
            ToolboxBlockEntity made = madeToolbox(box, List.of(), 0);
            if (made == null) {
                return null;
            }
            boxes.add(made);
        }
        ToolboxBlockEntity from = recorded.picked() ? madeToolbox(recorded.from(), recorded.items(), recorded.stocked()) : null;
        if (from == null && (state == RadialToolboxMenu.State.SELECT_ITEM || state == RadialToolboxMenu.State.SELECT_ITEM_UNEQUIP)) {
            return null;
        }
        return new RecordedToolbox(boxes, state, from, recorded);
    }

    /**
     * A toolbox of its own, in no world, as the wheel reads one: its colour, its name, the item its slots are kept for and
     * one of it in each slot still stocked. Its slots are filled without telling the toolbox, which has no world to tell
     */
    private static @Nullable ToolboxBlockEntity madeToolbox(States.Toolbox recorded, List<String> items, int stocked) {
        DyeColor colour = DyeColor.byName(recorded.colour(), DyeColor.BROWN);
        // By id, as Create registers them: a toolbox of each colour, and one kind of block entity for them all
        Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("create", colour.getName() + "_toolbox"));
        BlockEntityType<?> type = BuiltInRegistries.BLOCK_ENTITY_TYPE.get(ResourceLocation.fromNamespaceAndPath("create", "toolbox"));
        if (type == null || !(type.create(BlockPos.ZERO, block.defaultBlockState()) instanceof ToolboxBlockEntity box)) {
            return null;
        }
        if (!recorded.name().isEmpty()) {
            box.setCustomName(Texts.read(recorded.name()));
        }
        ToolboxInventory inventory = Reflect.get(box, "inventory");
        List<ItemStack> filters = new ArrayList<>(8);
        NonNullList<ItemStack> stacks = Reflect.get(inventory, "stacks");
        for (int slot = 0; slot < 8; slot++) {
            ItemStack item = slot < items.size() ? Texts.stack(items.get(slot)) : ItemStack.EMPTY;
            filters.add(item);
            if ((stocked & (1 << slot)) != 0 && !item.isEmpty()) {
                stacks.set(slot * ToolboxInventory.STACKS_PER_COMPARTMENT, item.copy());
            }
        }
        Reflect.set(inventory, "filters", filters);
        return box;
    }

    static final class RecordedToolbox extends RadialToolboxMenu implements RecordedScreen {
        private final States.ToolboxWheel recorded;
        private final ItemStack held;

        RecordedToolbox(List<ToolboxBlockEntity> boxes, State state, @Nullable ToolboxBlockEntity from, States.ToolboxWheel recorded) {
            super(boxes, state, from);
            this.recorded = recorded;
            this.held = Texts.stack("minecraft:stick").copyWithCount(1);
            this.held.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Texts.read(recorded.held()));
            prevSlot(recorded.scrolled());
            Reflect.setBoolean(this, "scrollMode", recorded.scrolling());
            Reflect.setInt(this, "ticksOpen", recorded.ticksOpen());
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            // The wheel picks the segment under the pointer as it draws: drawn with a pointer on the recorded one
            int[] at = this.recorded.pointer();
            int x = this.getMinecraft().getWindow().getGuiScaledWidth() / 2 + at[0];
            int y = this.getMinecraft().getWindow().getGuiScaledHeight() / 2 + at[1];
            LocalPlayer viewer = this.getMinecraft().player;
            boolean namesHeld = viewer != null && this.recorded.hovered() == States.ToolboxWheel.UNEQUIP;
            ItemStack before = namesHeld ? viewer.getInventory().getSelected() : ItemStack.EMPTY;
            Inventory inventory = namesHeld ? viewer.getInventory() : null;
            try {
                if (namesHeld) {
                    // The middle's tip names the item in the game's player's hand: for the moment, one named as the recorder's was
                    inventory.items.set(inventory.selected, this.held);
                }
                super.render(graphics, x, y, partialTicks);
            } finally {
                if (namesHeld) {
                    inventory.items.set(inventory.selected, before);
                }
            }
        }

        @Override
        public void tick() {
        }

        @Override
        public void removed() {
            // Create's wheel sends the choice as it closes; this one never does
        }
    }

    // --- The wrench's ---

    static byte[] captureWrench(Screen screen) {
        BlockPos pos = Reflect.get(screen, "pos");
        BlockState block = Reflect.get(screen, "state");
        List<BlockState> offered = Reflect.get(screen, "allStates");
        List<String> states = new ArrayList<>(offered.size());
        for (BlockState state : offered) {
            states.add(BlockStateParser.serialize(state));
        }
        Minecraft minecraft = Minecraft.getInstance();
        // Where the wheel was drawn with the pointer, worked out as the game hands it to the screen
        int mouseX = (int) (minecraft.mouseHandler.xpos() * minecraft.getWindow().getGuiScaledWidth() / minecraft.getWindow().getScreenWidth());
        int mouseY = (int) (minecraft.mouseHandler.ypos() * minecraft.getWindow().getGuiScaledHeight() / minecraft.getWindow().getScreenHeight());
        int dx = mouseX - screen.width / 2;
        int dy = mouseY - screen.height / 2;
        // As RadialWrenchMenu decides that the pointer picks: further out than the inner ring, less 5
        boolean pointed = Math.sqrt((double) dx * dx + (double) dy * dy) > 45;
        LocalPlayer player = minecraft.player;
        return States.wrenchWheel(new States.WrenchWheel(pos.asLong(), BlockStateParser.serialize(block), Reflect.getInt(screen, "selectedPropertyIndex"), states,
            Reflect.getInt(screen, "selectedStateIndex"), pointed, pointed ? dx : 0, pointed ? dy : 0, Math.min(FADED_IN, Reflect.getInt(screen, "ticksOpen")),
            player == null ? 0.0F : player.getXRot(), player == null ? 0.0F : player.getYRot()));
    }

    static @Nullable Screen buildWrench(byte[] data) {
        States.WrenchWheel recorded = States.readWrenchWheel(data);
        Minecraft minecraft = Minecraft.getInstance();
        BlockState block = state(recorded.block());
        if (minecraft.level == null || block == null) {
            return null;
        }
        RadialWrenchMenu menu = RadialWrenchMenu.tryCreateFor(block, BlockPos.of(recorded.pos()), minecraft.level).orElse(null);
        if (menu == null) {
            return null;
        }
        List<Object> properties = Reflect.get(menu, "propertiesForState");
        int property = Math.max(0, Math.min(properties.size() - 1, recorded.property()));
        List<BlockState> states = new ArrayList<>();
        for (String each : recorded.states()) {
            BlockState state = state(each);
            if (state != null) {
                states.add(state);
            }
        }
        @SuppressWarnings("unchecked")
        java.util.Map.Entry<Object, String> picked = (java.util.Map.Entry<Object, String>) properties.get(property);
        Reflect.setInt(menu, "selectedPropertyIndex", property);
        Reflect.set(menu, "allStates", states);
        Reflect.set(menu, "propertyLabel", picked.getValue());
        Reflect.setInt(menu, "selectedStateIndex", Math.max(0, Math.min(states.size() - 1, recorded.selected())));
        Reflect.setInt(menu, "ticksOpen", recorded.ticksOpen());
        return new RecordedWrench(menu, recorded);
    }

    private static @Nullable BlockState state(String written) {
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), written, false).blockState();
        } catch (Exception unknown) {
            return null;
        }
    }

    /**
     * Create's wrench wheel for playback. Create makes it only through its own factory, so it is held here rather than
     * extended: laid out and drawn by this screen, never ticked (its tick closes whatever screen the game has open when
     * the block has changed), never given a key or a button, and drawn with the pointer where it was recorded and the
     * game's player turned as the recorder was, which the blocks on the wheel are turned by
     */
    static final class RecordedWrench extends Screen implements RecordedScreen {
        private final RadialWrenchMenu menu;
        private final States.WrenchWheel recorded;
        /** Read by the compat's corner, as on Create's own screens: the middle */
        private int guiLeft;
        private int guiTop;

        RecordedWrench(RadialWrenchMenu menu, States.WrenchWheel recorded) {
            super(Component.empty());
            this.menu = menu;
            this.recorded = recorded;
        }

        @Override
        protected void init() {
            this.guiLeft = this.width / 2;
            this.guiTop = this.height / 2;
            // Laid out as Catnip lays it out, without the game's opening of a screen
            Reflect.set(this.menu, "minecraft", this.minecraft);
            Reflect.set(this.menu, "font", this.font);
            this.menu.width = this.width;
            this.menu.height = this.height;
            Reflect.setInt(this.menu, "guiLeft", this.guiLeft);
            Reflect.setInt(this.menu, "guiTop", this.guiTop);
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            int x = this.width / 2 + this.recorded.pointerX();
            int y = this.height / 2 + this.recorded.pointerY();
            LocalPlayer viewer = this.minecraft == null ? null : this.minecraft.player;
            float pitch = viewer == null ? 0.0F : viewer.getXRot();
            float yaw = viewer == null ? 0.0F : viewer.getYRot();
            try {
                if (viewer != null) {
                    viewer.setXRot(this.recorded.pitch());
                    viewer.setYRot(this.recorded.yaw());
                }
                this.menu.render(graphics, x, y, partialTicks);
            } finally {
                if (viewer != null) {
                    viewer.setXRot(pitch);
                    viewer.setYRot(yaw);
                }
            }
        }

        @Override
        public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            // The wheel draws its own
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }

}
