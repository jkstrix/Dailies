package comedymonkey.dailies.selftest;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.file.RecordingFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * A staged step's recording kept as a fixture and played back on later runs, as the bestiary and the tour keep theirs:
 * dailies/fixtures/NAME.dailies with NAME.json.gz, which holds all the live game showed (the staging's items, marks
 * and stills, the live census, the pictures of the live game) and whatever else the step's comparison needs. Recorded
 * again only when it is missing or unreadable, its staging has changed (the step's version), the code that writes
 * recordings has changed (its fingerprint, {@link FixtureRules}), or when asked for with dailies.test.recordTours. Used by
 * the steps that stage in a world of their own and compare playback with the live game: the addon, trains and edges.
 */
final class StagedFixtures {

    /** Stages the step in a live world and records it; false when nothing could be staged, and then nothing is kept */
    interface Staging {
        boolean record() throws Exception;
    }

    /** The step's own part of the notes, written after the shared part */
    interface Extra {
        void write(TourFixtures.Out out) throws Exception;
    }

    /** And read back, in the same order */
    interface ExtraReading {
        void read(TourFixtures.In in) throws Exception;
    }

    private StagedFixtures() {
    }

    /**
     * Leaves the step's recording at {@code recording}, with all the live game showed as if it had just been staged:
     * from the fixture when it can be used, otherwise staged and recorded now and kept as the fixture.
     *
     * @param name    the fixture's file name, and the step's name in the log
     * @param version the version of the step's staging: it goes up whenever the staging changes
     * @param folder  the step's own folder, emptied by the step before this, where the live pictures go
     * @return false when the staging had nothing to record
     */
    static boolean recordOrReuse(SelfTest test, String name, int version, Path folder, Path recording, Staging staging, Extra extra, ExtraReading extraReading) throws Exception {
        Path fixture = TourFixtures.folder().resolve(name + RecordingFile.EXTENSION);
        Path notes = TourFixtures.folder().resolve(name + ".json.gz");
        if (TourFixtures.reuse(test, "the " + name + " recording", fixture, notes, TourFixtures.RECORD_TOURS, version)) {
            try {
                String made = TourFixtures.read(notes, in -> {
                    BestiarySteps.readNotes(in, folder);
                    readLivePictures(in, folder);
                    extraReading.read(in);
                });
                // Playback and the editor may write into what they open: the fixture itself is never opened
                TourFixtures.copy(fixture, recording);
                test.log(name + ": played back from the fixture recorded " + made + ", not staged again");
                return true;
            } catch (Exception e) {
                test.log(name + ": the fixture could not be used (" + e + "); staging and recording it again");
                BestiarySteps.forget();
            }
        }
        if (!staging.record()) {
            return false;
        }
        try {
            TourFixtures.keep(recording, fixture);
            TourFixtures.write(notes, version, out -> {
                BestiarySteps.writeNotes(out, folder);
                writeLivePictures(out, folder);
                extra.write(out);
            });
            test.log(name + ": kept as a fixture at " + fixture + " (" + Files.size(fixture) / 1024 + " KB) with its notes");
        } catch (Throwable t) {
            Host.LOGGER.warn("Dailies self test: the {} recording could not be kept as a fixture", name, t);
            test.log(name + ": could not be kept as a fixture (" + t + "); the next run stages it again");
        }
        return true;
    }

    /** The pictures of the live game taken while staging (NAME-live.png), to set beside playback's */
    private static void writeLivePictures(TourFixtures.Out out, Path folder) throws Exception {
        List<Path> pictures = new ArrayList<>();
        try (Stream<Path> files = Files.list(folder)) {
            files.filter(file -> file.getFileName().toString().endsWith("-live.png")).sorted().forEach(pictures::add);
        }
        out.name("live pictures");
        out.json.beginArray();
        for (Path picture : pictures) {
            out.string(picture.getFileName().toString());
            out.file(picture);
        }
        out.json.endArray();
    }

    private static void readLivePictures(TourFixtures.In in, Path folder) throws Exception {
        in.name("live pictures");
        in.json.beginArray();
        while (in.more()) {
            in.file(folder.resolve(in.string()));
        }
        in.json.endArray();
    }
}
