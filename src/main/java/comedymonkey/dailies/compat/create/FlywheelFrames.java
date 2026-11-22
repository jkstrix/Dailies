package comedymonkey.dailies.compat.create;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.playback.Playback;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Makes every frame of playback, and so every exported frame, show Create's moving parts where they are at that
 * frame's moment (specification REN-2).
 * <p>
 * Flywheel, which draws Create's shafts, cogs and contraptions, moves each of them once per frame to the frame's point
 * between two ticks, and finishes a tick's work before it starts the next, so the ticks an export runs several at a
 * time in one frame are each done in full. But to save time in live play it can also skip far-off things on most
 * frames ("limitUpdates", on by default), letting them catch up every few frames. In a video that is a contraption
 * that stutters, more the further it is from the camera. So while a playback world is open that setting is off, and it
 * is put back as it was when playback ends. Flywheel reads it when it starts on a world, which is why it is changed as
 * each playback world loads.
 * <p>
 * The setting is reached by name in Flywheel's own settings ({@code NeoForgeFlwConfig.INSTANCE.client.limitUpdates})
 * and only changed in memory, never written to Flywheel's settings file. Without Flywheel, or with a version that keeps
 * it elsewhere, one line is logged and nothing else changes.
 */
public final class FlywheelFrames {

    private static final String CONFIG = "dev.engine_room.flywheel.impl.NeoForgeFlwConfig";

    private static boolean looked = false;
    private static @Nullable ModConfigSpec.BooleanValue limitUpdates = null;
    /** What the setting was before playback changed it; null while playback has not changed it */
    private static @Nullable Boolean before = null;

    private FlywheelFrames() {
    }

    public static void init() {
        if (!Host.get().isModLoaded("create") && !Host.get().isModLoaded("flywheel")) {
            return;
        }
        // Before Flywheel starts on the new world, which it does when the first entity joins it or the first tick runs
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, (LevelEvent.Load event) -> {
            if (event.getLevel() instanceof ClientLevel) {
                if (Playback.active()) {
                    everyFrame();
                } else {
                    putBack();
                }
            }
        });
        Playback.onClosing(FlywheelFrames::putBack);
    }

    private static @Nullable ModConfigSpec.BooleanValue setting() {
        if (!looked) {
            looked = true;
            try {
                Object config = Class.forName(CONFIG).getField("INSTANCE").get(null);
                Object client = config.getClass().getField("client").get(config);
                limitUpdates = (ModConfigSpec.BooleanValue) client.getClass().getField("limitUpdates").get(client);
            } catch (Throwable t) {
                Host.LOGGER.warn("Dailies: this version of Flywheel keeps its update limit where Dailies cannot find it, so far-off Create machines may move in steps in playback and exports: {}", t.toString());
            }
        }
        return limitUpdates;
    }

    private static void everyFrame() {
        ModConfigSpec.BooleanValue value = setting();
        if (value == null) {
            return;
        }
        try {
            if (before == null) {
                before = value.get();
            }
            value.set(false);
        } catch (Throwable t) {
            Host.LOGGER.warn("Dailies: Flywheel's update limit could not be switched off for playback: {}", t.toString());
        }
    }

    private static void putBack() {
        ModConfigSpec.BooleanValue value = limitUpdates;
        Boolean was = before;
        if (value == null || was == null) {
            return;
        }
        before = null;
        try {
            value.set(was);
        } catch (Throwable t) {
            Host.LOGGER.warn("Dailies: Flywheel's update limit could not be put back after playback: {}", t.toString());
        }
    }

    /** Whether Flywheel skips far-off things on some frames now; null without Flywheel. For the scripted test */
    public static @Nullable Boolean limiting() {
        ModConfigSpec.BooleanValue value = setting();
        try {
            return value == null ? null : value.get();
        } catch (Throwable t) {
            return null;
        }
    }

}
