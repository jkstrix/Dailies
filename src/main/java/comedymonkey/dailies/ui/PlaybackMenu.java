package comedymonkey.dailies.ui;

import comedymonkey.dailies.export.QueueRunner;
import comedymonkey.dailies.file.RecordingManifest;
import comedymonkey.dailies.playback.Playback;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * What Escape brings up while a recording is watched, in place of the game's pause menu: only Dailies' own ways on,
 * in the kit's look. The game's options, statistics and quitting, and whatever other mods add to the pause menu, have
 * nothing to do with watching a recording, and leaving the world through them skipped the way playback closes.
 * <p>
 * Opening it does not pause the recording: playback keeps its own pause, as it does behind every other screen.
 */
public final class PlaybackMenu extends KitScreen {

    private static final int WIDTH = 200;

    /** Whether Escape was pressed in the editor, so that going back puts the editor back */
    private final boolean fromEditor;
    private final List<Gadget> buttons = new ArrayList<>();
    private @Nullable Component under;
    private boolean leaving = false;

    public PlaybackMenu(boolean fromEditor) {
        super(Component.translatable("dailies.ui.playback_menu.title"), null);
        this.fromEditor = fromEditor;
    }

    @Override
    void build() {
        this.buttons.clear();
        RecordingManifest manifest = Playback.manifest();
        this.under = manifest == null || manifest.name.isBlank() ? null : Component.translatable("dailies.ui.playback_menu.watching", manifest.name);

        this.add(new Gadget.Push(Component.translatable(this.fromEditor ? "dailies.ui.playback_menu.back_editor" : "dailies.ui.playback_menu.back_watching"), this::onClose));
        if (this.fromEditor) {
            this.add(new Gadget.Push(Component.translatable("dailies.ui.playback_menu.hide_editor"), this::hideEditor)
                    .tip(Component.translatable("dailies.ui.playback_menu.hide_editor.tip")));
        } else {
            this.add(new Gadget.Push(Component.translatable("dailies.ui.playback_menu.open_editor"), EditorScreen::open).when(Playback::loaded));
        }
        this.add(new Gadget.Push(Component.translatable("dailies.ui.pause.moments"), Controls::openMomentsPage).tip(Component.translatable("dailies.ui.moments.tip")));
        this.add(new Gadget.Push(Component.translatable("dailies.ui.playback_menu.recordings"), () -> this.minecraft.setScreen(new RecordingsScreen(this))));
        this.add(new Gadget.Push(Component.translatable("dailies.ui.playback_menu.settings"), () -> Screens.openSettings(this)));
        if (!QueueRunner.view().jobs().isEmpty()) {
            this.add(new Gadget.Push(Component.translatable("dailies.ui.playback_menu.queue"), this::openQueue)
                    .when(Playback::loaded).tip(Component.translatable("dailies.ui.playback_menu.queue.tip")));
        }
        this.add(new Gadget.Push(Component.translatable("dailies.ui.playback_menu.leave"), this::leave)
                .when(() -> !this.leaving && Playback.active()).tip(Component.translatable("dailies.ui.playback_menu.leave.tip")));
    }

    private void add(Gadget button) {
        this.buttons.add(button);
        this.fixed.add(button);
    }

    @Override
    void arrange(Font font) {
        int step = Kit.ROW + 2 + Kit.GAP;
        this.centre(WIDTH, 0);
        this.centre(WIDTH, this.headingHeight(font, this.under) + 2 + this.buttons.size() * step + 6);
        int y = this.top + this.headingHeight(font, this.under) + 2;
        for (Gadget button : this.buttons) {
            button.clip(Integer.MIN_VALUE, Integer.MAX_VALUE);
            button.place(font, this.left + 8, y, this.right - this.left - 16, Kit.ROW + 2);
            y += step;
        }
    }

    @Override
    void paint(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        this.heading(graphics, font, this.under);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Back to where Escape was pressed: the editor, opened afresh so that it comes back after a jump, or the world */
    @Override
    public void onClose() {
        if (this.fromEditor && Playback.loaded()) {
            EditorScreen.open();
        } else {
            this.minecraft.setScreen(null);
        }
    }

    private void hideEditor() {
        EditorScreen.letGo();
        this.minecraft.setScreen(null);
    }

    private void openQueue() {
        EditorState.tab = EditorState.TAB_EXPORT;
        EditorState.sideFolded = false;
        EditorScreen.open();
    }

    /** Closes the recording the way the pause menu's button always has; playback takes the game to the title screen */
    private void leave() {
        this.leaving = true;
        Playback.close();
    }

}
