package comedymonkey.dailies.ui;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.edit.Scene;
import comedymonkey.dailies.export.Codecs;
import comedymonkey.dailies.export.Encoder;
import comedymonkey.dailies.export.Export;
import comedymonkey.dailies.export.ExportPreset;
import comedymonkey.dailies.export.ExportPresets;
import comedymonkey.dailies.export.NamePattern;
import comedymonkey.dailies.file.RecordingManifest;
import comedymonkey.dailies.playback.Playback;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Builds the "Export" tab of the editor's side panel (specification chapter VI), from the top down in the order a
 * person decides things: a preset, then the picture (size, frame rate, kind of file, codec, quality), the sound,
 * where the file goes, and the buttons that start. Choosing a preset fills in everything below it; changing anything
 * below marks the preset as modified.
 * <p>
 * Only codecs this machine can really make are offered (EXP-1), each saying whether a graphics card or software does
 * the work. Finding that out runs the encoder, so it is done once, away from the client thread. What is chosen here
 * is kept for as long as the game runs.
 */
final class ExportForms {

    private static final String PICTURES = "png_sequence";
    private static final List<String> SIZES = List.of("720p", "1080p", "1440p", "4k", "vertical", "custom");
    private static final int[][] SIZE_PIXELS = {{1280, 720}, {1920, 1080}, {2560, 1440}, {3840, 2160}, {1080, 1920}};
    private static final List<String> RATES = List.of("24", "25", "30", "50", "60", "120", "custom");
    private static final List<String> SOUND_CODECS = List.of("auto", "aac", "opus", "pcm");
    private static final List<String> APART = List.of("none", "files", "tracks", "both");
    private static final String DEFAULT_FOLDER = "dailies/exports";

    /** The codec choices that work on this machine, fastest first. Null until the encoder has been asked */
    private static volatile @Nullable List<Codecs.Choice> known = null;
    private static volatile boolean asking = false;

    // What the tab holds beyond what EditorState already keeps
    private static @Nullable String preset = null;
    private static boolean presetChosenOnce = false;
    private static String quality = "high";
    private static boolean stemFiles = false;
    private static boolean stemTracks = false;
    private static boolean overwrite = false;
    private static String folder = DEFAULT_FOLDER;
    /** 0 when no name is being typed, 1 for a new preset's, 2 for a new name for the chosen one */
    private static int naming = 0;
    private static String nameDraft = "";
    /** Goes up whenever the presets change, so the tab is built afresh */
    private static int presetsRevision = 0;

    private ExportForms() {
    }

    // ------------------------------------------------------------------ What can be had

    /** Asks the encoder, once, what it can encode here, and reads the presets, both away from the client thread */
    private static void ask() {
        if (known != null || asking) {
            return;
        }
        asking = true;
        CompletableFuture.runAsync(() -> {
            List<Codecs.Choice> found = List.of();
            try {
                Export.presets().all();
                if (Encoder.bundled()) {
                    found = Encoder.choices();
                }
            } catch (Exception | LinkageError e) {
                Host.LOGGER.warn("Dailies: the encoder could not be asked what it offers, so only pictures can be exported", e);
            }
            known = found;
            asking = false;
        }, Util.ioPool());
    }

    private static List<Codecs.Choice> choices() {
        List<Codecs.Choice> have = known;
        return have == null ? List.of() : have;
    }

    private static List<String> containers() {
        List<String> usable = new ArrayList<>();
        for (String container : Codecs.CONTAINERS) {
            if (choices().stream().anyMatch(choice -> Codecs.holds(container, choice.codec()))) {
                usable.add(container);
            }
        }
        usable.add(PICTURES);
        return usable;
    }

    /** The choice the tab is set to: the encoder picked by hand, else the fastest for the codec */
    private static @Nullable Codecs.Choice choice() {
        List<Codecs.Choice> forCodec = Codecs.forCodec(choices(), EditorState.exportCodec);
        for (Codecs.Choice one : forCodec) {
            if (one.encoder().equals(EditorState.exportEncoder)) {
                return one;
            }
        }
        return forCodec.isEmpty() ? null : forCodec.get(0);
    }

    /** Brings the choices into line with what can be had: a container that exists, a codec it holds and this machine makes */
    private static void settle() {
        if (known == null) {
            return;
        }
        if (!presetChosenOnce) {
            // The first look at the tab starts from the first preset, already fitted to this machine
            presetChosenOnce = true;
            List<ExportPreset> presets = Export.presets().all();
            if (!presets.isEmpty()) {
                apply(presets.get(0));
            }
        }
        if (!containers().contains(EditorState.exportContainer)) {
            EditorState.exportContainer = containers().get(0);
        }
        if (pictures()) {
            return;
        }
        if (!Codecs.holds(EditorState.exportContainer, EditorState.exportCodec) || !Codecs.has(choices(), EditorState.exportCodec)) {
            for (Codecs.Choice one : choices()) {
                if (Codecs.holds(EditorState.exportContainer, one.codec())) {
                    EditorState.exportCodec = one.codec();
                    break;
                }
            }
            EditorState.exportEncoder = "";
        }
        if (!EditorState.exportEncoder.isEmpty() && Codecs.forCodec(choices(), EditorState.exportCodec).stream().noneMatch(one -> one.encoder().equals(EditorState.exportEncoder))) {
            EditorState.exportEncoder = "";
        }
        if (!soundFits(EditorState.exportContainer, EditorState.exportSoundCodec)) {
            EditorState.exportSoundCodec = "auto";
        }
    }

    private static boolean pictures() {
        return EditorState.exportContainer.equals(PICTURES);
    }

    private static boolean master() {
        return Codecs.master(EditorState.exportCodec);
    }

    /** As the export's sound code decides it: what each kind of file can carry */
    private static boolean soundFits(String container, String codec) {
        return switch (container) {
            case "webm" -> codec.equals("auto") || codec.equals("opus");
            case "mp4" -> !codec.equals("pcm");
            case "mov" -> !codec.equals("opus");
            default -> true;
        };
    }

    private static String name() {
        if (EditorState.exportName == null) {
            EditorState.exportName = Host.get().setting("defaultFileName", "{date}_{time}");
        }
        return EditorState.exportName;
    }

    // ------------------------------------------------------------------ Presets

    private static @Nullable ExportPreset chosen() {
        return preset == null || known == null ? null : Export.presets().find(preset);
    }

    /** Fills the tab in from a preset, fitted to what this machine can encode */
    private static void apply(ExportPreset from) {
        preset = from.name();
        EditorState.exportWidth = from.width();
        EditorState.exportHeight = from.height();
        EditorState.exportRate = from.framesPerSecond();
        Codecs.Pick pick = Codecs.pick(from.codecs(), from.container(), choices());
        if (pick.codec() == null) {
            EditorState.exportContainer = PICTURES;
        } else {
            EditorState.exportContainer = pick.container();
            EditorState.exportCodec = pick.codec();
        }
        EditorState.exportEncoder = "";
        quality = from.quality();
        if (quality.equals("custom")) {
            EditorState.exportBitrate = Codecs.bitrateWords(from.bitrate());
        }
        EditorState.exportSound = from.audio();
        EditorState.exportStereo = from.stereo();
        EditorState.exportSoundCodec = from.audioCodec();
        stemFiles = from.stemFiles();
        stemTracks = from.stemTracks();
    }

    /** Whether the tab still holds what the preset fills in */
    private static boolean matches(ExportPreset from) {
        Codecs.Pick pick = Codecs.pick(from.codecs(), from.container(), choices());
        if (EditorState.exportWidth != from.width() || EditorState.exportHeight != from.height() || Math.abs(EditorState.exportRate - from.framesPerSecond()) > 1.0e-9) {
            return false;
        }
        if (pick.codec() == null) {
            return pictures();
        }
        if (!EditorState.exportContainer.equals(pick.container()) || !EditorState.exportCodec.equals(pick.codec()) || !EditorState.exportEncoder.isEmpty()) {
            return false;
        }
        if (!master() && (!quality.equals(from.quality()) || (quality.equals("custom") && Codecs.parseBitrate(EditorState.exportBitrate) != from.bitrate()))) {
            return false;
        }
        if (EditorState.exportSound != from.audio()) {
            return false;
        }
        return !from.audio() || (EditorState.exportStereo == from.stereo() && EditorState.exportSoundCodec.equals(from.audioCodec())
            && stemFiles == from.stemFiles() && stemTracks == from.stemTracks());
    }

    /** What the tab holds now, as a preset of this name. The codecs wanted are kept where the one in use is theirs */
    private static ExportPreset current(String name, @Nullable ExportPreset from) {
        List<String> codecs = List.of(pictures() ? "h264" : EditorState.exportCodec);
        String container = pictures() ? "mp4" : EditorState.exportContainer;
        if (from != null) {
            Codecs.Pick pick = Codecs.pick(from.codecs(), from.container(), choices());
            if (pick.codec() == null ? pictures() : pick.codec().equals(EditorState.exportCodec) && pick.container().equals(EditorState.exportContainer)) {
                // Standing in for a codec this machine lacks: the preset goes on wanting what it wanted
                codecs = from.codecs();
                container = from.container();
            }
        }
        long bits = Codecs.parseBitrate(EditorState.exportBitrate);
        return new ExportPreset(name, from == null ? "" : from.builtIn(), EditorState.exportWidth, EditorState.exportHeight, EditorState.exportRate, container, codecs,
            quality, bits > 0 ? bits : 20_000_000L, EditorState.exportSound, EditorState.exportStereo, EditorState.exportSoundCodec, stemFiles, stemTracks,
            from == null ? 0 : from.maxSeconds());
    }

    private static void presetsChanged() {
        presetsRevision++;
        ExportPresets presets = Export.presets();
        Controls.offThread(presets::write, Kit.t("export.preset.save_failed"), () -> {
        });
    }

    private static List<Menu.Item> presetMenu() {
        List<Menu.Item> items = new ArrayList<>();
        for (ExportPreset one : Export.presets().all()) {
            items.add(new Menu.Item(Component.literal(one.name()), () -> {
                apply(one);
                settle();
                naming = 0;
            }, true, one.name().equals(preset)));
        }
        return items;
    }

    private static List<Menu.Item> presetActions() {
        ExportPreset from = chosen();
        List<Menu.Item> items = new ArrayList<>();
        items.add(new Menu.Item(from == null ? Kit.t("export.preset.save") : Kit.t("export.preset.save_named", from.name()), () -> {
            if (from != null) {
                Export.presets().save(current(from.name(), from));
                presetsChanged();
                EditorState.say(Kit.t("export.preset.saved", from.name()));
            }
        }, from != null && !matches(from), false));
        items.add(new Menu.Item(Kit.t("export.preset.save_as"), () -> {
            naming = 1;
            nameDraft = "";
        }));
        items.add(new Menu.Item(Kit.t("export.preset.rename"), () -> {
            naming = 2;
            nameDraft = from == null ? "" : from.name();
        }, from != null, false));
        items.add(new Menu.Item(from == null ? Kit.t("export.preset.remove") : Kit.t("export.preset.remove_named", from.name()), () -> {
            if (from != null) {
                Export.presets().remove(from.name());
                preset = null;
                presetsChanged();
                EditorState.say(Kit.t("export.preset.removed", from.name()));
            }
        }, from != null, false));
        items.add(new Menu.Item(Kit.t("export.preset.reset"), () -> {
            Export.presets().reset();
            presetsChanged();
            EditorState.say(Kit.t("export.preset.reset_done"));
        }));
        return items;
    }

    /** The name typed for a new preset, or as a new name for the chosen one, is taken */
    private static void finishNaming() {
        String typed = nameDraft.trim();
        if (typed.isEmpty()) {
            EditorState.say(Kit.t("export.preset.name_empty"));
            return;
        }
        ExportPresets presets = Export.presets();
        ExportPreset from = chosen();
        if (naming == 2 && from != null) {
            presets.rename(from.name(), typed);
        } else {
            if (presets.find(typed) != null) {
                throw new IllegalArgumentException(Kit.t("export.preset.name_taken", typed).getString());
            }
            ExportPreset made = current(typed, from);
            // A copy of a built-in preset is the person's own, and "reset" leaves it alone
            presets.save(new ExportPreset(typed, "", made.width(), made.height(), made.framesPerSecond(), made.container(), made.codecs(), made.quality(), made.bitrate(),
                made.audio(), made.stereo(), made.audioCodec(), made.stemFiles(), made.stemTracks(), made.maxSeconds()));
        }
        preset = typed;
        naming = 0;
        presetsChanged();
        EditorState.say(Kit.t("export.preset.saved", typed));
    }

    private static Component presetWords() {
        ExportPreset from = chosen();
        if (from == null) {
            return Kit.t("export.preset.none");
        }
        return matches(from) ? Component.literal(from.name()) : Kit.t("export.preset.modified", from.name());
    }

    /** What the chosen preset had to give up on this machine, or nothing */
    private static Component fallbackWords() {
        ExportPreset from = chosen();
        if (from == null) {
            return Component.empty();
        }
        Codecs.Pick pick = Codecs.pick(from.codecs(), from.container(), choices());
        if (pick.codec() == null || !pick.fellBack()) {
            return Component.empty();
        }
        boolean hardwareOnly = pick.wanted().equals("h264") || pick.wanted().equals("h265");
        return Kit.t(hardwareOnly ? "export.fallback.hardware" : "export.fallback.plain", Codecs.codecLabel(pick.wanted()), choiceWords(Codecs.forCodec(choices(), pick.codec()).get(0)).getString());
    }

    // ------------------------------------------------------------------ The tab

    /** A word for what the tab should hold now; when it changes the tab is built afresh */
    static String shape() {
        ask();
        return (known == null ? "asking" : "known") + "|" + pictures() + "|" + naming + "|" + presetsRevision;
    }

    static void build(Form form) {
        form.clear();
        settle();
        boolean ready = known != null;

        // --- The preset
        form.add(new Gadget.Heading(Kit.t("export.heading.preset")));
        form.add(new Row(Kit.t("export.preset"), new Gadget.Line(
            new Drop(ExportForms::presetWords, ExportForms::presetMenu).tip(Kit.t("export.preset.tip")),
            new Gadget.Push(Component.literal("..."), ExportForms::openPresetActions).wide(18).tip(Kit.t("export.preset.actions.tip"))))
            .explain(Kit.t("export.preset.tip")).unless(() -> ready ? null : Kit.t("export.asking")));
        if (naming != 0) {
            form.add(new Row(Kit.t(naming == 1 ? "export.preset.new_name" : "export.preset.other_name"), new Gadget.Line(
                new Entry(() -> nameDraft, typed -> {
                    nameDraft = typed;
                    return null;
                }).hint(Kit.t("export.preset.name_hint")).onEnter(ExportForms::finishNaming).tip(Kit.t("export.preset.name.tip")),
                new Gadget.Push(Kit.t("export.preset.name_ok"), ExportForms::finishNaming).wide(30).tip(Kit.t("export.preset.name_ok.tip")),
                new Gadget.Push(Kit.t("button.cancel"), () -> naming = 0).wide(40).tip(Kit.t("export.preset.name_cancel.tip"))))
                .explain(Kit.t("export.preset.name.tip")));
        }
        form.add(new Gadget.Note(() -> {
            ExportPreset from = chosen();
            return from == null ? Component.empty() : Component.literal(from.summary());
        }, Controls.PARCHMENT_DIM));
        form.add(new Gadget.Note(ExportForms::fallbackWords, Controls.BRASS_BRIGHT));

        // --- The picture
        form.add(new Gadget.Heading(Kit.t("export.heading.picture")));
        if (!ready) {
            form.add(new Gadget.Note(Kit.t("export.asking")));
        } else if (!Encoder.bundled()) {
            form.add(new Gadget.Note(() -> Kit.t("export.no_platform", Encoder.SUPPORTED, Encoder.platform()), Controls.BRASS_BRIGHT));
        } else if (choices().isEmpty()) {
            form.add(new Gadget.Note(Kit.t("export.no_encoder")));
        }
        form.add(new Row(Kit.t("export.size"), drop(() -> SIZES, id -> Kit.t("export.size." + id), ExportForms::sizeName, id -> {
            int index = SIZES.indexOf(id);
            if (index >= 0 && index < SIZE_PIXELS.length) {
                EditorState.exportWidth = SIZE_PIXELS[index][0];
                EditorState.exportHeight = SIZE_PIXELS[index][1];
            }
        })).explain(Kit.t("export.size.tip")));
        form.add(new Row(Kit.t("export.pixels"), new Gadget.Line(
            Entry.number(() -> (double) EditorState.exportWidth, 16, 16384, true, value -> {
                EditorState.exportWidth = (int) Math.rint(value);
                return null;
            }).tip(Kit.t("export.width.tip")),
            new Gadget.Words(() -> Component.literal("x"), Controls.PARCHMENT_DIM).wide(8).tip(Kit.t("export.pixels.tip")),
            Entry.number(() -> (double) EditorState.exportHeight, 16, 16384, true, value -> {
                EditorState.exportHeight = (int) Math.rint(value);
                return null;
            }).tip(Kit.t("export.height.tip")))).explain(Kit.t("export.pixels.tip")));
        form.add(new Row(Kit.t("export.rate"), new Gadget.Line(
            drop(() -> RATES, id -> id.equals("custom") ? Kit.t("export.rate.custom") : Kit.t("export.rate.value", id), ExportForms::rateName, id -> {
                if (!id.equals("custom")) {
                    EditorState.exportRate = Double.parseDouble(id);
                }
            }).tip(Kit.t("export.rate.tip")),
            Entry.number(() -> EditorState.exportRate, 1, 240, false, value -> {
                EditorState.exportRate = value;
                return null;
            }).wide(44).tip(Kit.t("export.rate_free.tip")))).explain(Kit.t("export.rate.tip")));
        form.add(new Row(Kit.t("export.container"), drop(ExportForms::containers, id -> Kit.t("export.container." + id), () -> EditorState.exportContainer, id -> {
            EditorState.exportContainer = id;
            settle();
        }).tip(Kit.t("export.container.tip"))).explain(Kit.t("export.container.tip")));
        if (!pictures()) {
            form.add(new Row(Kit.t("export.codec"), new Drop(() -> {
                Codecs.Choice now = choice();
                return now == null ? Component.empty() : choiceWords(now);
            }, ExportForms::codecMenu).tip(Kit.t("export.codec.tip"))).explain(Kit.t("export.codec.tip")));
            form.add(new Gadget.Note(ExportForms::missingWords, Controls.PARCHMENT_DIM));
            form.add(new Row(Kit.t("export.quality"), drop(() -> Codecs.QUALITIES, id -> Kit.t("export.quality." + id), () -> quality, id -> {
                if (id.equals("custom") && !quality.equals("custom")) {
                    // Start from what the named quality came to, so "custom" changes nothing until a number is typed
                    EditorState.exportBitrate = Codecs.bitrateWords(bitrate());
                }
                quality = id;
            }).tip(Kit.t("export.quality.tip"))).explain(Kit.t("export.quality.tip"))
                .unless(() -> master() ? Kit.t("export.quality.master", Codecs.codecLabel(EditorState.exportCodec)) : null));
            form.add(new Row(Kit.t("export.bitrate"), new Entry(() -> master() ? "" : quality.equals("custom") ? EditorState.exportBitrate : Codecs.bitrateWords(bitrate()), typed -> {
                if (Codecs.parseBitrate(typed) < 0) {
                    return Kit.t("export.bitrate.wrong");
                }
                EditorState.exportBitrate = typed.trim().toLowerCase(Locale.ROOT);
                return null;
            }).tip(Kit.t("export.bitrate.tip"))).explain(Kit.t("export.bitrate.tip")).unless(() -> master() ? Kit.t("export.quality.master", Codecs.codecLabel(EditorState.exportCodec))
                : quality.equals("custom") ? null : Kit.t("export.bitrate.fixed")));
        }
        form.add(new Gadget.Check(Kit.t("export.supersample"), () -> EditorState.exportSupersample, on -> EditorState.exportSupersample = on)
                .tip(Kit.t("export.supersample.tip")));
        form.add(new Gadget.Check(Kit.t("export.hide_hud"), () -> EditorState.exportHideHud, on -> EditorState.exportHideHud = on)
                .tip(Kit.t("export.hide_hud.tip")));
        form.add(new Gadget.Check(Kit.t("export.transparent"), () -> EditorState.exportTransparent, on -> EditorState.exportTransparent = on)
                .tip(Kit.t("export.transparent.tip")));

        // --- The sound
        if (!pictures()) {
            Supplier<Component> silent = () -> EditorState.exportSound ? null : Kit.t("export.needs_sound");
            form.add(new Gadget.Heading(Kit.t("export.heading.sound")));
            form.add(new Gadget.Check(Kit.t("export.sound"), () -> EditorState.exportSound, on -> EditorState.exportSound = on).tip(Kit.t("export.sound.tip")));
            form.add(new Row(null, new Gadget.Check(Kit.t("export.stereo"), () -> EditorState.exportStereo, on -> EditorState.exportStereo = on).tip(Kit.t("export.stereo.tip")))
                .explain(Kit.t("export.stereo.tip")).unless(silent));
            form.add(new Row(Kit.t("export.sound_codec"), new Drop(() -> Kit.t("export.sound_codec." + EditorState.exportSoundCodec), () -> {
                List<Menu.Item> items = new ArrayList<>();
                for (String id : SOUND_CODECS) {
                    boolean fits = soundFits(EditorState.exportContainer, id);
                    Component label = fits ? Kit.t("export.sound_codec." + id)
                        : Kit.t("export.not_in", Kit.t("export.sound_codec." + id).getString(), Kit.t("export.container." + EditorState.exportContainer).getString());
                    items.add(new Menu.Item(label, () -> EditorState.exportSoundCodec = id, fits, id.equals(EditorState.exportSoundCodec)));
                }
                return items;
            }).tip(Kit.t("export.sound_codec.tip"))).explain(Kit.t("export.sound_codec.tip")).unless(silent));
            form.add(new Row(Kit.t("export.apart"), new Drop(() -> Kit.t("export.apart." + ExportPreset.separateWord(stemFiles, stemTracks)), () -> {
                List<Menu.Item> items = new ArrayList<>();
                boolean holds = Codecs.holdsNamedTracks(EditorState.exportContainer);
                for (String id : APART) {
                    boolean[] parts = ExportPreset.separate(id);
                    boolean can = holds || !parts[1];
                    Component label = can ? Kit.t("export.apart." + id) : Kit.t("export.apart.needs_file", Kit.t("export.apart." + id).getString());
                    items.add(new Menu.Item(label, () -> {
                        stemFiles = parts[0];
                        stemTracks = parts[1];
                    }, can, id.equals(ExportPreset.separateWord(stemFiles, stemTracks))));
                }
                return items;
            }).tip(Kit.t("export.apart.tip"))).explain(Kit.t("export.apart.tip")).unless(silent));
            form.add(new Gadget.Note(() -> EditorState.exportSound && stemTracks && !Codecs.holdsNamedTracks(EditorState.exportContainer)
                ? Kit.t("export.apart.as_files", Kit.t("export.container." + EditorState.exportContainer).getString()) : Component.empty(), Controls.BRASS_BRIGHT));
        }

        // --- Where it goes
        form.add(new Gadget.Heading(Kit.t("export.heading.output")));
        form.add(new Row(Kit.t("export.folder"), new Entry(() -> folder, typed -> {
            String wanted = typed.trim();
            if (wanted.isEmpty()) {
                wanted = DEFAULT_FOLDER;
            }
            try {
                Path.of(wanted);
            } catch (RuntimeException e) {
                return Kit.t("export.folder.wrong");
            }
            folder = wanted;
            return null;
        }).tip(Kit.t("export.folder.tip"))).explain(Kit.t("export.folder.tip")));
        form.add(new Row(Kit.t("export.name"), new Entry(ExportForms::name, typed -> {
            if (typed.isBlank()) {
                return Kit.t("export.name.empty");
            }
            EditorState.exportName = typed.trim();
            return null;
        }).tip(Kit.t("export.name.tip", NamePattern.PLACES))).explain(Kit.t("export.name.tip", NamePattern.PLACES)));
        form.add(new Gadget.Check(Kit.t("export.overwrite"), () -> overwrite, on -> overwrite = on).tip(Kit.t("export.overwrite.tip")));
        form.add(new Gadget.Note(() -> Kit.t("export.where", folder.replace('\\', '/') + "/" + fileName(false)), Controls.PARCHMENT_DIM));
        form.add(new Gadget.Note(ExportForms::rangeWords, Controls.PARCHMENT_DIM));
        form.add(new Gadget.Note(ExportForms::tooLongWords, Controls.ALERT));
        form.add(new Row(null, new Gadget.Line(
            new Gadget.Push(Kit.t("export.start"), () -> start(false)).tip(Kit.t("export.start.tip")),
            new Gadget.Push(Kit.t("export.still"), () -> start(true)).tip(Kit.t("export.still.tip"))))
            .unless(() -> Export.active() ? Kit.t("export.result.running") : !Playback.loaded() || Playback.jumping() ? Kit.t("export.not_now") : null));
        form.add(new Gadget.Note(ExportForms::resultWords, Controls.PARCHMENT).action(ExportForms::openFolder).tip(Kit.t("export.result.tip")));
        form.add(new Gadget.Note(() -> {
            String note = Export.lastNote();
            return note == null || Export.lastOutput() == null ? Component.empty() : Component.literal(note);
        }, Controls.PARCHMENT_DIM));
        form.add(new Gadget.Note(() -> {
            String sound = Export.soundNote();
            return sound == null ? Component.empty() : Component.literal(sound);
        }, Controls.PARCHMENT_DIM));
    }

    /** The "..." beside the preset: what can be done with presets, in a menu under the button */
    private static void openPresetActions() {
        Minecraft minecraft = Minecraft.getInstance();
        double scale = (double) minecraft.getWindow().getGuiScaledWidth() / Math.max(1, minecraft.getWindow().getScreenWidth());
        Menu.show(presetActions(), minecraft.mouseHandler.xpos() * scale, minecraft.mouseHandler.ypos() * scale);
    }

    /** "H.264 (NVIDIA hardware)" or "AV1 (software, SVT-AV1)" */
    private static Component choiceWords(Codecs.Choice one) {
        String codec = Kit.named("export.codec." + one.codec(), Codecs.codecLabel(one.codec())).getString();
        if (!one.hardware() && Codecs.forCodec(choices(), one.codec()).stream().filter(other -> !other.hardware()).count() == 1) {
            // Only one piece of software makes this codec: its name would add nothing but length
            return Kit.t("export.codec.software_only", codec);
        }
        return Kit.t(one.hardware() ? "export.codec.hardware" : "export.codec.software", codec, one.on());
    }

    /** Every codec choice this machine has; and, greyed, the hardware-only codecs it lacks, so nobody wonders where they went */
    private static List<Menu.Item> codecMenu() {
        List<Menu.Item> items = new ArrayList<>();
        Codecs.Choice now = choice();
        for (String codec : List.of("h264", "h265")) {
            if (!Codecs.has(choices(), codec)) {
                items.add(new Menu.Item(Kit.t("export.codec.none_here", Codecs.codecLabel(codec)), () -> {
                }, false, false));
            }
        }
        for (Codecs.Choice one : choices()) {
            items.add(new Menu.Item(choiceWords(one), () -> {
                EditorState.exportCodec = one.codec();
                // The fastest for a codec is "choose for me": a preset then still matches on another machine
                EditorState.exportEncoder = Codecs.forCodec(choices(), one.codec()).get(0).equals(one) ? "" : one.encoder();
                EditorState.exportContainer = Codecs.containerFor(one.codec(), EditorState.exportContainer);
                settle();
            }, true, one.equals(now)));
        }
        return items;
    }

    /** Why H.264 is not in the list, when it is not */
    private static Component missingWords() {
        if (known == null || choices().isEmpty() || Codecs.has(choices(), "h264")) {
            return Component.empty();
        }
        return Kit.t("export.codec.missing");
    }

    /** The bitrate the tab comes to: typed for a custom quality, else worked out from the quality; 0 for an editing master */
    private static long bitrate() {
        if (master()) {
            return 0L;
        }
        if (quality.equals("custom")) {
            long bits = Codecs.parseBitrate(EditorState.exportBitrate);
            return bits > 0 ? bits : 20_000_000L;
        }
        return Codecs.bitrate(quality, EditorState.exportCodec, EditorState.exportWidth, EditorState.exportHeight, EditorState.exportRate);
    }

    private static String sizeName() {
        for (int i = 0; i < SIZE_PIXELS.length; i++) {
            if (SIZE_PIXELS[i][0] == EditorState.exportWidth && SIZE_PIXELS[i][1] == EditorState.exportHeight) {
                return SIZES.get(i);
            }
        }
        return "custom";
    }

    private static String rateName() {
        for (String rate : RATES) {
            if (!rate.equals("custom") && Math.abs(Double.parseDouble(rate) - EditorState.exportRate) < 1.0e-9) {
                return rate;
            }
        }
        return "custom";
    }

    /** The name of the file an export started now would write, with every place of the pattern filled in */
    private static String fileName(boolean still) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("preset", preset == null ? "" : preset);
        values.put("scene", EditorState.scene().name());
        RecordingManifest manifest = Playback.manifest();
        values.put("recording", manifest == null || manifest.name == null ? "" : manifest.name);
        values.put("size", EditorState.exportWidth + "x" + EditorState.exportHeight);
        values.put("fps", Kit.number(EditorState.exportRate));
        values.put("codec", still || pictures() ? "png" : EditorState.exportCodec);
        String filled = NamePattern.fill(name(), LocalDateTime.now(), values);
        if (still) {
            return filled + ".png";
        }
        return pictures() ? filled : filled + "." + EditorState.exportContainer;
    }

    private static Path folder() {
        Path wanted = Path.of(folder);
        return wanted.isAbsolute() ? wanted : Minecraft.getInstance().gameDirectory.toPath().resolve(wanted);
    }

    /** The ticks an export started now would cover, worked out as the export itself does, and how long the video would be */
    private static Component rangeWords() {
        Scene scene = EditorState.scene();
        int total = Playback.total();
        int start = scene.hasExportRange() ? scene.exportStart() : scene.firstKeyframeTick() >= 0 ? scene.firstKeyframeTick() : 0;
        int end = scene.hasExportRange() ? scene.exportEnd() : scene.lastKeyframeTick() > start ? scene.lastKeyframeTick() : total;
        start = Math.max(0, Math.min(total, start));
        end = Math.max(0, Math.min(total, end));
        if (end <= start) {
            return Kit.t("export.range.empty", start, end);
        }
        return Kit.t(scene.hasExportRange() ? "export.range.set" : "export.range.default", start, end, Kit.seconds(rangeSeconds()));
    }

    private static long lengthFor = Long.MIN_VALUE;
    private static double lengthSeconds = 0.0;

    /** How long the video would be; kept until the scene changes, since it is asked for on every frame */
    private static double rangeSeconds() {
        long stamp = EditorState.history().revision() * 1_000_003L + Playback.total() * 31L + System.identityHashCode(EditorState.scene());
        if (stamp != lengthFor) {
            lengthFor = stamp;
            lengthSeconds = Export.rangeSeconds(-1, -1);
        }
        return lengthSeconds;
    }

    /** A preset made for short videos says so when the range is longer (Shorts and Reels take a minute at most) */
    private static Component tooLongWords() {
        ExportPreset from = chosen();
        if (from == null || from.maxSeconds() <= 0 || !Playback.loaded()) {
            return Component.empty();
        }
        double seconds = rangeSeconds();
        return seconds > from.maxSeconds() + 1.0e-6 ? Kit.t("export.too_long", Kit.seconds(seconds), from.name(), from.maxSeconds()) : Component.empty();
    }

    private static Component resultWords() {
        if (Export.active()) {
            return Kit.t("export.result.running");
        }
        String error = Export.lastError();
        if (error != null) {
            return Kit.t("export.result.failed", error);
        }
        Path written = Export.lastOutput();
        if (written != null) {
            return Kit.t(Export.lastCancelled() ? "export.result.cancelled" : "export.result.done", written.getFileName().toString());
        }
        return Component.empty();
    }

    private static void openFolder() {
        Path written = Export.lastOutput();
        Path target = written != null && written.toAbsolutePath().getParent() != null ? written.toAbsolutePath().getParent() : folder();
        Controls.offThread(() -> {
            Files.createDirectories(target);
            Util.getPlatform().openPath(target);
        }, Kit.t("export.folder_failed"), () -> {
        });
    }

    // ------------------------------------------------------------------ Starting

    /**
     * Starts an export with what the tab holds. The folder is made, and a name that is not yet taken found, away
     * from the client thread; the export itself begins back on it.
     */
    static void start(boolean still) {
        if (!Playback.loaded() || Playback.jumping()) {
            EditorState.say(Kit.t("export.not_now"));
            return;
        }
        if (Export.active()) {
            EditorState.say(Kit.t("export.result.running"));
            return;
        }
        boolean pictures = still || pictures();
        Export.Settings settings = new Export.Settings();
        settings.width = EditorState.exportWidth;
        settings.height = EditorState.exportHeight;
        if (!pictures) {
            // Video formats want even sizes
            settings.width += settings.width & 1;
            settings.height += settings.height & 1;
            if (choice() == null) {
                EditorState.say(Kit.t("export.no_encoder"));
                return;
            }
            settings.bitrate = bitrate();
            settings.videoCodec = EditorState.exportCodec;
            settings.encoder = EditorState.exportEncoder.isEmpty() ? null : EditorState.exportEncoder;
            settings.audio = EditorState.exportSound;
            settings.stereo = EditorState.exportStereo;
            settings.audioCodec = EditorState.exportSoundCodec;
            settings.stemFiles = EditorState.exportSound && stemFiles;
            settings.stemTracks = EditorState.exportSound && stemTracks;
            Component fallback = fallbackWords();
            ExportPreset from = chosen();
            settings.note = from != null && matches(from) && !fallback.getString().isEmpty() ? fallback.getString() : null;
        }
        settings.container = still ? PICTURES : EditorState.exportContainer;
        settings.framesPerSecond = EditorState.exportRate;
        settings.hideGui = EditorState.exportHideHud;
        settings.supersample = EditorState.exportSupersample;
        settings.transparent = EditorState.exportTransparent;
        settings.still = still;

        Path target = folder();
        String wanted = fileName(still);
        boolean replace = overwrite;
        String recording = Playback.recordingId();
        Path[] free = new Path[1];
        Controls.offThread(() -> {
            Files.createDirectories(target);
            // A folder of pictures is added to, never replaced; a file is replaced only when that was ticked
            free[0] = target.resolve(replace && !Files.isDirectory(target.resolve(wanted)) ? wanted : NamePattern.unused(wanted, name -> Files.exists(target.resolve(name))));
        }, Kit.t("export.folder_failed"), () -> {
            if (free[0] == null || !Playback.loaded() || !java.util.Objects.equals(recording, Playback.recordingId())) {
                return;
            }
            settings.output = free[0];
            try {
                Export.start(settings);
                if (still) {
                    EditorState.say(Kit.t("export.still.started", free[0].getFileName().toString()));
                }
            } catch (IllegalStateException | IllegalArgumentException refused) {
                EditorState.say(Component.literal(String.valueOf(refused.getMessage())));
            } catch (Exception | LinkageError e) {
                Host.LOGGER.error("Dailies: the export could not start", e);
                EditorState.say(Kit.t("export.result.failed", String.valueOf(e.getMessage())));
            }
        });
    }

    /** How the last export ended, in words, for the notice shown when the editor comes back */
    static Component ended() {
        Component words = resultWords();
        return words.getString().isEmpty() ? Kit.t("export.result.none") : words;
    }

    // ------------------------------------------------------------------ For the scripted test

    /** What the tab is set to, in a line, once the encoder has answered; null while it is still being asked. Client thread */
    public static @Nullable String describe() {
        ask();
        if (known == null) {
            return null;
        }
        settle();
        Codecs.Choice now = choice();
        ExportPreset from = chosen();
        return "preset=" + preset + " modified=" + (from != null && !matches(from)) + " size=" + EditorState.exportWidth + "x" + EditorState.exportHeight
            + " container=" + EditorState.exportContainer + " codec=" + (now == null ? "none" : now.encoder()) + " fallback=" + fallbackWords().getString();
    }

    /** Chooses a preset as its line of the menu does; false when there is none of that name. Client thread */
    public static boolean choosePreset(String name) {
        ExportPreset from = Export.presets().find(name);
        if (from == null || known == null) {
            return false;
        }
        apply(from);
        settle();
        return true;
    }

    /** Changes the size as typing in its boxes does, so a test can see the preset marked as modified. Client thread */
    public static void setSize(int width, int height) {
        EditorState.exportWidth = width;
        EditorState.exportHeight = height;
    }

    // ------------------------------------------------------------------ Controls of this tab

    /** A dropdown for a list of ids with a label each: the one in use is marked in the menu */
    private static <T> Drop drop(Supplier<List<T>> choices, Function<T, Component> names, Supplier<T> value, Consumer<T> change) {
        return new Drop(() -> {
            T now = value.get();
            return now == null ? Component.empty() : names.apply(now);
        }, () -> {
            List<Menu.Item> items = new ArrayList<>();
            T now = value.get();
            for (T one : choices.get()) {
                items.add(new Menu.Item(names.apply(one), () -> change.accept(one), true, one.equals(now)));
            }
            return items;
        });
    }

    /**
     * A dropdown: a button that shows the choice in use and opens a menu of all of them just under itself. Kept in
     * this file because the shared controls are being reworked elsewhere; it is drawn with the same kit.
     */
    static final class Drop extends Gadget {
        private final Supplier<Component> shown;
        private final Supplier<List<Menu.Item>> items;
        private boolean cut = false;

        Drop(Supplier<Component> shown, Supplier<List<Menu.Item>> items) {
            this.shown = shown;
            this.items = items;
        }

        @Override
        int wantedWidth(Font font) {
            return this.fixedWidth > 0 ? this.fixedWidth : 80;
        }

        @Override
        void draw(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
            boolean enabled = this.usable.getAsBoolean();
            Kit.face(graphics, this.x, this.y, this.width, this.height, this.over(mouseX, mouseY), false, enabled);
            int colour = enabled ? Controls.PARCHMENT : Controls.PARCHMENT_DIM;
            String words = this.shown.get().getString();
            int room = this.width - 16;
            this.cut = font.width(words) > room;
            Kit.text(graphics, font, Kit.clip(font, words, room), this.x + 4, this.y + (this.height - 8) / 2, colour);
            // The arrow head that says "this opens a list"
            Kit.icon(graphics, Kit.Icon.DOWN, this.x + this.width - 7, this.y + this.height / 2 - 1, enabled ? Controls.BRASS_BRIGHT : Controls.BRASS);
        }

        @Override
        @Nullable Gadget press(double mouseX, double mouseY, int button) {
            if (button != 0 || !this.over(mouseX, mouseY)) {
                return null;
            }
            if (this.usable.getAsBoolean()) {
                Kit.click();
                Menu.show(this.items.get(), this.x, this.y + this.height);
            }
            return this;
        }

        @Override
        @Nullable Component tipAt(double mouseX, double mouseY) {
            if (!this.over(mouseX, mouseY)) {
                return null;
            }
            // Words too long for the button are given in full
            return this.cut ? (this.tip == null ? this.shown.get() : Component.empty().append(this.shown.get()).append(". ").append(this.tip)) : this.tip;
        }
    }

    /**
     * A row of the tab: a name on the left, where every row's name starts, and its control on the right, where every
     * control starts. A row that cannot be used now is dimmed, and its explanation says why. Without a name the
     * control has the whole row.
     */
    static final class Row extends Gadget {
        private final @Nullable Component label;
        private final Gadget control;
        private Supplier<Component> reason = () -> null;
        private boolean cut = false;

        Row(@Nullable Component label, Gadget control) {
            this.label = label;
            this.control = control;
            this.usable = () -> this.reason.get() == null;
            this.control.when(this.usable);
        }

        Row explain(Component words) {
            this.tip = words;
            return this;
        }

        /** Gives the reason the row cannot be used now, or null when it can */
        Row unless(Supplier<Component> why) {
            this.reason = why;
            return this;
        }

        private int labelWidth(int of) {
            return this.label == null ? 0 : Math.max(30, of * 36 / 100);
        }

        @Override
        int heightFor(Font font, int forWidth) {
            return this.control.heightFor(font, forWidth - this.labelWidth(forWidth));
        }

        @Override
        void clip(int top, int bottom) {
            super.clip(top, bottom);
            this.control.clip(top, bottom);
        }

        @Override
        void place(Font font, int newX, int newY, int newWidth, int newHeight) {
            super.place(font, newX, newY, newWidth, newHeight);
            int labelWidth = this.labelWidth(newWidth);
            this.control.place(font, newX + labelWidth, newY, newWidth - labelWidth, newHeight);
        }

        @Override
        void draw(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
            boolean enabled = this.usable.getAsBoolean();
            if (this.label != null) {
                int room = this.labelWidth(this.width) - 4;
                this.cut = font.width(this.label) > room;
                Kit.text(graphics, font, Kit.clip(font, this.label.getString(), room), this.x + 1, this.y + 3, enabled ? Controls.PARCHMENT : Controls.PARCHMENT_DIM);
            }
            this.control.draw(graphics, font, enabled ? mouseX : Integer.MIN_VALUE / 2, enabled ? mouseY : Integer.MIN_VALUE / 2);
            if (!enabled) {
                // A veil of the panel's own colour: plainly there, plainly not to be used
                graphics.fill(this.control.x, this.control.y, this.control.x + this.control.width, this.control.y + Math.min(this.control.height, this.height), 0x992B1D0E);
            }
        }

        @Override
        @Nullable Gadget press(double mouseX, double mouseY, int button) {
            if (!this.usable.getAsBoolean()) {
                return this.over(mouseX, mouseY) ? this : null;
            }
            return this.control.press(mouseX, mouseY, button);
        }

        @Override
        @Nullable Component tipAt(double mouseX, double mouseY) {
            if (!this.over(mouseX, mouseY)) {
                return null;
            }
            Component why = this.reason.get();
            if (why != null) {
                Component cannot = Kit.t("export.unavailable", why.getString());
                return this.tip == null ? cannot : Component.empty().append(this.tip).append(" ").append(cannot);
            }
            Component inner = this.control.tipAt(mouseX, mouseY);
            if (inner != null) {
                return inner;
            }
            return this.label == null ? this.tip : Gadget.whole(this.label, this.tip, this.cut);
        }

        @Override
        void forget() {
            this.control.forget();
        }
    }

}
