package comedymonkey.dailies.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import comedymonkey.dailies.export.Codecs;
import comedymonkey.dailies.export.Encoder;
import comedymonkey.dailies.export.Export;
import comedymonkey.dailies.export.ExportPreset;
import comedymonkey.dailies.export.ExportPresets;
import comedymonkey.dailies.mcp.McpTool;
import comedymonkey.dailies.mcp.McpArgs;
import comedymonkey.dailies.mcp.McpProtocol;
import comedymonkey.dailies.mcp.McpSchema;
import comedymonkey.dailies.mcp.McpToolException;
import comedymonkey.dailies.playback.Playback;
import net.minecraft.client.Minecraft;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * The MCP tools that turn a scene into a video, a picture sequence or a still. Starting an export returns at once;
 * export_status says how far it has got.
 */
final class ExportTools {

    private static int lastWidth = 1920;
    private static int lastHeight = 1080;
    private static double lastRate = 60.0;
    private static boolean lastNoGui = false;
    private static boolean lastSupersample = false;
    private static final List<String> CONTAINERS = List.of("mp4", "mkv", "mov", "webm", "png_sequence");

    private ExportTools() {
    }

    static void register(McpProtocol.Registrar registrar) {
        registrar.add("export_start",
            "Render the current scene to a video file, or to a folder of numbered PNG pictures. Every frame is drawn at an exact moment of the recording, so a slow computer "
                + "gives the same result as a fast one, only later. Returns at once: poll export_status. Playback is put back as it was when the export ends.",
            McpSchema.object()
                .string("output_path", "File to write, absolute or relative to the game directory; a folder for png_sequence. {date} and {time} are filled in")
                .integer("width", "Width in pixels (default: last used, at first 1920). May be larger than the screen")
                .integer("height", "Height in pixels (default: last used, at first 1080)")
                .number("framerate", "Frames per second, 1 to 240, fractions allowed (default: last used, at first 60)")
                .integer("start_tick", "First tick to export (default: the scene's export range, else its first keyframe, else 0)")
                .integer("end_tick", "Last tick to export (default: the scene's export range, else its last keyframe, else the end of the recording)")
                .string("preset", "The name of an export preset (export_presets_list). It gives the size, frame rate, kind of file, codec, quality and sound; "
                    + "any of those given here as well wins over the preset. A preset whose codec this computer cannot make falls back, and the reply says so under 'note'")
                .string("container", "mp4 (default), mkv, mov, webm or png_sequence")
                .string("video_codec", "h264, h265, av1, vp9, prores (ProRes 422 HQ) or dnxhr (DNxHR HQ), of those export_codecs_list shows. "
                    + "Default: the fastest this computer has that the container holds. H.264 and H.265 are hardware only")
                .string("encoder", "A particular encoder for the codec. Left out, hardware encoders are tried first, then software")
                .any("bitrate", "Video bitrate in bits per second, or text like '20m' or '800k' (default 20m). Editing masters take none")
                .string("separate_audio", "Besides the mixed sound, keep the game, each speaker's voice and the audio keyframes apart: 'files' for WAV files beside the video, "
                    + "'tracks' for named tracks inside an mkv or mov, 'both', or 'none' (default). Needs record_audio")
                .bool("record_audio", "Put the game's sound in the file, with recorded voice chat and the scene's audio keyframes (default false). export_status says under 'sound' what became of it")
                .bool("stereo_audio", "Stereo sound and not mono (default true)")
                .string("audio_codec", "aac, opus or pcm (default: aac for mp4 and mov, opus for webm and mkv). pcm is uncompressed and fits mov and mkv")
                .bool("no_gui", "Leave the HUD out of the picture (default: last used, at first false)")
                .bool("ssaa", "Draw at twice the size and scale down, for smoother edges (default: last used, at first false)")
                .bool("transparent", "Keep the background transparent. Needs the scene's sky switched off and a format that can hold it")
                .bool("reset_rng", "Accepted for compatibility. Frames are already drawn at exact recording times")
                .bool("overwrite", "Replace output_path when it exists (default false)")
                .string("caption_files", "Also write what was said in the range as captions beside the video, timed in video time: none (default), srt, vtt or both. "
                    + "export_status lists them under 'caption_files'")
                .bool("caption_speakers", "Begin each caption with the speaker's name (default: the captionSpeakerNames setting)")
                .bool("burn_captions", "Draw what was said into the picture, in the font, size, colour, outline and place the caption settings give (default false)")
                .string("caption_style", "lines or word_by_word, for captions drawn into the picture (default: the captionStyle setting)")
                .required("output_path"),
            ExportTools::start);

        registrar.add("export_status", "Whether an export is running and how far it has got: frames done and to do, speed, time left. Afterwards, the file the last export wrote, or why it failed.",
            McpSchema.object(), comedymonkey.dailies.mcp.McpTool.ThreadMode.HTTP, 10, args -> {
                CoreTools.allowOnly(args);
                return status();
            });

        registrar.add("export_cancel", "Stop the running export after the frame it is on. The frames already written are kept and the file is finished so that it plays.",
            McpSchema.object(), args -> {
                CoreTools.allowOnly(args);
                try {
                    Export.cancel();
                } catch (IllegalStateException e) {
                    throw new McpToolException(e.getMessage() + ". Call export_status to see the last one");
                }
                return status();
            });

        registrar.add("export_codecs_list",
            "The video codecs this computer can really export, fastest first: each with its encoder and whether it runs on a graphics card (and whose) or in software. "
                + "Hardware encoders are tried once before they are listed. Also says which codecs are missing and why, and which platform has an encoder packed in.",
            McpSchema.object(), McpTool.ThreadMode.HTTP, 60, args -> {
                CoreTools.allowOnly(args);
                return codecs();
            });

        registrar.add("export_presets_list", "The export presets, in their order: name, a line saying what each makes, and what it would use on this computer.",
            McpSchema.object(), McpTool.ThreadMode.HTTP, 60, args -> {
                CoreTools.allowOnly(args);
                JsonArray list = new JsonArray();
                for (ExportPreset preset : Export.presets().all()) {
                    list.add(describe(preset, false));
                }
                JsonObject json = new JsonObject();
                json.add("presets", list);
                return json;
            });

        registrar.add("export_presets_get", "Every setting of one export preset.",
            McpSchema.object().string("name", "The preset's name").required("name"), McpTool.ThreadMode.HTTP, 60, args -> {
                CoreTools.allowOnly(args, "name");
                return describe(preset(args.string("name")), true);
            });

        registrar.add("export_presets_set",
            "Add an export preset, or change the one of that name: only the settings given change. A new preset starts from 'YouTube 1080p60'. Saved with the mod's data.",
            McpSchema.object()
                .string("name", "The preset's name")
                .string("new_name", "Rename it to this")
                .integer("width", "Width in pixels")
                .integer("height", "Height in pixels")
                .number("framerate", "Frames per second, 1 to 240")
                .string("container", "mp4, mkv, mov or webm")
                .stringArray("video_codecs", "The codecs wanted, most wanted first: h264, h265, av1, vp9, prores, dnxhr")
                .string("quality", "high, moderate, small, or custom for a bitrate")
                .any("bitrate", "Bits per second, or text like '20m', used when quality is custom")
                .bool("record_audio", "Whether the export has sound")
                .bool("stereo_audio", "Stereo and not mono")
                .string("audio_codec", "auto, aac, opus or pcm")
                .string("separate_audio", "none, files, tracks or both")
                .integer("max_seconds", "The longest video the preset is meant for; a longer range gets a warning. 0 for no limit")
                .required("name"),
            McpTool.ThreadMode.HTTP, 60, ExportTools::presetSet);

        registrar.add("export_presets_remove", "Remove an export preset. A built-in one comes back with export_presets_reset.",
            McpSchema.object().string("name", "The preset's name").required("name"), McpTool.ThreadMode.HTTP, 60, args -> {
                CoreTools.allowOnly(args, "name");
                ExportPresets presets = Export.presets();
                try {
                    presets.remove(args.string("name"));
                } catch (IllegalArgumentException e) {
                    throw new McpToolException(e.getMessage() + ". Call export_presets_list for the names");
                }
                presets.write();
                JsonObject json = new JsonObject();
                json.addProperty("removed", args.string("name"));
                return json;
            });

        registrar.add("export_presets_reset", "Bring the built-in export presets back as Dailies made them. Presets you added are kept.",
            McpSchema.object(), McpTool.ThreadMode.HTTP, 60, args -> {
                CoreTools.allowOnly(args);
                ExportPresets presets = Export.presets();
                presets.reset();
                presets.write();
                JsonArray names = new JsonArray();
                presets.all().forEach(preset -> names.add(preset.name()));
                JsonObject json = new JsonObject();
                json.add("presets", names);
                return json;
            });

        registrar.add("export_screenshot", "Write one PNG picture of the view as it is now, at any size. Returns at once: poll export_status for the file.",
            McpSchema.object()
                .string("output_path", "PNG file to write, absolute or relative to the game directory")
                .integer("width", "Width in pixels (default: the last export size, at first 1920)")
                .integer("height", "Height in pixels (default: the last export size, at first 1080)")
                .bool("no_gui", "Leave the HUD out of the picture (default: last used, at first false)")
                .bool("ssaa", "Draw at twice the size and scale down (default: last used, at first false)")
                .bool("transparent", "Keep the background transparent. Needs the scene's sky switched off")
                .bool("overwrite", "Replace output_path when it exists (default false)")
                .required("output_path"),
            args -> {
                CoreTools.allowOnly(args, "output_path", "width", "height", "no_gui", "ssaa", "transparent", "overwrite");
                Export.Settings settings = common(args, true);
                settings.still = true;
                begin(settings);
                return status();
            });
    }

    private static JsonElement start(McpArgs args) throws Exception {
        CoreTools.allowOnly(args, "output_path", "width", "height", "framerate", "start_tick", "end_tick", "preset", "container", "video_codec", "encoder", "bitrate",
            "record_audio", "stereo_audio", "audio_codec", "separate_audio", "no_gui", "ssaa", "transparent", "reset_rng", "overwrite",
            "caption_files", "caption_speakers", "burn_captions", "caption_style");
        ExportPreset preset = args.has("preset") ? preset(args.string("preset")) : null;
        String asked = args.has("container") ? args.string("container").toLowerCase(Locale.ROOT) : preset != null ? preset.container() : "mp4";
        String container = asked;
        if (!CONTAINERS.contains(container)) {
            throw new McpToolException("'container' must be one of " + String.join(", ", CONTAINERS));
        }
        boolean pictures = container.equals("png_sequence");
        Export.Settings settings = common(args, pictures, preset);
        settings.framesPerSecond = args.optNumber("framerate", preset != null ? preset.framesPerSecond() : lastRate);
        if (!(settings.framesPerSecond >= 1.0 && settings.framesPerSecond <= 240.0)) {
            throw new McpToolException("'framerate' must be between 1 and 240; the nearest allowed value is " + (settings.framesPerSecond < 1.0 ? "1" : "240"));
        }
        settings.startTick = args.optInteger("start_tick", -1);
        settings.endTick = args.optInteger("end_tick", -1);
        settings.encoder = args.optString("encoder");
        String warning = null;
        if (!pictures) {
            if (!Encoder.bundled()) {
                throw new McpToolException(Encoder.noEncoderWords());
            }
            List<Codecs.Choice> working = Encoder.choices();
            if (args.has("video_codec")) {
                settings.videoCodec = args.string("video_codec").toLowerCase(Locale.ROOT);
            } else {
                // A preset names what it would like; without one, the fastest thing this computer has that fits the file
                List<String> wanted = preset != null ? preset.codecs() : working.stream().map(Codecs.Choice::codec).filter(codec -> Codecs.holds(asked, codec)).limit(1).toList();
                Codecs.Pick pick = Codecs.pick(wanted, container, working);
                if (pick.codec() == null) {
                    throw new McpToolException(pick.words(working));
                }
                settings.videoCodec = pick.codec();
                if (preset != null || !args.has("container")) {
                    container = pick.container();
                    settings.note = pick.words(working);
                } else if (pick.containerChanged()) {
                    throw new McpToolException("This computer has no codec that a " + container + " file holds. It can encode: "
                        + String.join(", ", working.stream().map(Codecs.Choice::codec).distinct().toList()));
                }
            }
            settings.bitrate = args.has("bitrate") ? bitrate(args) : preset != null ? preset.bitrateFor(settings.videoCodec) : 20_000_000L;
        }
        settings.container = container;
        settings.audio = args.optBool("record_audio", preset != null && preset.audio());
        settings.stereo = args.optBool("stereo_audio", preset == null || preset.stereo());
        settings.audioCodec = args.has("audio_codec") ? args.string("audio_codec").toLowerCase(Locale.ROOT) : preset != null ? preset.audioCodec() : "auto";
        if (!List.of("auto", "aac", "opus", "pcm").contains(settings.audioCodec)) {
            throw new McpToolException("'audio_codec' must be one of aac, opus, pcm");
        }
        try {
            boolean[] apart = args.has("separate_audio") ? ExportPreset.separate(args.string("separate_audio"))
                : new boolean[]{preset != null && preset.stemFiles() && settings.audio, preset != null && preset.stemTracks() && settings.audio};
            settings.stemFiles = apart[0];
            settings.stemTracks = apart[1];
        } catch (IllegalArgumentException e) {
            throw new McpToolException(e.getMessage());
        }
        try {
            boolean[] captions = comedymonkey.dailies.export.Captions.files(args.optString("caption_files"));
            settings.captionSrt = captions[0];
            settings.captionVtt = captions[1];
        } catch (IllegalArgumentException e) {
            throw new McpToolException(e.getMessage());
        }
        settings.captionSpeakers = args.optBool("caption_speakers", comedymonkey.dailies.Host.get().setting("captionSpeakerNames", true));
        settings.burnCaptions = args.optBool("burn_captions", false);
        if (args.has("caption_style")) {
            String style = args.string("caption_style").toLowerCase(Locale.ROOT);
            if (!style.equals("lines") && !style.equals("word_by_word")) {
                throw new McpToolException("'caption_style' must be lines or word_by_word");
            }
            settings.captionStyle = comedymonkey.dailies.export.Captions.style(style);
        }
        if (preset != null && preset.maxSeconds() > 0) {
            double seconds = Export.rangeSeconds(settings.startTick, settings.endTick);
            if (seconds > preset.maxSeconds() + 1.0e-6) {
                warning = "The range makes " + Math.round(seconds * 10.0) / 10.0 + " seconds of video; the preset '" + preset.name() + "' is meant for at most " + preset.maxSeconds();
            }
        }
        begin(settings);
        lastRate = settings.framesPerSecond;
        JsonObject json = status();
        if (preset != null) {
            json.addProperty("preset", preset.name());
        }
        json.addProperty("container", settings.container);
        if (!pictures) {
            json.addProperty("video_codec", settings.videoCodec);
        }
        if (warning != null) {
            json.addProperty("warning", warning);
        }
        return json;
    }

    private static ExportPreset preset(String name) {
        ExportPreset preset = Export.presets().find(name);
        if (preset == null) {
            throw new McpToolException("There is no export preset called '" + name + "'. There are: "
                + String.join(", ", Export.presets().all().stream().map(ExportPreset::name).toList()));
        }
        return preset;
    }

    /** A preset as JSON: its name and summary, what it comes to on this computer, and with {@code whole} every setting */
    private static JsonObject describe(ExportPreset preset, boolean whole) throws Exception {
        JsonObject json = whole ? preset.toJson() : new JsonObject();
        json.addProperty("name", preset.name());
        json.addProperty("built_in", !preset.builtIn().isEmpty());
        json.addProperty("summary", preset.summary());
        if (Encoder.bundled()) {
            List<Codecs.Choice> working = Encoder.choices();
            Codecs.Pick pick = Codecs.pick(preset.codecs(), preset.container(), working);
            if (pick.codec() != null) {
                json.addProperty("uses_here", Codecs.forCodec(working, pick.codec()).get(0).label() + " in " + pick.container());
            }
            if (pick.words(working) != null) {
                json.addProperty("note", pick.words(working));
            }
        } else {
            json.addProperty("note", Encoder.noEncoderWords());
        }
        return json;
    }

    private static JsonElement presetSet(McpArgs args) throws Exception {
        CoreTools.allowOnly(args, "name", "new_name", "width", "height", "framerate", "container", "video_codecs", "quality", "bitrate", "record_audio", "stereo_audio",
            "audio_codec", "separate_audio", "max_seconds");
        ExportPresets presets = Export.presets();
        String name = args.string("name");
        ExportPreset old = presets.find(name);
        JsonObject changes = new JsonObject();
        for (String key : args.names()) {
            if (!key.equals("name") && !key.equals("new_name")) {
                changes.add(key, args.element(key));
            }
        }
        try {
            ExportPreset base = old != null ? old : ExportPresets.builtIn().get(0).named(name);
            ExportPreset made = ExportPreset.fromJson(changes, old != null ? base : ExportPreset.fromJson(blankOrigin(), base));
            presets.save(made);
            String newName = args.optString("new_name");
            if (newName != null && !newName.equals(made.name())) {
                presets.rename(made.name(), newName);
                name = newName;
            }
        } catch (IllegalArgumentException e) {
            throw new McpToolException(e.getMessage());
        }
        presets.write();
        JsonObject json = describe(presets.find(name), true);
        json.addProperty("added", old == null);
        return json;
    }

    /** A preset made from a built-in one is the person's own */
    private static JsonObject blankOrigin() {
        JsonObject json = new JsonObject();
        json.addProperty("built_in", "");
        return json;
    }

    private static JsonObject codecs() throws Exception {
        JsonObject json = new JsonObject();
        json.addProperty("platform", Encoder.platform());
        json.addProperty("platforms_with_an_encoder", Encoder.SUPPORTED);
        json.addProperty("video_encoder_bundled", Encoder.bundled());
        JsonArray list = new JsonArray();
        JsonObject missing = new JsonObject();
        if (Encoder.bundled()) {
            List<Codecs.Choice> working = Encoder.choices();
            for (Codecs.Choice choice : working) {
                JsonObject one = new JsonObject();
                one.addProperty("video_codec", choice.codec());
                one.addProperty("name", Codecs.codecLabel(choice.codec()));
                one.addProperty("encoder", choice.encoder());
                one.addProperty("runs_on", choice.hardware() ? "hardware" : "software");
                one.addProperty("what", choice.on());
                JsonArray containers = new JsonArray();
                Codecs.CONTAINERS.stream().filter(container -> Codecs.holds(container, choice.codec())).forEach(containers::add);
                one.add("containers", containers);
                list.add(one);
            }
            if (!working.isEmpty()) {
                json.addProperty("default", working.get(0).codec());
            }
            for (String codec : Codecs.ORDER) {
                String why = Codecs.whyMissing(working, codec);
                if (why != null) {
                    missing.addProperty(codec, why);
                }
            }
        } else {
            json.addProperty("note", Encoder.noEncoderWords());
        }
        json.add("codecs", list);
        json.add("missing", missing);
        return json;
    }

    private static Export.Settings common(McpArgs args, boolean pictures) {
        return common(args, pictures, null);
    }

    private static Export.Settings common(McpArgs args, boolean pictures, ExportPreset preset) {
        if (!Playback.loaded()) {
            throw new McpToolException(Playback.active() ? "The recording is still loading. Poll dailies_status until playback.loaded is true" : "No recording is open. Open one with replay_open");
        }
        Export.Settings settings = new Export.Settings();
        settings.width = args.optInteger("width", preset != null ? preset.width() : lastWidth);
        settings.height = args.optInteger("height", preset != null ? preset.height() : lastHeight);
        if (settings.width < 16 || settings.height < 16 || settings.width > 16384 || settings.height > 16384) {
            throw new McpToolException("'width' and 'height' must be between 16 and 16384");
        }
        if (!pictures) {
            // Video formats want even sizes
            settings.width += settings.width & 1;
            settings.height += settings.height & 1;
        }
        settings.hideGui = args.optBool("no_gui", lastNoGui);
        settings.supersample = args.optBool("ssaa", lastSupersample);
        settings.transparent = args.optBool("transparent", false);
        LocalDateTime now = LocalDateTime.now();
        String name = args.string("output_path").replace("{date}", now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))).replace("{time}", now.format(DateTimeFormatter.ofPattern("HH.mm.ss")));
        Path output = Path.of(name);
        if (!output.isAbsolute()) {
            output = Minecraft.getInstance().gameDirectory.toPath().resolve(output);
        }
        if (Files.exists(output) && !args.optBool("overwrite", false) && !(Files.isDirectory(output) && pictures)) {
            throw new McpToolException(output + " already exists. Pass overwrite true to replace it, or choose another output_path");
        }
        settings.output = output;
        return settings;
    }

    private static void begin(Export.Settings settings) {
        try {
            Export.start(settings);
        } catch (IllegalStateException | IllegalArgumentException e) {
            throw new McpToolException(e.getMessage());
        } catch (Exception e) {
            throw new McpToolException("The export could not start: " + e.getMessage());
        }
        lastWidth = settings.width;
        lastHeight = settings.height;
        lastNoGui = settings.hideGui;
        lastSupersample = settings.supersample;
    }

    private static long bitrate(McpArgs args) {
        JsonElement given = args.element("bitrate");
        if (given == null) {
            return 20_000_000L;
        }
        String text = given.getAsString().trim().toLowerCase(Locale.ROOT);
        try {
            double scale = text.endsWith("m") ? 1_000_000 : text.endsWith("k") ? 1_000 : 1;
            double number = Double.parseDouble(scale == 1 ? text : text.substring(0, text.length() - 1));
            if (number < 0) {
                throw new NumberFormatException();
            }
            return (long) (number * scale);
        } catch (NumberFormatException e) {
            throw new McpToolException("'bitrate' must be a number of bits per second, or text like '20m' or '800k'");
        }
    }

    static JsonObject status() {
        JsonObject json = new JsonObject();
        json.addProperty("running", Export.active());
        if (Export.active()) {
            json.addProperty("frames_done", Export.frame());
            json.addProperty("frames_total", Export.totalFrames());
            double speed = Export.speed();
            json.addProperty("frames_per_second", Math.round(speed * 10.0) / 10.0);
            if (speed > 0.0) {
                json.addProperty("seconds_left", Math.round((Export.totalFrames() - Export.frame()) / speed));
            }
        } else {
            if (Export.lastOutput() != null) {
                json.addProperty("last_output", Export.lastOutput().toAbsolutePath().toString());
                json.addProperty("last_cancelled", Export.lastCancelled());
            }
            if (Export.lastError() != null) {
                json.addProperty("last_error", Export.lastError());
            }
        }
        if (Export.soundNote() != null) {
            json.addProperty("sound", Export.soundNote());
        }
        if (Export.lastNote() != null) {
            json.addProperty("note", Export.lastNote());
        }
        if (Export.captionNote() != null) {
            json.addProperty("captions", Export.captionNote());
        }
        if (!Export.captionFiles().isEmpty()) {
            JsonArray names = new JsonArray();
            Export.captionFiles().forEach(file -> names.add(file.toAbsolutePath().toString()));
            json.add("caption_files", names);
        }
        if (!Export.active()) {
            List<String> tracks = comedymonkey.dailies.sound.ExportSound.tracks();
            if (!tracks.isEmpty()) {
                JsonArray names = new JsonArray();
                tracks.forEach(names::add);
                json.add("audio_tracks", names);
            }
            List<Path> files = comedymonkey.dailies.sound.ExportSound.files();
            if (!files.isEmpty()) {
                JsonArray names = new JsonArray();
                files.forEach(file -> names.add(file.toAbsolutePath().toString()));
                json.add("audio_files", names);
            }
        }
        json.addProperty("video_encoder_bundled", Encoder.bundled());
        if (!Encoder.bundled()) {
            json.addProperty("video_encoder_note", Encoder.noEncoderWords());
            JsonArray containers = new JsonArray();
            containers.add("png_sequence");
            json.add("containers_available", containers);
        }
        return json;
    }

}
