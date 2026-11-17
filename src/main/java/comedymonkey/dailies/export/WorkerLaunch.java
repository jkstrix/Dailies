package comedymonkey.dailies.export;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Works out the command that starts the hidden worker game (specification EXP-13): the game as it was started, with
 * the same Java, memory, class and module paths, game folder and mods, and with what makes it a worker added and what
 * would make it do anything else taken out. No Minecraft and no process here, so every rule can be tested.
 * <p>
 * How the running game was started is found out in the best way the platform allows:
 * <ol>
 * <li>Linux and macOS give the whole command through {@link ProcessHandle.Info#arguments()}.</li>
 * <li>Windows gives only the program; the whole command line is asked of the system and taken apart by Windows' own
 * rules ({@link #parseWindows}).</li>
 * <li>Failing both, it is put back together from the Java options the game was given, its class path, and
 * {@code sun.java.command} (the main class and the game's own arguments).</li>
 * </ol>
 */
public final class WorkerLaunch {

    /** The system property that makes a game the worker; its value is the queue file */
    public static final String PROPERTY = "dailies.worker";

    /**
     * What is known about how this game was started.
     *
     * @param executable   the Java program, never empty
     * @param arguments    every argument after the program, when the platform gives them; else null
     * @param commandLine  the whole command line as one string (Windows), when it could be had; else null
     * @param jvmArguments the options the Java machine was given (RuntimeMXBean), used only when nothing better is known
     * @param classPath    java.class.path
     * @param javaCommand  sun.java.command: the main class or jar and the program's arguments, joined by spaces
     */
    public record Launched(String executable, @Nullable List<String> arguments, @Nullable String commandLine, List<String> jvmArguments,
                           String classPath, String javaCommand) {
    }

    /**
     * The command to run, and how the original was found, for the log and for whoever asks why a worker would not start.
     *
     * @param how arguments, command line or rebuilt
     */
    public record Plan(List<String> command, String how) {
    }

    /** Java options that take the next argument as their value */
    private static final Set<String> JAVA_WITH_VALUE = Set.of("-cp", "-classpath", "--class-path", "-p", "--module-path", "--upgrade-module-path",
        "--add-modules", "--limit-modules", "--add-reads", "--add-exports", "--add-opens", "--patch-module", "--enable-native-access",
        "--describe-module", "-d", "--source");
    /** The game's own options that are taken out of a worker, with their value: joining a world, the window's size */
    private static final Set<String> GAME_DROPPED_WITH_VALUE = Set.of("--quickplaysingleplayer", "--quickplaymultiplayer", "--quickplayrealms",
        "--quickplaypath", "--server", "--port", "--width", "--height");
    /** The same, for options without a value */
    private static final Set<String> GAME_DROPPED_FLAGS = Set.of("--fullscreen");

    private WorkerLaunch() {
    }

    // ------------------------------------------------------------------ The whole thing

    /**
     * The command for a worker that reads this queue file, with a window of this size.
     *
     * @throws IllegalStateException when the original command cannot be worked out
     */
    public static Plan plan(Launched launched, String queueFile, int width, int height) {
        List<String> original;
        String how;
        if (launched.arguments() != null && !launched.arguments().isEmpty()) {
            original = new ArrayList<>();
            original.add(launched.executable());
            original.addAll(launched.arguments());
            how = "arguments";
        } else if (launched.commandLine() != null && !launched.commandLine().isBlank() && parseWindows(launched.commandLine()).size() > 1) {
            original = parseWindows(launched.commandLine());
            // The command line's program may be written without its folder; the one Java reports is the one running
            original.set(0, launched.executable());
            how = "command line";
        } else {
            original = rebuilt(launched);
            how = "rebuilt";
        }
        return new Plan(forWorker(original, queueFile, width, height), how);
    }

    /**
     * The game's command changed into a worker's: the worker property first, a debugger, the self test and an earlier
     * worker property taken out, and of the game's own options those that would join a world or set the window's size,
     * with the worker's size added.
     *
     * @param original the program followed by every argument
     */
    public static List<String> forWorker(List<String> original, String queueFile, int width, int height) {
        if (original.isEmpty()) {
            throw new IllegalStateException("The command the game was started with is empty");
        }
        int main = mainIndex(original);
        List<String> command = new ArrayList<>();
        command.add(original.get(0));
        command.add("-D" + PROPERTY + "=" + queueFile);
        int javaEnd = main < 0 ? original.size() : main;
        for (int i = 1; i < javaEnd; i++) {
            String option = original.get(i);
            if (droppedJavaOption(option)) {
                continue;
            }
            command.add(option);
        }
        if (main < 0) {
            // Everything is in an argument file: the game's own options cannot be seen, so they are left as they are
            return command;
        }
        command.add(original.get(main));
        List<String> game = original.subList(main + 1, original.size());
        for (int i = 0; i < game.size(); i++) {
            String argument = game.get(i);
            String lower = argument.toLowerCase(Locale.ROOT);
            String name = lower.contains("=") ? lower.substring(0, lower.indexOf('=')) : lower;
            if (GAME_DROPPED_FLAGS.contains(name)) {
                continue;
            }
            if (GAME_DROPPED_WITH_VALUE.contains(name)) {
                if (!lower.contains("=") && i + 1 < game.size() && !game.get(i + 1).startsWith("--")) {
                    i++;
                }
                continue;
            }
            command.add(argument);
        }
        command.add("--width");
        command.add(Integer.toString(width));
        command.add("--height");
        command.add(Integer.toString(height));
        return command;
    }

    /**
     * Where the main class (or the jar or module that stands for it) is among the arguments, or -1 when it cannot be
     * seen because it is in an argument file. Java's options come before it and the program's own after.
     */
    static int mainIndex(List<String> command) {
        for (int i = 1; i < command.size(); i++) {
            String argument = command.get(i);
            if (JAVA_WITH_VALUE.contains(argument)) {
                i++;
                continue;
            }
            if (argument.equals("-jar") || argument.equals("-m") || argument.equals("--module")) {
                // The jar or module is the main; what came before it, including this option, is Java's
                return i + 1 < command.size() ? i + 1 : -1;
            }
            if (argument.startsWith("-") || argument.startsWith("@")) {
                continue;
            }
            return i;
        }
        return -1;
    }

    private static boolean droppedJavaOption(String option) {
        return option.startsWith("-D" + PROPERTY + "=") || option.startsWith("-D" + PROPERTY + ".")
            || option.startsWith("-Ddailies.test")
            // A debugger listening on a port would stop the worker from starting while the game holds that port
            || option.startsWith("-agentlib:jdwp") || option.startsWith("-Xrunjdwp") || option.equals("-Xdebug");
    }

    // ------------------------------------------------------------------ Windows

    /**
     * Takes a Windows command line apart as the C runtime does: spaces part arguments outside quotes, a quote begins
     * or ends quoting, a doubled quote inside quotes is one quote, and backslashes count only before a quote (2n
     * backslashes and a quote give n and a quote that quotes, 2n + 1 give n and a quote that is kept). The program, the
     * first argument, is taken more simply, as Windows does: up to the next space, or between quotes.
     */
    public static List<String> parseWindows(String commandLine) {
        List<String> arguments = new ArrayList<>();
        int length = commandLine.length();
        int i = 0;
        while (i < length && Character.isWhitespace(commandLine.charAt(i))) {
            i++;
        }
        if (i >= length) {
            return arguments;
        }
        StringBuilder program = new StringBuilder();
        if (commandLine.charAt(i) == '"') {
            i++;
            while (i < length && commandLine.charAt(i) != '"') {
                program.append(commandLine.charAt(i++));
            }
            i++;
        } else {
            while (i < length && !Character.isWhitespace(commandLine.charAt(i))) {
                program.append(commandLine.charAt(i++));
            }
        }
        arguments.add(program.toString());

        StringBuilder current = new StringBuilder();
        boolean inArgument = false;
        boolean quoted = false;
        while (i < length) {
            char c = commandLine.charAt(i);
            if (!quoted && (c == ' ' || c == '\t')) {
                if (inArgument) {
                    arguments.add(current.toString());
                    current.setLength(0);
                    inArgument = false;
                }
                i++;
                continue;
            }
            inArgument = true;
            if (c == '\\') {
                int slashes = 0;
                while (i < length && commandLine.charAt(i) == '\\') {
                    slashes++;
                    i++;
                }
                if (i < length && commandLine.charAt(i) == '"') {
                    current.append("\\".repeat(slashes / 2));
                    if (slashes % 2 == 1) {
                        current.append('"');
                        i++;
                    }
                } else {
                    current.append("\\".repeat(slashes));
                }
                continue;
            }
            if (c == '"') {
                if (quoted && i + 1 < length && commandLine.charAt(i + 1) == '"') {
                    current.append('"');
                    i += 2;
                    continue;
                }
                quoted = !quoted;
                i++;
                continue;
            }
            current.append(c);
            i++;
        }
        if (inArgument) {
            arguments.add(current.toString());
        }
        return arguments;
    }

    // ------------------------------------------------------------------ Put back together

    /**
     * The command as well as it can be put back together without the platform's help: the Java options (with the
     * module options turned back into the form they were given in), the class path, and the main class with the
     * program's arguments from {@code sun.java.command}.
     */
    static List<String> rebuilt(Launched launched) {
        if (launched.javaCommand() == null || launched.javaCommand().isBlank()) {
            throw new IllegalStateException("How the game was started cannot be found out on this system");
        }
        List<String> command = new ArrayList<>();
        command.add(launched.executable());
        List<String> java = javaOptions(launched.jvmArguments());
        command.addAll(java);
        boolean hasClassPath = java.stream().anyMatch(option -> option.equals("-cp") || option.equals("-classpath") || option.equals("--class-path"));
        List<String> rest = splitJavaCommand(launched.javaCommand());
        boolean jar = !rest.isEmpty() && rest.get(0).toLowerCase(Locale.ROOT).endsWith(".jar");
        if (!hasClassPath && !jar && launched.classPath() != null && !launched.classPath().isBlank()) {
            command.add("-cp");
            command.add(launched.classPath());
        }
        if (jar) {
            command.add("-jar");
        } else if (!rest.isEmpty() && rest.get(0).contains("/") && !rest.get(0).contains("\\")) {
            // module/class
            command.add("-m");
        }
        command.addAll(rest);
        return command;
    }

    /**
     * The Java options as RuntimeMXBean reports them, made fit to be given again. Java turns some options into
     * properties of its own, which it refuses when given back as properties: those are turned back into options.
     */
    static List<String> javaOptions(List<String> reported) {
        List<String> options = new ArrayList<>();
        for (String option : reported) {
            if (option.startsWith("-Djdk.module.")) {
                int equals = option.indexOf('=');
                String key = equals < 0 ? option.substring(2) : option.substring(2, equals);
                String value = equals < 0 ? "" : option.substring(equals + 1);
                String flag = moduleFlag(key);
                if (flag == null) {
                    // jdk.module.main and the like are set by the -m that is given again with the main
                    continue;
                }
                options.add(flag);
                options.add(value);
                continue;
            }
            if (option.startsWith("-Djava.class.path=") || option.startsWith("-Dsun.java.command=") || option.startsWith("-Dsun.java.launcher")) {
                continue;
            }
            options.add(option);
        }
        return options;
    }

    private static @Nullable String moduleFlag(String key) {
        String base = key.replaceAll("\\.\\d+$", "");
        return switch (base) {
            case "jdk.module.path" -> "--module-path";
            case "jdk.module.upgrade.path" -> "--upgrade-module-path";
            case "jdk.module.addmods" -> "--add-modules";
            case "jdk.module.limitmods" -> "--limit-modules";
            case "jdk.module.addreads" -> "--add-reads";
            case "jdk.module.addexports" -> "--add-exports";
            case "jdk.module.addopens" -> "--add-opens";
            case "jdk.module.patch" -> "--patch-module";
            case "jdk.module.enable.native.access" -> "--enable-native-access";
            default -> null;
        };
    }

    /**
     * Takes {@code sun.java.command} apart: the main class first, then the program's arguments. Java joins them with
     * single spaces and quotes nothing, so a value with spaces in it (a folder name) cannot be told from several
     * values by the spaces alone. The game's options all begin with "--", so everything up to the next "--" is taken
     * as one value.
     */
    public static List<String> splitJavaCommand(String javaCommand) {
        List<String> parts = new ArrayList<>();
        String text = javaCommand.trim();
        if (text.isEmpty()) {
            return parts;
        }
        int firstSpace = text.indexOf(' ');
        String main = firstSpace < 0 ? text : text.substring(0, firstSpace);
        String rest = firstSpace < 0 ? "" : text.substring(firstSpace + 1);
        // A jar's path may itself hold spaces: it runs to ".jar"
        int jarEnd = text.toLowerCase(Locale.ROOT).indexOf(".jar");
        if (jarEnd > 0 && jarEnd + 4 > main.length() && (jarEnd + 4 == text.length() || text.charAt(jarEnd + 4) == ' ') && !text.substring(0, jarEnd).contains(" --")) {
            main = text.substring(0, jarEnd + 4);
            rest = text.substring(Math.min(text.length(), jarEnd + 5));
        }
        parts.add(main);
        String[] words = rest.isEmpty() ? new String[0] : rest.split(" ", -1);
        StringBuilder value = null;
        for (String word : words) {
            if (word.startsWith("--") && word.length() > 2) {
                if (value != null) {
                    parts.add(value.toString());
                    value = null;
                }
                parts.add(word);
                continue;
            }
            if (value == null) {
                value = new StringBuilder(word);
            } else {
                value.append(' ').append(word);
            }
        }
        if (value != null && !value.toString().isEmpty()) {
            parts.add(value.toString());
        }
        return parts;
    }

}
