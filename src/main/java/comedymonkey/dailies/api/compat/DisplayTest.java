package comedymonkey.dailies.api.compat;

import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.function.Consumer;

/**
 * A compat's display test, run by Dailies' scripted run (specification TEST-8): kept small on purpose. In a flat world of
 * its own the stage is set, then while recording each display is opened in turn and held open for a couple of seconds,
 * and a screen still open is closed again as the player would close it. Then in playback, riding along with the
 * recorder in first person with the HUD on, each must be drawn at a tick while it was open; and switching to third
 * person, or flying free, must stop anything being drawn. That a state lands the same after a jump or a trim is for
 * unit tests.
 */
public interface DisplayTest {

    /** Sets up what the displays need before recording starts: blocks, items, entities. The player stands at 0.5, -60, 0.5 */
    default void stage(Stage stage) throws Exception {
    }

    /** The displays, opened in this order */
    List<Display> displays();

    /**
     * One display.
     *
     * @param label   what the test calls it, such as "a letter being written"
     * @param drawnAs what playback draws it as: {@code <compat id>/<claim name>} for a screen or overlay of the compat's
     *                own, or the menu type id for a server window Dailies draws (with or without extras)
     * @param opener  opens it in the live game (a screen), or makes it show (an overlay); it may go on to use it (type,
     *                click a tab) before it returns. The test then holds it for a couple of seconds, notes a tick in the
     *                middle, and closes any screen still open with the screen's own close
     */
    record Display(String label, String drawnAs, Opener opener) {
    }

    @FunctionalInterface
    interface Opener {
        void open(Stage stage) throws Exception;
    }

    /** What a display test may do in the live game, which is singleplayer. Each call waits until it is done */
    interface Stage {
        /** Runs a command as the player, with every permission */
        void command(String command) throws Exception;

        /** Runs on the built-in server's thread with the player */
        void onServer(Consumer<ServerPlayer> action) throws Exception;

        /** Runs on the game's own thread */
        void onClient(Runnable action) throws Exception;

        /** Lets the game run on for this many ticks */
        void waitTicks(int ticks) throws Exception;
    }

}
