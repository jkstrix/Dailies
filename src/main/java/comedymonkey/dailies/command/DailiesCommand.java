package comedymonkey.dailies.command;

import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.capture.Recorder;
import comedymonkey.dailies.mcp.McpToolException;
import comedymonkey.dailies.mcp.tools.MomentsTools;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.ui.Controls;
import comedymonkey.dailies.ui.Notices;
import comedymonkey.dailies.ui.Screens;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * The "/dailies" command (specification REC-7). It is a command of the game on this computer only: the game finds it
 * before anything is sent, so it works on every server and no server ever hears of it.
 * <p>
 * Each sub-command answers in the chat, where a typed command is expected to answer, whether or not on-screen notices
 * are switched on. While a recording is watched only "hide", "config" and "moments" do anything; the rest say why not, and
 * commands that are not this one are turned away before they could be sent into the recording (PLAY-4).
 */
public final class DailiesCommand {

    public static final String ROOT = "dailies";
    private static final String KEYS = "dailies.command.";

    /** The key of the last answer given, for the scripted test */
    private static volatile String lastAnswer = "";

    private DailiesCommand() {
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener((RegisterClientCommandsEvent event) -> register(event.getDispatcher()));
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(ROOT)
            .executes(context -> fail(context.getSource(), "usage"))
            .then(Commands.literal("start").executes(context -> start(context.getSource())))
            .then(Commands.literal("finish")
                .executes(context -> finish(context.getSource(), null))
                .then(Commands.argument("name", StringArgumentType.greedyString())
                    .executes(context -> finish(context.getSource(), StringArgumentType.getString(context, "name")))))
            .then(Commands.literal("pause").executes(context -> pause(context.getSource())))
            .then(Commands.literal("resume").executes(context -> resume(context.getSource())))
            .then(Commands.literal("cancel").executes(context -> cancel(context.getSource())))
            .then(Commands.literal("mark")
                .executes(context -> mark(context.getSource(), ""))
                .then(Commands.argument("words", StringArgumentType.greedyString())
                    .suggests((context, builder) -> {
                        // Only the word under the cursor is replaced by what is chosen
                        String typed = builder.getRemaining();
                        SuggestionsBuilder word = builder.createOffset(builder.getStart() + MarkWords.wordStart(typed));
                        MarkWords.suggestions(typed).forEach(word::suggest);
                        return word.buildFuture();
                    })
                    .executes(context -> mark(context.getSource(), StringArgumentType.getString(context, "words")))))
            .then(Commands.literal("moment")
                .executes(context -> fail(context.getSource(), "moment.usage"))
                .then(Commands.argument("label", StringArgumentType.greedyString())
                    .executes(context -> moment(context.getSource(), StringArgumentType.getString(context, "label")))))
            .then(Commands.literal("config").executes(context -> config(context.getSource())))
            .then(Commands.literal("moments").executes(context -> moments(context.getSource())))
            .then(Commands.literal("hide").executes(context -> hide(context.getSource()))));
    }

    /** The key of the last answer a sub-command gave, without the "dailies.command." in front */
    public static String lastAnswer() {
        return lastAnswer;
    }

    // ------------------------------------------------------------------ Answers

    private static int say(CommandSourceStack source, String key, Object... arguments) {
        lastAnswer = key;
        source.sendSuccess(() -> Component.translatable(KEYS + key, arguments), false);
        return 1;
    }

    private static int fail(CommandSourceStack source, String key, Object... arguments) {
        lastAnswer = key;
        source.sendFailure(Component.translatable(KEYS + key, arguments));
        return 0;
    }

    /** Runs something of the recorder, turning a refusal into an answer and anything worse into an answer and a line in the log */
    private static int guarded(CommandSourceStack source, Work work) {
        try {
            return work.run();
        } catch (IllegalStateException | IllegalArgumentException | McpToolException refused) {
            lastAnswer = "refused";
            source.sendFailure(Component.translatable(KEYS + "refused", String.valueOf(refused.getMessage())));
        } catch (Exception | LinkageError e) {
            Host.LOGGER.error("Dailies: a /dailies command failed", e);
            lastAnswer = "went_wrong";
            source.sendFailure(Component.translatable(KEYS + "went_wrong"));
        }
        return 0;
    }

    @FunctionalInterface
    private interface Work {
        int run() throws Exception;
    }

    private static boolean watching() {
        return Playback.active();
    }

    // ------------------------------------------------------------------ Recording

    private static int start(CommandSourceStack source) {
        Minecraft minecraft = Minecraft.getInstance();
        if (watching()) {
            return fail(source, "not_while_watching");
        }
        if (Recorder.get().state() != Recorder.State.IDLE) {
            return fail(source, "already_recording");
        }
        if (minecraft.level == null || minecraft.player == null) {
            return fail(source, "join_first");
        }
        return guarded(source, () -> {
            Recorder.get().start();
            return say(source, "started");
        });
    }

    private static int finish(CommandSourceStack source, @Nullable String name) {
        if (watching()) {
            return fail(source, "not_while_watching");
        }
        if (Recorder.get().state() == Recorder.State.IDLE) {
            return fail(source, "nothing_recording");
        }
        if (name == null || name.isBlank()) {
            // Asks for a name unless the setting says not to. The chat closes itself after a command, so the
            // question is put up once it has
            Minecraft.getInstance().tell(() -> Controls.finishRecording(null));
            return say(source, "finishing");
        }
        String wanted = name.trim();
        return guarded(source, () -> {
            Recorder.get().finish(wanted).whenComplete((file, error) -> {
                if (error != null) {
                    Host.LOGGER.error("Dailies: the recording could not be saved", error);
                }
            });
            return say(source, "finished", wanted);
        });
    }

    private static int pause(CommandSourceStack source) {
        if (watching()) {
            return fail(source, "not_while_watching");
        }
        return switch (Recorder.get().state()) {
            case RECORDING -> guarded(source, () -> {
                Recorder.get().pause();
                return say(source, "paused");
            });
            case PAUSED -> fail(source, "already_paused");
            default -> fail(source, "nothing_recording");
        };
    }

    private static int resume(CommandSourceStack source) {
        if (watching()) {
            return fail(source, "not_while_watching");
        }
        return switch (Recorder.get().state()) {
            case PAUSED -> guarded(source, () -> {
                Recorder.get().resume();
                return say(source, "resumed");
            });
            case RECORDING -> fail(source, "not_paused");
            default -> fail(source, "nothing_recording");
        };
    }

    private static int cancel(CommandSourceStack source) {
        if (watching()) {
            return fail(source, "not_while_watching");
        }
        if (Recorder.get().state() == Recorder.State.IDLE) {
            return fail(source, "nothing_recording");
        }
        // A recording is only ever thrown away after a yes (REC-1)
        Minecraft.getInstance().tell(() -> Controls.cancelRecording(null));
        return say(source, "cancel_asking");
    }

    private static int mark(CommandSourceStack source, String words) {
        if (watching()) {
            return fail(source, "not_while_watching");
        }
        if (Recorder.get().state() == Recorder.State.IDLE) {
            return fail(source, "nothing_recording");
        }
        MarkWords.Mark mark = MarkWords.parse(words);
        return guarded(source, () -> {
            Recorder.get().addMarker(mark.colour(), mark.description(), mark.savePosition());
            String colour = mark.colourName().toLowerCase(Locale.ROOT);
            if (mark.description().isEmpty()) {
                return say(source, mark.savePosition() ? "marked_here" : "marked", colour);
            }
            return say(source, mark.savePosition() ? "marked_described_here" : "marked_described", colour, mark.description());
        });
    }

    private static int moment(CommandSourceStack source, String label) {
        if (watching()) {
            return fail(source, "not_while_watching");
        }
        String words = label.trim();
        if (words.isEmpty()) {
            return fail(source, "moment.usage");
        }
        try {
            JsonObject metadata = new JsonObject();
            metadata.addProperty("via", "command");
            MomentsTools.addEvent(null, words, metadata);
            return say(source, "moment", words);
        } catch (McpToolException noSession) {
            return fail(source, "moment.no_session");
        } catch (RuntimeException | LinkageError e) {
            Host.LOGGER.error("Dailies: /dailies moment failed", e);
            return fail(source, "went_wrong");
        }
    }

    // ------------------------------------------------------------------ The interface

    private static int config(CommandSourceStack source) {
        Minecraft minecraft = Minecraft.getInstance();
        // Once the chat has closed itself, or it would take the settings with it
        minecraft.tell(() -> Screens.openSettings(minecraft.screen));
        return say(source, "config");
    }

    private static int moments(CommandSourceStack source) {
        // The same way in as the button and the key have
        return Controls.openMomentsPage() ? say(source, "moments") : fail(source, "moments.off");
    }

    private static int hide(CommandSourceStack source) {
        boolean hidden = !Notices.hidden();
        Notices.setHidden(hidden);
        return say(source, hidden ? "hidden" : "shown");
    }

    // ------------------------------------------------------------------ While watching

    /**
     * Called with every command about to be sent to the server. While a recording is watched there is no server, and
     * nothing may go into the recording, so only this command is let through, to be run here (PLAY-4).
     *
     * @return true when the command was turned away and must not be sent
     */
    public static boolean turnedAway(String command) {
        if (!Playback.active()) {
            return false;
        }
        String typed = command == null ? "" : command.trim();
        if (typed.equals(ROOT) || typed.startsWith(ROOT + " ")) {
            return false;
        }
        lastAnswer = "only_own";
        Minecraft.getInstance().gui.getChat().addMessage(Component.translatable(KEYS + "only_own").withStyle(ChatFormatting.RED));
        return true;
    }

}
