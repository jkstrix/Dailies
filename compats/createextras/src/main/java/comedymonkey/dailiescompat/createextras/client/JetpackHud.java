package comedymonkey.dailiescompat.createextras.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.LayeredDraw;
import org.jetbrains.annotations.Nullable;

/**
 * Create Jetpack's one overlay: the controls beside the hotbar while a jetpack is worn (which keys fly, hover and are on,
 * and the air left), drawn by its own HUD layer. Recorded as drawn ({@link Page}): its layer is run onto a page at the end
 * of each recorded tick, so the air, the keys held and the mode are the recorder's, never the watcher's. The layer is found
 * by name (the mod is written in Kotlin, on a library of its own), so the compat needs nothing of it to build. Its
 * flight's sounds and particles are the world's; its settings are not play. Create Jetpack Curios, which lets a jetpack be
 * worn in a curios slot, draws nothing of its own.
 */
final class JetpackHud {

    static final String NAME = "jetpack_controls";

    private static final String LAYER = "com.possible_triangle.create_jetpack.client.ControlsDisplay";
    private static @Nullable LayeredDraw.Layer layer = null;

    private JetpackHud() {
    }

    private static LayeredDraw.Layer layer() {
        LayeredDraw.Layer found = layer;
        if (found == null) {
            Class<?> type = Reflect.find(LAYER);
            if (type == null) {
                throw new IllegalStateException("Create Jetpack has no " + LAYER);
            }
            found = Reflect.getStatic(type, "INSTANCE");
            layer = found;
        }
        return found;
    }

    static byte @Nullable [] capture() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || minecraft.player == null) {
            return null;
        }
        Page page = new Page();
        layer().render(page, minecraft.getTimer());
        return page.state();
    }

}
