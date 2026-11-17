package comedymonkey.dailies.export;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The command that starts the hidden worker game (specification EXP-13) */
class WorkerLaunchTest {

    private static final String QUEUE = "C:\\Games\\mc\\dailies\\export-queue.json";

    @Test
    void theWorkerIsTheGameWithAPropertyAndNoWorld() {
        List<String> game = List.of("/usr/bin/java", "-Xmx6G", "-Ddailies.test=true", "-agentlib:jdwp=transport=dt_socket,server=y,address=5005",
            "-cp", "a.jar:b.jar", "net.example.Main", "--gameDir", "/home/me/mc", "--width", "1920", "--height", "1080", "--fullscreen",
            "--quickPlaySingleplayer", "My World", "--accessToken", "x");
        List<String> worker = WorkerLaunch.forWorker(game, QUEUE, 640, 360);
        assertEquals(List.of("/usr/bin/java", "-Ddailies.worker=" + QUEUE, "-Xmx6G", "-cp", "a.jar:b.jar", "net.example.Main",
            "--gameDir", "/home/me/mc", "--accessToken", "x", "--width", "640", "--height", "360"), worker);
    }

    @Test
    void aJarOrAModuleIsTheMain() {
        List<String> jar = WorkerLaunch.forWorker(List.of("java", "-Xmx2G", "-jar", "game.jar", "--width", "800"), QUEUE, 320, 180);
        assertEquals(List.of("java", "-Ddailies.worker=" + QUEUE, "-Xmx2G", "-jar", "game.jar", "--width", "320", "--height", "180"), jar);
        List<String> module = WorkerLaunch.forWorker(List.of("java", "-p", "libs", "-m", "boot/boot.Main", "--version", "1"), QUEUE, 320, 180);
        assertEquals(List.of("java", "-Ddailies.worker=" + QUEUE, "-p", "libs", "-m", "boot/boot.Main", "--version", "1", "--width", "320", "--height", "180"), module);
    }

    @Test
    void anArgumentFileIsPassedOnUntouched() {
        List<String> worker = WorkerLaunch.forWorker(List.of("java", "@C:\\launcher\\args.txt"), QUEUE, 320, 180);
        assertEquals(List.of("java", "-Ddailies.worker=" + QUEUE, "@C:\\launcher\\args.txt"), worker);
    }

    @Test
    void anEarlierWorkerPropertyIsReplaced() {
        List<String> worker = WorkerLaunch.forWorker(List.of("java", "-Ddailies.worker=old.json", "Main"), QUEUE, 320, 180);
        assertEquals(1, worker.stream().filter(argument -> argument.startsWith("-Ddailies.worker=")).count());
        assertTrue(worker.contains("-Ddailies.worker=" + QUEUE));
    }

    @Test
    void windowsCommandLinesFollowWindowsRules() {
        assertEquals(List.of("C:\\Program Files\\Java\\bin\\javaw.exe", "-Xmx4G", "-cp", "C:\\My Games\\a.jar;b.jar", "Main", "--gameDir", "C:\\My Games\\mc"),
            WorkerLaunch.parseWindows("\"C:\\Program Files\\Java\\bin\\javaw.exe\" -Xmx4G -cp \"C:\\My Games\\a.jar;b.jar\" Main --gameDir \"C:\\My Games\\mc\""));
        // Backslashes count only before a quote; a doubled quote inside quotes is one quote
        assertEquals(List.of("java", "a\\\\b", "say \"hi\"", "end\\", "x\"y"),
            WorkerLaunch.parseWindows("java a\\\\b \"say \"\"hi\"\"\" \"end\\\\\" x\\\"y"));
        assertEquals(List.of("java", ""), WorkerLaunch.parseWindows("java \"\""));
    }

    @Test
    void theJavaCommandIsSplitAtTheGamesOptions() {
        assertEquals(List.of("cpw.mods.bootstraplauncher.BootstrapLauncher", "--username", "Me", "--gameDir", "C:\\My Games\\mc", "--demo"),
            WorkerLaunch.splitJavaCommand("cpw.mods.bootstraplauncher.BootstrapLauncher --username Me --gameDir C:\\My Games\\mc --demo"));
        assertEquals(List.of("C:\\My Games\\game.jar", "--width", "10"), WorkerLaunch.splitJavaCommand("C:\\My Games\\game.jar --width 10"));
    }

    @Test
    void moduleOptionsAreGivenBackAsOptions() {
        List<String> options = WorkerLaunch.javaOptions(List.of("-Xmx3G", "-Djdk.module.path=libs", "-Djdk.module.addopens.0=java.base/java.lang=ALL-UNNAMED",
            "-Djdk.module.main=boot", "-Dfoo=bar", "--add-opens=java.base/java.util=ALL-UNNAMED"));
        assertEquals(List.of("-Xmx3G", "--module-path", "libs", "--add-opens", "java.base/java.lang=ALL-UNNAMED", "-Dfoo=bar", "--add-opens=java.base/java.util=ALL-UNNAMED"), options);
    }

    @Test
    void thePlanUsesTheBestThereIs() {
        WorkerLaunch.Launched linux = new WorkerLaunch.Launched("/usr/bin/java", List.of("-cp", "x", "Main", "--gameDir", "g"), null, List.of(), "x", "Main --gameDir g");
        WorkerLaunch.Plan plan = WorkerLaunch.plan(linux, QUEUE, 320, 180);
        assertEquals("arguments", plan.how());
        assertEquals("Main", plan.command().get(4));

        WorkerLaunch.Launched windows = new WorkerLaunch.Launched("C:\\Java\\bin\\javaw.exe", null, "javaw -cp x Main --gameDir g", List.of(), "x", "Main --gameDir g");
        plan = WorkerLaunch.plan(windows, QUEUE, 320, 180);
        assertEquals("command line", plan.how());
        assertEquals("C:\\Java\\bin\\javaw.exe", plan.command().get(0));

        WorkerLaunch.Launched bare = new WorkerLaunch.Launched("C:\\Java\\bin\\javaw.exe", null, null, List.of("-Xmx2G"), "a.jar;b.jar", "Main --gameDir C:\\My Games\\mc");
        plan = WorkerLaunch.plan(bare, QUEUE, 320, 180);
        assertEquals("rebuilt", plan.how());
        assertEquals(List.of("C:\\Java\\bin\\javaw.exe", "-Ddailies.worker=" + QUEUE, "-Xmx2G", "-cp", "a.jar;b.jar", "Main", "--gameDir", "C:\\My Games\\mc",
            "--width", "320", "--height", "180"), plan.command());

        WorkerLaunch.Launched nothing = new WorkerLaunch.Launched("java", null, null, List.of(), "", "");
        assertThrows(IllegalStateException.class, () -> WorkerLaunch.plan(nothing, QUEUE, 320, 180));
        assertFalse(WorkerLaunch.forWorker(List.of("java", "Main", "--quickPlayMultiplayer=server"), QUEUE, 1, 1).contains("--quickPlayMultiplayer=server"));
    }

}
