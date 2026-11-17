package comedymonkey.dailies.ui;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.capture.Recorder;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.selftest.SelfTest;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * What Dailies adds to the game's own screens: the recording buttons at the side of the pause menu (specification
 * REC-1), a way to the list of recordings from the title screen and the pause menu (REC-5), and, the first time the
 * title screen opens, the offer to recover recordings a crash left unfinished (REC-6).
 */
public final class Screens {

    private static final int BRASS_EDGE = Controls.BRASS;
    private static boolean lookedForUnfinished = false;
    /** Unfinished recordings found and not yet offered. Client thread */
    private static @Nullable List<Path> unfinished = null;

    private Screens() {
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener(Screens::screenBuilt);
        // Explanations in the editor are drawn by the game; they are given the house colours here
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.RenderTooltipEvent.Color event) -> {
            if (Minecraft.getInstance().screen instanceof EditorScreen) {
                event.setBackground(0xFA1C1308);
                event.setBorderStart(BRASS_EDGE);
                event.setBorderEnd(BRASS_EDGE);
            }
        });
    }

    private static void screenBuilt(ScreenEvent.Init.Post event) {
        try {
            if (event.getScreen() instanceof PauseScreen pause && pause.showsPauseMenu()) {
                addToPauseMenu(event, pause);
            } else if (event.getScreen() instanceof TitleScreen title) {
                addToTitleScreen(event, title);
            }
        } catch (RuntimeException | LinkageError e) {
            Host.LOGGER.error("Dailies: its buttons could not be added to a screen", e);
        }
    }

    // --- The pause menu ---

    private static void addToPauseMenu(ScreenEvent.Init.Post event, PauseScreen pause) {
        Minecraft minecraft = Minecraft.getInstance();
        // The game's own buttons take 204 pixels down the middle; ours keep to what is left at one side
        int width = Math.max(40, Math.min(110, pause.width / 2 - 102 - 12));
        boolean left = "LEFT".equalsIgnoreCase(Host.get().setting("controlsLocation", "RIGHT"));
        int x = left ? 6 : pause.width - 6 - width;
        int y = Math.max(8, pause.height / 4);

        event.addListener(new StringWidget(x, y, width, 9, Component.translatable("dailies.ui.pause.heading"), minecraft.font).setColor(Controls.PARCHMENT));
        y += 12;

        if (Playback.active()) {
            event.addListener(Button.builder(Component.translatable("dailies.ui.pause.close_recording"), button -> Controls.guarded(() -> {
                button.active = false;
                Playback.close();
            })).bounds(x, y, width, 20).build());
            y += 24;
            event.addListener(Button.builder(Component.translatable("dailies.ui.pause.settings"), button -> SettingsScreen.open(pause)).bounds(x, y, width, 20).build());
            return;
        }

        Recorder.State state = Recorder.get().state();
        if (state == Recorder.State.IDLE) {
            event.addListener(Button.builder(Component.translatable("dailies.ui.pause.start"), button -> {
                Controls.startRecording();
                minecraft.setScreen(null);
            }).bounds(x, y, width, 20).build());
            y += 24;
        } else {
            event.addListener(Button.builder(Component.translatable(state == Recorder.State.PAUSED ? "dailies.ui.pause.resume" : "dailies.ui.pause.pause"), button -> {
                Controls.togglePauseRecording();
                minecraft.setScreen(null);
            }).bounds(x, y, width, 20).build());
            y += 24;
            event.addListener(Button.builder(Component.translatable("dailies.ui.pause.finish"), button -> Controls.finishRecording(null)).bounds(x, y, width, 20).build());
            y += 24;
            event.addListener(Button.builder(Component.translatable("dailies.ui.pause.cancel"), button -> Controls.cancelRecording(pause)).bounds(x, y, width, 20).build());
            y += 24;
        }
        event.addListener(Button.builder(Component.translatable("dailies.ui.pause.recordings"), button -> minecraft.setScreen(new RecordingsScreen(pause))).bounds(x, y, width, 20).build());
        y += 24;
        event.addListener(Button.builder(Component.translatable("dailies.ui.pause.settings"), button -> SettingsScreen.open(pause)).bounds(x, y, width, 20).build());
    }

    // --- The title screen ---

    private static void addToTitleScreen(ScreenEvent.Init.Post event, TitleScreen title) {
        Minecraft minecraft = Minecraft.getInstance();

        // A small square above the language button, to the left of the main column
        int x = title.width / 2 - 124;
        int y = title.height / 4 + 32 + 82;
        SpriteIconButton language = null;
        for (GuiEventListener listener : event.getListenersList()) {
            if (listener instanceof SpriteIconButton icon && (language == null || icon.getX() < language.getX())) {
                language = icon;
            }
        }
        if (language != null) {
            x = language.getX();
            y = language.getY() - 24;
        }
        event.addListener(new ReelButton(x, y, false, Component.translatable("dailies.ui.title.recordings"), button -> minecraft.setScreen(new RecordingsScreen(title))));
        // And above that one, the way to the settings
        event.addListener(new ReelButton(x, y - 24, true, Component.translatable("dailies.ui.title.settings"), button -> SettingsScreen.open(title)));

        if (!lookedForUnfinished && !SelfTest.asked()) {
            lookedForUnfinished = true;
            CompletableFuture.supplyAsync(Recorder::unfinished, Util.ioPool()).whenComplete((found, error) -> minecraft.execute(() -> {
                if (error != null) {
                    Host.LOGGER.error("Dailies: could not look for unfinished recordings", error);
                    return;
                }
                unfinished = found;
                offerRecovery(minecraft);
            }));
        } else if (unfinished != null) {
            // Found while another screen was open: offered now, once this screen has finished being built
            minecraft.execute(() -> offerRecovery(minecraft));
        }
    }

    private static void offerRecovery(Minecraft minecraft) {
        List<Path> found = unfinished;
        if (found == null || !(minecraft.screen instanceof TitleScreen title)) {
            return;
        }
        unfinished = null;
        if (!found.isEmpty()) {
            minecraft.setScreen(new RecoveryScreen(found, title));
        }
    }

    /** A square button with a drawn picture in place of words: a brass frame with a play arrow in it */
    private static final class ReelButton extends Button {

        private final boolean gear;

        ReelButton(int x, int y, boolean gear, Component label, OnPress onPress) {
            super(x, y, 20, 20, label, onPress, DEFAULT_NARRATION);
            this.gear = gear;
            this.setTooltip(Tooltip.create(label));
        }

        @Override
        public void renderString(GuiGraphics graphics, Font font, int colour) {
            int centreX = this.getX() + this.getWidth() / 2;
            int centreY = this.getY() + this.getHeight() / 2;
            int brass = this.active ? Controls.BRASS_BRIGHT : Controls.BRASS;
            if (this.gear) {
                // A brass cog for the settings
                Kit.icon(graphics, Kit.Icon.GEAR, centreX, centreY, brass);
                return;
            }
            graphics.fill(centreX - 6, centreY - 5, centreX + 6, centreY - 4, brass);
            graphics.fill(centreX - 6, centreY + 4, centreX + 6, centreY + 5, brass);
            graphics.fill(centreX - 6, centreY - 5, centreX - 5, centreY + 5, brass);
            graphics.fill(centreX + 5, centreY - 5, centreX + 6, centreY + 5, brass);
            for (int i = 0; i < 3; i++) {
                graphics.fill(centreX - 2 + i, centreY - 3 + i, centreX - 1 + i, centreY + 3 - i, Controls.PARCHMENT);
            }
        }
    }

}
