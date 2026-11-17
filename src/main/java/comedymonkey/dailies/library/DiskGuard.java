package comedymonkey.dailies.library;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.capture.Recorder;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Keeps an eye on the free space while a recording is being made (specification REC-8): a notice when it falls below
 * {@code diskWarnGigabytes}, and below {@code diskPauseGigabytes} the recording is paused, with a notice, before the
 * disk fills and the file is cut short. The person can resume once there is room again; resuming with too little
 * room pauses it again a few seconds later.
 */
public final class DiskGuard {

    /** How often the free space is looked at while recording, in ticks */
    private static final int EVERY_TICKS = 100;
    /** A warning is not given again sooner than this */
    private static final long WARN_AGAIN_MILLIS = 10 * 60 * 1000L;

    private static int ticks = 0;
    private static long warnedAt = 0;

    private DiskGuard() {
    }

    static void init() {
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> tick());
    }

    /** Free bytes on the disk recordings are written to, or -1 when that cannot be told */
    public static long free() {
        try {
            Path folder = Recorder.folder();
            Files.createDirectories(folder);
            return Files.getFileStore(folder).getUsableSpace();
        } catch (Exception e) {
            return -1;
        }
    }

    /** What the free space calls for now, by the settings */
    public static Retention.Disk state(long free) {
        return Retention.disk(free, Host.get().setting("diskWarnGigabytes", 20), Host.get().setting("diskPauseGigabytes", 5));
    }

    private static void tick() {
        Recorder recorder = Recorder.get();
        if (recorder.state() != Recorder.State.RECORDING) {
            ticks = 0;
            return;
        }
        // Once as the recording starts or resumes, then every few seconds
        ticks++;
        if (ticks != 1 && ticks % EVERY_TICKS != 0) {
            return;
        }
        long free = free();
        Retention.Disk now = state(free);
        String gigabytes = String.format(java.util.Locale.ROOT, "%.1f", free / (double) Retention.GIGABYTE);
        if (now == Retention.Disk.FULL) {
            try {
                recorder.pause();
                Host.LOGGER.warn("Dailies: only {} GB free on the disk, so the recording was paused before it fills", gigabytes);
                comedymonkey.dailies.ui.Notices.show(Component.translatable("dailies.storage.notice.paused", gigabytes));
            } catch (RuntimeException e) {
                Host.LOGGER.error("Dailies: the recording could not be paused although the disk is nearly full", e);
            }
        } else if (now == Retention.Disk.LOW && System.currentTimeMillis() - warnedAt > WARN_AGAIN_MILLIS) {
            warnedAt = System.currentTimeMillis();
            comedymonkey.dailies.ui.Notices.show(Component.translatable("dailies.storage.notice.low", gigabytes));
        }
    }

}
