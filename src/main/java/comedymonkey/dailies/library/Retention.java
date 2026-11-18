package comedymonkey.dailies.library;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The decisions behind the storage settings (specification REC-8), kept apart from the files so they can be tested:
 * which recordings are old enough to go to the bin, and what the free space on the disk calls for. The defaults keep
 * everything, warn below 20 GB free and pause a recording below 5 GB.
 */
public final class Retention {

    public static final long GIGABYTE = 1024L * 1024 * 1024;

    /** What the free space calls for */
    public enum Disk {
        /** Nothing */
        FINE,
        /** A warning: free space is below the warning threshold */
        LOW,
        /** The recording being made is paused before the disk fills */
        FULL
    }

    private Retention() {
    }

    /**
     * Whether a recording has been kept long enough to move to the bin.
     *
     * @param days         recordings older than this many days go; 0 or less keeps every recording
     * @param starred      starred recordings always stay
     * @param holdsExport  so do recordings that hold footage a video was made from
     */
    public static boolean tooOld(LibraryIndex.Entry entry, long now, int days, boolean starred, boolean holdsExport) {
        if (days <= 0 || starred || holdsExport) {
            return false;
        }
        long when = entry.newest() > 0 ? entry.newest() : entry.modified;
        return when > 0 && now - when > days * Bin.DAY_MILLIS;
    }

    /** The recordings that are to go to the bin now, oldest first */
    public static List<LibraryIndex.Entry> toBin(List<LibraryIndex.Entry> entries, long now, int days, Set<String> starred, List<ExportLog.Exported> exports) {
        List<LibraryIndex.Entry> going = new ArrayList<>();
        if (days <= 0) {
            return going;
        }
        for (LibraryIndex.Entry entry : entries) {
            if (tooOld(entry, now, days, starred.contains(entry.id), ExportLog.holdsExported(entry, exports))) {
                going.add(entry);
            }
        }
        going.sort(java.util.Comparator.comparingLong(LibraryIndex.Entry::newest));
        return going;
    }

    /**
     * What the free space on the disk calls for.
     *
     * @param warnGigabytes  below this a warning is given; 0 never warns
     * @param pauseGigabytes below this a recording is paused; 0 never pauses
     */
    public static Disk disk(long freeBytes, int warnGigabytes, int pauseGigabytes) {
        if (freeBytes < 0) {
            return Disk.FINE;
        }
        if (pauseGigabytes > 0 && freeBytes < pauseGigabytes * GIGABYTE) {
            return Disk.FULL;
        }
        if (warnGigabytes > 0 && freeBytes < warnGigabytes * GIGABYTE) {
            return Disk.LOW;
        }
        return Disk.FINE;
    }

    /** The days left before something in the bin is deleted for good, rounded up: 1 on its last day, 0 once it is due */
    public static int daysLeft(Bin.Item item, int binDays, long now) {
        long left = item.expires(binDays) - now;
        if (left <= 0) {
            return 0;
        }
        return (int) Math.max(1, (left + Bin.DAY_MILLIS - 1) / Bin.DAY_MILLIS);
    }

}
