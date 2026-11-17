package comedymonkey.dailies.editor;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.edit.Evaluator;
import comedymonkey.dailies.edit.LinkedRecording;
import comedymonkey.dailies.edit.Scene;
import comedymonkey.dailies.file.Alignment;
import comedymonkey.dailies.file.RecordingFile;
import comedymonkey.dailies.library.Links;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.playback.Recordings;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Several points of view in one scene (PLAY-19, EDIT-11). A scene belongs to one recording, its home, and its ticks
 * are that recording's; it can also hold linked recordings, other players' of the same stretch. A point of view
 * keyframe shows one of them from its tick on: playback opens that recording and jumps it to the tick that lines up,
 * by the server's game time, so a switch is a seek and the camera stays where it was. The editor's state stays the
 * home recording's throughout, and its keyframes are read in home ticks however the shown recording counts its own.
 * <p>
 * While another's recording is shown, standing still or jumping by hand goes back to the home recording at the same
 * moment, so that editing is always done in the scene's own ticks. Switches are made while playing; an export shows the
 * home recording. Client thread, except where said.
 */
public final class PointOfView {

    /** How far ahead a coming switch is prepared for: the next recording's segment is read before it is needed */
    private static final int PRELOAD_TICKS = 100;

    private static @Nullable RecordingFile homeFile = null;
    private static @Nullable Path homePath = null;
    private static @Nullable String homeId = null;
    private static int homeTotal = 0;
    private static Alignment homeRuns = Alignment.of(List.of());
    private static @Nullable String shownId = null;
    private static Alignment shownRuns = Alignment.of(List.of());
    private static boolean switching = false;
    private static double lastSceneTime = 0.0;
    private static long pendingGame = Long.MIN_VALUE;
    private static int pendingTick = -1;
    private static @Nullable String preloaded = null;
    private static final Set<String> COMPLAINED = new HashSet<>();

    /** The runs of other recordings, by id, worked out away from the client thread and kept */
    private static final Map<String, Alignment> RUNS = new ConcurrentHashMap<>();
    private static final Map<String, Path> FILES = new ConcurrentHashMap<>();
    private static final Set<String> ASKED = ConcurrentHashMap.newKeySet();
    private static final ExecutorService READER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Dailies-Point-Of-View");
        thread.setDaemon(true);
        return thread;
    });

    private PointOfView() {
    }

    // --- What playback and the editor tell it ---

    /** The editor has opened a recording: it is the home of whatever is edited until another is opened */
    static void home(RecordingFile file) {
        homeFile = file;
        homePath = file.file();
        homeId = file.id();
        homeTotal = file.totalTicks();
        homeRuns = runsOf(file);
        shownId = homeId;
        shownRuns = homeRuns;
        switching = false;
        preloaded = null;
        COMPLAINED.clear();
        ASKED.clear();
        FILES.clear();
    }

    /** A switch has opened another recording, or the home one again, in playback */
    public static void shown(RecordingFile file) {
        shownId = file.id();
        shownRuns = file.id().equals(homeId) ? homeRuns : runsOf(file);
        RUNS.put(file.id(), shownRuns);
        switching = false;
        preloaded = null;
    }

    /** For a switch: the editor's own file when the home recording is asked for again, so it is never open twice */
    public static @Nullable RecordingFile reuse(Path path) {
        return homeFile != null && path.equals(homePath) ? homeFile : null;
    }

    /** Whether this file is the home recording's, which playback must not close while the editor still saves into it */
    public static boolean holds(@Nullable RecordingFile file) {
        return file != null && file == homeFile;
    }

    /**
     * Playback has closed, or opened another recording to edit: the home recording is let go of, and closed if playback
     * was showing someone else's when it happened.
     */
    public static void forget(@Nullable RecordingFile closedByPlayback) {
        RecordingFile held = homeFile;
        homeFile = null;
        homePath = null;
        homeId = null;
        shownId = null;
        switching = false;
        preloaded = null;
        if (held != null && held != closedByPlayback) {
            try {
                held.close();
            } catch (IOException e) {
                Host.LOGGER.warn("Dailies: the scene's own recording could not be closed after showing another point of view", e);
            }
        }
    }

    /**
     * Where a switch lands in the recording just opened: the tick whose game time, by its own time index, is the one
     * the home recording was at; the tick worked out from the runs when the index cannot say
     */
    public static int landing(RecordingFile opened, int fallback) {
        if (pendingGame == Long.MIN_VALUE) {
            return pendingTick >= 0 ? pendingTick : fallback;
        }
        try {
            int exact = opened.tickAtGame(pendingGame);
            return exact >= 0 ? exact : fallback;
        } catch (IOException e) {
            return fallback;
        } finally {
            pendingGame = Long.MIN_VALUE;
        }
    }

    // --- Questions ---

    /** Whether someone else's recording is being shown in place of the scene's own */
    public static boolean away() {
        return homeId != null && shownId != null && !homeId.equals(shownId);
    }

    public static boolean switching() {
        return switching;
    }

    public static @Nullable String homeId() {
        return homeId;
    }

    public static @Nullable String shownId() {
        return shownId;
    }

    /** A moment of the shown recording as the scene's own time, which every keyframe is in */
    public static double sceneTime(double shownTime) {
        if (!away()) {
            return shownTime;
        }
        double time = shownRuns.map(shownTime, homeRuns);
        if (time >= 0) {
            lastSceneTime = time;
        }
        return lastSceneTime;
    }

    /** Where the recording is, in the scene's ticks: what the timeline shows */
    public static int position() {
        return away() ? (int) Math.floor(sceneTime(Playback.position())) : Playback.position();
    }

    /** Where the recording is going, or is, in the scene's ticks */
    public static int shown() {
        return away() ? (int) Math.floor(sceneTime(Playback.shown())) : Playback.shown();
    }

    /** The length of the scene's own recording */
    public static int total() {
        return away() ? homeTotal : Playback.total();
    }

    /**
     * The stretches of the scene's ticks that a linked recording holds, each {first, last}; empty until its runs have
     * been read, which is started here and done away from the client thread
     */
    public static List<int[]> coverage(String recordingId) {
        if (recordingId.equals(homeId)) {
            return homeTotal > 0 ? List.<int[]>of(new int[]{0, homeTotal}) : List.of();
        }
        Alignment runs = runs(recordingId);
        return runs == null ? List.of() : homeRuns.shared(runs);
    }

    // --- Switching ---

    /**
     * Once a frame from the editor: makes the switch the scene asks for at this moment, prepares the next one, and goes
     * home when the recording has been stopped on someone else's footage.
     *
     * @param playing whether the recording is playing (switches are made only then)
     */
    static void frame(Scene scene, double sceneTick, boolean playing) {
        if (homeId == null || switching || !Playback.loaded() || Playback.jumping()) {
            return;
        }
        if (!playing) {
            if (away()) {
                goHome((int) Math.floor(sceneTick), false);
            }
            return;
        }
        String wanted = Evaluator.pointOfView(scene, sceneTick, homeId);
        String target = wanted == null ? homeId : wanted;
        if (!target.equals(shownId)) {
            switchTo(scene, target, (int) Math.floor(sceneTick), true);
        } else {
            preload(scene, (int) Math.floor(sceneTick));
        }
    }

    /** Back to the scene's own recording at this tick of it. Used by jumps made by hand while someone else's is shown */
    public static void goHome(int homeTick, boolean playOn) {
        if (!away() || switching || homePath == null) {
            return;
        }
        pendingGame = Long.MIN_VALUE;
        pendingTick = Math.max(0, Math.min(homeTotal, homeTick));
        switching = true;
        Playback.switchTo(homePath, pendingTick, playOn);
    }

    private static void switchTo(Scene scene, String target, int homeTick, boolean playOn) {
        if (target.equals(homeId)) {
            goHome(homeTick, playOn);
            return;
        }
        Path file = fileOf(target);
        Alignment runs = runs(target);
        if (file == null || runs == null) {
            complain(target, "is not in the recordings folder, or is still being looked for");
            return;
        }
        int there = homeRuns.map(homeTick, runs);
        if (there < 0) {
            // That player was not recording at this moment: the scene stays on what it shows
            complain(target, "does not hold tick " + homeTick);
            return;
        }
        pendingGame = homeRuns.gameAt(homeTick);
        pendingTick = there;
        switching = true;
        Playback.switchTo(file, there, playOn);
        LinkedRecording linked = scene.linked(target);
        Host.LOGGER.info("Dailies: point of view switched to {} at tick {} (tick {} there)", linked == null ? target : linked.label(), homeTick, there);
    }

    /** Reads ahead: the next recording a switch will show, and the part of it the switch lands in */
    private static void preload(Scene scene, int sceneTick) {
        List<Evaluator.Switch> coming = Evaluator.pointOfViewSwitches(scene, sceneTick + 1, sceneTick + PRELOAD_TICKS, homeId);
        if (coming.isEmpty()) {
            return;
        }
        Evaluator.Switch next = coming.get(0);
        String target = next.recordingId() == null ? homeId : next.recordingId();
        if (target == null || target.equals(preloaded) || target.equals(shownId)) {
            return;
        }
        preloaded = target;
        Path known = target.equals(homeId) ? homePath : null;
        Alignment home = homeRuns;
        int at = next.tick();
        READER.execute(() -> {
            try {
                Path file = known != null ? known : findFile(target);
                if (file == null) {
                    return;
                }
                Alignment runs = target.equals(homeId) ? home : runsFor(target, file);
                int there = runs == home ? at : home.map(at, runs);
                if (there < 0) {
                    return;
                }
                // Reading the segment brings it into the system's file cache, so opening it at the cut is quick
                try (RecordingFile recording = RecordingFile.open(file); InputStream segment = recording.openSegment(recording.segmentAt(there))) {
                    segment.transferTo(java.io.OutputStream.nullOutputStream());
                }
            } catch (Exception e) {
                Host.LOGGER.debug("Dailies: the next point of view could not be read ahead", e);
            }
        });
    }

    /** Starts reading every linked recording's runs, so that the lanes and the first switch need not wait */
    public static void prepare(Scene scene) {
        for (LinkedRecording linked : scene.linked()) {
            runs(linked.recordingId());
        }
    }

    private static @Nullable Alignment runs(String recordingId) {
        Alignment known = RUNS.get(recordingId);
        if (known == null && ASKED.add(recordingId)) {
            READER.execute(() -> {
                Path file = findFile(recordingId);
                // One that is not there is looked for again when a recording is next opened
                if (file != null) {
                    runsFor(recordingId, file);
                }
            });
        }
        return known;
    }

    /** The file of a recording, once it has been found away from the client thread; finding it is started here */
    private static @Nullable Path fileOf(String recordingId) {
        Path known = FILES.get(recordingId);
        if (known == null) {
            runs(recordingId);
        }
        return known;
    }

    private static @Nullable Path findFile(String recordingId) {
        try {
            Path file = Recordings.findById(recordingId);
            if (file != null) {
                FILES.put(recordingId, file);
            }
            return file;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Alignment runsFor(String recordingId, Path file) {
        Links.Link link = Links.describe(file);
        Alignment runs = link == null ? Alignment.of(List.of()) : link.alignment();
        RUNS.put(recordingId, runs);
        return runs;
    }

    private static Alignment runsOf(RecordingFile file) {
        try {
            return Alignment.of(file.segments(), file.segmentTimes());
        } catch (IOException e) {
            return Alignment.of(List.of());
        }
    }

    private static void complain(String recordingId, String why) {
        if (COMPLAINED.add(recordingId + why)) {
            Host.LOGGER.warn("Dailies: the point of view {} {}, so the scene goes on showing what it shows", recordingId, why);
        }
    }

}
