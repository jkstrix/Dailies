package comedymonkey.dailies.export;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.ui.Notices;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Says on the desktop that the export queue is done (specification EXP-10), so a run left going overnight, or in a
 * hidden worker, is noticed. Through Java's system tray where the platform offers one; Minecraft runs without a
 * desktop toolkit, so most of the time that is the system's own way of showing a notification instead (a balloon on
 * Windows, Notification Centre on macOS, notify-send on Linux). In the game, a notice as well, when one can be shown.
 */
public final class Notifier {

    private Notifier() {
    }

    /** Tells the person, on the desktop and in the game where there is one. Any thread */
    public static void queueDone(int done, int failed) {
        String title = "Dailies";
        String words = failed == 0
            ? (done == 1 ? "The export queue is done: 1 export written." : "The export queue is done: " + done + " exports written.")
            : "The export queue is done: " + (done - failed) + " written, " + failed + " failed.";
        if (Host.get().setting("exportQueueNotify", true)) {
            Thread thread = new Thread(() -> desktop(title, words), "Dailies-Notify");
            thread.setDaemon(true);
            thread.start();
        }
        if (!Worker.active()) {
            Minecraft.getInstance().execute(() -> Notices.show(Component.literal(words)));
        }
    }

    /** Shows a notification on the desktop; false when no way of doing so worked */
    static boolean desktop(String title, String words) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        // The toolkit and the game's window fight over the main thread on macOS, so the tray is only tried elsewhere
        if (!os.contains("mac") && tray(title, words)) {
            return true;
        }
        try {
            List<String> command;
            if (os.contains("win")) {
                command = List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                    "Add-Type -AssemblyName System.Windows.Forms; Add-Type -AssemblyName System.Drawing; "
                        + "$n = New-Object System.Windows.Forms.NotifyIcon; $n.Icon = [System.Drawing.SystemIcons]::Information; $n.Visible = $true; "
                        + "$n.ShowBalloonTip(10000, '" + quoted(title) + "', '" + quoted(words) + "', 'Info'); Start-Sleep -Seconds 10; $n.Dispose()");
            } else if (os.contains("mac")) {
                command = List.of("osascript", "-e", "display notification \"" + words.replace("\"", "'") + "\" with title \"" + title.replace("\"", "'") + "\"");
            } else {
                command = List.of("notify-send", title, words);
            }
            Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            process.getOutputStream().close();
            return !process.waitFor(20, TimeUnit.SECONDS) || process.exitValue() == 0;
        } catch (Exception e) {
            Host.LOGGER.debug("Dailies: no desktop notification could be shown", e);
            return false;
        }
    }

    /** Java's own tray icon, where a desktop toolkit is running */
    private static boolean tray(String title, String words) {
        try {
            if (java.awt.GraphicsEnvironment.isHeadless() || !java.awt.SystemTray.isSupported()) {
                return false;
            }
            java.awt.SystemTray tray = java.awt.SystemTray.getSystemTray();
            java.awt.image.BufferedImage picture = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.TrayIcon icon = new java.awt.TrayIcon(picture, title);
            tray.add(icon);
            icon.displayMessage(title, words, java.awt.TrayIcon.MessageType.INFO);
            Thread.sleep(10_000);
            tray.remove(icon);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Safe inside single quotes in PowerShell */
    private static String quoted(String text) {
        return text.replace("'", "''");
    }

}
