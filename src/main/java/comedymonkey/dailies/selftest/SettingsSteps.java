package comedymonkey.dailies.selftest;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.config.DailiesConfig;
import comedymonkey.dailies.config.Setting;
import comedymonkey.dailies.ui.Previews;
import comedymonkey.dailies.ui.SettingsScreen;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static comedymonkey.dailies.selftest.SelfTest.check;

/**
 * The scripted steps for Dailies' own settings screen (specification CFG-4): every declared setting is on it, the
 * search finds settings by name and by description, a choice is changed through its dropdown, a number out of range
 * is refused with the nearest allowed value, a reset puts the default back, each change reaches the file on disk, a
 * change made to the file by hand reaches the screen, and the Test button of an address says whether a server answers.
 */
final class SettingsSteps {

    private SettingsSteps() {
    }

    static void run(SelfTest test, int mcpPort) throws Exception {
        Minecraft minecraft = Minecraft.getInstance();
        Path file = FMLPaths.CONFIGDIR.get().resolve("dailies-client.toml");
        check(Files.exists(file), "the settings file is not at " + file);

        test.onClientRun(() -> SettingsScreen.open(minecraft.screen));
        test.waitFor(() -> minecraft.screen instanceof SettingsScreen, 10, "the settings screen to open");
        SettingsScreen screen = (SettingsScreen) minecraft.screen;
        test.sleepTicks(5);

        // Every declared setting is shown, in the order it is declared, with nothing made up
        List<String> declared = new ArrayList<>();
        for (Setting<?> setting : DailiesConfig.all()) {
            declared.add(setting.name());
        }
        List<String> shown = test.onClient(screen::shown);
        check(shown.equals(declared), "the settings screen does not show exactly the declared settings: " + shown.size() + " shown, " + declared.size() + " declared");

        // Search: by words of the label, by the name in the file, by the description, and nothing for nonsense
        shown = found(test, screen, "updates second");
        check(shown.contains("recorderUpdatesPerSecond") && shown.size() <= 3, "searching for 'updates second' found " + shown);
        shown = found(test, screen, "mcpport");
        check(shown.equals(List.of("mcpPort")), "searching for the name 'mcpport' found " + shown);
        shown = found(test, screen, "side of the pause menu");
        check(shown.equals(List.of("controlsLocation")), "searching the descriptions for 'side of the pause menu' found " + shown);
        shown = found(test, screen, "qqzzxx");
        check(shown.isEmpty(), "searching for nonsense found " + shown);
        shown = found(test, screen, "");
        check(shown.equals(declared), "clearing the search did not bring every setting back");

        // An earlier run that stopped half way may have left these changed
        test.onClientRun(() -> {
            screen.reset("controlsLocation");
            screen.reset("recorderUpdatesPerSecond");
            screen.reset("momentsClipPostRollSeconds");
        });
        saved(test, screen);
        waitForLine(test, file, "controlsLocation", "\"RIGHT\"");
        waitForLine(test, file, "recorderUpdatesPerSecond", "20");

        // A choice, through its dropdown: the list opens with every choice, typing narrows it, choosing sets it
        check(test.onClient(() -> screen.choose("controlsLocation", null)) == null, "the dropdown of controlsLocation did not open");
        List<String> lines = test.onClient(Previews::menuLines);
        check(lines.size() == 2, "the dropdown of controlsLocation lists " + lines);
        List<String> narrowed = test.onClient(() -> Previews.menuFind("le"));
        check(narrowed.size() == 1 && narrowed.get(0).toLowerCase().startsWith("le"), "typing 'le' in the dropdown left " + narrowed);
        test.onClientRun(Previews::closeMenu);
        String refusal = test.onClient(() -> screen.choose("controlsLocation", "LEFT"));
        check(refusal == null, "choosing LEFT from the dropdown failed: " + refusal);
        saved(test, screen);
        check("LEFT".equals(Host.get().setting("controlsLocation", "RIGHT")), "the dropdown did not set controlsLocation");
        waitForLine(test, file, "controlsLocation", "\"LEFT\"");
        check(test.onClient(() -> screen.changed("controlsLocation")), "a changed setting is not marked as changed");
        refusal = test.onClient(() -> screen.choose("controlsLocation", "MIDDLE"));
        check(refusal != null, "a choice that is not in the list was taken");
        test.onClientRun(() -> screen.reset("controlsLocation"));
        saved(test, screen);
        waitForLine(test, file, "controlsLocation", "\"RIGHT\"");
        check(!test.onClient(() -> screen.changed("controlsLocation")), "a reset setting is still marked as changed");

        // A number out of range is refused, the nearest allowed value named, and nothing is written
        refusal = test.onClient(() -> screen.type("recorderUpdatesPerSecond", "99"));
        check(refusal != null && refusal.contains("20"), "99 updates per second was not refused with the nearest allowed value: " + refusal);
        refusal = test.onClient(() -> screen.type("recorderUpdatesPerSecond", "0"));
        check(refusal != null && refusal.contains("1"), "0 updates per second was not refused: " + refusal);
        refusal = test.onClient(() -> screen.type("recorderUpdatesPerSecond", "7.5"));
        check(refusal != null, "7.5 updates per second was not refused");
        refusal = test.onClient(() -> screen.type("recorderUpdatesPerSecond", "lots"));
        check(refusal != null, "'lots' was taken as a number");
        test.sleepTicks(5);
        check(Host.get().setting("recorderUpdatesPerSecond", 20) == 20 && !test.onClient(screen::saving), "a refused number changed the setting");
        // One in range is taken and written, and the reset writes the default back
        refusal = test.onClient(() -> screen.type("recorderUpdatesPerSecond", "7"));
        check(refusal == null, "7 updates per second was refused: " + refusal);
        saved(test, screen);
        check(Host.get().setting("recorderUpdatesPerSecond", 20) == 7, "typing 7 did not set recorderUpdatesPerSecond");
        waitForLine(test, file, "recorderUpdatesPerSecond", "7");
        test.onClientRun(() -> screen.reset("recorderUpdatesPerSecond"));
        saved(test, screen);
        waitForLine(test, file, "recorderUpdatesPerSecond", "20");
        check(Host.get().setting("recorderUpdatesPerSecond", 7) == 20, "the reset did not put recorderUpdatesPerSecond back");

        // The file changed by hand: the game reads it again and the screen shows the new value, marked as changed
        String text = Files.readString(file, StandardCharsets.UTF_8);
        Pattern line = Pattern.compile("(?m)^(\\s*momentsClipPostRollSeconds\\s*=\\s*)20\\s*$");
        check(line.matcher(text).find(), "momentsClipPostRollSeconds is not at its default in the file");
        Files.writeString(file, line.matcher(text).replaceFirst("$121"), StandardCharsets.UTF_8);
        try {
            test.waitFor(() -> Host.get().setting("momentsClipPostRollSeconds", 20) == 21, 20, "a hand edit of the settings file to be picked up");
            check(test.onClient(() -> screen.changed("momentsClipPostRollSeconds")), "a value changed in the file by hand is not marked as changed on the screen");
        } finally {
            test.onClientRun(() -> screen.reset("momentsClipPostRollSeconds"));
            saved(test, screen);
        }
        waitForLine(test, file, "momentsClipPostRollSeconds", "20");

        // An address is looked at as it is typed, and Test asks whatever is in the box without keeping it
        String whisper = Host.get().setting("momentsWhisperUrl", "");
        refusal = test.onClient(() -> screen.test("momentsWhisperUrl", "192.168.1.5:8000"));
        check(refusal != null, "an address without http:// was not refused");
        refusal = test.onClient(() -> screen.test("momentsWhisperUrl", "http://127.0.0.1:" + mcpPort + "/"));
        check(refusal == null, "the test of an address that is written properly did not start: " + refusal);
        test.waitFor(() -> !screen.tested("momentsWhisperUrl").isEmpty(), 10, "the Test button to say what came back");
        String said = screen.tested("momentsWhisperUrl");
        check(said.startsWith("answered"), "Test did not report the MCP server, which is running, as answering: " + said);
        // Port 9 on this machine: nothing listens there
        refusal = test.onClient(() -> screen.test("momentsWhisperUrl", "http://127.0.0.1:9/"));
        check(refusal == null, "the second test did not start: " + refusal);
        test.waitFor(() -> !screen.tested("momentsWhisperUrl").isEmpty(), 10, "the Test button to say that nothing answered");
        said = screen.tested("momentsWhisperUrl");
        check(said.startsWith("no answer"), "Test reported an answer from a port nothing listens on: " + said);
        // Closing the screen hands over a box that is being typed into, so the address tried is taken out of it first
        test.onClientRun(() -> {
            screen.abandon();
            minecraft.setScreen(null);
        });
        test.sleepTicks(10);
        check(whisper.equals(Host.get().setting("momentsWhisperUrl", "")), "testing an address changed the setting");
        check(!Host.get().setting("momentsAiEnabled", true), "the settings steps left the AI switch on");
        test.log("settings screen: " + declared.size() + " settings shown, searched, changed, refused, reset and tested");
    }

    private static List<String> found(SelfTest test, SettingsScreen screen, String words) throws Exception {
        test.onClientRun(() -> screen.search(words));
        return test.onClient(screen::shown);
    }

    private static void saved(SelfTest test, SettingsScreen screen) throws Exception {
        test.waitFor(() -> !screen.saving(), 20, "a changed setting to be saved");
    }

    private static void waitForLine(SelfTest test, Path file, String name, String value) throws Exception {
        Pattern line = Pattern.compile("(?m)^\\s*" + Pattern.quote(name) + "\\s*=\\s*" + Pattern.quote(value) + "\\s*$");
        test.waitFor(() -> {
            try {
                return line.matcher(Files.readString(file, StandardCharsets.UTF_8)).find();
            } catch (Exception e) {
                // Being written this moment
                return false;
            }
        }, 20, name + " = " + value + " to be in the settings file");
    }

}
