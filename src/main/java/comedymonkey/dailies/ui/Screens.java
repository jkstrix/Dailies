package comedymonkey.dailies.ui;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.capture.Recorder;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.selftest.SelfTest;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
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
        // Last, so that the buttons other mods add to the same screens are there to be kept clear of
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, Screens::screenBuilt);
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

    /**
     * Opens the settings of the mod. The one place that knows which screen that is.
     *
     * @param parent the screen to come back to, or null for the game
     */
    public static void openSettings(@Nullable Screen parent) {
        SettingsScreen.open(parent);
    }

    // --- The pause menu ---

    private static void addToPauseMenu(ScreenEvent.Init.Post event, PauseScreen pause) {
        Minecraft minecraft = Minecraft.getInstance();
        // The game's own buttons take 204 pixels down the middle; ours keep to what is left at one side
        int width = Math.max(40, Math.min(110, pause.width / 2 - 102 - 12));
        boolean left = "LEFT".equalsIgnoreCase(Host.get().setting("controlsLocation", "RIGHT"));
        Recorder.State state = Recorder.get().state();
        int buttons = Playback.active() ? 3 : state == Recorder.State.IDLE ? 4 : 6;
        int[] place = pauseColumnPlace(event, pause, width, 12 + buttons * 24 - 4, left);
        int x = place[0];
        int y = place[1];

        event.addListener(new StringWidget(x, y, width, 9, Component.translatable("dailies.ui.pause.heading"), minecraft.font).setColor(Controls.PARCHMENT));
        y += 12;

        if (Playback.active()) {
            event.addListener(Button.builder(Component.translatable("dailies.ui.pause.close_recording"), button -> Controls.guarded(() -> {
                button.active = false;
                Playback.close();
            })).bounds(x, y, width, 20).build());
            y += 24;
            event.addListener(momentsButton(x, y, width));
            y += 24;
            event.addListener(Button.builder(Component.translatable("dailies.ui.pause.settings"), button -> SettingsScreen.open(pause)).bounds(x, y, width, 20).build());
            return;
        }

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
        event.addListener(momentsButton(x, y, width));
        y += 24;
        event.addListener(Button.builder(Component.translatable("dailies.ui.pause.settings"), button -> SettingsScreen.open(pause)).bounds(x, y, width, 20).build());
    }

    /**
     * Where the column of Dailies' buttons goes on the pause menu: at the chosen side a quarter of the way down if that
     * is clear of every other widget, else the nearest clear place up or down that side, else the same on the other.
     *
     * @return the column's top left corner
     */
    private static int[] pauseColumnPlace(ScreenEvent.Init.Post event, PauseScreen pause, int width, int height, boolean left) {
        int top = Math.max(8, pause.height / 4);
        List<List<FreeSpot.Box>> candidates = new ArrayList<>();
        List<int[]> places = new ArrayList<>();
        for (boolean side : new boolean[]{left, !left}) {
            int x = side ? 6 : pause.width - 6 - width;
            for (int step = 0; step < pause.height; step += 4) {
                for (int y : step == 0 ? new int[]{top} : new int[]{top + step, top - step}) {
                    if (y >= 2 && y + height <= pause.height - 2) {
                        candidates.add(List.of(new FreeSpot.Box(x, y, width, height)));
                        places.add(new int[]{x, y});
                    }
                }
            }
        }
        int chosen = FreeSpot.first(candidates, widgets(event), pause.width, pause.height, 2);
        return chosen >= 0 ? places.get(chosen) : new int[]{left ? 6 : pause.width - 6 - width, top};
    }

    /** The rectangles of every widget on the screen being built, whichever mod put it there */
    private static List<FreeSpot.Box> widgets(ScreenEvent.Init.Post event) {
        List<FreeSpot.Box> taken = new ArrayList<>();
        for (GuiEventListener listener : event.getListenersList()) {
            if (listener instanceof AbstractWidget widget && widget.visible) {
                taken.add(new FreeSpot.Box(widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight()));
            }
        }
        return taken;
    }

    /** Opens the moments page in the web browser */
    private static Button momentsButton(int x, int y, int width) {
        return Button.builder(Component.translatable("dailies.ui.pause.moments"), button -> Controls.openMomentsPage())
            .bounds(x, y, width, 20).tooltip(Tooltip.create(Component.translatable("dailies.ui.moments.tip"))).build();
    }

    // --- The title screen ---

    private static void addToTitleScreen(ScreenEvent.Init.Post event, TitleScreen title) {
        Minecraft minecraft = Minecraft.getInstance();

        int[] place = titleButtonsPlace(event, title);
        int x = place[0];
        int y = place[1];
        int stepX = place[2];
        int stepY = place[3];
        event.addListener(new ReelButton(x, y, false, Component.translatable("dailies.ui.title.recordings"), button -> minecraft.setScreen(new RecordingsScreen(title))));
        event.addListener(new ReelButton(x + stepX, y + stepY, true, Component.translatable("dailies.ui.title.settings"), button -> SettingsScreen.open(title)));
        event.addListener(new MomentsButton(x + 2 * stepX, y + 2 * stepY, Component.translatable("dailies.ui.title.moments"), button -> Controls.openMomentsPage()));

        if (!lookedForUnfinished && !SelfTest.asked() && !comedymonkey.dailies.export.Worker.active()) {
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

    /**
     * Where Dailies' three small squares go on the title screen. Other mods add buttons of their own beside the main
     * column (Create's goggles among them), so the squares go to the first of several places where all three are clear
     * of every widget and on the screen: to the right of the accessibility button stacking upward, to the left of the
     * language button stacking upward, in a row above the main column, and then other places round the edge.
     *
     * @return the first square's corner and the step from one square to the next: x, y, step x, step y
     */
    private static int[] titleButtonsPlace(ScreenEvent.Init.Post event, TitleScreen title) {
        // The game's own small squares at either end of the options row, or where they would be if a mod took them away
        int rowY = title.height / 4 + 48 + 72 + 12;
        SpriteIconButton language = null;
        for (GuiEventListener listener : event.getListenersList()) {
            if (listener instanceof SpriteIconButton icon && (language == null || icon.getX() < language.getX())) {
                language = icon;
            }
        }
        int languageX = language != null ? language.getX() : title.width / 2 - 124;
        int languageY = language != null ? language.getY() : rowY;
        SpriteIconButton accessibility = null;
        for (GuiEventListener listener : event.getListenersList()) {
            if (listener instanceof SpriteIconButton icon && icon != language && icon.getY() == languageY
                && (accessibility == null || icon.getX() > accessibility.getX())) {
                accessibility = icon;
            }
        }
        int accessibilityX = accessibility != null ? accessibility.getX() : title.width / 2 + 104;
        int accessibilityY = accessibility != null ? accessibility.getY() : rowY;
        int mainTop = title.height / 4 + 48;
        int centre = title.width / 2;

        List<int[]> places = new ArrayList<>();
        places.add(new int[]{accessibilityX + 24, accessibilityY, 0, -24});
        places.add(new int[]{languageX - 24, languageY, 0, -24});
        places.add(new int[]{centre - 34, mainTop - 24, 24, 0});
        places.add(new int[]{languageX, languageY - 24, 0, -24});
        places.add(new int[]{accessibilityX, accessibilityY - 24, 0, -24});
        places.add(new int[]{accessibilityX + 48, accessibilityY, 0, -24});
        places.add(new int[]{languageX - 48, languageY, 0, -24});
        places.add(new int[]{centre - 100, mainTop - 24, 24, 0});
        places.add(new int[]{centre + 32, mainTop - 24, 24, 0});
        // Round the edge of the screen, out of the way of everything
        for (int y = 4; y + 20 <= title.height - 4; y += 24) {
            places.add(new int[]{4, y, 24, 0});
            places.add(new int[]{title.width - 4 - 68, y, 24, 0});
        }
        List<List<FreeSpot.Box>> candidates = new ArrayList<>();
        for (int[] place : places) {
            candidates.add(FreeSpot.line(place[0], place[1], place[2], place[3], 3, 20));
        }
        int chosen = FreeSpot.first(candidates, widgets(event), title.width, title.height, 2);
        if (chosen < 0) {
            Host.LOGGER.warn("Dailies: no clear place for its buttons on the title screen; they go above the language button");
            return places.get(3);
        }
        return places.get(chosen);
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

    /** A square button with a drawn picture in place of words: a list of three moments, each a brass dot and a line */
    private static final class MomentsButton extends Button {

        MomentsButton(int x, int y, Component label, OnPress onPress) {
            super(x, y, 20, 20, label, onPress, DEFAULT_NARRATION);
            this.setTooltip(Tooltip.create(label));
        }

        @Override
        public void renderString(GuiGraphics graphics, Font font, int colour) {
            int left = this.getX() + 5;
            int top = this.getY() + 5;
            int brass = this.active ? Controls.BRASS_BRIGHT : Controls.BRASS;
            for (int row = 0; row < 3; row++) {
                int y = top + row * 4;
                graphics.fill(left, y, left + 2, y + 2, brass);
                graphics.fill(left + 4, y, left + 10 - row * 2, y + 2, Controls.PARCHMENT);
            }
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
