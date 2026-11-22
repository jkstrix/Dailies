package comedymonkey.dailiescompat.jade.client;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

/**
 * The compat's plugin for Jade, which Jade finds by itself as it finishes loading, and only loads when Dailies is loaded
 * too. Through Jade's own callbacks around the drawing of its box: first of all before it, to keep Jade's box off the
 * screen in playback; last of all after it (after Jade's own line of a block being broken), to keep the box as drawn.
 * Both stand down unless the compat registered with Dailies.
 */
@WailaPlugin("dailies")
public final class JadeHooks implements IWailaPlugin {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Before every other callback, and after every other, Jade's own included (it sorts them by this, lowest first) */
    private static final int FIRST = Integer.MIN_VALUE + 1;
    private static final int LAST = Integer.MAX_VALUE - 1;
    private static boolean failed = false;

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.addBeforeRenderCallback(FIRST, (root, animation, graphics, accessor) -> JadeDailies.registered() && TooltipBox.beforeRender());
        registration.addAfterRenderCallback(LAST, (root, animation, graphics, accessor) -> {
            if (!JadeDailies.registered() || failed) {
                return;
            }
            try {
                TooltipBox.afterRender(root, animation);
            } catch (RuntimeException | LinkageError e) {
                // Never Jade's drawing broken by the compat: the box goes unrecorded from here on
                failed = true;
                LOGGER.warn("Dailies Jade compat: Jade's box could not be kept as it was drawn, so recordings leave it out from now on", e);
            }
        });
    }

}
