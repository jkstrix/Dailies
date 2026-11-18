package comedymonkey.dailies.extras;

import com.mojang.blaze3d.systems.RenderSystem;
import comedymonkey.dailies.Dailies;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.editor.EditorSession;
import comedymonkey.dailies.file.RecordingManifest;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.playback.PlaybackExtensions;
import comedymonkey.dailies.playback.Viewer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

import java.util.Arrays;

/**
 * The playback half of the recorder's HUD (specification PLAY-3). While the viewer rides along with the recorder and
 * the scene's "recorder's HUD" look is on, the hotbar, hearts, hunger, armour, air and experience the recorder saw
 * are drawn from what the recording holds, with the game's own sprites and in the game's own places. The viewer
 * itself stays a spectator with no HUD of its own. Every recording keeps the HUD.
 */
public final class HudLayer implements PlaybackExtensions.Extension {

    private static final ResourceLocation HOTBAR = sprite("hud/hotbar");
    private static final ResourceLocation HOTBAR_SELECTION = sprite("hud/hotbar_selection");
    private static final ResourceLocation OFF_HAND_LEFT = sprite("hud/hotbar_offhand_left");
    private static final ResourceLocation OFF_HAND_RIGHT = sprite("hud/hotbar_offhand_right");
    private static final ResourceLocation EXPERIENCE_BACKGROUND = sprite("hud/experience_bar_background");
    private static final ResourceLocation EXPERIENCE_PROGRESS = sprite("hud/experience_bar_progress");
    private static final ResourceLocation ARMOUR_EMPTY = sprite("hud/armor_empty");
    private static final ResourceLocation ARMOUR_HALF = sprite("hud/armor_half");
    private static final ResourceLocation ARMOUR_FULL = sprite("hud/armor_full");
    private static final ResourceLocation FOOD_EMPTY = sprite("hud/food_empty");
    private static final ResourceLocation FOOD_HALF = sprite("hud/food_half");
    private static final ResourceLocation FOOD_FULL = sprite("hud/food_full");
    private static final ResourceLocation AIR = sprite("hud/air");
    private static final ResourceLocation AIR_BURSTING = sprite("hud/air_bursting");
    private static final ResourceLocation HEART_CONTAINER = sprite("hud/heart/container");
    private static final ResourceLocation HEART_CONTAINER_HARDCORE = sprite("hud/heart/container_hardcore");
    private static final ResourceLocation HEART_FULL = sprite("hud/heart/full");
    private static final ResourceLocation HEART_HALF = sprite("hud/heart/half");
    private static final ResourceLocation HEART_FULL_HARDCORE = sprite("hud/heart/hardcore_full");
    private static final ResourceLocation HEART_HALF_HARDCORE = sprite("hud/heart/hardcore_half");
    private static final ResourceLocation ABSORBING_FULL = sprite("hud/heart/absorbing_full");
    private static final ResourceLocation ABSORBING_HALF = sprite("hud/heart/absorbing_half");
    private static final ResourceLocation ABSORBING_FULL_HARDCORE = sprite("hud/heart/absorbing_hardcore_full");
    private static final ResourceLocation ABSORBING_HALF_HARDCORE = sprite("hud/heart/absorbing_hardcore_half");

    /** How far the playback bar reaches up from the bottom of the screen: the HUD sits above it */
    private static final int PLAYBACK_BAR = 22;
    private static final int LEVEL_COLOUR = 0x80FF20;

    private static final HudLayer INSTANCE = new HudLayer();

    /** The newest HUD record reached, as it was written; null when the recording holds none here */
    private byte[] record = null;
    /** That record read, or null when it has not been read yet or could not be */
    private HudState state = null;
    private boolean stale = false;
    private boolean readFailed = false;
    private boolean drawFailed = false;

    private HudLayer() {
    }

    private static ResourceLocation sprite(String path) {
        return ResourceLocation.withDefaultNamespace(path);
    }

    static void init() {
        PlaybackExtensions.register(HudState.ID, INSTANCE);
        Playback.onClosing(INSTANCE::reset);
    }

    static void register(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.HOTBAR, ResourceLocation.fromNamespaceAndPath(Dailies.ID, "recorder_hud"), INSTANCE::draw);
    }

    @Override
    public void play(byte[] data, int from, int length, boolean skipping) {
        // Only kept here. It is read when it is first drawn, so skipping through a recording costs nothing
        this.record = length == 0 || data[from] == 0 ? null : Arrays.copyOfRange(data, from, from + length);
        this.state = null;
        this.stale = this.record != null;
    }

    /** The HUD as last recorded, so the part after a cut starts with it */
    @Override
    public void describe(java.util.function.Consumer<byte[]> out) {
        if (this.record != null) {
            out.accept(this.record);
        }
    }

    @Override
    public void reset() {
        this.record = null;
        this.state = null;
        this.stale = false;
    }

    /**
     * The recorder's HUD as playback holds it now, in numbers: health, greatest health, absorption, hunger, air,
     * greatest air, experience level, experience progress and game mode. Null when the recording holds none here.
     * For the tests and tools that compare a recording with the game it was made from.
     */
    public static float[] numbers() {
        HudState hud = INSTANCE.current(Minecraft.getInstance());
        return hud == null ? null : numbers(hud);
    }

    static float[] numbers(HudState hud) {
        return new float[]{hud.health, hud.maxHealth, hud.absorption, hud.food, hud.air, hud.maxAir, hud.level, hud.progress, hud.gameMode.getId()};
    }

    private HudState current(Minecraft minecraft) {
        if (this.stale) {
            this.stale = false;
            ClientPacketListener listener = minecraft.getConnection();
            try {
                this.state = listener == null ? null : HudState.decode(this.record, listener.registryAccess(), listener.getConnectionType());
            } catch (Throwable t) {
                this.state = null;
                if (!this.readFailed) {
                    this.readFailed = true;
                    Host.LOGGER.warn("Dailies: the recorder's HUD in this recording could not be read and is not shown. Said once", t);
                }
            }
        }
        return this.state;
    }

    /** The recorder's experience level, progress to the next, health and greatest health as last recorded; null when none is. For the tests */
    public static float[] recordedNumbers() {
        HudState state = INSTANCE.record == null ? null : INSTANCE.current(Minecraft.getInstance());
        return state == null ? null : new float[]{state.level, state.progress, state.health, state.maxHealth};
    }

    /** What the recorder held in the selected slot as last recorded; null when no HUD is. For the tests */
    public static net.minecraft.world.item.ItemStack recordedHeld() {
        HudState state = INSTANCE.record == null ? null : INSTANCE.current(Minecraft.getInstance());
        return state == null || state.selected < 0 || state.selected >= 9 ? null : state.stacks[state.selected];
    }

    private void draw(GuiGraphics graphics, DeltaTracker delta) {
        if (this.record == null || !Playback.loaded()) {
            return;
        }
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.options.hideGui) {
                // Hidden with the rest of the HUD: F1, or a picture asked for without it
                return;
            }
            RecordingManifest manifest = Playback.manifest();
            Entity riding = Viewer.riding();
            if (manifest == null || !(riding instanceof Player recorder) || !recorder.getUUID().toString().equalsIgnoreCase(manifest.recorderId)) {
                return;
            }
            boolean hotbar = true;
            if (EditorSession.open()) {
                comedymonkey.dailies.edit.Looks looks = EditorSession.scene().looks();
                if (!looks.showHud) {
                    return;
                }
                hotbar = looks.showHotbar;
            }
            HudState hud = this.current(minecraft);
            if (hud == null || hud.gameMode == net.minecraft.world.level.GameType.SPECTATOR) {
                return;
            }
            boolean barShown = !comedymonkey.dailies.export.Export.active() && !comedymonkey.dailies.ui.Notices.hidden();
            graphics.pose().pushPose();
            try {
                graphics.pose().translate(0.0F, barShown ? -PLAYBACK_BAR : 0.0F, 0.0F);
                if (hotbar) {
                    this.drawHotbar(graphics, minecraft, recorder, hud);
                }
                if (hud.gameMode.isSurvival()) {
                    this.drawBars(graphics, hud);
                    this.drawExperience(graphics, minecraft.font, hud);
                }
            } finally {
                graphics.pose().popPose();
            }
        } catch (Throwable t) {
            if (!this.drawFailed) {
                this.drawFailed = true;
                Host.LOGGER.warn("Dailies: the recorder's HUD could not be drawn. Said once", t);
            }
        }
    }

    // The arithmetic below is the game's own (net.minecraft.client.gui.Gui), so everything lands where the recorder saw it

    private void drawHotbar(GuiGraphics graphics, Minecraft minecraft, Player recorder, HudState hud) {
        int centre = graphics.guiWidth() / 2;
        int bottom = graphics.guiHeight();
        ItemStack offHand = hud.stacks[HudState.OFF_HAND];
        // The off-hand slot sits on the side of the arm that holds it
        boolean offHandLeft = hud.rightHanded;

        RenderSystem.enableBlend();
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, -90.0F);
        graphics.blitSprite(HOTBAR, centre - 91, bottom - 22, 182, 22);
        graphics.blitSprite(HOTBAR_SELECTION, centre - 91 - 1 + hud.selected * 20, bottom - 22 - 1, 24, 23);
        if (!offHand.isEmpty()) {
            if (offHandLeft) {
                graphics.blitSprite(OFF_HAND_LEFT, centre - 91 - 29, bottom - 23, 29, 24);
            } else {
                graphics.blitSprite(OFF_HAND_RIGHT, centre + 91, bottom - 23, 29, 24);
            }
        }
        graphics.pose().popPose();
        RenderSystem.disableBlend();

        int seed = 1;
        int y = bottom - 16 - 3;
        for (int slot = 0; slot < 9; slot++) {
            this.drawStack(graphics, minecraft.font, recorder, hud.stacks[slot], centre - 90 + slot * 20 + 2, y, seed++);
        }
        if (!offHand.isEmpty()) {
            this.drawStack(graphics, minecraft.font, recorder, offHand, offHandLeft ? centre - 91 - 26 : centre + 91 + 10, y, seed);
        }
    }

    private void drawStack(GuiGraphics graphics, Font font, Player recorder, ItemStack stack, int x, int y, int seed) {
        if (!stack.isEmpty()) {
            graphics.renderItem(recorder, stack, x, y, seed);
            graphics.renderItemDecorations(font, stack, x, y);
        }
    }

    private void drawBars(GuiGraphics graphics, HudState hud) {
        int left = graphics.guiWidth() / 2 - 91;
        int right = graphics.guiWidth() / 2 + 91;
        int bottom = graphics.guiHeight();
        int leftHeight = 39;
        int rightHeight = 39;
        RenderSystem.enableBlend();

        // Hearts
        int health = Mth.ceil(hud.health);
        float most = Math.max(hud.maxHealth, health);
        int absorption = Mth.ceil(hud.absorption);
        int rows = Mth.ceil((most + absorption) / 2.0F / 10.0F);
        int rowHeight = Math.max(10 - (rows - 2), 3);
        int containers = Mth.ceil(most / 2.0);
        int extra = Mth.ceil(absorption / 2.0);
        int top = bottom - leftHeight;
        for (int heart = containers + extra - 1; heart >= 0; heart--) {
            int x = left + (heart % 10) * 8;
            int y = top - (heart / 10) * rowHeight;
            graphics.blitSprite(hud.hardcore ? HEART_CONTAINER_HARDCORE : HEART_CONTAINER, x, y, 9, 9);
            int points = heart * 2;
            if (heart >= containers) {
                int absorbed = points - containers * 2;
                if (absorbed < absorption) {
                    boolean half = absorbed + 1 == absorption;
                    graphics.blitSprite(hud.hardcore ? (half ? ABSORBING_HALF_HARDCORE : ABSORBING_FULL_HARDCORE) : (half ? ABSORBING_HALF : ABSORBING_FULL), x, y, 9, 9);
                }
            }
            if (points < health) {
                boolean half = points + 1 == health;
                graphics.blitSprite(hud.hardcore ? (half ? HEART_HALF_HARDCORE : HEART_FULL_HARDCORE) : (half ? HEART_HALF : HEART_FULL), x, y, 9, 9);
            }
        }
        leftHeight += (rows - 1) * rowHeight + 10;

        // Armour
        if (hud.armour > 0) {
            int y = bottom - leftHeight;
            for (int piece = 0; piece < 10; piece++) {
                int x = left + piece * 8;
                int points = piece * 2 + 1;
                graphics.blitSprite(points < hud.armour ? ARMOUR_FULL : points == hud.armour ? ARMOUR_HALF : ARMOUR_EMPTY, x, y, 9, 9);
            }
        }

        // Hunger
        int foodTop = bottom - rightHeight;
        for (int leg = 0; leg < 10; leg++) {
            int x = right - leg * 8 - 9;
            graphics.blitSprite(FOOD_EMPTY, x, foodTop, 9, 9);
            if (leg * 2 + 1 < hud.food) {
                graphics.blitSprite(FOOD_FULL, x, foodTop, 9, 9);
            } else if (leg * 2 + 1 == hud.food) {
                graphics.blitSprite(FOOD_HALF, x, foodTop, 9, 9);
            }
        }
        rightHeight += 10;

        // Air
        int air = Math.min(hud.air, hud.maxAir);
        if (hud.maxAir > 0 && (hud.underWater || air < hud.maxAir)) {
            int y = bottom - rightHeight;
            int whole = Mth.ceil((air - 2) * 10.0 / hud.maxAir);
            int bursting = Mth.ceil(air * 10.0 / hud.maxAir) - whole;
            for (int bubble = 0; bubble < whole + bursting; bubble++) {
                graphics.blitSprite(bubble < whole ? AIR : AIR_BURSTING, right - bubble * 8 - 9, y, 9, 9);
            }
        }
        RenderSystem.disableBlend();
    }

    private void drawExperience(GuiGraphics graphics, Font font, HudState hud) {
        int left = graphics.guiWidth() / 2 - 91;
        int top = graphics.guiHeight() - 32 + 3;
        int filled = Mth.clamp((int) (hud.progress * 183.0F), 0, 182);
        RenderSystem.enableBlend();
        graphics.blitSprite(EXPERIENCE_BACKGROUND, left, top, 182, 5);
        if (filled > 0) {
            graphics.blitSprite(EXPERIENCE_PROGRESS, 182, 5, 0, 0, left, top, filled, 5);
        }
        RenderSystem.disableBlend();
        if (hud.level > 0) {
            String level = Integer.toString(hud.level);
            int x = (graphics.guiWidth() - font.width(level)) / 2;
            int y = graphics.guiHeight() - 31 - 4;
            graphics.drawString(font, level, x + 1, y, 0, false);
            graphics.drawString(font, level, x - 1, y, 0, false);
            graphics.drawString(font, level, x, y + 1, 0, false);
            graphics.drawString(font, level, x, y - 1, 0, false);
            graphics.drawString(font, level, x, y, LEVEL_COLOUR, false);
        }
    }

}
