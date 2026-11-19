package comedymonkey.dailiescompat.extras.client;

import com.mojang.blaze3d.systems.RenderSystem;
import comedymonkey.dailies.api.compat.Claims;
import comedymonkey.dailiescompat.extras.state.ExtrasStates;
import net.mehvahdjukaar.vista.client.ViewFinderController;
import net.mehvahdjukaar.vista.client.ui.SignalProjectorScreen;
import net.mehvahdjukaar.vista.client.ui.ViewFinderHud;
import net.mehvahdjukaar.vista.common.projector.SignalProjectorBlockEntity;
import net.mehvahdjukaar.vista.common.view_finder.ViewFinderAccess;
import net.mehvahdjukaar.vista.common.view_finder.ViewFinderBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Vista's two play-time parts:
 * <ul>
 * <li>the viewfinder looked through ({@link #VIEWFINDER}): Vista's scope over the screen and its zoom bar above the
 * hotbar, shown while the player has taken a viewfinder over. Recorded as the zoom, the lock and how far the scope had
 * opened, and drawn as Vista's layer draws them (its lock is read from the viewfinder taken over, which playback has
 * none of). The view itself is the viewfinder's camera on the recorder's game; playback rides along at the recorder's
 * eyes, so the scope is drawn over what the recorder's body saw;</li>
 * <li>the signal projector's screen ({@link #PROJECTOR}), a screen only the player's game knows, where the address of a
 * feed is typed: recorded as the address as typed, the cursor and the selection, and drawn with Vista's own screen on a
 * projector of the compat's own in no world, which sends nothing as it closes.</li>
 * </ul>
 */
final class VistaParts {

    static final String VIEWFINDER = "viewfinder";
    static final String PROJECTOR = "signal_projector";

    /** Typing is written at most five times a second */
    private static final int TYPING_TICKS = 4;

    private static final ResourceLocation BAR = ResourceLocation.fromNamespaceAndPath("vista", "hud/bar");
    private static final ResourceLocation INDICATOR = ResourceLocation.fromNamespaceAndPath("vista", "hud/indicator");
    private static final ResourceLocation LOCKED = ResourceLocation.fromNamespaceAndPath("vista", "hud/lock");
    private static final ResourceLocation SCOPE = ResourceLocation.fromNamespaceAndPath("vista", "textures/gui/viewfinder_scope.png");
    private static final ResourceLocation PROJECTOR_BLOCK = ResourceLocation.fromNamespaceAndPath("vista", "wave_collector");

    private VistaParts() {
    }

    static void claim(Claims claims) {
        claims.overlay(VIEWFINDER, Parts.overlay(VistaParts::captureViewfinder, VistaParts::drawViewfinder, 1));
        claims.screen(PROJECTOR, SignalProjectorScreen.class, Parts.screen(VistaParts::captureProjector, VistaParts::buildProjector, TYPING_TICKS,
            screen -> null));
    }

    // --- The viewfinder ---

    static byte @Nullable [] captureViewfinder() {
        Minecraft minecraft = Minecraft.getInstance();
        ViewFinderAccess access = ViewFinderController.getAccess();
        if (access == null || minecraft.options.hideGui) {
            return null;
        }
        ViewFinderBlockEntity viewfinder = access.getInternalTile();
        float scope = Reflect.get(ViewFinderHud.INSTANCE, "scopeScale");
        return ExtrasStates.viewfinder(new ExtrasStates.Viewfinder(viewfinder.getZoomLevel(), viewfinder.isLocked(), scope));
    }

    /** As ViewFinderHud draws the scope and the bar, with the recorded zoom and lock */
    static void drawViewfinder(GuiGraphics graphics, byte[] state, float partialTick) {
        ExtrasStates.Viewfinder viewfinder = ExtrasStates.readViewfinder(state);
        ViewFinderHud.INSTANCE.setupOverlayRenderState();
        try {
            scope(graphics, viewfinder.scope());
            bar(graphics, graphics.guiWidth(), graphics.guiHeight(), viewfinder.zoom(), viewfinder.locked());
        } finally {
            // Vista leaves the depth test off for the layers after it; what Dailies draws after this wants it back
            RenderSystem.enableDepthTest();
        }
    }

    private static void scope(GuiGraphics graphics, float scopeScale) {
        float side = (float) Math.min(graphics.guiWidth(), graphics.guiHeight());
        float scale = Math.min(graphics.guiWidth() / side, graphics.guiHeight() / side) * scopeScale;
        int width = Mth.floor(side * scale);
        int height = Mth.floor(side * scale);
        int left = (graphics.guiWidth() - width) / 2;
        int top = (graphics.guiHeight() - height) / 2;
        int right = left + width;
        int bottom = top + height;
        RenderSystem.enableBlend();
        graphics.blit(SCOPE, left, top, -90, 0.0F, 0.0F, width, height, width, height);
        RenderSystem.disableBlend();
        graphics.fill(RenderType.guiOverlay(), 0, bottom, graphics.guiWidth(), graphics.guiHeight(), -90, 0xFF000000);
        graphics.fill(RenderType.guiOverlay(), 0, 0, graphics.guiWidth(), top, -90, 0xFF000000);
        graphics.fill(RenderType.guiOverlay(), 0, top, left, bottom, -90, 0xFF000000);
        graphics.fill(RenderType.guiOverlay(), right, top, graphics.guiWidth(), bottom, -90, 0xFF000000);
    }

    private static void bar(GuiGraphics graphics, int screenWidth, int screenHeight, int zoom, boolean locked) {
        int barLeft = screenWidth / 2 - 91;
        int barTop = screenHeight - 32 + 3;
        graphics.blitSprite(BAR, barLeft + 1, barTop - 1 + 4, 180, 8);
        int zoomOffset = (zoom - 1) * 4;
        if (locked) {
            graphics.blitSprite(LOCKED, barLeft + zoomOffset, barTop + 1, 9, 11);
        } else {
            graphics.blitSprite(INDICATOR, barLeft + zoomOffset, barTop + 5, 11, 7);
        }
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        Font font = Minecraft.getInstance().font;
        String number = String.valueOf(zoom);
        int x = (screenWidth - font.width(number)) / 2;
        int y = screenHeight - 31 - 4;
        graphics.drawString(font, number, x + 1, y, 0, false);
        graphics.drawString(font, number, x - 1, y, 0, false);
        graphics.drawString(font, number, x, y + 1, 0, false);
        graphics.drawString(font, number, x, y - 1, 0, false);
        graphics.drawString(font, number, x, y, 0xFF8000, false);
    }

    // --- The signal projector ---

    static byte @Nullable [] captureProjector(Screen screen) {
        if (!(screen instanceof SignalProjectorScreen)) {
            return null;
        }
        SignalProjectorBlockEntity projector = Reflect.get(screen, "tile");
        EditBox box = Reflect.get(screen, "editBox");
        if (projector == null || box == null) {
            return null;
        }
        int selection = Reflect.get(box, "highlightPos");
        return ExtrasStates.projector(new ExtrasStates.Projector(projector.getBlockPos().asLong(), Texts.block(projector.getBlockState()), box.getValue(),
            box.getCursorPosition(), selection));
    }

    static @Nullable Screen buildProjector(byte[] data) {
        ExtrasStates.Projector recorded = ExtrasStates.readProjector(data);
        BlockState block = Texts.block(recorded.block());
        if (block == null) {
            Block projector = BuiltInRegistries.BLOCK.get(PROJECTOR_BLOCK);
            block = projector.defaultBlockState();
        }
        return new RecordedProjector(new SignalProjectorBlockEntity(BlockPos.of(recorded.pos()), block), recorded);
    }

    /** Vista's screen on a projector in no world, with the address as typed; it sends nothing as it closes */
    static final class RecordedProjector extends SignalProjectorScreen {
        private final ExtrasStates.Projector recorded;

        RecordedProjector(SignalProjectorBlockEntity projector, ExtrasStates.Projector recorded) {
            super(projector);
            this.recorded = recorded;
        }

        @Override
        public void init() {
            super.init();
            EditBox box = Reflect.get(this, "editBox");
            box.setValue(this.recorded.url());
            box.setCursorPosition(this.recorded.cursor());
            box.setHighlightPos(this.recorded.selection());
        }

        @Override
        public void tick() {
        }

        @Override
        public void removed() {
            // Vista's screen sends the address to the server as it closes; this one never does
        }

        @Override
        public void onClose() {
        }
    }

}
