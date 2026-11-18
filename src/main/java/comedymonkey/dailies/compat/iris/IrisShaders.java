package comedymonkey.dailies.compat.iris;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.edit.Looks;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;

/**
 * The "shaders" look: a scene can be shown, and exported, with Iris's shader pack on or off whatever the viewer's own
 * setting is. Uses Iris's public API ({@code IrisApi.getInstance().getConfig()}, with {@code areShadersEnabled} and
 * {@code setShadersEnabledAndApply}), found by name once so nothing is compiled against Iris; without Iris the look
 * does nothing and the editor shows it unusable.
 * <p>
 * Iris keeps the setting in its own settings file, so the viewer's setting is put back when the scene leaves it to the
 * game again, when the recording closes (the next frame after), and when the game closes, which writes it into Iris's
 * file without reloading anything. {@link ShaderChoice} decides when.
 */
public final class IrisShaders {

    private static final String MOD = "iris";

    private static final ShaderChoice CHOICE = new ShaderChoice();

    private static boolean looked = false;
    private static boolean failed = false;
    private static @Nullable Object config;
    private static @Nullable Method enabled, setAndApply;

    private IrisShaders() {
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener((GameShuttingDownEvent event) -> closing());
    }

    /** Whether Iris is here with the API this look uses */
    public static boolean available() {
        return ready();
    }

    /**
     * Once per drawn frame, and when an export starts: the scene's choice, or null when no scene is being shown.
     * Switches Iris only when the choice differs from what it has. Client thread, between frames
     */
    public static void apply(@Nullable Looks.Shaders wanted) {
        if ((wanted == null || wanted == Looks.Shaders.AS_GAME) && CHOICE.own() == null) {
            // Nothing asked for and nothing to put back: the usual case costs this one comparison
            return;
        }
        if (!ready()) {
            return;
        }
        try {
            Boolean to = CHOICE.step(wanted, (Boolean) enabled.invoke(config));
            if (to != null) {
                Host.LOGGER.info("Dailies: turning Iris's shaders {} {}", to ? "on" : "off", wanted == null || wanted == Looks.Shaders.AS_GAME
                    ? "again, as they were before the scene changed them" : "for the scene being shown");
                setAndApply.invoke(config, to);
            }
        } catch (Throwable t) {
            failed = true;
            Host.LOGGER.warn("Dailies: Iris's shaders could not be switched, so the shaders look does nothing from here. Said once", t);
        }
    }

    /** The game is closing: the viewer's own setting goes back into Iris's file, with nothing reloaded */
    private static void closing() {
        Boolean own = CHOICE.own();
        if (own == null || failed) {
            return;
        }
        try {
            Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
            Object settings = iris.getMethod("getIrisConfig").invoke(null);
            settings.getClass().getMethod("setShadersEnabled", boolean.class).invoke(settings, own);
            settings.getClass().getMethod("save").invoke(settings);
        } catch (Throwable t) {
            Host.LOGGER.warn("Dailies: Iris's shaders could not be set back to {} as the game closed; they stay as the last scene had them", own ? "on" : "off", t);
        }
    }

    private static boolean ready() {
        if (!looked) {
            looked = true;
            if (Host.get().isModLoaded(MOD)) {
                try {
                    Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                    Object instance = api.getMethod("getInstance").invoke(null);
                    config = api.getMethod("getConfig").invoke(instance);
                    Class<?> settings = Class.forName("net.irisshaders.iris.api.v0.IrisApiConfig");
                    enabled = settings.getMethod("areShadersEnabled");
                    setAndApply = settings.getMethod("setShadersEnabledAndApply", boolean.class);
                } catch (Throwable t) {
                    failed = true;
                    Host.LOGGER.warn("Dailies: this version of Iris does not have the API the shaders look uses, so it does nothing. Said once", t);
                }
            } else {
                failed = true;
            }
        }
        return !failed;
    }

}
