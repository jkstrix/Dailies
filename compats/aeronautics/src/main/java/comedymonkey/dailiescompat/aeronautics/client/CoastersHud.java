package comedymonkey.dailiescompat.aeronautics.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.simibubi.create.content.equipment.blueprint.BlueprintOverlayRenderer;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import comedymonkey.dailiescompat.aeronautics.state.AeroStates;
import dev.silvergold.simulatedcoasters.client.cart.CoasterCartEditHud;
import dev.silvergold.simulatedcoasters.client.item.RivetPlacementHud;
import dev.silvergold.simulatedcoasters.client.track.BezierHandleDragManager;
import dev.silvergold.simulatedcoasters.client.track.CoasterTrackPlacementOverlayRenderer;
import dev.silvergold.simulatedcoasters.client.track.CoasterTrackPlacementSmoothHud;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Simulated Coasters' rows over play, worked out on the player's own game and drawn by its HUD:
 * <ul>
 * <li>the row of track above the hotbar while a coaster track is being placed, with how many it takes, whether there are
 * enough and whether it fits ({@link #TRACK_ROW});</li>
 * <li>the row of hints above the hotbar ({@link #HINTS}), one of five as Simulated Coasters picks: the key to snap a
 * handle being dragged, the curvature, tilt and lift of a joint being shaped with the wrench, a cart's links as it is
 * edited, the key to keep a placed track's neighbours from being smoothed, and a rivet's placing keys.</li>
 * </ul>
 * The track row is recorded as its numbers and drawn as Simulated Coasters draws it, with Create's textures. The hints are
 * recorded as drawn: at the end of each tick Simulated Coasters' own drawing is run, in its own order, onto a page of the
 * compat's that only notes each line it is handed (its text, place, width and colour), and those lines are drawn again
 * with the game's backdrop. The line Simulated Coasters puts above the hotbar beside them (a cart's link lengths, a
 * rivet's angle) is the status line's ({@link StatusLine}).
 */
final class CoastersHud {

    static final String TRACK_ROW = "track_placement_row";
    static final String HINTS = "hint_row";

    private CoastersHud() {
    }

    // --- The row of track ---

    static byte @Nullable [] captureTrackRow() {
        Minecraft minecraft = Minecraft.getInstance();
        // As CoasterTrackPlacementOverlayRenderer.renderOverlay decides whether to draw
        if (minecraft.options.hideGui || minecraft.screen != null) {
            return null;
        }
        boolean active = Reflect.getStatic(CoasterTrackPlacementOverlayRenderer.class, "active");
        int required = Reflect.getStatic(CoasterTrackPlacementOverlayRenderer.class, "requiredTracks");
        if (!active || required <= 0) {
            return null;
        }
        boolean enough = Reflect.getStatic(CoasterTrackPlacementOverlayRenderer.class, "hasRequiredTracks");
        boolean valid = Reflect.getStatic(CoasterTrackPlacementOverlayRenderer.class, "validPreview");
        return AeroStates.trackRow(new AeroStates.TrackRow(required, enough, valid));
    }

    /** As CoasterTrackPlacementOverlayRenderer.renderOverlay draws it: a slot of up to 64 track for each stack it takes */
    static void drawTrackRow(GuiGraphics graphics, byte[] state) {
        AeroStates.TrackRow row = AeroStates.readTrackRow(state);
        Minecraft minecraft = Minecraft.getInstance();
        ItemStack icon = Texts.stack("simulatedcoasters:coaster_track");
        if (icon.isEmpty()) {
            return;
        }
        List<ItemStack> slots = new ArrayList<>();
        for (int remaining = row.required(); remaining > 0; remaining -= 64) {
            slots.add(icon.copyWithCount(Math.min(64, remaining)));
        }
        int x = (graphics.guiWidth() - 21 * slots.size()) / 2;
        int y = graphics.guiHeight() - 100;
        for (ItemStack stack : slots) {
            RenderSystem.enableBlend();
            (row.enough() ? AllGuiTextures.HOTSLOT_ACTIVE : AllGuiTextures.HOTSLOT).render(graphics, x, y);
            String count = null;
            if (!row.enough()) {
                count = ChatFormatting.GOLD.toString() + stack.getCount();
            } else if (!row.valid()) {
                count = ChatFormatting.GRAY.toString() + stack.getCount();
            }
            BlueprintOverlayRenderer.drawItemStack(graphics, minecraft, x, y, stack, count);
            x += 21;
        }
        RenderSystem.disableBlend();
    }

    // --- The row of hints ---

    static byte @Nullable [] captureHints() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.options.hideGui) {
            return null;
        }
        NotingGraphics page = new NotingGraphics(minecraft);
        // As SimulatedCoastersClient.onRenderGuiLayerPost picks the one row to draw
        if (BezierHandleDragManager.shouldShowBezierSnapControlHint()) {
            BezierHandleDragManager.renderBezierSnapControlHintHud(minecraft, page);
        } else if (BezierHandleDragManager.shouldShowBezierEditAnchorStatusHud(minecraft)) {
            BezierHandleDragManager.renderBezierEditAnchorStatusHud(minecraft, page);
        } else if (CoasterCartEditHud.shouldShow(minecraft)) {
            CoasterCartEditHud.render(minecraft, page);
        } else if (CoasterTrackPlacementSmoothHud.shouldShow()) {
            CoasterTrackPlacementSmoothHud.render(minecraft, page);
        } else if (RivetPlacementHud.shouldShow(minecraft)) {
            RivetPlacementHud.render(minecraft, page);
        }
        return page.lines.isEmpty() ? null : AeroStates.lines(new AeroStates.Lines(page.lines));
    }

    /** Each line as the game draws a line with its backdrop, kept as far from the middle and the foot as it was */
    static void drawHints(GuiGraphics graphics, byte[] state) {
        Font font = Minecraft.getInstance().font;
        for (AeroStates.Line line : AeroStates.readLines(state).lines()) {
            Component text = Texts.read(line.text());
            int x = graphics.guiWidth() / 2 + line.fromMiddle();
            int y = graphics.guiHeight() - line.fromFoot();
            if (line.backdrop()) {
                graphics.drawStringWithBackdrop(font, text, x, y, line.width(), line.colour());
            } else {
                graphics.drawString(font, text, x, y, line.colour());
            }
        }
    }

    /**
     * A page that draws nothing and notes each line handed to it with a backdrop, which is how Simulated Coasters draws all
     * of its hints
     */
    private static final class NotingGraphics extends GuiGraphics {
        final List<AeroStates.Line> lines = new ArrayList<>();

        NotingGraphics(Minecraft minecraft) {
            super(minecraft, minecraft.renderBuffers().bufferSource());
        }

        @Override
        public int drawStringWithBackdrop(Font font, Component text, int x, int y, int width, int colour) {
            this.lines.add(new AeroStates.Line(Texts.json(text), x - this.guiWidth() / 2, this.guiHeight() - y, width, colour, true));
            return x + width;
        }
    }

}
