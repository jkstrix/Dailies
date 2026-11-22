package comedymonkey.dailies.ui;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.export.Export;
import comedymonkey.dailies.file.RecordingFile;
import comedymonkey.dailies.file.RecordingManifest;
import comedymonkey.dailies.library.Library;
import comedymonkey.dailies.library.LibraryIndex;
import comedymonkey.dailies.playback.Playback;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Changes a recording's name and its preview picture (specification REC-5 and FILE-6). The picture can be the one it
 * has, the preview of a recording it was made from, or, when this recording is the one open, a still of the view at
 * the tick it is at, taken as an export takes one and with nothing of Dailies' own drawing in it.
 */
final class RecordingEditScreen extends KitScreen {

    private static final String KEYS = "dailies.ui.edit_recording.";
    private static final String KEEP = "keep";
    private static final String STILL = "still";
    private static final int STILL_WIDTH = 480;
    private static final int STILL_HEIGHT = 270;

    /** A picture to choose: its key (keep, still, or the id of a source), its words, and the picture itself where known */
    private record Choice(String key, Component words, byte @Nullable [] png) {
    }

    private final LibraryIndex.Entry entry;
    private final Path file;
    private final Runnable after;
    private String name;
    private String picture = KEEP;
    private final List<Choice> choices = new ArrayList<>();
    private final Map<String, LibraryBits.Picture> textures = new HashMap<>();
    private boolean loaded = false;
    private boolean saving = false;

    private Entry nameBox;
    private Gadget.Dropdown<String> picturePick;
    private Gadget box;
    private Gadget.Push save;
    private Gadget.Push cancel;

    RecordingEditScreen(@Nullable Screen parent, LibraryIndex.Entry entry, Runnable after) {
        super(Component.translatable(KEYS + "title"), parent);
        this.entry = entry;
        this.file = Library.fileOf(entry);
        this.after = after;
        this.name = RecordingsScreen.nameOf(entry);
    }

    private static Component t(String key, Object... arguments) {
        return Component.translatable(KEYS + key, arguments);
    }

    private boolean isOpen() {
        RecordingFile open = Playback.loaded() ? Playback.file() : null;
        return open != null && open.file().toAbsolutePath().normalize().equals(this.file.toAbsolutePath().normalize());
    }

    @Override
    void build() {
        this.nameBox = new Entry(() -> this.name, typed -> {
            this.name = typed;
            return null;
        }).live(typed -> {
            this.name = typed;
            return null;
        }).max(80);
        this.nameBox.tip(t("name.tip"));
        this.picturePick = new Gadget.Dropdown<>(this::pictureKeys, this::pictureWords, () -> this.picture, chosen -> this.picture = chosen);
        this.picturePick.when(() -> this.loaded).tip(t("picture.tip"));
        this.box = new Gadget() {
            @Override
            void draw(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
                LibraryBits.Picture shown = RecordingEditScreen.this.texture(RecordingEditScreen.this.picture);
                if (shown != null) {
                    shown.draw(graphics, this.x, this.y, this.width, this.height);
                } else {
                    Component words = STILL.equals(RecordingEditScreen.this.picture) ? t("picture.still_later", Playback.position()) : t("picture.none");
                    LibraryBits.noPicture(graphics, font, this.x, this.y, this.width, this.height, words);
                }
            }
        };
        this.box.tip(t("preview.tip"));
        this.save = new Gadget.Push(t("save"), this::saveNow);
        this.save.when(() -> !this.saving && !this.name.isBlank()).tip(t("save.tip"));
        this.cancel = new Gadget.Push(t("cancel"), this::onClose);
        this.cancel.tip(t("cancel.tip"));
        this.fixed.add(this.nameBox);
        this.fixed.add(this.picturePick);
        this.fixed.add(this.box);
        this.fixed.add(this.save);
        this.fixed.add(this.cancel);
        if (!this.loaded) {
            this.load();
        }
    }

    private List<String> pictureKeys() {
        List<String> keys = new ArrayList<>();
        for (Choice choice : this.choices) {
            if (!STILL.equals(choice.key()) || this.isOpen()) {
                keys.add(choice.key());
            }
        }
        if (!keys.contains(this.picture)) {
            keys.add(0, this.picture);
        }
        return keys;
    }

    private Component pictureWords(String key) {
        for (Choice choice : this.choices) {
            if (choice.key().equals(key)) {
                return choice.words();
            }
        }
        return t("picture.keep_none");
    }

    private @Nullable LibraryBits.Picture texture(String key) {
        LibraryBits.Picture known = this.textures.get(key);
        if (known != null) {
            return known;
        }
        for (Choice choice : this.choices) {
            if (choice.key().equals(key) && choice.png() != null) {
                LibraryBits.Picture made = LibraryBits.Picture.of(choice.png());
                if (made != null) {
                    this.textures.put(key, made);
                }
                return made;
            }
        }
        return null;
    }

    /** Reads the picture it has and those of the recordings it was made from, off the client thread */
    private void load() {
        Minecraft game = Minecraft.getInstance();
        CompletableFuture.supplyAsync(() -> {
            List<Choice> found = new ArrayList<>();
            try (RecordingFile recording = RecordingFile.open(this.file)) {
                byte[] own = recording.preview();
                found.add(new Choice(KEEP, own == null ? t("picture.keep_none") : t("picture.keep"), own));
                found.add(new Choice(STILL, t("picture.still"), null));
                RecordingManifest manifest = recording.manifest();
                for (RecordingManifest.SourceEntry source : manifest.sources) {
                    Path other = Library.index().findById(source.id);
                    if (other == null) {
                        continue;
                    }
                    try (RecordingFile sourceFile = RecordingFile.open(other)) {
                        byte[] png = sourceFile.preview();
                        if (png != null) {
                            found.add(new Choice(source.id, t("picture.source", source.name.isBlank() ? other.getFileName().toString() : source.name), png));
                        }
                    } catch (Exception unreadable) {
                        // Not offered
                    }
                }
            } catch (Exception e) {
                Host.LOGGER.warn("Dailies: the preview of {} could not be read", this.file.getFileName(), e);
                found.add(new Choice(KEEP, t("picture.keep_none"), null));
                found.add(new Choice(STILL, t("picture.still"), null));
            }
            return found;
        }, Util.ioPool()).whenComplete((found, error) -> game.execute(() -> {
            this.choices.clear();
            if (found != null) {
                this.choices.addAll(found);
            }
            this.loaded = true;
        }));
    }

    @Override
    void arrange(Font font) {
        this.centre(420, 0);
        Component under = Component.literal(this.file.getFileName().toString());
        int head = this.headingHeight(font, under);
        int width = 420 - 16;
        int pictureHeight = Math.min(160, Math.max(60, this.height - 24 - head - 3 * (Kit.ROW + 6) - 40));
        this.centre(420, head + (Kit.ROW + 6) * 2 + pictureHeight + 6 + Kit.ROW + 2 + 8 + 4);
        int y = this.top + head;
        int labelWidth = 70;
        this.nameBox.clip(Integer.MIN_VALUE, Integer.MAX_VALUE);
        this.nameBox.place(font, this.left + 8 + labelWidth, y, width - labelWidth, Kit.ROW);
        y += Kit.ROW + 6;
        this.picturePick.clip(Integer.MIN_VALUE, Integer.MAX_VALUE);
        this.picturePick.place(font, this.left + 8 + labelWidth, y, width - labelWidth, Kit.ROW);
        y += Kit.ROW + 6;
        int pictureWidth = Math.min(width - labelWidth, pictureHeight * 16 / 9);
        this.box.clip(Integer.MIN_VALUE, Integer.MAX_VALUE);
        this.box.place(font, this.left + 8 + labelWidth, y, pictureWidth, pictureHeight);
        this.foot(font, this.save, this.cancel);
    }

    @Override
    void paint(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        this.heading(graphics, font, Component.literal(this.file.getFileName().toString()));
        Kit.text(graphics, font, t("name").getString(), this.left + 9, this.nameBox.y + 3, Controls.PARCHMENT);
        Kit.text(graphics, font, t("picture").getString(), this.left + 9, this.picturePick.y + 3, Controls.PARCHMENT);
    }

    private void saveNow() {
        if (this.saving) {
            return;
        }
        Entry.blur();
        String newName = this.name.trim();
        boolean renamed = !newName.isEmpty() && !newName.equals(RecordingsScreen.nameOf(this.entry));
        String chosen = this.picture;
        byte[] png = null;
        for (Choice choice : this.choices) {
            if (choice.key().equals(chosen) && !KEEP.equals(chosen) && !STILL.equals(chosen)) {
                png = choice.png();
            }
        }
        byte[] fromSource = png;
        this.saving = true;
        Minecraft game = Minecraft.getInstance();
        Screen back = this.parent;
        CompletableFuture.runAsync(() -> {
            try {
                if (renamed) {
                    Library.rename(this.file, newName);
                }
                if (fromSource != null) {
                    Library.setPreview(this.file, fromSource);
                }
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, Util.ioPool()).whenComplete((nothing, error) -> game.execute(() -> {
            this.saving = false;
            if (error != null) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                Host.LOGGER.warn("Dailies: a recording's name or picture could not be changed", cause);
                Notices.show(Component.translatable("dailies.ui.notice.refused", String.valueOf(cause.getMessage())));
                return;
            }
            if (STILL.equals(chosen)) {
                takeStill(this.file, back, this.after);
            } else {
                game.setScreen(back);
                this.after.run();
            }
        }));
    }

    /**
     * Takes a still of the view at the tick the open recording is at and makes it the recording's preview. The
     * screens are put away while it is taken, so it holds the world and nothing else, and the one given comes back
     * afterwards. Client thread.
     */
    static void takeStill(Path file, @Nullable Screen back, Runnable after) {
        Minecraft game = Minecraft.getInstance();
        RecordingFile open = Playback.loaded() ? Playback.file() : null;
        if (open == null || !open.file().toAbsolutePath().normalize().equals(file.toAbsolutePath().normalize())) {
            Notices.show(t("still.not_open"));
            return;
        }
        Path output;
        try {
            output = Files.createTempDirectory("dailies-preview").resolve("preview.png");
        } catch (Exception e) {
            Notices.show(t("still.failed", String.valueOf(e.getMessage())));
            return;
        }
        Export.Settings settings = new Export.Settings();
        settings.output = output;
        settings.width = STILL_WIDTH;
        settings.height = STILL_HEIGHT;
        settings.still = true;
        settings.hideGui = true;
        game.setScreen(null);
        try {
            Export.start(settings);
        } catch (Exception e) {
            game.setScreen(back);
            Notices.show(t("still.failed", String.valueOf(e.getMessage())));
            return;
        }
        Thread waiter = new Thread(() -> {
            Component trouble = null;
            try {
                long deadline = System.currentTimeMillis() + 60_000;
                while (Export.active() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(50);
                }
                Path written = Export.lastOutput();
                if (Export.active() || Export.lastError() != null || written == null || !Files.isRegularFile(written)) {
                    trouble = Export.lastError() != null ? Component.literal(Export.lastError()) : t("still.no_picture");
                } else {
                    Library.setPreview(file, Files.readAllBytes(written));
                }
            } catch (Exception e) {
                trouble = Component.literal(String.valueOf(e.getMessage()));
            } finally {
                try {
                    Files.deleteIfExists(output);
                    Files.deleteIfExists(output.getParent());
                } catch (Exception ignored) {
                    // A small file in the temporary folder
                }
            }
            Component said = trouble;
            game.execute(() -> {
                game.setScreen(back);
                Notices.show(said == null ? t("still.done") : t("still.failed", said));
                after.run();
            });
        }, "Dailies-Preview-Still");
        waiter.setDaemon(true);
        waiter.start();
    }

    @Override
    public void removed() {
        super.removed();
        this.textures.values().forEach(LibraryBits.Picture::close);
        this.textures.clear();
    }

    @Override
    public void onClose() {
        Objects.requireNonNull(this.minecraft).setScreen(this.parent);
    }

}
