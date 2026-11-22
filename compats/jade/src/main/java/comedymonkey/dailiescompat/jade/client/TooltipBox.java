package comedymonkey.dailiescompat.jade.client;

import com.mojang.logging.LogUtils;
import comedymonkey.dailies.api.DailiesApi;
import comedymonkey.dailies.api.compat.DailiesCompats;
import comedymonkey.dailiescompat.jade.state.JadeStates;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3x2fStack;
import org.slf4j.Logger;
import snownee.jade.api.config.IWailaConfig;
import snownee.jade.api.theme.IThemeHelper;
import snownee.jade.api.theme.Theme;
import snownee.jade.api.ui.BoxElement;
import snownee.jade.api.ui.TooltipAnimation;
import snownee.jade.gui.BaseOptionsScreen;
import snownee.jade.impl.ui.BoxElementImpl;
import snownee.jade.overlay.OverlayRenderer;
import snownee.jade.util.JadeClientCodecs;
import snownee.jade.util.JadeGuiGraphics;

import java.nio.charset.StandardCharsets;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Jade's box over play, beside the crosshair: what the player looks at. Jade works out its lines from the server's data
 * (a chest's contents, a furnace's progress) and the player's own settings, so the box recorded is the one Jade drew:
 * just after Jade has drawn it each frame, the compat keeps the box, where it was drawn, its scale and fade, the theme and
 * the settings it was drawn with (the line of a block being broken, which Jade draws along its foot, is the box's own and
 * goes with it). At the end of each
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
    private static boolean overrideUnread = false;

    private TooltipBox() {
    }

    /** The box Jade drew in one frame, with what it was drawn with */
    private record Drawn(BoxElementImpl root, int x, int y, int width, int height, float scale, float fade, int guiWidth, int guiHeight,
                         float anchorX, float anchorY, float opacity, int iconMode, Theme theme) {
    }

    /** A state built again into Jade's own elements, with the theme it is drawn in */
    private record Built(JadeStates.Box box, BoxElementImpl root, Theme theme) {
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
    static void afterRender(BoxElement root, TooltipAnimation animation) {
        if (!(root instanceof BoxElementImpl box) || DailiesApi.isPlayback()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gui.screen() instanceof BaseOptionsScreen) {
            // Jade's settings show a box to preview them: settings are not play
            return;
        }
        IWailaConfig.Overlay overlay = IWailaConfig.get().overlay();
        IWailaConfig.Accessibility accessibility = IWailaConfig.get().accessibility();
        lastDrawn = new Drawn(box, (int) animation.rect.getX(), (int) animation.rect.getY(), (int) animation.rect.getWidth(),
            (int) animation.rect.getHeight(), animation.scale, animation.alpha, minecraft.getWindow().getGuiScaledWidth(),
            minecraft.getWindow().getGuiScaledHeight(), accessibility.tryFlip(overlay.getOverlayPosX()), 1.0F - overlay.getOverlayPosY(),
            overlay.getAlpha(), overlay.getIconMode().ordinal(), IThemeHelper.get().theme());
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
        Identifier themeId = drawn.theme().id;
        // This Jade has no square corners, and no line of a block being broken of its own: the line is the main box's own
        byte[] state = JadeStates.box(new JadeStates.Box(drawn.x(), drawn.y(), drawn.width(), drawn.height(), drawn.scale(), drawn.fade(),
            drawn.guiWidth(), drawn.guiHeight(), drawn.anchorX(), drawn.anchorY(), false, drawn.opacity(), drawn.iconMode(),
            themeId == null ? "" : themeId.toString(), handle, 0.0F, 0.0F, true, root));
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
            json = THEMES.computeIfAbsent(theme, each -> Json.write(JadeClientCodecs.THEME.codec(), each).getBytes(StandardCharsets.UTF_8));
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

    static void draw(GuiGraphicsExtractor graphics, byte[] state, float partialTick) {
        IWailaConfig.Overlay overlay = IWailaConfig.get().overlay();
        // Asked for first, so Jade has picked its theme and there is one to put back
        overlay.getTheme();
        Built built = built(state, overlay);
        JadeStates.Box box = built.box();
        BoxElementImpl root = built.root();
        // The recorder's theme and settings, put in place just while the box is drawn, as Jade reads them as it draws
        TooltipAnimation animation = OverlayRenderer.animation;
        float fade = animation.alpha;
        float showHide = animation.showHideAlpha;
        Theme override = themeOverride();
        Theme configTheme = configTheme(overlay);
        float opacity = overlay.getAlpha();
        Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix();
        try {
            animation.alpha = box.fade();
            animation.showHideAlpha = box.fade();
            IThemeHelper.get().setThemeOverride(built.theme());
            setConfigTheme(overlay, built.theme());
            overlay.setAlpha(box.opacity());
            // As OverlayRenderer.renderOverlay draws it, where the recorder's chasing left it, kept to the same anchor
            pose.translate((float) box.xOn(graphics.guiWidth()), (float) box.yOn(graphics.guiHeight()));
            float scale = box.scale() > 0.0F ? box.scale() : 1.0F;
            if (scale != 1.0F) {
                pose.scale(scale);
            }
            // The size Jade's chasing had the box at, as BoxElementImpl.updateRect gives it
            Reflect.set(root, "width", (int) (box.width() / scale));
            Reflect.set(root, "height", (int) (box.height() / scale));
            ((JadeGuiGraphics) graphics).jade$setIgnoreScissorTest(true);
            try {
                root.extractRenderState(graphics, -1, -1, partialTick);
            } finally {
                ((JadeGuiGraphics) graphics).jade$setIgnoreScissorTest(false);
            }
        } finally {
            pose.popMatrix();
            animation.alpha = fade;
            animation.showHideAlpha = showHide;
            IThemeHelper.get().setThemeOverride(override);
            setConfigTheme(overlay, configTheme);
            overlay.setAlpha(opacity);
        }
    }

    private static Built built(byte[] state, IWailaConfig.Overlay overlay) {
        Built made = built;
        if (made != null && builtFrom == state) {
            return made;
        }
        JadeStates.Box box = JadeStates.readBox(state);
        Theme theme = theme(box);
        // The recorded theme's styles are what the box falls back on for a style the recorder's game could not write; and
        // the recorder's icon mode, as Jade lays the box out beside its icon as it makes it
        Theme override = themeOverride();
        IWailaConfig.IconMode iconMode = overlay.getIconMode();
        IThemeHelper.get().setThemeOverride(theme);
        try {
            if (box.iconMode() >= 0 && box.iconMode() < ICON_MODES.length) {
                overlay.setIconMode(ICON_MODES[box.iconMode()]);
            }
            BoxElementImpl root = Elements.root(box.root(), theme.tooltipStyle);
            if (root == null) {
                throw new IllegalArgumentException("the recorded box is not a box");
            }
            made = new Built(box, root, theme);
        } finally {
            IThemeHelper.get().setThemeOverride(override);
            overlay.setIconMode(iconMode);
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
                    theme = Json.read(JadeClientCodecs.THEME.codec(), new String(stored, StandardCharsets.UTF_8));
                    if (theme != null) {
                        theme.id = Identifier.tryParse(box.theme());
                        READ_THEMES.put(box.themeStored(), theme);
                    }
                }
            }
        }
        if (theme == null) {
            Identifier id = Identifier.tryParse(box.theme());
            theme = id != null ? IThemeHelper.get().getTheme(id) : IThemeHelper.get().theme();
        }
        return theme;
    }

    /** The theme Jade's settings hold, which Jade reads for its text's shadow; null when this Jade keeps it elsewhere */
    private static @Nullable Theme configTheme(IWailaConfig.Overlay overlay) {
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

    private static void setConfigTheme(IWailaConfig.Overlay overlay, @Nullable Theme theme) {
        if (!themeFieldMissing && theme != null) {
            Reflect.set(overlay, "activeThemeInstance", theme);
        }
    }

    /** The theme Jade draws in instead of its own just now (its settings' preview), or null; null too when unreadable */
    private static @Nullable Theme themeOverride() {
        if (overrideUnread) {
            return null;
        }
        try {
            return Reflect.get(IThemeHelper.get(), "themeOverride");
        } catch (RuntimeException renamed) {
            overrideUnread = true;
            LOGGER.warn("Dailies Jade compat: Jade's theme override could not be read, so playback leaves Jade with no override after it draws", renamed);
            return null;
        }
    }

}
