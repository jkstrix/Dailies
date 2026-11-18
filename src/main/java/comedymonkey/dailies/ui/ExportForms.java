package comedymonkey.dailies.ui;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.edit.Scene;
import comedymonkey.dailies.editor.EditorSession;
import comedymonkey.dailies.export.Captions;
import comedymonkey.dailies.export.Codecs;
import comedymonkey.dailies.export.ExportQueue;
import comedymonkey.dailies.export.QueueRunner;
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
    private static final List<String> CAPTION_FILES = List.of("none", "srt", "vtt", "both");
    private static final List<String> CAPTION_STYLES = List.of("lines", "word_by_word");
    /** Caption files to write and whether to draw captions into the picture; the look is in the settings */
    private static String captionFiles = "none";
    private static boolean burnCaptions = false;
    /** lines or word_by_word; null until chosen here, for the setting */
    private static @Nullable String captionStyle = null;

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
    /** Whether the rarer settings are unfolded */
    private static boolean more = false;
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
        return (known == null ? "asking" : "known") + "|" + pictures() + "|" + naming + "|" + presetsRevision + "|" + queueRevision + "|" + queueShape();
    }

    static void build(Form form) {
        form.clear();
        settle();
        boolean ready = known != null;
        Supplier<Component> silent = () -> EditorState.exportSound ? null : Kit.t("export.needs_sound");
        Supplier<Component> fixed = () -> master() ? Kit.t("export.quality.master", Codecs.codecLabel(EditorState.exportCodec)) : null;

        // --- The preset, and what can be done with presets
        Gadget.Push[] actions = new Gadget.Push[1];
        actions[0] = Gadget.Push.icon(Kit.Icon.DOTS, Kit.t("export.preset.actions.tip"),
            () -> Menu.drop(presetActions(), actions[0].x, actions[0].y, actions[0].width, Kit.ROW, actions[0]));
        form.add(new Told(new Gadget.Pair(Kit.t("export.preset"), new Gadget.Line(
            new Gadget.Dropdown<>(ExportForms::presetNames, ExportForms::presetLabel, () -> preset, name -> {
                ExportPreset from = Export.presets().find(name);
                if (from != null) {
                    apply(from);
                    settle();
                    naming = 0;
                }
            }).otherwise(() -> Kit.t("export.preset.none")),
            actions[0])))
            .says(() -> {
                ExportPreset from = chosen();
                return from == null ? Kit.t("export.preset.tip") : Kit.t("export.preset.tip_of", from.summary());
            }).unless(() -> ready ? null : Kit.t("export.asking")));
        if (naming != 0) {
            form.add(new Gadget.Pair(Kit.t(naming == 1 ? "export.preset.new_name" : "export.preset.other_name"), new Gadget.Line(
                new Entry(() -> nameDraft, typed -> {
                    nameDraft = typed;
                    return null;
                }).hint(Kit.t("export.preset.name_hint")).onEnter(ExportForms::finishNaming).tip(Kit.t("export.preset.name.tip")),
                new Gadget.Push(Kit.t("export.preset.name_ok"), ExportForms::finishNaming).wide(30).tip(Kit.t("export.preset.name_ok.tip")),
                new Gadget.Push(Kit.t("button.cancel"), () -> naming = 0).wide(40).tip(Kit.t("export.preset.name_cancel.tip"))))
                .tip(Kit.t("export.preset.name.tip")));
        }
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
        form.add(pair("export.size", "export.size.tip", new Gadget.Dropdown<>(SIZES, id -> Kit.t("export.size." + id), ExportForms::sizeName, id -> {
            int index = SIZES.indexOf(id);
            if (index >= 0 && index < SIZE_PIXELS.length) {
                EditorState.exportWidth = SIZE_PIXELS[index][0];
                EditorState.exportHeight = SIZE_PIXELS[index][1];
            } else {
                // The boxes to type a size into are among the rarer settings
                more = true;
            }
        })));
        form.add(new Gadget.Pair(Kit.t("export.rate"), new Gadget.Line(
            new Gadget.Dropdown<>(RATES, id -> id.equals("custom") ? Kit.t("export.rate.custom") : Kit.t("export.rate.value", id), ExportForms::rateName, id -> {
                if (!id.equals("custom")) {
                    EditorState.exportRate = Double.parseDouble(id);
                }
            }).tip(Kit.t("export.rate.tip")),
            Entry.number(() -> EditorState.exportRate, 1, 240, false, value -> {
                EditorState.exportRate = value;
                return null;
            }).wide(44).tip(Kit.t("export.rate_free.tip")))).tip(Kit.t("export.rate.tip")));
        form.add(pair("export.container", "export.container.tip", new Gadget.Dropdown<>(ExportForms::containers, id -> Kit.t("export.container." + id), () -> EditorState.exportContainer, id -> {
            EditorState.exportContainer = id;
            settle();
        })));
        if (!pictures()) {
            form.add(pair("export.codec", "export.codec.tip", new Gadget.Dropdown<>(ExportForms::choices, ExportForms::choiceWords, ExportForms::choice, one -> {
                EditorState.exportCodec = one.codec();
                // The fastest for a codec is "choose for me": a preset then still matches on another machine
                EditorState.exportEncoder = Codecs.forCodec(choices(), one.codec()).get(0).equals(one) ? "" : one.encoder();
                EditorState.exportContainer = Codecs.containerFor(one.codec(), EditorState.exportContainer);
                settle();
            })));
            form.add(new Gadget.Note(ExportForms::missingWords, Controls.PARCHMENT_DIM));
            form.add(new Told(pair("export.quality", "export.quality.tip", new Gadget.Dropdown<>(Codecs.QUALITIES, id -> Kit.t("export.quality." + id), () -> quality, id -> {
                if (id.equals("custom")) {
                    if (!quality.equals("custom")) {
                        // Start from what the named quality came to, so "custom" changes nothing until a number is typed
                        EditorState.exportBitrate = Codecs.bitrateWords(bitrate());
                    }
                    more = true;
                }
                quality = id;
            }))).unless(fixed));

            // --- The sound
            form.add(new Gadget.Heading(Kit.t("export.heading.sound")));
            form.add(new Gadget.Check(Kit.t("export.sound"), () -> EditorState.exportSound, on -> EditorState.exportSound = on).tip(Kit.t("export.sound.tip")));
        }

        // --- What was said (EXP-11, EXP-15)
        form.add(new Gadget.Heading(Kit.t("export.heading.captions")));
        form.add(pair("export.captions.files", "export.captions.files.tip", new Gadget.Dropdown<>(CAPTION_FILES, id -> Kit.t("export.captions.files." + id),
            () -> captionFiles, id -> captionFiles = id)));
        form.add(new Gadget.Check(Kit.t("export.captions.burn"), () -> burnCaptions, on -> burnCaptions = on).tip(Kit.t("export.captions.burn.tip")));
        form.add(new Told(pair("export.captions.style", "export.captions.style.tip", new Gadget.Dropdown<>(CAPTION_STYLES, id -> Kit.t("export.captions.style." + id),
            ExportForms::captionStyleNow, id -> captionStyle = id))).unless(() -> burnCaptions ? null : Kit.t("export.captions.needs_burn")));
        form.add(new Gadget.Check(Kit.t("export.captions.speakers"), () -> EditorState.setting("captionSpeakerNames", true), on -> saveSetting("captionSpeakerNames", on))
            .tip(Kit.t("export.captions.speakers.tip")));
        form.add(new Gadget.Check(Kit.t("export.captions.preview"), () -> EditorState.setting("captionPreview", false), on -> saveSetting("captionPreview", on))
            .tip(Kit.t("export.captions.preview.tip")));

        // --- Where it goes
        form.add(new Gadget.Heading(Kit.t("export.heading.output")));
        form.add(pair("export.folder", "export.folder.tip", new Entry(() -> folder, typed -> {
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
        })));
        Component nameTip = Kit.t("export.name.tip", NamePattern.PLACES);
        form.add(new Gadget.Pair(Kit.t("export.name"), new Entry(ExportForms::name, typed -> {
            if (typed.isBlank()) {
                return Kit.t("export.name.empty");
            }
            EditorState.exportName = typed.trim();
            return null;
        }).tip(nameTip)).tip(nameTip));
        form.add(new Gadget.Note(() -> Kit.t("export.where", folder.replace('\\', '/') + "/" + fileName(false)), Controls.PARCHMENT_DIM));
        form.add(new Gadget.Note(ExportForms::rangeWords, Controls.PARCHMENT_DIM));
        form.add(new Gadget.Note(ExportForms::tooLongWords, Controls.ALERT));
        Supplier<Component> busy = () -> Export.active() ? Kit.t("export.result.running") : !Playback.loaded() || Playback.jumping() ? Kit.t("export.not_now") : null;
        Gadget begin = new Gadget.Push(Kit.t("export.start"), () -> start(false)).when(() -> busy.get() == null).tip(Kit.t("export.start.tip"));
        Gadget still = new Gadget.Push(Kit.t("export.still"), () -> start(true)).when(() -> busy.get() == null).tip(Kit.t("export.still.tip"));
        Gadget queue = new Gadget.Push(Kit.t("export.queue.add"), ExportForms::addToQueue).when(() -> Playback.loaded() && !Playback.jumping()).tip(Kit.t("export.queue.add.tip"));
        form.add(new Told(new Gadget.Line(begin, still, queue)).unless(busy));
        form.add(new Gadget.Note(ExportForms::resultWords, Controls.PARCHMENT).action(ExportForms::openFolder).tip(Kit.t("export.result.tip")));
        form.add(new Gadget.Note(() -> {
            String note = Export.lastNote();
            return note == null || Export.lastOutput() == null ? Component.empty() : Component.literal(note);
        }, Controls.PARCHMENT_DIM));
        form.add(new Gadget.Note(() -> {
            String sound = Export.soundNote();
            return sound == null ? Component.empty() : Component.literal(sound);
        }, Controls.PARCHMENT_DIM));

        // --- The rarer settings, folded away until asked for
        form.add(new Gadget.Push(() -> Kit.t(more ? "export.more.hide" : "export.more.show"), () -> more = !more).lit(() -> more).tip(Kit.t("export.more.tip")));
        Gadget.Fold rarer = new Gadget.Fold(() -> more);
        rarer.add(new Gadget.Pair(Kit.t("export.pixels"), new Gadget.Line(
            Entry.number(() -> (double) EditorState.exportWidth, 16, 16384, true, value -> {
                EditorState.exportWidth = (int) Math.rint(value);
                return null;
            }).tip(Kit.t("export.width.tip")),
            new Gadget.Words(() -> Component.literal("x"), Controls.PARCHMENT_DIM).wide(8).tip(Kit.t("export.pixels.tip")),
            Entry.number(() -> (double) EditorState.exportHeight, 16, 16384, true, value -> {
                EditorState.exportHeight = (int) Math.rint(value);
                return null;
            }).tip(Kit.t("export.height.tip")))).tip(Kit.t("export.pixels.tip")));
        if (!pictures()) {
            rarer.add(new Told(pair("export.bitrate", "export.bitrate.tip", new Entry(() -> master() ? "" : quality.equals("custom") ? EditorState.exportBitrate : Codecs.bitrateWords(bitrate()), typed -> {
                if (Codecs.parseBitrate(typed) < 0) {
                    return Kit.t("export.bitrate.wrong");
                }
                EditorState.exportBitrate = typed.trim().toLowerCase(Locale.ROOT);
                return null;
            }))).unless(() -> fixed.get() != null ? fixed.get() : quality.equals("custom") ? null : Kit.t("export.bitrate.fixed")));
        }
        rarer.add(new Gadget.Check(Kit.t("export.supersample"), () -> EditorState.exportSupersample, on -> EditorState.exportSupersample = on)
                .tip(Kit.t("export.supersample.tip")));
        rarer.add(new Gadget.Check(Kit.t("export.hide_hud"), () -> EditorState.exportHideHud, on -> EditorState.exportHideHud = on)
                .tip(Kit.t("export.hide_hud.tip")));
        rarer.add(new Gadget.Check(Kit.t("export.transparent"), () -> EditorState.exportTransparent, on -> EditorState.exportTransparent = on)
                .tip(Kit.t("export.transparent.tip")));
        if (!pictures()) {
            rarer.add(new Told(new Gadget.Check(Kit.t("export.stereo"), () -> EditorState.exportStereo, on -> EditorState.exportStereo = on).tip(Kit.t("export.stereo.tip"))).unless(silent));
            rarer.add(new Told(pair("export.sound_codec", "export.sound_codec.tip", new Gadget.Dropdown<>(
                () -> SOUND_CODECS.stream().filter(id -> soundFits(EditorState.exportContainer, id)).toList(),
                id -> Kit.t("export.sound_codec." + id), () -> EditorState.exportSoundCodec, id -> EditorState.exportSoundCodec = id))).unless(silent));
            rarer.add(new Told(pair("export.apart", "export.apart.tip", new Gadget.Dropdown<>(
                () -> APART.stream().filter(id -> Codecs.holdsNamedTracks(EditorState.exportContainer) || !ExportPreset.separate(id)[1] || id.equals(ExportPreset.separateWord(stemFiles, stemTracks))).toList(),
                id -> Kit.t("export.apart." + id), () -> ExportPreset.separateWord(stemFiles, stemTracks), id -> {
                    boolean[] parts = ExportPreset.separate(id);
                    stemFiles = parts[0];
                    stemTracks = parts[1];
                }))).unless(silent));
            rarer.add(new Gadget.Note(() -> EditorState.exportSound && stemTracks && !Codecs.holdsNamedTracks(EditorState.exportContainer)
                ? Kit.t("export.apart.as_files", Kit.t("export.container." + EditorState.exportContainer).getString()) : Component.empty(), Controls.BRASS_BRIGHT));
        }
        rarer.add(new Gadget.Check(Kit.t("export.overwrite"), () -> overwrite, on -> overwrite = on).tip(Kit.t("export.overwrite.tip")));
        form.add(rarer);

        // --- Exports waiting to run, here or in the background
        addQueue(form);
    }

    /** A named row whose control and name give the same explanation */
    private static Gadget pair(String label, String tip, Gadget control) {
        return new Gadget.Pair(Kit.t(label), control.tip(Kit.t(tip))).tip(Kit.t(tip));
    }

    private static List<String> presetNames() {
        return Export.presets().all().stream().map(ExportPreset::name).toList();
    }

    /** A preset's name; the one in use says so when the tab no longer holds what it fills in */
    private static Component presetLabel(String name) {
        ExportPreset from = chosen();
        return from != null && from.name().equals(name) && !matches(from) ? Kit.t("export.preset.modified", name) : Component.literal(name);
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
        Export.Settings settings = settingsNow(still);
        if (settings == null) {
            return;
        }

        Path target = folder();
        String wanted = fileName(still);
        boolean replace = overwrite;
        String recording = Playback.recordingId();
        Path[] free = new Path[1];
        boolean pictures = still || "png_sequence".equals(settings.container);
        Controls.offThread(() -> {
            if (!pictures && Encoder.bundled()) {
                // Unpacked and tried here, away from the client thread, so starting the export never waits for it
                try {
                    Encoder.choices();
                } catch (java.io.IOException e) {
                    // Said when the export starts
                }
            }
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

    /** What the tab holds, as export settings without a file; null, having said why, when it cannot be exported */
    private static Export.@Nullable Settings settingsNow(boolean still) {
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
                return null;
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
        if (!still) {
            boolean[] files = Captions.files(captionFiles);
            settings.captionSrt = files[0];
            settings.captionVtt = files[1];
            settings.captionSpeakers = Host.get().setting("captionSpeakerNames", true);
            settings.burnCaptions = burnCaptions;
            settings.captionStyle = Captions.style(captionStyleNow());
        }
        return settings;
    }

    // ------------------------------------------------------------------ The queue (EXP-10, EXP-13)

    /** Puts what the tab holds on the export queue, exactly as it is, to run later */
    static void addToQueue() {
        if (!Playback.loaded() || Playback.jumping()) {
            EditorState.say(Kit.t("export.not_now"));
            return;
        }
        Export.Settings settings = settingsNow(false);
        if (settings == null) {
            return;
        }
        ExportPreset from = chosen();
        ExportQueue.Job job = QueueRunner.jobForOpen(-1, from == null ? null : from.name(), folder().resolve(fileName(false)).toString());
        job.settings = QueueRunner.snapshot(settings);
        job.captionFiles = captionFiles;
        job.captionSpeakers = settings.captionSpeakers;
        job.burnCaptions = burnCaptions;
        job.captionStyle = captionStyleNow();
        Controls.offThread(() -> {
            // The background game reads the scene from the recording's file
            QueueRunner.add(job);
        }, Kit.t("export.queue.add_failed"), () -> {
            EditorSession.saveNow();
            queueRevision++;
            EditorState.say(Kit.t("export.queue.added", job.title()));
        });
    }

    private static int queueRevision = 0;

    /** Changes the queue file away from the client thread; a refusal is said in the editor */
    private static void changeQueue(java.util.function.Consumer<ExportQueue> change) {
        CompletableFuture.runAsync(() -> {
            try {
                ExportQueue.update(QueueRunner.file(), queue -> {
                    change.accept(queue);
                    return null;
                });
                QueueRunner.refresh();
            } catch (IllegalStateException | IllegalArgumentException refused) {
                Minecraft.getInstance().execute(() -> EditorState.say(Component.literal(String.valueOf(refused.getMessage()))));
            } catch (Exception e) {
                Host.LOGGER.error("Dailies: the export queue could not be changed", e);
                Minecraft.getInstance().execute(() -> EditorState.say(Kit.t("export.queue.change_failed")));
            }
        }, Util.ioPool());
    }

    private static void startQueue(boolean background) {
        CompletableFuture.runAsync(() -> {
            String said;
            try {
                said = QueueRunner.start(background, false);
            } catch (IllegalStateException | IllegalArgumentException refused) {
                said = refused.getMessage();
            } catch (Exception e) {
                Host.LOGGER.error("Dailies: the export queue could not start", e);
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                said = String.valueOf(cause.getMessage());
            }
            String words = said;
            Minecraft.getInstance().execute(() -> EditorState.say(Component.literal(String.valueOf(words))));
        }, Util.ioPool());
    }

    /** The job's line in the list: its file, its scene, and how it stands */
    private static Component jobWords(String id) {
        ExportQueue.Job job = QueueRunner.view().job(id);
        if (job == null) {
            return Component.empty();
        }
        ExportQueue.Job here = QueueRunner.current();
        String state;
        if (job.state == ExportQueue.State.RUNNING) {
            double share = here != null && here.id.equals(id) && Export.active() && Export.totalFrames() > 0 ? (double) Export.frame() / Export.totalFrames() : job.progress();
            state = Kit.t("export.queue.state.running_at", Math.round(share * 100.0)).getString();
        } else {
            state = Kit.t("export.queue.state." + job.state.word()).getString();
        }
        return Kit.t("export.queue.job", job.title(), what(job), state);
    }

    /** What a job exports, in a few words: its scene, or whose eyes a point of view is seen through */
    private static String what(ExportQueue.Job job) {
        if (job.kind == ExportQueue.Kind.POINT_OF_VIEW) {
            return Kit.t("export.queue.point_of_view", job.recorder.isEmpty() ? job.recordingName : job.recorder).getString();
        }
        return job.scene;
    }

    private static Component jobTip(String id) {
        ExportQueue.Job job = QueueRunner.view().job(id);
        if (job == null) {
            return Kit.t("export.queue.job.tip_gone");
        }
        String why = job.error != null ? job.error : job.note != null ? job.note : "";
        if (job.kind == ExportQueue.Kind.POINT_OF_VIEW) {
            return Kit.t("export.queue.job.tip_point_of_view", job.title(), job.recordingName.isEmpty() ? job.recording : job.recordingName,
                job.recorder.isEmpty() ? "-" : job.recorder, job.startTick, job.endTick, job.preset == null ? "-" : job.preset, why);
        }
        return Kit.t("export.queue.job.tip", job.title(), job.recordingName.isEmpty() ? job.recording : job.recordingName, job.scene,
            job.preset == null ? "-" : job.preset, why);
    }

    private static Component queueStatus() {
        if (QueueRunner.runningHere()) {
            String because = QueueRunner.hereBecause();
            return because == null ? Kit.t("export.queue.status.here") : Kit.t("export.queue.status.here_because", because);
        }
        if (QueueRunner.workerAlive()) {
            ExportQueue.Worker worker = QueueRunner.view().worker();
            return Kit.t("export.queue.status.background", worker == null ? 0 : worker.done);
        }
        String last = QueueRunner.lastWords();
        return last == null ? Component.empty() : Component.literal(last);
    }

    private static boolean queueBusy() {
        return QueueRunner.runningHere() || QueueRunner.workerAlive();
    }

    /** The jobs by id, so the tab is built again when they come and go */
    private static String queueShape() {
        StringBuilder shape = new StringBuilder();
        for (ExportQueue.Job job : QueueRunner.view().jobs()) {
            // The state too, so the explanation of a job that has failed says why
            shape.append(job.id).append(':').append(job.state.word()).append(',');
        }
        return shape.toString();
    }

    private static void addQueue(Form form) {
        form.add(new Gadget.Heading(Kit.t("export.heading.queue")));
        List<ExportQueue.Job> jobs = QueueRunner.view().jobs();
        if (jobs.isEmpty()) {
            form.add(new Gadget.Note(Kit.t("export.queue.empty")));
        }
        for (ExportQueue.Job job : jobs) {
            String id = job.id;
            Supplier<ExportQueue.State> state = () -> {
                ExportQueue.Job now = QueueRunner.view().job(id);
                return now == null ? ExportQueue.State.DONE : now.state;
            };
            Gadget.Words line = new Gadget.Words(() -> jobWords(id), Controls.PARCHMENT);
            Gadget up = Gadget.Push.icon(Kit.Icon.UP, Kit.t("export.queue.up.tip"), () -> changeQueue(queue -> queue.move(id, queue.indexOf(id) - 1)));
            Gadget down = Gadget.Push.icon(Kit.Icon.DOWN, Kit.t("export.queue.down.tip"), () -> changeQueue(queue -> queue.move(id, queue.indexOf(id) + 1)));
            Gadget.Push hold = Gadget.Push.icon(() -> state.get() == ExportQueue.State.WAITING ? Kit.Icon.PAUSE : Kit.Icon.PLAY, Kit.t("export.queue.hold.tip"),
                () -> changeQueue(queue -> queue.setPaused(id, state.get() == ExportQueue.State.WAITING)));
            hold.liveTip(() -> Kit.t(state.get() == ExportQueue.State.WAITING ? "export.queue.hold.tip" : "export.queue.release.tip"));
            hold.when(() -> state.get() != ExportQueue.State.RUNNING && state.get() != ExportQueue.State.DONE);
            Gadget remove = Gadget.Push.icon(Kit.Icon.CROSS, Kit.t("export.queue.remove.tip"), () -> changeQueue(queue -> queue.remove(id)))
                .when(() -> state.get() != ExportQueue.State.RUNNING);
            Gadget.Line row = new Gadget.Line(line, up, down, hold, remove);
            row.tip(jobTip(id));
            form.add(row);
        }
        form.add(new Gadget.Note(ExportForms::queueStatus, Controls.PARCHMENT_DIM));
        Supplier<Component> nothingWaiting = () -> QueueRunner.view().next() == null ? Kit.t("export.queue.nothing_waiting") : queueBusy() ? Kit.t("export.queue.busy") : null;
        Gadget background = new Gadget.Push(Kit.t("export.queue.background"), () -> startQueue(true)).tip(Kit.t("export.queue.background.tip"));
        Gadget here = new Gadget.Push(Kit.t("export.queue.here"), () -> startQueue(false)).tip(Kit.t("export.queue.here.tip"));
        form.add(new Told(new Gadget.Line(background, here)).unless(nothingWaiting));
        Gadget stop = new Gadget.Push(Kit.t("export.queue.stop"), QueueRunner::stop).when(ExportForms::queueBusy).tip(Kit.t("export.queue.stop.tip"));
        Gadget clear = new Gadget.Push(Kit.t("export.queue.clear"), () -> changeQueue(ExportQueue::clearEnded))
            .when(() -> QueueRunner.view().jobs().stream().anyMatch(one -> one.state.ended())).tip(Kit.t("export.queue.clear.tip"));
        form.add(new Gadget.Line(stop, clear));
    }

    private static String captionStyleNow() {
        return captionStyle != null ? captionStyle : Host.get().setting("captionStyle", "LINES").toLowerCase(Locale.ROOT);
    }

    private static void saveSetting(String name, Object value) {
        SettingsWriter.save(name, value, trouble -> {
            if (trouble != null) {
                Controls.tell(Kit.t("export.captions.save_failed"));
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

    /** Unfolds or folds the rarer settings, as the button over them does. Client thread */
    static void showMore(boolean unfolded) {
        more = unfolded;
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

    /**
     * A row of the kit that can say more than a fixed explanation: words that change with what is chosen, and, while
     * the row cannot be used, the reason. It looks and behaves as the row inside it; the kit draws both states.
     */
    static final class Told extends Gadget {
        private final Gadget row;
        private @Nullable Supplier<Component> says = null;
        private Supplier<Component> reason = () -> null;

        Told(Gadget row) {
            this.row = row;
        }

        /** The explanation, asked for afresh each time it is shown */
        Told says(Supplier<Component> words) {
            this.says = words;
            return this;
        }

        /** Gives the reason the row cannot be used now, or null when it can */
        Told unless(Supplier<Component> why) {
            this.reason = why;
            this.usable = () -> why.get() == null;
            this.row.when(this.usable);
            return this;
        }

        @Override
        void collect(List<Gadget> order) {
            if (this.usable.getAsBoolean()) {
                this.row.collect(order);
            }
        }

        @Override
        boolean holds(Gadget control) {
            return control == this || this.row.holds(control);
        }

        @Override
        int heightFor(Font font, int forWidth) {
            return this.row.heightFor(font, forWidth);
        }

        @Override
        void clip(int top, int bottom) {
            super.clip(top, bottom);
            this.row.clip(top, bottom);
        }

        @Override
        void place(Font font, int newX, int newY, int newWidth, int newHeight) {
            super.place(font, newX, newY, newWidth, newHeight);
            this.row.place(font, newX, newY, newWidth, newHeight);
        }

        @Override
        void draw(GuiGraphics graphics, Font font, int mouseX, int mouseY) {
            this.row.draw(graphics, font, mouseX, mouseY);
        }

        @Override
        @Nullable Gadget press(double mouseX, double mouseY, int button) {
            if (!this.usable.getAsBoolean()) {
                return this.over(mouseX, mouseY) ? this : null;
            }
            return this.row.press(mouseX, mouseY, button);
        }

        @Override
        @Nullable Component tipAt(double mouseX, double mouseY) {
            if (!this.over(mouseX, mouseY)) {
                return null;
            }
            Component words = this.says != null ? this.says.get() : this.row.tipAt(mouseX, mouseY);
            Component why = this.reason.get();
            if (why == null) {
                return words;
            }
            Component cannot = Kit.t("export.unavailable", why.getString());
            return words == null ? cannot : Component.empty().append(words).append(" ").append(cannot);
        }

        @Override
        void forget() {
            this.row.forget();
        }
    }

}
