package comedymonkey.dailies.ui;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.config.DailiesConfig;
import comedymonkey.dailies.config.Setting;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Dailies' own settings screen (specification CFG-4), in place of the game's standard one: every setting in its group,
 * a dropdown for every choice, a slider and a box for every number, a search across names and descriptions, groups
 * that fold, and a way back to the default for each setting and each group.
 * <p>
 * It is built from the settings as {@link DailiesConfig} declares them, never from a list of its own, so a setting
 * added there appears here by itself. The file stays NeoForge's: values are read from it on every frame, so an edit
 * made by hand shows as soon as the game has read the file again, and written through the same path the MCP tools use.
 */
public final class SettingsScreen extends KitScreen {

    private static final String KEYS = "dailies.settings.";
    /** In a dropdown of suggestions, the line that stands for "something else, typed in the box beside it" */
    private static final String CUSTOM = "\u0000custom";

    /** The groups that are open. Kept between visits, so the screen comes back as it was left */
    private static final Set<String> UNFOLDED = new HashSet<>();
    private static boolean visited = false;
    /** Lists of known values for settings that are stored as plain text, by a pattern for the setting's name */
    private static final Map<Pattern, List<String>> OFFERED = new LinkedHashMap<>();
    /** One at a time and in order, so that two changes to one setting cannot overtake each other on their way to the file */
    private static final ExecutorService SAVER = Executors.newSingleThreadExecutor(work -> {
        Thread thread = new Thread(work, "Dailies-Settings-Save");
        thread.setDaemon(true);
        return thread;
    });

    private static final List<String> COLOURS = List.of("red", "orange", "yellow", "green", "cyan", "blue", "purple", "white");
    private static final List<String> LANGUAGES = List.of("", "en", "de", "fr", "es", "it", "pt", "nl", "sv", "no", "da", "fi", "pl", "cs", "uk", "ru",
            "tr", "el", "ar", "he", "hi", "id", "vi", "th", "zh", "ja", "ko");

    static {
        offer("marker\\d+Colour", COLOURS);
        offer("caption(Colour|Outline)", List.of("white", "yellow", "black", "red", "orange", "green", "cyan", "blue", "purple"));
        offer(".*Language", LANGUAGES);
        // No setting holds one of these yet; the lists are the ones the export panel offers, ready for when one does
        offer("(?i).*(video)?codec", List.of("h264", "h265", "av1", "vp9"));
        offer("(?i).*(sound|audio)codec", List.of("auto", "aac", "opus", "pcm"));
        offer("(?i).*container", List.of("mp4", "mkv", "webm", "mov"));
    }

    /** Changes on their way to the file: what the screen shows for a setting until the save is done */
    private final Map<String, Object> pending = new ConcurrentHashMap<>();
    /** What a slider is being dragged through, shown but not yet set */
    private final Map<String, Object> trying = new ConcurrentHashMap<>();
    private final Form list = new Form();
    private final Map<String, Row> rows = new LinkedHashMap<>();
    private String query = "";
    private Entry search;
    private Gadget.Push clear;
    private Gadget.Push file;
    private Gadget.Push done;

    private SettingsScreen(@Nullable Screen parent) {
        super(Component.translatable(KEYS + "title"), parent);
    }

    /** Opens the settings. From the mods list, the pause menu, the title screen and the /dailies config command */
    public static void open(@Nullable Screen parent) {
        Minecraft.getInstance().setScreen(new SettingsScreen(parent));
    }

    /** The screen without putting it up, for the mods list, which does that itself */
    public static Screen create(@Nullable Screen parent) {
        return new SettingsScreen(parent);
    }

    /**
     * Gives the settings whose names fit a pattern a dropdown of known values, with a box beside it for any other.
     * For settings stored as text whose usual values are a short list: codecs, colours, languages.
     *
     * @param namePattern a regular expression the whole name of the setting must fit; a later offer for the same
     *                    setting takes the place of an earlier one
     */
    public static void offer(String namePattern, List<String> values) {
        OFFERED.put(Pattern.compile(namePattern), List.copyOf(values));
    }

    private static @Nullable List<String> offeredFor(String name) {
        List<String> found = null;
        for (Map.Entry<Pattern, List<String>> entry : OFFERED.entrySet()) {
            if (entry.getKey().matcher(name).matches()) {
                found = entry.getValue();
            }
        }
        return found;
    }

    private static MutableComponent t(String key, Object... arguments) {
        return Component.translatable(KEYS + key, arguments);
    }

    // ------------------------------------------------------------------ The words for a setting

    private static Component label(Setting<?> setting) {
        String key = "dailies.configuration." + setting.name();
        return I18n.exists(key) ? Component.translatable(key) : Component.literal(Kit.pretty(Find.plain(setting.name())));
    }

    private static Component groupTitle(String group) {
        String key = "dailies.configuration." + group;
        return I18n.exists(key) ? Component.translatable(key) : Component.literal(Kit.pretty(Find.plain(group)));
    }

    private static String groupComment(String group) {
        try {
            String comment = DailiesConfig.SPEC.getLevelComment(List.of(group));
            return comment == null ? "" : comment.trim();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static Component choiceName(String id) {
        String key = KEYS + "choice." + id.toLowerCase(Locale.ROOT);
        return I18n.exists(key) ? Component.translatable(key) : Component.literal(Kit.pretty(id.toLowerCase(Locale.ROOT)));
    }

    private static Component languageName(String code) {
        if (code.isEmpty()) {
            return t("language.detect");
        }
        String name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH);
        return Component.literal(name.isBlank() || name.equalsIgnoreCase(code) ? code : name + " (" + code + ")");
    }

    /** A value as words: a number without needless digits, a choice by its name in the file */
    private static String words(@Nullable Object value) {
        if (value instanceof Enum<?> constant) {
            return constant.name();
        }
        if (value instanceof Number number) {
            return Kit.number(number.doubleValue());
        }
        return value == null ? "" : String.valueOf(value);
    }

    private static Component shownValue(Setting<?> setting, @Nullable Object value) {
        return switch (setting.kind()) {
            case BOOLEAN -> Component.translatable(Boolean.TRUE.equals(value) ? "dailies.ui.switch.on" : "dailies.ui.switch.off");
            case CHOICE -> choiceName(words(value));
            default -> words(value).isEmpty() ? t("empty") : Component.literal(words(value));
        };
    }

    /** What hovering over a setting shows: what it does, the range it must keep to, its default, and its name in the file */
    private static Component describe(Setting<?> setting) {
        MutableComponent all = Component.empty().append(Component.literal(setting.comment()));
        if (setting.min() != null && setting.max() != null) {
            all.append("\n").append(t("range", words(setting.min()), words(setting.max())).withStyle(ChatFormatting.GRAY));
        }
        all.append("\n").append(t("default", shownValue(setting, setting.fallback())).withStyle(ChatFormatting.GRAY));
        all.append("\n").append(Component.literal(setting.group() + "." + setting.name()).withStyle(ChatFormatting.DARK_GRAY));
        return all;
    }

    // ------------------------------------------------------------------ Reading and writing

    /** The value as it will be once anything changed here has reached the file */
    private Object now(Setting<?> setting) {
        Object tried = this.trying.get(setting.name());
        if (tried != null) {
            return tried;
        }
        Object waiting = this.pending.get(setting.name());
        return waiting != null ? waiting : setting.get();
    }

    private boolean changed(Setting<?> setting) {
        Object now = this.now(setting);
        Object usual = setting.fallback();
        if (now instanceof Number a && usual instanceof Number b) {
            return a.doubleValue() != b.doubleValue();
        }
        return !Objects.equals(words(now), words(usual));
    }

    /** Sets a setting and writes the file, away from the client thread. A refusal comes back as a notice */
    private void save(Setting<?> setting, Object value) {
        String name = setting.name();
        this.trying.remove(name);
        this.pending.put(name, value);
        Minecraft minecraft = Minecraft.getInstance();
        SAVER.execute(() -> {
            Component trouble = null;
            try {
                Host.get().saveSetting(name, value);
            } catch (IllegalArgumentException refused) {
                trouble = Component.literal(String.valueOf(refused.getMessage()));
            } catch (RuntimeException | LinkageError e) {
                Host.LOGGER.error("Dailies: the setting {} could not be saved", name, e);
                trouble = t("save_failed");
            }
            Component said = trouble;
            minecraft.execute(() -> {
                this.pending.remove(name, value);
                if (said != null) {
                    Notices.show(said);
                }
            });
        });
    }

    private void reset(Setting<?> setting) {
        Object usual = setting.fallback();
        this.save(setting, usual instanceof Enum<?> constant ? constant.name() : usual);
    }

    // ------------------------------------------------------------------ Building

    @Override
    void build() {
        this.search = new Entry(() -> this.query, typed -> {
            this.find(typed);
            return null;
        }).live(typed -> {
            this.find(typed);
            return null;
        }).max(60).hint(t("search.hint"));
        this.search.tip(t("search.tip"));
        this.clear = Gadget.Push.icon(Kit.Icon.CROSS, t("search.clear"), () -> {
            Entry.drop();
            this.find("");
        });
        this.clear.when(() -> !this.query.isEmpty());
        this.file = new Gadget.Push(t("open_file"), SettingsScreen::openFile);
        this.file.tip(t("open_file.tip"));
        this.done = new Gadget.Push(t("done"), this::onClose);
        this.fixed.add(this.search);
        this.fixed.add(this.clear);
        this.fixed.add(this.file);
        this.fixed.add(this.done);
        this.forms.add(this.list);
        if (!visited) {
            visited = true;
            // The first group open, so that the screen shows what a setting looks like; the rest as a table of contents
            for (Setting<?> setting : DailiesConfig.all()) {
                UNFOLDED.add(setting.group());
                break;
            }
        }
        this.rebuild();
    }

    private static void openFile() {
        Path path = FMLPaths.CONFIGDIR.get().resolve("dailies-client.toml");
        Controls.offThread(() -> Util.getPlatform().openPath(path), t("open_file.failed"), () -> {
        });
    }

    private boolean searching() {
        return !this.query.isBlank();
    }

    private void find(String words) {
        String wanted = words == null ? "" : words;
        if (!wanted.equals(this.query)) {
            this.query = wanted;
            this.rebuild();
            this.list.scrollToTop();
        }
    }

    private void rebuild() {
        this.list.clear();
        this.rows.clear();
        Map<String, List<Setting<?>>> groups = new LinkedHashMap<>();
        for (Setting<?> setting : DailiesConfig.all()) {
            groups.computeIfAbsent(setting.group(), group -> new ArrayList<>()).add(setting);
        }
        for (Map.Entry<String, List<Setting<?>>> group : groups.entrySet()) {
            String name = group.getKey();
            String title = groupTitle(name).getString();
            String comment = groupComment(name);
            List<Setting<?>> fitting = new ArrayList<>();
            for (Setting<?> setting : group.getValue()) {
                if (Find.matches(this.query, label(setting).getString(), setting.name(), setting.comment(), title, comment)) {
                    fitting.add(setting);
                }
            }
            if (fitting.isEmpty()) {
                continue;
            }
            Gadget.Fold fold = new Gadget.Fold(() -> this.searching() || UNFOLDED.contains(name));
            for (Setting<?> setting : fitting) {
                Row row = new Row(setting);
                this.rows.put(setting.name(), row);
                fold.add(row);
            }
            this.list.add(new GroupHead(name, Component.literal(title), comment, fitting));
            this.list.add(fold);
        }
        if (this.rows.isEmpty()) {
            this.list.add(new Gadget.Note(() -> t("search.none", this.query), Controls.PARCHMENT_DIM));
        }
    }

    @Override
    void arrange(Font font) {
        this.centre(580, this.height - 20);
        int wide = this.right - this.left;
        int searchWidth = Math.max(70, Math.min(190, wide - 150));
        this.clear.clip(Integer.MIN_VALUE, Integer.MAX_VALUE);
        // Only there while there is a search to clear
        this.clear.place(font, this.right - 8 - 14, this.top + 5, 14, this.query.isEmpty() ? 0 : Kit.ROW);
        this.search.clip(Integer.MIN_VALUE, Integer.MAX_VALUE);
        int cross = this.query.isEmpty() ? 0 : 14 + Kit.GAP;
        this.search.place(font, this.right - 8 - 14 - Kit.GAP - searchWidth, this.top + 5, searchWidth + 14 + Kit.GAP - cross, Kit.ROW);
        int head = this.headingHeight(font, null);
        int foot = Kit.ROW + 2 + 8 + 6;
        this.list.place(font, this.left + 5, this.top + head, wide - 10, this.bottom - foot - this.top - head);
        this.foot(font, this.file, this.done);
    }

    @Override
    void paint(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
        this.heading(graphics, font, null);
        Kit.icon(graphics, Kit.Icon.SEARCH, this.search.x - 8, this.search.y + Kit.ROW / 2, Controls.PARCHMENT_DIM);
        int different = 0;
        for (Setting<?> setting : DailiesConfig.all()) {
            if (this.changed(setting)) {
                different++;
            }
        }
        Component count = this.searching() ? t("count.found", this.rows.size(), DailiesConfig.all().size())
                : different == 0 ? t("count.none") : different == 1 ? t("count.one") : t("count.many", different);
        Kit.text(graphics, font, Kit.clip(font, count.getString(), this.file.x - this.left - 16), this.left + 8, this.bottom - 8 - Kit.ROW + 2, Controls.PARCHMENT_DIM);
    }

    /** Tab goes from the search down through the settings and only then to the buttons at the foot */
    @Override
    List<Gadget> order() {
        List<Gadget> order = new ArrayList<>();
        this.search.collect(order);
        this.clear.collect(order);
        this.list.collect(order);
        this.file.collect(order);
        this.done.collect(order);
        return order;
    }

    @Override
    void typedElsewhere(char character, int modifiers) {
        // Typing anywhere starts a search, as it does in a list of files
        if (character > ' ' && character != 127) {
            this.search.focus();
            this.search.typed(character, modifiers);
        }
    }

    @Override
    boolean keyElsewhere(int keyCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_F && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0) {
            this.search.focus();
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ For the scripted test and the manual's pictures

    /** Types into the search box */
    public void search(String words) {
        Entry.drop();
        this.find(words);
    }

    /** The names of the settings on show, in order: all of them, or those the search found. Folded groups count */
    public List<String> shown() {
        return List.copyOf(this.rows.keySet());
    }

    public boolean folded(String group) {
        return !UNFOLDED.contains(group);
    }

    public void fold(String group, boolean folded) {
        if (folded) {
            UNFOLDED.remove(group);
        } else {
            UNFOLDED.add(group);
        }
    }

    /** Whether a change made here is still on its way to the file */
    public boolean saving() {
        return !this.pending.isEmpty();
    }

    public boolean changed(String name) {
        Setting<?> setting = DailiesConfig.find(name);
        return setting != null && this.changed(setting);
    }

    /**
     * Opens the dropdown of a setting, as a click on it would. With a choice, goes on to choose that line of the list.
     *
     * @return null when it was done, else why it could not be
     */
    public @Nullable String choose(String name, @Nullable String choice) {
        Row row = this.rows.get(name);
        if (row == null || row.dropdown == null) {
            return "'" + name + "' has no dropdown on show";
        }
        row.dropdown.open();
        Menu menu = Menu.open;
        if (menu == null || menu.owner != row.dropdown) {
            return "the dropdown of '" + name + "' did not open";
        }
        if (choice == null) {
            return null;
        }
        int place = row.dropdown.offered().indexOf(choice);
        if (place < 0 || !menu.pick(place)) {
            Menu.close();
            return "'" + choice + "' is not in the dropdown of '" + name + "': " + row.dropdown.offered();
        }
        return null;
    }

    /**
     * Types a value into the box of a setting and presses Enter.
     *
     * @return null when it was taken, else the refusal the box shows
     */
    public @Nullable String type(String name, String words) {
        Row row = this.rows.get(name);
        if (row == null || row.box == null) {
            return "'" + name + "' has no box on show";
        }
        Component refusal = row.box.enter(words);
        return refusal == null ? null : refusal.getString();
    }

    /** Presses the reset button of a setting */
    public void reset(String name) {
        Setting<?> setting = DailiesConfig.find(name);
        if (setting != null) {
            this.reset(setting);
        }
    }

    /** Scrolls a setting into view, unfolding its group, and puts the mouse's place over it for a picture */
    public void reveal(String name) {
        Row row = this.rows.get(name);
        if (row != null) {
            UNFOLDED.add(row.setting.group());
            for (Gadget gadget : this.list.rows()) {
                if (gadget instanceof Gadget.Fold fold) {
                    fold.settle();
                }
            }
            this.arrange(this.font);
            this.list.reveal(row);
            this.arrange(this.font);
        }
    }

    /** Where a setting is on the screen: left and top; null when it is not on show */
    public int @Nullable [] where(String name) {
        Row row = this.rows.get(name);
        return row == null ? null : new int[]{row.x, row.y};
    }

    // ------------------------------------------------------------------ The heading of a group

    /** The heading of a group: a click folds or unfolds it, and it carries the way back to the defaults for all of it */
    private final class GroupHead extends Gadget {
        private final String group;
        private final Component title;
        private final String comment;
        private final List<Setting<?>> settings;
        private final Gadget.Push reset;

        GroupHead(String group, Component title, String comment, List<Setting<?>> settings) {
            this.group = group;
            this.title = title;
            this.comment = comment;
            this.settings = settings;
            this.reset = new Gadget.Push(t("reset_group"), () -> {
                for (Setting<?> setting : settings) {
                    if (SettingsScreen.this.changed(setting)) {
                        SettingsScreen.this.reset(setting);
                    }
                }
            });
            this.reset.when(() -> this.different() > 0).tip(t("reset_group.tip", title));
        }

        private int different() {
            int count = 0;
            for (Setting<?> setting : this.settings) {
                if (SettingsScreen.this.changed(setting)) {
                    count++;
                }
            }
            return count;
        }

        private boolean open() {
            return SettingsScreen.this.searching() || UNFOLDED.contains(this.group);
        }

        @Override
        int heightFor(Font font, int forWidth) {
            return 22;
        }

        @Override
        boolean focusable() {
            return true;
        }

        @Override
        void collect(List<Gadget> order) {
            order.add(this);
            this.reset.collect(order);
        }

        @Override
        void clip(int top, int bottom) {
            super.clip(top, bottom);
            this.reset.clip(top, bottom);
        }

        @Override
        void place(Font font, int newX, int newY, int newWidth, int newHeight) {
            super.place(font, newX, newY, newWidth, newHeight);
            int wide = this.reset.wantedWidth(font) + 4;
            this.reset.place(font, newX + newWidth - wide - 2, newY + 5, wide, Kit.ROW);
        }

        private void toggle() {
            if (SettingsScreen.this.searching()) {
                return;
            }
            Kit.click();
            if (!UNFOLDED.remove(this.group)) {
                UNFOLDED.add(this.group);
            }
        }

        @Override
        boolean key(int keyCode, int modifiers) {
            if (activates(keyCode)) {
                this.toggle();
                return true;
            }
            return false;
        }

        @Override
        void draw(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
            boolean hovered = this.over(mouseX, mouseY) && !this.reset.over(mouseX, mouseY);
            int top = this.y + 3;
            int bottom = this.y + this.height - 1;
            graphics.fill(this.x, top, this.x + this.width, bottom, hovered ? 0xFF3F2D1A : 0xFF33230F);
            graphics.fill(this.x, top, this.x + this.width, top + 1, 0x14FFFFFF);
            graphics.fill(this.x, bottom - 1, this.x + this.width, bottom, Controls.BRASS_DIM);
            if (focus == this) {
                Kit.focusRing(graphics, this.x, top, this.width, bottom - top);
            }
            int middle = top + (bottom - top) / 2;
            Kit.icon(graphics, this.open() ? Kit.Icon.FOLD_DOWN : Kit.Icon.FOLD_RIGHT, this.x + 9, middle - 1, Controls.BRASS_BRIGHT);
            int textY = middle - 4;
            String title = this.title.getString();
            Kit.text(graphics, font, title, this.x + 18, textY, Controls.BRASS_BRIGHT);
            int after = this.x + 18 + font.width(title) + 8;
            int different = this.different();
            int right = different > 0 ? this.reset.x - 6 : this.x + this.width - 8;
            int size = this.settings.size();
            String count = different > 0 ? t(size == 1 ? "group.changed_one" : "group.changed", size, different).getString()
                    : t(size == 1 ? "group.count_one" : "group.count", size).getString();
            int countWidth = font.width(count);
            if (right - countWidth > after) {
                Kit.text(graphics, font, count, right - countWidth, textY, different > 0 ? Controls.PARCHMENT : Controls.PARCHMENT_FAINT);
                right -= countWidth + 8;
            }
            // A group whose description only repeats its name is better off without it
            if (right - after > 40 && !this.comment.equalsIgnoreCase(title) && !this.comment.equalsIgnoreCase("The " + title)) {
                Kit.text(graphics, font, Kit.clip(font, this.comment, right - after), after, textY, Controls.PARCHMENT_DIM);
            }
            if (different > 0) {
                this.reset.draw(graphics, font, mouseX, mouseY);
            }
        }

        @Override
        @Nullable Gadget press(double mouseX, double mouseY, int button) {
            Gadget hit = this.reset.press(mouseX, mouseY, button);
            if (hit != null) {
                return hit;
            }
            if (button != 0 || !this.over(mouseX, mouseY)) {
                return null;
            }
            this.toggle();
            return this;
        }

        @Override
        @Nullable Component tipAt(double mouseX, double mouseY) {
            Component inner = this.different() > 0 ? this.reset.tipAt(mouseX, mouseY) : null;
            if (inner != null) {
                return inner;
            }
            if (!this.over(mouseX, mouseY)) {
                return null;
            }
            return Component.empty().append(Component.literal(this.comment.isEmpty() ? this.title.getString() : this.comment)).append("\n")
                    .append(t(this.open() ? "group.fold" : "group.unfold").withStyle(ChatFormatting.GRAY));
        }
    }

    // ------------------------------------------------------------------ One setting

    /** One setting: a mark when it is not at its default, its name, the control that fits its kind, and its way back to the default */
    private final class Row extends Gadget {
        private final Setting<?> setting;
        private final Component label;
        private final Gadget control;
        private final Gadget.Push reset;
        /** The dropdown and the box of the control, where it has them */
        private @Nullable Gadget.Cycle<String> dropdown;
        private @Nullable Entry box;
        private boolean cut = false;

        Row(Setting<?> setting) {
            this.setting = setting;
            this.label = label(setting);
            this.control = this.controlFor(setting);
            this.reset = Gadget.Push.icon(Kit.Icon.RESET, t("reset"), () -> SettingsScreen.this.reset(setting));
            this.reset.when(() -> SettingsScreen.this.changed(setting));
            this.reset.liveTip(() -> SettingsScreen.this.changed(setting)
                    ? t("reset.tip", shownValue(setting, setting.fallback()))
                    : t("reset.unchanged"));
        }

        private String text() {
            return words(SettingsScreen.this.now(this.setting));
        }

        private Gadget controlFor(Setting<?> setting) {
            switch (setting.kind()) {
                case BOOLEAN:
                    return new Gadget.Switch(() -> Boolean.TRUE.equals(SettingsScreen.this.now(setting)), on -> SettingsScreen.this.save(setting, on));
                case INTEGER:
                case DECIMAL:
                    return this.number(setting);
                case CHOICE: {
                    String[] names = setting.choices();
                    this.dropdown = new Gadget.Dropdown<>(names == null ? List.of() : List.of(names), SettingsScreen::choiceName, this::text,
                            id -> SettingsScreen.this.save(setting, id));
                    return this.dropdown;
                }
                default:
                    break;
            }
            Checks.AddressKind address = Checks.addressKind(setting.name());
            if (address != null) {
                AddressControl control = new AddressControl(setting, address);
                this.box = control.box;
                return control;
            }
            List<String> offered = offeredFor(setting.name());
            if (offered != null) {
                return this.suggested(setting, offered);
            }
            this.box = new Entry(this::text, typed -> {
                SettingsScreen.this.save(setting, typed);
                return null;
            });
            return this.box;
        }

        /** A slider and a box, both kept to the range. A wide range is slid through by ratio, or its low end could not be reached */
        private Gadget number(Setting<?> setting) {
            boolean whole = setting.kind() == Setting.Kind.INTEGER;
            double min = setting.min() == null ? 0.0 : setting.min().doubleValue();
            double max = setting.max() == null ? 100.0 : setting.max().doubleValue();
            boolean ratio = min > 0.0 && max / min >= 500.0;
            String name = setting.name();
            Gadget.Slide slide = new Gadget.Slide(min, max, ratio,
                    () -> SettingsScreen.this.now(setting) instanceof Number number ? number.doubleValue() : min,
                    value -> SettingsScreen.this.trying.put(name, asValue(value, whole)),
                    value -> SettingsScreen.this.save(setting, asValue(Checks.clamp(value, min, max, whole), whole)),
                    Kit::number);
            if (whole) {
                slide.whole();
            }
            this.box = new Entry(this::text, typed -> {
                Checks.Number number = Checks.number(typed, min, max, whole);
                if (!number.ok()) {
                    return problem(number, min, max);
                }
                SettingsScreen.this.save(setting, asValue(number.value(), whole));
                return null;
            }).live(typed -> {
                Checks.Number number = Checks.number(typed, min, max, whole);
                return number.ok() ? null : problem(number, min, max);
            });
            this.box.wide(58);
            return new Gadget.Line(slide, this.box);
        }

        private static Object asValue(double value, boolean whole) {
            if (whole) {
                return (int) Math.rint(value);
            }
            // What a slider gives is tidied to what a person would have typed
            return Math.rint(value * 1000.0) / 1000.0;
        }

        private static Component problem(Checks.Number number, double min, double max) {
            return switch (number.problem()) {
                case NOT_A_NUMBER -> t("number.not");
                case NOT_WHOLE -> t("number.whole", Kit.number(number.nearest()));
                case TOO_LOW -> t("number.low", Kit.number(min), Kit.number(number.nearest()));
                case TOO_HIGH -> t("number.high", Kit.number(max), Kit.number(number.nearest()));
                case NONE -> Component.empty();
            };
        }

        /** A dropdown of the usual values with a box beside it for any other */
        private Gadget suggested(Setting<?> setting, List<String> offered) {
            boolean colour = offered == COLOURS;
            boolean language = offered == LANGUAGES;
            List<String> choices = new ArrayList<>(offered);
            choices.add(CUSTOM);
            this.box = new Entry(this::text, typed -> {
                String wanted = typed.trim();
                if (colour && !COLOURS.contains(wanted.toLowerCase(Locale.ROOT)) && !wanted.matches("#?[0-9a-fA-F]{6}")) {
                    return t("colour.wrong");
                }
                if (language && !wanted.matches("[A-Za-z]{0,3}(-[A-Za-z0-9]{2,8})?")) {
                    return t("language.wrong");
                }
                SettingsScreen.this.save(setting, wanted);
                return null;
            });
            Entry box = this.box;
            this.dropdown = new Gadget.Dropdown<>(choices,
                    id -> id.equals(CUSTOM) ? t("custom") : language ? languageName(id) : choiceName(id),
                    () -> offered.contains(this.text()) ? this.text() : CUSTOM,
                    id -> {
                        if (id.equals(CUSTOM)) {
                            box.focus();
                        } else {
                            SettingsScreen.this.save(setting, id);
                        }
                    });
            if (colour) {
                this.dropdown.swatch(id -> id.equals(CUSTOM) ? Controls.colourOf(this.text()) : Controls.colourOf(id));
                box.swatch(() -> Controls.colourOf(this.text()));
            }
            box.wide(colour ? 76 : 58);
            box.tip(t(colour ? "colour.tip" : "custom.tip"));
            return new Gadget.Line(this.dropdown, box);
        }

        private int labelWidth(int of) {
            return Math.max(60, Math.min(210, of * 43 / 100));
        }

        private int controlWidth(int of) {
            return Math.max(40, of - this.labelWidth(of) - 14 - Kit.GAP * 2);
        }

        @Override
        void collect(List<Gadget> order) {
            this.control.collect(order);
            this.reset.collect(order);
        }

        @Override
        int heightFor(Font font, int forWidth) {
            return Math.max(Kit.ROW, this.control.heightFor(font, this.controlWidth(forWidth)));
        }

        @Override
        void clip(int top, int bottom) {
            super.clip(top, bottom);
            this.control.clip(top, bottom);
            this.reset.clip(top, bottom);
        }

        @Override
        void place(Font font, int newX, int newY, int newWidth, int newHeight) {
            super.place(font, newX, newY, newWidth, newHeight);
            int label = this.labelWidth(newWidth);
            int wide = this.controlWidth(newWidth);
            this.control.place(font, newX + label, newY, wide, Math.max(1, this.control.heightFor(font, wide)));
            this.reset.place(font, newX + newWidth - 14 - 2, newY, 14, Kit.ROW);
        }

        @Override
        void draw(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
            boolean different = SettingsScreen.this.changed(this.setting);
            if (this.over(mouseX, mouseY)) {
                // The row under the mouse, so the eye can follow from a name to its control
                graphics.fill(this.x, this.y - 1, this.x + this.width, this.y + this.height + 1, 0x18D4B25A);
            }
            if (different) {
                Kit.diamond(graphics, this.x + 6, this.y + Kit.ROW / 2, 2, Controls.BRASS_BRIGHT);
            }
            int room = this.labelWidth(this.width) - 18;
            String words = this.label.getString();
            this.cut = font.width(words) > room;
            Kit.text(graphics, font, Kit.clip(font, words, room), this.x + 13, this.y + 3, different ? Controls.PARCHMENT : 0xFFD8CDB8);
            this.control.draw(graphics, font, mouseX, mouseY);
            if (different) {
                this.reset.draw(graphics, font, mouseX, mouseY);
            }
        }

        @Override
        @Nullable Gadget press(double mouseX, double mouseY, int button) {
            Gadget hit = this.control.press(mouseX, mouseY, button);
            return hit != null ? hit : this.reset.press(mouseX, mouseY, button);
        }

        @Override
        @Nullable Component tipAt(double mouseX, double mouseY) {
            if (!this.over(mouseX, mouseY)) {
                return null;
            }
            Component inner = this.reset.tipAt(mouseX, mouseY);
            if (inner != null) {
                return SettingsScreen.this.changed(this.setting) ? inner : null;
            }
            inner = this.control.tipAt(mouseX, mouseY);
            if (inner != null) {
                return inner;
            }
            Component all = describe(this.setting);
            return this.cut ? Component.empty().append(this.label).append("\n").append(all) : all;
        }

        @Override
        void forget() {
            this.control.forget();
        }
    }

    // ------------------------------------------------------------------ The address of a server

    /**
     * A box for the address of a server, looked at as it is typed, with a button that asks the server whether it is
     * there and says what came back. Nothing is ever asked of any server except by pressing that button.
     */
    private final class AddressControl extends Gadget {
        private final Checks.AddressKind kind;
        private final Entry box;
        private final Gadget.Push test;
        private @Nullable Component result = null;
        private int resultColour = Controls.PARCHMENT_DIM;
        /** Goes up with every test, so that the answer to an earlier one is not shown for a later address */
        private int asked = 0;
        private boolean asking = false;

        AddressControl(Setting<?> setting, Checks.AddressKind kind) {
            this.kind = kind;
            this.box = new Entry(() -> words(SettingsScreen.this.now(setting)), typed -> {
                Component wrong = this.wrong(typed);
                if (wrong != null) {
                    return wrong;
                }
                SettingsScreen.this.save(setting, typed.trim());
                return null;
            }).live(typed -> {
                // What it said of another address says nothing of this one
                this.result = null;
                this.asked++;
                this.asking = false;
                return this.wrong(typed);
            });
            this.test = new Gadget.Push(t("test"), this::ask);
            this.test.wide(36);
            this.test.when(() -> !this.asking && !this.box.text().isBlank() && this.wrong(this.box.text()) == null);
            this.test.tip(t("test.tip"));
        }

        private @Nullable Component wrong(String typed) {
            Checks.AddressProblem problem = this.kind == Checks.AddressKind.URL ? Checks.url(typed) : Checks.host(typed);
            return switch (problem) {
                case NONE, EMPTY -> null;
                case NO_SCHEME -> t("address.no_scheme");
                case NOT_HTTP -> t("address.not_http");
                case NO_HOST -> t("address.no_host");
                case BAD_PORT -> t("address.bad_port");
                case BAD_CHARACTERS -> t("address.bad_characters");
            };
        }

        /** Asks whatever is in the box now, saved or not, so an address can be tried before it is kept */
        private void ask() {
            String address = this.box.text().trim();
            if (address.isEmpty() || this.wrong(address) != null) {
                return;
            }
            int mine = ++this.asked;
            this.asking = true;
            this.result = t("test.asking");
            this.resultColour = Controls.PARCHMENT_DIM;
            Minecraft minecraft = Minecraft.getInstance();
            (this.kind == Checks.AddressKind.URL ? Probe.ask(address, 3000) : Probe.knock(address, Host.get().setting("mcpPort", 25595), 3000))
                    .thenAccept(answer -> minecraft.execute(() -> {
                        if (mine != this.asked) {
                            return;
                        }
                        this.asking = false;
                        if (answer.answered()) {
                            this.result = answer.status() > 0 ? t("test.answers_status", answer.status(), answer.millis()) : t("test.answers", answer.millis());
                            this.resultColour = Controls.VERDIGRIS;
                        } else {
                            this.result = answer.problem().equals("other") ? t("test.failed", answer.detail()) : t("test." + answer.problem());
                            this.resultColour = Controls.ALERT;
                        }
                    }));
        }

        @Override
        void collect(List<Gadget> order) {
            this.box.collect(order);
            this.test.collect(order);
        }

        private int boxWidth(int of) {
            return Math.max(30, of - 36 - Kit.GAP);
        }

        @Override
        int heightFor(Font font, int forWidth) {
            return this.box.heightFor(font, this.boxWidth(forWidth)) + (this.result == null ? 0 : 11);
        }

        @Override
        void clip(int top, int bottom) {
            super.clip(top, bottom);
            this.box.clip(top, bottom);
            this.test.clip(top, bottom);
        }

        @Override
        void place(Font font, int newX, int newY, int newWidth, int newHeight) {
            super.place(font, newX, newY, newWidth, newHeight);
            int wide = this.boxWidth(newWidth);
            this.box.place(font, newX, newY, wide, this.box.heightFor(font, wide));
            this.test.place(font, newX + wide + Kit.GAP, newY, 36, Kit.ROW);
        }

        @Override
        void draw(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
            this.box.draw(graphics, font, mouseX, mouseY);
            this.test.draw(graphics, font, mouseX, mouseY);
            Component said = this.result;
            if (said != null) {
                Kit.text(graphics, font, Kit.clip(font, said.getString(), this.width - 2), this.x + 1, this.y + this.height - 9, this.resultColour);
            }
        }

        @Override
        @Nullable Gadget press(double mouseX, double mouseY, int button) {
            Gadget hit = this.box.press(mouseX, mouseY, button);
            return hit != null ? hit : this.test.press(mouseX, mouseY, button);
        }

        @Override
        @Nullable Component tipAt(double mouseX, double mouseY) {
            Component inner = this.test.tipAt(mouseX, mouseY);
            if (inner == null && this.result != null && this.over(mouseX, mouseY) && mouseY >= this.y + this.height - 11) {
                inner = this.result;
            }
            return inner;
        }

        @Override
        void forget() {
            this.box.forget();
        }
    }

    /**
     * Puts an address in the box of a setting without keeping it and presses Test. The address stays in the box, not
     * handed over, until {@link #abandon} takes it out again.
     *
     * @return null when the test was started, else why it could not be
     */
    public @Nullable String test(String name, String address) {
        Row row = this.rows.get(name);
        if (row == null || !(row.control instanceof AddressControl control)) {
            return "'" + name + "' has no Test button on show";
        }
        Entry.drop();
        control.box.focus();
        control.box.type(address);
        Component wrong = control.wrong(address);
        if (wrong != null) {
            return wrong.getString();
        }
        control.ask();
        return null;
    }

    /** Leaves the box being typed into without keeping what is in it, as Escape does */
    public void abandon() {
        Entry.drop();
    }

    /** What the Test button of a setting last said; empty while it is still asking or when it has not been pressed */
    public String tested(String name) {
        Row row = this.rows.get(name);
        if (row == null || !(row.control instanceof AddressControl control) || control.asking || control.result == null) {
            return "";
        }
        return (control.resultColour == Controls.VERDIGRIS ? "answered: " : "no answer: ") + control.result.getString();
    }

}
