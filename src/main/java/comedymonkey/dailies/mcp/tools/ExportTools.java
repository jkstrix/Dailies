package comedymonkey.dailies.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import comedymonkey.dailies.export.Encoder;
import comedymonkey.dailies.export.Export;
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
                .string("container", "mp4 (default), mkv, mov, webm or png_sequence")
                .string("video_codec", "h264 (default), h265, av1 or vp9, where this build's encoder has it")
                .string("encoder", "A particular encoder for the codec. Left out, hardware encoders are tried first, then software")
                .any("bitrate", "Video bitrate in bits per second, or text like '20m' or '800k' (default 20m)")
                .bool("record_audio", "Put the game's sound in the file (default false)")
                .bool("stereo_audio", "Stereo sound and not mono (default true)")
                .bool("no_gui", "Leave the HUD out of the picture (default: last used, at first false)")
                .bool("ssaa", "Draw at twice the size and scale down, for smoother edges (default: last used, at first false)")
                .bool("transparent", "Keep the background transparent. Needs the scene's sky switched off and a format that can hold it")
                .bool("reset_rng", "Accepted for compatibility. Frames are already drawn at exact recording times")
                .bool("overwrite", "Replace output_path when it exists (default false)")
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

    private static JsonElement start(McpArgs args) {
        CoreTools.allowOnly(args, "output_path", "width", "height", "framerate", "start_tick", "end_tick", "container", "video_codec", "encoder", "bitrate",
            "record_audio", "stereo_audio", "no_gui", "ssaa", "transparent", "reset_rng", "overwrite");
        String container = args.has("container") ? args.string("container").toLowerCase(Locale.ROOT) : "mp4";
        if (!CONTAINERS.contains(container)) {
            throw new McpToolException("'container' must be one of " + String.join(", ", CONTAINERS));
        }
        Export.Settings settings = common(args, container.equals("png_sequence"));
        settings.container = container;
        settings.framesPerSecond = args.optNumber("framerate", lastRate);
        if (!(settings.framesPerSecond >= 1.0 && settings.framesPerSecond <= 240.0)) {
            throw new McpToolException("'framerate' must be between 1 and 240; the nearest allowed value is " + (settings.framesPerSecond < 1.0 ? "1" : "240"));
        }
        settings.startTick = args.optInteger("start_tick", -1);
        settings.endTick = args.optInteger("end_tick", -1);
        settings.videoCodec = args.has("video_codec") ? args.string("video_codec").toLowerCase(Locale.ROOT) : "h264";
        settings.encoder = args.optString("encoder");
        settings.bitrate = bitrate(args);
        settings.audio = args.optBool("record_audio", false);
        settings.stereo = args.optBool("stereo_audio", true);
        if (settings.audio) {
            throw new McpToolException("Recording the game's sound into an export is not built yet. Export without record_audio and add sound afterwards");
        }
        begin(settings);
        lastRate = settings.framesPerSecond;
        return status();
    }

    private static Export.Settings common(McpArgs args, boolean pictures) {
        if (!Playback.loaded()) {
            throw new McpToolException(Playback.active() ? "The recording is still loading. Poll dailies_status until playback.loaded is true" : "No recording is open. Open one with replay_open");
        }
        Export.Settings settings = new Export.Settings();
        settings.width = args.optInteger("width", lastWidth);
        settings.height = args.optInteger("height", lastHeight);
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
        json.addProperty("video_encoder_bundled", Encoder.bundled());
        if (!Encoder.bundled()) {
            JsonArray containers = new JsonArray();
            containers.add("png_sequence");
            json.add("containers_available", containers);
        }
        return json;
    }

}
