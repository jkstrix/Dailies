package comedymonkey.dailies.ui;

import comedymonkey.dailies.Host;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The one way Dailies' screens write the mod's settings: the settings screen, the library's storage view, the editor
 * and its export tab all save through here. Saves run one at a time and in order on a thread of their own, so two
 * changes to one setting cannot overtake each other on their way to the file and leave the older value written.
 * Until a change is saved, {@link #pending} gives its value, for everything that shows the setting meanwhile.
 */
final class SettingsWriter {

    private static final ExecutorService SAVER = Executors.newSingleThreadExecutor(work -> {
        Thread thread = new Thread(work, "Dailies-Settings-Save");
        thread.setDaemon(true);
        return thread;
    });
    private static final PendingValues PENDING = new PendingValues();

    private SettingsWriter() {
    }

    /** The value a setting will have once a change on its way has been saved, or null when none is. Any thread */
    static @Nullable Object pending(String name) {
        return PENDING.get(name);
    }

    /** Whether a change is still on its way to the file */
    static boolean saving() {
        return !PENDING.isEmpty();
    }

    /**
     * Sets a setting and writes the file, away from the client thread. Client thread.
     *
     * @param done runs on the client thread once the save is over, with what went wrong, or null when it worked. A
     *             refused value comes as an {@link IllegalArgumentException}; anything else is in the log as well
     */
    static void save(String name, Object value, Consumer<@Nullable Throwable> done) {
        PendingValues.Ticket ticket = PENDING.put(name, value);
        Minecraft game = Minecraft.getInstance();
        SAVER.execute(() -> {
            Throwable trouble = null;
            try {
                Host.get().saveSetting(name, value);
            } catch (IllegalArgumentException refused) {
                trouble = refused;
            } catch (RuntimeException | LinkageError e) {
                Host.LOGGER.error("Dailies: the setting {} could not be saved", name, e);
                trouble = e;
            }
            Throwable said = trouble;
            game.execute(() -> {
                PENDING.done(name, ticket);
                Controls.guarded(() -> done.accept(said));
            });
        });
    }

}
