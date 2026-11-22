package comedymonkey.dailies.ui;

import com.mojang.blaze3d.platform.NativeImage;
import comedymonkey.dailies.Dailies;
import comedymonkey.dailies.library.LibraryJob;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.function.Supplier;

/**
 * The small things the screens of the library share (the list, the bin, the storage view, combining and trimming):
 * a recording's preview picture as a texture, the little pictures of a star, a folder and the bin, and a bar that
 * shows how a combine or trim is getting on, and a line with a name and its value. Drawn with the kit's colours, in
 * whole pixels of the interface.
 */
final class LibraryBits {

    static final DateTimeFormatter DATE = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT);
    static final DateTimeFormatter DAY = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM);
    private static int pictures = 0;

    private LibraryBits() {
    }

    static String date(long millis) {
        return millis <= 0 ? "?" : DATE.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
    }

    static String day(long millis) {
        return millis <= 0 ? "?" : DAY.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
    }

    /** A preview picture made into a texture. Client thread; closed when the screen that made it goes */
    static final class Picture {
        private final Identifier location;
        final int width;
        final int height;
        private boolean closed = false;

        private Picture(Identifier location, int width, int height) {
            this.location = location;
            this.width = width;
            this.height = height;
        }

        /** The picture in these bytes, or null when they are not a picture the game can read */
        static @Nullable Picture of(@Nullable byte[] png) {
            if (png == null || png.length < 8) {
                return null;
            }
            try {
                NativeImage image = NativeImage.read(png);
                Identifier location = Identifier.fromNamespaceAndPath(Dailies.ID, "library_preview/" + pictures++);
                Minecraft.getInstance().getTextureManager().register(location, new DynamicTexture(location::toString, image));
                return new Picture(location, image.getWidth(), image.getHeight());
            } catch (Exception | LinkageError unreadable) {
                return null;
            }
        }

        /** Draws it as large as fits in the box without stretching it, in the middle, with a brass edge round the box */
        void draw(GuiGraphicsExtractor graphics, int x, int y, int boxWidth, int boxHeight) {
            graphics.fill(x, y, x + boxWidth, y + boxHeight, Controls.FIELD);
            if (!this.closed && this.width > 0 && this.height > 0) {
                double scale = Math.min((boxWidth - 2) / (double) this.width, (boxHeight - 2) / (double) this.height);
                int drawnWidth = Math.max(1, (int) Math.round(this.width * scale));
                int drawnHeight = Math.max(1, (int) Math.round(this.height * scale));
                int left = x + (boxWidth - drawnWidth) / 2;
                int top = y + (boxHeight - drawnHeight) / 2;
                graphics.blit(RenderPipelines.GUI_TEXTURED, this.location, left, top, 0.0F, 0.0F, drawnWidth, drawnHeight, this.width, this.height, this.width, this.height);
            }
            graphics.outline(x, y, boxWidth, boxHeight, Controls.BRASS_DIM);
        }

        void close() {
            if (!this.closed) {
                this.closed = true;
                Minecraft.getInstance().getTextureManager().release(this.location);
            }
        }
    }

    /** The box a recording without a preview gets instead: dark, with a reel in the middle */
    static void noPicture(GuiGraphicsExtractor graphics, Font font, int x, int y, int width, int height, @Nullable Component words) {
        Kit.field(graphics, x, y, width, height, Controls.BRASS_DIM);
        int middleX = x + width / 2;
        int middleY = y + height / 2 - (words == null ? 0 : 5);
        reel(graphics, middleX, middleY, Controls.BRASS_DIM, Controls.PARCHMENT_FAINT);
        if (words != null && height > 30) {
            Kit.centred(graphics, font, words.getString(), x + 2, middleY + 9, width - 4, Controls.PARCHMENT_FAINT);
        }
    }

    /** A reel of film: what a recording looks like before a word of it is read */
    static void reel(GuiGraphicsExtractor graphics, int centreX, int centreY, int edge, int play) {
        graphics.fill(centreX - 6, centreY - 5, centreX + 6, centreY + 5, Controls.FIELD);
        graphics.outline(centreX - 6, centreY - 5, 12, 10, edge);
        Kit.icon(graphics, Kit.Icon.PLAY, centreX, centreY, play);
    }

    private static final String[] STAR = {
        "....#....",
        "...###...",
        "#########",
        ".#######.",
        "..#####..",
        ".###.###.",
        ".#.....#."};
    private static final String[] FOLDER = {
        "####......",
        "#..#######",
        "#........#",
        "#........#",
        "#........#",
        "##########"};
    private static final String[] BIN = {
        "..####..",
        "########",
        ".#.##.#.",
        ".#.##.#.",
        ".#.##.#.",
        ".######."};
    private static final String[] SCENE = {
        "#########",
        "#.#.#.#.#",
        "#########",
        "#.......#",
        "#.......#",
        "#########"};
    private static final String[] VOICE = {
        "...##...",
        "..####..",
        "..####..",
        "#.####.#",
        ".#.##.#.",
        "..####..",
        "...##..."};

    static void star(GuiGraphicsExtractor graphics, int centreX, int centreY, int colour) {
        dots(graphics, STAR, centreX, centreY, colour);
    }

    static void folder(GuiGraphicsExtractor graphics, int centreX, int centreY, int colour) {
        dots(graphics, FOLDER, centreX, centreY, colour);
    }

    static void bin(GuiGraphicsExtractor graphics, int centreX, int centreY, int colour) {
        dots(graphics, BIN, centreX, centreY, colour);
    }

    static void scene(GuiGraphicsExtractor graphics, int centreX, int centreY, int colour) {
        dots(graphics, SCENE, centreX, centreY, colour);
    }

    static void voice(GuiGraphicsExtractor graphics, int centreX, int centreY, int colour) {
        dots(graphics, VOICE, centreX, centreY, colour);
    }

    /** A picture given as rows of dots, drawn about its middle, as the kit draws its own */
    private static void dots(GuiGraphicsExtractor graphics, String[] rows, int centreX, int centreY, int colour) {
        int left = centreX - rows[0].length() / 2;
        int top = centreY - rows.length / 2;
        for (int row = 0; row < rows.length; row++) {
            String line = rows[row];
            int from = -1;
            for (int column = 0; column <= line.length(); column++) {
                boolean on = column < line.length() && line.charAt(column) == '#';
                if (on && from < 0) {
                    from = column;
                } else if (!on && from >= 0) {
                    graphics.fill(left + from, top + row, left + column, top + row + 1, colour);
                    from = -1;
                }
            }
        }
    }

    /** A bar filled from the left to a share, with words on it: how a job or a share of the disk stands */
    static void bar(GuiGraphicsExtractor graphics, Font font, int x, int y, int width, int height, double share, int fill, @Nullable String words) {
        Kit.field(graphics, x, y, width, height, Controls.BRASS_DIM);
        int to = x + 1 + (int) Math.round(Math.max(0.0, Math.min(1.0, share)) * Math.max(0, width - 2));
        if (to > x + 1) {
            graphics.fill(x + 1, y + 1, to, y + height - 1, fill);
            graphics.fill(x + 1, y + 1, to, y + 2, 0x20FFFFFF);
        }
        if (words != null && height >= 10) {
            String shown = Kit.clip(font, words, width - 6);
            graphics.text(font, shown, x + (width - font.width(shown)) / 2, y + (height - 8) / 2, Controls.PARCHMENT, true);
        }
    }

    /** Why a job failed, without the word a tool reads to know it must ask before leaving a world */
    static String plain(@Nullable String error) {
        String words = String.valueOf(error);
        return words.startsWith("LEAVE_WORLD_NEEDED: ") ? words.substring("LEAVE_WORLD_NEEDED: ".length()) : words;
    }

    /**
     * How a combine or a trim is getting on: a bar with what it is doing, and once it is over, how it ended. Takes no
     * room while there is no job.
     */
    static final class JobBar extends Gadget {
        private final Supplier<LibraryJob> job;

        JobBar(Supplier<LibraryJob> job) {
            this.job = job;
        }

        @Override
        int heightFor(Font font, int forWidth) {
            return this.job.get() == null ? 0 : Kit.ROW;
        }

        @Override
        void draw(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
            LibraryJob now = this.job.get();
            if (now == null || this.height <= 0) {
                return;
            }
            String words = switch (now.state()) {
                case RUNNING -> Component.translatable("dailies.library.job.running", now.stage(), Math.round(now.progress() * 100)).getString();
                case DONE -> Component.translatable("dailies.library.job.done").getString();
                case CANCELLED -> Component.translatable("dailies.library.job.cancelled").getString();
                case FAILED -> Component.translatable("dailies.library.job.failed", plain(now.error())).getString();
            };
            int fill = now.state() == LibraryJob.State.FAILED ? 0xFF7A2E22 : now.state() == LibraryJob.State.CANCELLED ? 0xFF4A3A1C : Controls.FACE_LIT;
            bar(graphics, font, this.x, this.y, this.width, this.height, now.state() == LibraryJob.State.RUNNING ? now.progress() : 1.0, fill, words);
        }

        @Override
        @Nullable Component tipAt(double mouseX, double mouseY) {
            LibraryJob now = this.job.get();
            if (now == null || !this.over(mouseX, mouseY)) {
                return null;
            }
            Component tip = now.state() == LibraryJob.State.FAILED ? Component.literal(plain(now.error())) : Component.translatable("dailies.library.job.tip");
            if (now.throughGame() && now.state() == LibraryJob.State.RUNNING) {
                tip = Component.empty().append(tip).append("\n").append(Component.translatable("dailies.library.job.through_game"));
            }
            return tip;
        }
    }


    /** A name and its value on one line, the value cut short with its whole in the explanation when it does not fit */
    static final class Fact extends Gadget {

        final Component label;
        final String value;
        private boolean cut = false;

        Fact(Component label, String value) {
            this.label = label;
            this.value = value == null ? "" : value;
        }

        @Override
        int heightFor(Font font, int forWidth) {
            return 12;
        }

        private int labelWidth() {
            return Math.max(48, Math.min(76, this.width * 34 / 100));
        }

        @Override
        void draw(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
            int labelWidth = this.labelWidth();
            Kit.text(graphics, font, Kit.clip(font, this.label.getString(), labelWidth - 4), this.x + 1, this.y + 2, Controls.PARCHMENT_DIM);
            int room = this.width - labelWidth - 2;
            this.cut = font.width(this.value) > room;
            Kit.text(graphics, font, Kit.clip(font, this.value, room), this.x + labelWidth, this.y + 2, Controls.PARCHMENT);
        }

        @Override
        @Nullable Component tipAt(double mouseX, double mouseY) {
            if (!this.over(mouseX, mouseY)) {
                return null;
            }
            return this.cut ? Component.empty().append(this.label).append(": ").append(this.value) : null;
        }
    }

}
