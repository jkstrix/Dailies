package comedymonkey.dailiescompat.extras.client;

import comedymonkey.dailies.api.compat.Claims;
import comedymonkey.dailiescompat.extras.state.ExtrasStates;
import io.github.kosmx.emotes.arch.screen.ingame.FastMenuScreen;
import io.github.kosmx.emotes.arch.screen.widget.ModernChooseWheel;
import io.github.kosmx.emotes.executor.EmoteInstance;
import io.github.kosmx.emotes.main.EmoteHolder;
import io.github.kosmx.emotes.main.config.ClientConfig;
import io.github.kosmx.emotes.main.network.ClientPacketManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Emotecraft's emote wheel, a screen only the player's game knows and a selection wheel used in play, as Create's toolbox
 * wheel is: eight places round a ring, each holding an emote, on one of ten pages. What it shows comes from the player's
 * own Emotecraft settings and emote files, so it is recorded as drawn: the page, the emote in each place (by id, with its
 * name as the wheel would write it), the warning over it when the server lacks Emotecraft, the recorder's choice of wheel,
 * dark or light and icons or names, and where the pointer was as a share of the wheel. Drawn with Emotecraft's own wheel,
 * built with the recorder's settings and given the recorded page and emotes only while it draws, with the pointer put
 * where it was so the same place is lit. An emote the watching game does not have is drawn by its recorded name where
 * Emotecraft would write a name. The wheel built for playback never plays an emote: it takes no click.
 */
final class EmoteWheel {

    static final String NAME = "emote_wheel";

    private static final Component WARN_NO_EMOTECRAFT = Component.translatable("emotecraft.no_server");
    private static final Component WARN_ONLY_PROXY = Component.translatable("emotecraft.only_proxy");
    private static final Component EMOTE_LIST = Component.translatable("emotecraft.emotelist");

    private EmoteWheel() {
    }

    static void claim(Claims claims) {
        claims.screen(NAME, FastMenuScreen.class, Parts.screen(EmoteWheel::capture, EmoteWheel::build, 1, screen -> null));
    }

    private static ClientConfig config() {
        return (ClientConfig) EmoteInstance.config;
    }

    /** The emotes the watching game has, by id: Emotecraft's own list, a map of its emotes by their ids */
    private static boolean known(UUID emote) {
        Map<?, ?> list = Reflect.getStatic(EmoteHolder.class, "list");
        return list != null && list.containsKey(emote);
    }

    static byte @Nullable [] capture(Screen screen) {
        if (!(screen instanceof FastMenuScreen)) {
            return null;
        }
        AbstractWidget wheel = Reflect.get(screen, "fastMenu");
        if (wheel == null || wheel.getWidth() <= 0) {
            return null;
        }
        ClientConfig config = config();
        int page = ModernChooseWheel.fastMenuPage;
        List<String> emotes = new ArrayList<>(ExtrasStates.PLACES);
        List<String> names = new ArrayList<>(ExtrasStates.PLACES);
        for (int place = 0; place < ExtrasStates.PLACES; place++) {
            UUID emote = config.fastMenuEmotes[page][place];
            emotes.add(emote == null ? "" : emote.toString());
            names.add(emote == null ? "" : Texts.json(EmoteHolder.getNonNull(emote).name));
        }
        int warning = ClientPacketManager.isRemoteAvailable() ? ExtrasStates.NO_WARNING
            : ClientPacketManager.isAvailableProxy() ? ExtrasStates.PROXY_ONLY : ExtrasStates.NO_SERVER;
        Minecraft minecraft = Minecraft.getInstance();
        // Where the wheel was drawn with the pointer, worked out as the game hands it to the screen
        double mouseX = minecraft.mouseHandler.xpos() * minecraft.getWindow().getGuiScaledWidth() / minecraft.getWindow().getScreenWidth();
        double mouseY = minecraft.mouseHandler.ypos() * minecraft.getWindow().getGuiScaledHeight() / minecraft.getWindow().getScreenHeight();
        float pointerX = (float) ((mouseX - wheel.getX() - wheel.getWidth() / 2.0) / wheel.getWidth());
        float pointerY = (float) ((mouseY - wheel.getY() - wheel.getHeight() / 2.0) / wheel.getHeight());
        return ExtrasStates.emoteWheel(new ExtrasStates.EmoteWheel(page, emotes, names, warning, config.oldChooseWheel.get(), config.dark.get(),
            config.showIcons.get(), pointerX, pointerY));
    }

    static @Nullable Screen build(byte[] data) {
        return new RecordedWheel(ExtrasStates.readEmoteWheel(data));
    }

    private static @Nullable UUID id(String written) {
        if (written.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(written);
        } catch (IllegalArgumentException notAnId) {
            return null;
        }
    }

    /**
     * Emotecraft's wheel screen for playback: laid out as Emotecraft lays it out, but with the recorded warning rather than
     * the watching game's, its wheel built with the recorder's settings, and the recorded page and emotes put in
     * Emotecraft's settings only while it draws
     */
    static final class RecordedWheel extends FastMenuScreen {
        private final ExtrasStates.EmoteWheel recorded;
        private HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);

        RecordedWheel(ExtrasStates.EmoteWheel recorded) {
            super(null);
            this.recorded = recorded;
        }

        @Override
        public void init() {
            this.layout = new HeaderAndFooterLayout(this);
            if (this.recorded.warning() == ExtrasStates.PROXY_ONLY) {
                this.layout.addTitleHeader(WARN_ONLY_PROXY, this.font);
            } else if (this.recorded.warning() == ExtrasStates.NO_SERVER) {
                this.layout.addTitleHeader(WARN_NO_EMOTECRAFT, this.font);
            }
            ClientConfig config = config();
            boolean legacy = config.oldChooseWheel.get();
            boolean dark = config.dark.get();
            try {
                // Emotecraft picks its wheel, and the wheel its colours, as the widget is made
                config.oldChooseWheel.set(this.recorded.legacy());
                config.dark.set(this.recorded.dark());
                this.fastMenu = this.layout.addToContents(new FastMenuWidget(0, 0, 0));
            } finally {
                config.oldChooseWheel.set(legacy);
                config.dark.set(dark);
            }
            // The button to the emote list, which takes no click here
            this.layout.addToFooter(Button.builder(EMOTE_LIST, button -> {
            }).width(120).build());
            this.layout.visitWidgets(this::addRenderableWidget);
            this.repositionElements();
        }

        @Override
        protected void repositionElements() {
            if (this.fastMenu != null) {
                int size = (int) Math.min(this.width * 0.8, this.height * 0.8);
                this.fastMenu.setSize(size, size);
            }
            this.layout.arrangeElements();
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            if (this.fastMenu == null) {
                return;
            }
            AbstractWidget wheel = this.fastMenu;
            // The wheel lights the place under the pointer as it draws: drawn with a pointer where the recorder's was
            int x = (int) Math.round(wheel.getX() + wheel.getWidth() / 2.0 + this.recorded.pointerX() * wheel.getWidth());
            int y = (int) Math.round(wheel.getY() + wheel.getHeight() / 2.0 + this.recorded.pointerY() * wheel.getHeight());
            ClientConfig config = config();
            int page = Math.max(0, Math.min(config.fastMenuEmotes.length - 1, this.recorded.page()));
            int pageBefore = ModernChooseWheel.fastMenuPage;
            UUID[] before = config.fastMenuEmotes[page].clone();
            boolean icons = config.showIcons.get();
            List<Integer> unknown = new ArrayList<>();
            try {
                ModernChooseWheel.fastMenuPage = page;
                config.showIcons.set(this.recorded.icons());
                for (int place = 0; place < ExtrasStates.PLACES && place < config.fastMenuEmotes[page].length; place++) {
                    UUID emote = id(place < this.recorded.emotes().size() ? this.recorded.emotes().get(place) : "");
                    boolean have = emote != null && known(emote);
                    config.fastMenuEmotes[page][place] = have ? emote : null;
                    if (emote != null && !have) {
                        unknown.add(place);
                    }
                }
                super.render(graphics, x, y, partialTick);
            } finally {
                System.arraycopy(before, 0, config.fastMenuEmotes[page], 0, before.length);
                config.showIcons.set(icons);
                ModernChooseWheel.fastMenuPage = pageBefore;
            }
            if (!this.recorded.legacy()) {
                this.nameUnknown(graphics, wheel, unknown);
            }
        }

        /** An emote this game lacks, by its recorded name, where Emotecraft's wheel writes a name: round the ring, an eighth apart */
        private void nameUnknown(GuiGraphics graphics, AbstractWidget wheel, List<Integer> places) {
            Font font = Minecraft.getInstance().font;
            int colour = this.recorded.dark() ? 0xFFFFFFFF : 0xFF000000;
            for (int place : places) {
                Component name = Texts.read(this.recorded.names().get(place));
                double angle = Math.toRadians(place * 45.0);
                float x = (float) (wheel.getX() + wheel.getWidth() / 2 + wheel.getWidth() * 0.4 * Math.sin(angle));
                float y = (float) (wheel.getY() + wheel.getHeight() / 2 + wheel.getHeight() * 0.4 * Math.cos(angle));
                graphics.drawString(font, name, (int) (x - font.width(name) / 2.0F), (int) (y - 2.0F), colour);
            }
        }

        @Override
        public void tick() {
        }

        @Override
        public void onClose() {
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            return false;
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
            return false;
        }
    }

}
