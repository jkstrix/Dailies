package comedymonkey.dailiescompat.jade.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import comedymonkey.dailies.api.DailiesApi;
import comedymonkey.dailies.api.compat.DailiesCompats;
import comedymonkey.dailiescompat.jade.state.JadeStates;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import snownee.jade.JadeClient;
import snownee.jade.api.config.IWailaConfig;
import snownee.jade.api.theme.IThemeHelper;
import snownee.jade.api.theme.Theme;
import snownee.jade.api.ui.BoxStyle;
import snownee.jade.api.ui.ColorPalette;
import snownee.jade.api.ui.IBoxElement;
import snownee.jade.api.ui.IElement;
import snownee.jade.api.ui.ScreenDirection;
import snownee.jade.api.ui.TooltipRect;
import snownee.jade.gui.BaseOptionsScreen;
import snownee.jade.impl.theme.ThemeHelper;
import snownee.jade.impl.ui.BoxElement;
import snownee.jade.overlay.DisplayHelper;
import snownee.jade.overlay.OverlayRenderer;
import snownee.jade.util.JadeCodecs;

import java.nio.charset.StandardCharsets;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Jade's box over play, beside the crosshair: what the player looks at. Jade works out its lines from the server's data
 * (a chest's contents, a furnace's progress) and the player's own settings, so the box recorded is the one Jade drew:
 * just after Jade has drawn it each frame, the compat keeps the box, where it was drawn, its scale and fade, the theme and
 * the settings it was drawn with, and the line of a block being broken that Jade draws along its foot. At the end of each
 * recorded tick the last box drawn is written down, every element of it (see {@link Elements}). In playback the box is
 * built again from Jade's own elements and drawn by Jade, with the recorder's theme and settings put in place just
 * while it is drawn; and Jade's own box, which it would work out from the playback world and its camera, is never shown.
 */
final class TooltipBox {

    static final String NAME = "tooltip";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final IWailaConfig.IconMode[] ICON_MODES = IWailaConfig.IconMode.values();

    /** The box as Jade last drew it on the recorder's game, and whether it has drawn since the last tick was recorded */
    private static @Nullable Drawn lastDrawn = null;
    private static boolean drawnSinceTick = false;
    private static int ticksUndrawn = 0;
    /** What the last box written was made from, and its bytes: Jade keeps its box from tick to tick while it waits on the server */
    private static @Nullable Drawn writtenFrom = null;
    private static byte @Nullable [] written = null;
    private static String lastHandle = "";
    /** Each theme as JSON, made once: stored once in the recording on every write, which costs nothing after the first */
    private static final Map<Theme, byte[]> THEMES = new IdentityHashMap<>();
    private static boolean themesUnstored = false;

    /** What playback built last, from which state: the same state is drawn every frame until the next arrives */
    private static byte @Nullable [] builtFrom = null;
    private static @Nullable Built built = null;
    /** Themes read from the recording, by their handle */
    private static final Map<String, Theme> READ_THEMES = new java.util.HashMap<>();
    private static boolean themeFieldMissing = false;
    private static boolean breakingUnread = false;

    private TooltipBox() {
    }

    /** The box Jade drew in one frame, with what it was drawn with */
    private record Drawn(BoxElement root, int x, int y, int width, int height, float scale, float fade, int guiWidth, int guiHeight, float anchorX,
                         float anchorY, boolean square, float opacity, int iconMode, Theme theme, float breakingFade, float breaking,
                         boolean canHarvest) {
    }

    /** A state built again into Jade's own elements, with the theme it is drawn in */
    private record Built(JadeStates.Box box, BoxElement root, Theme theme) {
    }

    // --- Jade's own drawing ---

    /**
     * Before Jade draws its box: in playback the box Jade has worked out is from the playback world and the watcher's
     * camera, never the recorder's, so it is not drawn; the recorded one is drawn by Dailies' overlay layer instead.
     *
     * @return whether Jade is to leave its box undrawn
     */
    static boolean beforeRender() {
        return DailiesApi.isPlayback();
    }

    /** Just after Jade has drawn its box on the player's game (and the line of a block being broken along its foot) */
    static void afterRender(IBoxElement root, TooltipRect rect) {
        if (!(root instanceof BoxElement box) || DailiesApi.isPlayback()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof BaseOptionsScreen) {
            // Jade's settings show a box to preview them: settings are not play
            return;
        }
        IWailaConfig.IConfigOverlay overlay = IWailaConfig.get().getOverlay();
        float breakingFade = 0.0F;
        float breaking = 0.0F;
        boolean canHarvest = true;
        if (!breakingUnread) {
            try {
                breakingFade = Reflect.<Float>getStatic(JadeClient.class, "progressAlpha");
                breaking = Reflect.<Float>getStatic(JadeClient.class, "savedProgress");
                canHarvest = Reflect.<Boolean>getStatic(JadeClient.class, "canHarvest");
            } catch (RuntimeException renamed) {
                breakingUnread = true;
                LOGGER.warn("Dailies Jade compat: the line of a block being broken could not be read from this Jade, so recordings leave it out", renamed);
            }
        }
        lastDrawn = new Drawn(box, rect.rect.getX(), rect.rect.getY(), rect.rect.getWidth(), rect.rect.getHeight(), rect.scale, OverlayRenderer.alpha,
            minecraft.getWindow().getGuiScaledWidth(), minecraft.getWindow().getGuiScaledHeight(), overlay.tryFlip(overlay.getOverlayPosX()),
            1.0F - overlay.getOverlayPosY(), overlay.getSquare(), overlay.getAlpha(), overlay.getIconMode().ordinal(), IThemeHelper.get().theme(),
            breakingFade, breaking, canHarvest);
        drawnSinceTick = true;
    }

    // --- Recording ---

    static byte @Nullable [] capture() {
        // A box that stops being drawn (nothing looked at, the HUD hidden, a world left) stops showing: a frame or two may
        // pass between ticks
        if (drawnSinceTick) {
            drawnSinceTick = false;
            ticksUndrawn = 0;
        } else if (++ticksUndrawn > 2) {
            lastDrawn = null;
        }
        Drawn drawn = lastDrawn;
        if (drawn == null) {
            return null;
        }
        String handle = storeTheme(drawn.theme());
        if (drawn.equals(writtenFrom) && written != null && handle.equals(lastHandle)) {
            return written;
        }
        JadeStates.Element root = Elements.write(drawn.root(), 0);
        ResourceLocation themeId = drawn.theme().id;
        byte[] state = JadeStates.box(new JadeStates.Box(drawn.x(), drawn.y(), drawn.width(), drawn.height(), drawn.scale(), drawn.fade(),
            drawn.guiWidth(), drawn.guiHeight(), drawn.anchorX(), drawn.anchorY(), drawn.square(), drawn.opacity(), drawn.iconMode(),
            themeId == null ? "" : themeId.toString(), handle, drawn.breakingFade(), drawn.breaking(), drawn.canHarvest(), root));
        writtenFrom = drawn;
        written = state;
        lastHandle = handle;
        return state;
    }

    /** The theme stored once in the recording being made, by its handle; empty when it cannot be */
    private static String storeTheme(Theme theme) {
        if (themesUnstored) {
            return "";
        }
        byte[] json;
        synchronized (THEMES) {
            json = THEMES.computeIfAbsent(theme, each -> Json.write(JadeCodecs.THEME, each).getBytes(StandardCharsets.UTF_8));
        }
        if (json.length == 0) {
            return "";
        }
        try {
            return DailiesCompats.storeOnce(json);
        } catch (LinkageError olderDailies) {
            // A Dailies from before storeOnce: playback finds the theme by its id instead
            themesUnstored = true;
            return "";
        }
    }

    // --- Playback ---

    static void draw(GuiGraphics graphics, byte[] state) {
        Built built = built(state);
        JadeStates.Box box = built.box();
        BoxElement root = built.root();
        IWailaConfig.IConfigOverlay overlay = IWailaConfig.get().getOverlay();
        // The recorder's theme and settings, put in place just while the box is drawn, as Jade reads them as it draws
        float fade = OverlayRenderer.alpha;
        Theme theme = ThemeHelper.theme.getValue();
        // Asked for first, so Jade has picked its theme and there is one to put back
        overlay.getTheme();
        Theme configTheme = configTheme(overlay);
        boolean square = overlay.getSquare();
        float opacity = overlay.getAlpha();
        IWailaConfig.IconMode iconMode = overlay.getIconMode();
        PoseStack pose = graphics.pose();
        pose.pushPose();
        try {
            OverlayRenderer.alpha = box.fade();
            ThemeHelper.theme.setValue(built.theme());
            setConfigTheme(overlay, built.theme());
            overlay.setSquare(box.square());
            overlay.setAlpha(box.opacity());
            if (box.iconMode() >= 0 && box.iconMode() < ICON_MODES.length) {
                overlay.setIconMode(ICON_MODES[box.iconMode()]);
            }
            // As OverlayRenderer.renderOverlay draws it, where the recorder's chasing left it, kept to the same anchor
            pose.translate((float) box.xOn(graphics.guiWidth()), (float) box.yOn(graphics.guiHeight()), 1.0F);
            float scale = box.scale() > 0.0F ? box.scale() : 1.0F;
            if (scale != 1.0F) {
                pose.scale(scale, scale, 1.0F);
            }
            RenderSystem.enableBlend();
            float maxWidth = box.width() / scale;
            float maxHeight = box.height() / scale;
            if (root.getStyle().hasRoundCorner()) {
                maxWidth -= 2.0F;
                maxHeight -= 2.0F;
            }
            root.render(graphics, 0.0F, 0.0F, maxWidth, maxHeight);
            drawBreaking(graphics, box, root, built.theme());
            RenderSystem.disableBlend();
            RenderSystem.disableDepthTest();
        } finally {
            pose.popPose();
            OverlayRenderer.alpha = fade;
            ThemeHelper.theme.setValue(theme);
            setConfigTheme(overlay, configTheme);
            overlay.setSquare(square);
            overlay.setAlpha(opacity);
            overlay.setIconMode(iconMode);
        }
    }

    /** The line of a block being broken, along the box's foot, as JadeClient.drawBreakingProgress draws it */
    private static void drawBreaking(GuiGraphics graphics, JadeStates.Box box, IBoxElement root, Theme theme) {
        if (box.breakingFade() <= 0.0F) {
            return;
        }
        ColorPalette colors = theme.tooltipStyle.boxProgressColors;
        int color = box.canHarvest() ? colors.normal() : colors.failure();
        float top = root.getCachedSize().y;
        float width = root.getCachedSize().x;
        if (!box.square() && theme.tooltipStyle instanceof BoxStyle.GradientBorder) {
            top++;
        }
        color = IWailaConfig.IConfigOverlay.applyAlpha(color, box.breakingFade());
        float up = theme.tooltipStyle.boxProgressOffset(ScreenDirection.UP);
        float right = theme.tooltipStyle.boxProgressOffset(ScreenDirection.RIGHT);
        float down = theme.tooltipStyle.boxProgressOffset(ScreenDirection.DOWN);
        float left = theme.tooltipStyle.boxProgressOffset(ScreenDirection.LEFT);
        width += right - left;
        DisplayHelper.fill(graphics, left, top - 1.0F + up, left + width * box.breaking(), top + down, color);
    }

    private static Built built(byte[] state) {
        Built made = built;
        if (made != null && builtFrom == state) {
            return made;
        }
        JadeStates.Box box = JadeStates.readBox(state);
        Theme theme = theme(box);
        // The recorded theme's styles are what the box falls back on for a style the recorder's game could not write
        Theme current = ThemeHelper.theme.getValue();
        ThemeHelper.theme.setValue(theme);
        try {
            IElement root = Elements.build(box.root(), theme.tooltipStyle);
            if (!(root instanceof BoxElement rootBox)) {
                throw new IllegalArgumentException("the recorded box is not a box");
            }
            made = new Built(box, rootBox, theme);
        } finally {
            ThemeHelper.theme.setValue(current);
        }
        built = made;
        builtFrom = state;
        return made;
    }

    /** The recorder's theme: as the recording stored it, else this game's theme of that id, else this game's own */
    private static Theme theme(JadeStates.Box box) {
        Theme theme = null;
        if (!box.themeStored().isEmpty()) {
            theme = READ_THEMES.get(box.themeStored());
            if (theme == null) {
                byte[] stored = null;
                try {
                    stored = DailiesCompats.stored(box.themeStored());
                } catch (LinkageError olderDailies) {
                    // Found by its id below
                }
                if (stored != null) {
                    theme = Json.read(JadeCodecs.THEME, new String(stored, StandardCharsets.UTF_8));
                    if (theme != null) {
                        theme.id = ResourceLocation.tryParse(box.theme());
                        READ_THEMES.put(box.themeStored(), theme);
                    }
                }
            }
        }
        if (theme == null) {
            ResourceLocation id = ResourceLocation.tryParse(box.theme());
            theme = id != null ? IThemeHelper.get().getTheme(id) : IThemeHelper.get().theme();
        }
        return theme;
    }

    /** The theme Jade's settings hold, which Jade reads for its text's shadow; null when this Jade keeps it elsewhere */
    private static @Nullable Theme configTheme(IWailaConfig.IConfigOverlay overlay) {
        if (themeFieldMissing) {
            return null;
        }
        try {
            return Reflect.get(overlay, "activeThemeInstance");
        } catch (RuntimeException renamed) {
            themeFieldMissing = true;
            LOGGER.warn("Dailies Jade compat: Jade's settings keep their theme somewhere this compat does not know, so text is drawn with this game's shadow", renamed);
            return null;
        }
    }

    private static void setConfigTheme(IWailaConfig.IConfigOverlay overlay, @Nullable Theme theme) {
        if (!themeFieldMissing && theme != null) {
            Reflect.set(overlay, "activeThemeInstance", theme);
        }
    }

}
