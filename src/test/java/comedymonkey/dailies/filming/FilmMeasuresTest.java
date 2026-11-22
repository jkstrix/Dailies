package comedymonkey.dailies.filming;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The measures of the camera test films (specification FILM-5 to FILM-8) */
class FilmMeasuresTest {

    private static final double FPS = 60.0;

    /** A player walking along +z at four blocks a second, and a camera six blocks behind and two up, looking at them */
    private static List<FilmFrame> chase(int count) {
        List<FilmFrame> frames = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            double z = i / FPS * 4.0;
            FilmFrame frame = subjectAt(i, 0.0, 64.0, z);
            frame.camX = 0.0;
            frame.camY = 64.0 + 1.62 + 2.0;
            frame.camZ = z - 6.0;
            lookAt(frame, frame.aimX, frame.aimY, frame.aimZ);
            frames.add(frame);
        }
        return frames;
    }

    private static FilmFrame subjectAt(int i, double x, double y, double z) {
        FilmFrame frame = new FilmFrame();
        frame.frame = i;
        frame.seconds = i / FPS;
        frame.tick = i / 3.0;
        frame.subjectPresent = true;
        frame.eyeX = x;
        frame.eyeY = y + 1.62;
        frame.eyeZ = z;
        frame.minX = x - 0.3;
        frame.maxX = x + 0.3;
        frame.minY = y;
        frame.maxY = y + 1.8;
        frame.minZ = z - 0.3;
        frame.maxZ = z + 0.3;
        frame.aimX = x;
        frame.aimY = y + 0.9;
        frame.aimZ = z;
        return frame;
    }

    private static void lookAt(FilmFrame frame, double x, double y, double z) {
        double dx = x - frame.camX;
        double dy = y - frame.camY;
        double dz = z - frame.camZ;
        frame.yaw = Math.toDegrees(Math.atan2(-dx, dz));
        frame.pitch = Math.toDegrees(-Math.atan2(dy, Math.hypot(dx, dz)));
    }

    @Test
    void screenPositionsFollowTheGamesDirections() {
        FilmFrame frame = new FilmFrame();
        frame.fov = 90.0;
        frame.aspect = 1.0;
        double[] ahead = FilmMeasures.screen(frame, 0, 0, 10);
        assertEquals(0.0, ahead[0], 1e-9);
        assertEquals(0.0, ahead[1], 1e-9);
        assertEquals(10.0, ahead[2], 1e-9);
        // Facing +z, -x is to the right; at 90 degrees a point as far aside as ahead is at the edge
        assertEquals(1.0, FilmMeasures.screen(frame, -10, 0, 10)[0], 1e-9);
        assertEquals(1.0, FilmMeasures.screen(frame, 0, 10, 10)[1], 1e-9);
        assertTrue(FilmMeasures.screen(frame, 0, 0, -5)[2] <= 0.0, "a point behind the camera has no depth");
        frame.yaw = 90.0;
        assertEquals(0.0, FilmMeasures.screen(frame, -10, 0, 0)[0], 1e-9);
        frame.pitch = 90.0;
        assertTrue(FilmMeasures.screen(frame, 0, -10, 0)[2] > 9.99, "pitch 90 looks straight down");
    }

    @Test
    void aSteadyChaseHasNoFaults() {
        FilmMeasures.Analysis analysis = FilmMeasures.analyse(chase(600), FPS, false);
        assertTrue(analysis.faults.isEmpty(), analysis.faults.toString());
        assertEquals(1.0, analysis.inFrameShare, 1e-9);
        assertEquals(0.0, analysis.halfHiddenShare, 1e-9);
        assertEquals(0.0, analysis.longestLostSeconds, 1e-9);
        assertTrue(analysis.meanScreenError < 1e-6, "the aim point is at the centre: " + analysis.meanScreenError);
        assertTrue(analysis.angularVelocity[0] < 1e-3, "a camera moving with its player does not turn");
        assertEquals(4.0, analysis.linearVelocity[0], 1e-6);
        assertTrue(analysis.linearAcceleration[1] < 1e-6);
        assertEquals(0, analysis.pops);
    }

    @Test
    void aSnapIsAPop() {
        List<FilmFrame> frames = chase(300);
        frames.get(150).yaw += 20.0;
        FilmMeasures.Analysis analysis = FilmMeasures.analyse(frames, FPS, false);
        // Snapping there and back again: two steps
        assertEquals(2, analysis.pops);
        assertTrue(analysis.faults.stream().anyMatch(fault -> fault.contains("pops")), analysis.faults.toString());

        List<FilmFrame> moved = chase(300);
        for (int i = 200; i < moved.size(); i++) {
            moved.get(i).camX += 2.0;
        }
        assertEquals(1, FilmMeasures.analyse(moved, FPS, false).pops, "the camera jumping two blocks sideways is a pop");
    }

    @Test
    void aFastSmoothTurnIsNotAPop() {
        List<FilmFrame> frames = chase(300);
        for (int i = 0; i < frames.size(); i++) {
            // Swinging round at up to 180 degrees a second, smoothly
            frames.get(i).yaw += 30.0 * Math.sin(i / FPS * 6.0);
        }
        assertEquals(0, FilmMeasures.analyse(frames, FPS, false).pops);
    }

    @Test
    void losingThePlayerForThreeSecondsIsAFault() {
        List<FilmFrame> frames = chase(600);
        for (int i = 120; i < 300; i++) {
            frames.get(i).yaw += 180.0;
        }
        FilmMeasures.Analysis analysis = FilmMeasures.analyse(frames, FPS, false);
        assertEquals(3.0, analysis.longestLostSeconds, 1e-9);
        assertEquals(2.0, analysis.longestLostFrom, 1e-9);
        assertEquals(0.7, analysis.inFrameShare, 1e-9);
        assertTrue(analysis.faults.stream().anyMatch(fault -> fault.contains("out of frame")), analysis.faults.toString());

        List<FilmFrame> hidden = chase(600);
        for (int i = 0; i < 100; i++) {
            hidden.get(i).occludedMask = 0b111111;
        }
        FilmMeasures.Analysis briefly = FilmMeasures.analyse(hidden, FPS, false);
        assertEquals(100 / FPS, briefly.longestLostSeconds, 1e-9);
        assertTrue(briefly.faults.stream().noneMatch(fault -> fault.contains("out of frame")), "under two seconds hidden is let off");
    }

    @Test
    void aCameraInsideABlockIsAFault() {
        List<FilmFrame> frames = chase(120);
        frames.get(30).cameraInBlock = true;
        FilmMeasures.Analysis analysis = FilmMeasures.analyse(frames, FPS, false);
        assertEquals(1, analysis.insideBlock);
        assertEquals(0.5, analysis.firstInsideBlock, 1e-9);
        assertEquals(1, analysis.faults.size());
    }

    @Test
    void aTeleportBreaksTheFilmAndIsNoPop() {
        List<FilmFrame> frames = chase(600);
        for (int i = 300; i < frames.size(); i++) {
            FilmFrame frame = frames.get(i);
            frame.eyeX += 500;
            frame.minX += 500;
            frame.maxX += 500;
            frame.aimX += 500;
            frame.camX += 500;
        }
        FilmMeasures.Analysis analysis = FilmMeasures.analyse(frames, FPS, false);
        assertEquals(1, analysis.subjectJumps);
        assertEquals(0, analysis.pops);
        assertTrue(analysis.linearVelocity[1] < 4.01, "the jump is not counted as speed: " + analysis.linearVelocity[1]);
    }

    @Test
    void theDirectorsCutsAreJudged() {
        // Three shots: from behind, then from the right side (a cut of 90 degrees), then a little round from there (a jump cut) after one second
        List<FilmFrame> frames = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            double z = i / FPS * 4.0;
            FilmFrame frame = subjectAt(i, 0.0, 64.0, z);
            if (i < 240) {
                frame.shot = 0;
                frame.shotKind = "chase";
                frame.camX = 0;
                frame.camZ = z - 6;
            } else if (i < 300) {
                frame.shot = 1;
                frame.shotKind = "tripod";
                frame.camX = -6;
                frame.camZ = z;
            } else {
                frame.shot = 2;
                frame.shotKind = "orbit";
                frame.camX = -6;
                frame.camZ = z - 1.5;
            }
            frame.camY = 66;
            lookAt(frame, frame.aimX, frame.aimY, frame.aimZ);
            frames.add(frame);
        }
        FilmMeasures.Analysis analysis = FilmMeasures.analyse(frames, FPS, true);
        assertEquals(2, analysis.cuts);
        assertEquals(12.0, analysis.cutsPerMinute, 1e-9);
        assertEquals(1.0, analysis.shortestShot, 1e-9);
        assertEquals(5.0, analysis.longestShot, 1e-9);
        assertEquals(List.of("chase", "tripod", "orbit"), new ArrayList<>(analysis.shotKinds));
        assertEquals(1, analysis.jumpCuts);
        assertEquals(0, analysis.lineCrossings);
        assertEquals(0, analysis.pops, "cuts are not pops");
        assertTrue(analysis.faults.stream().anyMatch(fault -> fault.contains("only 1.00 s")), analysis.faults.toString());
        assertTrue(analysis.faults.stream().anyMatch(fault -> fault.contains("jump cut")), analysis.faults.toString());
    }

    @Test
    void crossingTheLineIsCounted() {
        List<FilmFrame> frames = new ArrayList<>();
        for (int i = 0; i < 240; i++) {
            double z = i / FPS * 4.0;
            FilmFrame frame = subjectAt(i, 0.0, 64.0, z);
            frame.shot = i < 120 ? 0 : 1;
            frame.camX = i < 120 ? -6 : 6;
            frame.camY = 66;
            frame.camZ = z;
            lookAt(frame, frame.aimX, frame.aimY, frame.aimZ);
            frames.add(frame);
        }
        FilmMeasures.Analysis analysis = FilmMeasures.analyse(frames, FPS, true);
        assertEquals(1, analysis.lineCrossings);
        assertEquals(0, analysis.jumpCuts);
    }

    @Test
    void theCsvHasALineAFrame() {
        String csv = FilmMeasures.csv(chase(10));
        String[] lines = csv.split("\n");
        assertEquals(11, lines.length);
        assertEquals(FilmMeasures.CSV_HEADER, lines[0]);
        assertEquals(FilmMeasures.CSV_HEADER.split(",").length, lines[5].split(",", -1).length);
    }

    private static void shiftSubject(FilmFrame frame, double dx) {
        frame.eyeX += dx;
        frame.minX += dx;
        frame.maxX += dx;
        frame.aimX += dx;
    }

    @Test
    void gettingOnAVehicleBreaksTheFilmAndALateAnswerIsNoPop() {
        List<FilmFrame> frames = chase(300);
        for (int i = 150; i < frames.size(); i++) {
            frames.get(i).vehicle = 7;
        }
        // The camera answers two frames later by stepping two blocks aside
        for (int i = 152; i < frames.size(); i++) {
            frames.get(i).camX += 2.0;
        }
        FilmMeasures.Analysis analysis = FilmMeasures.analyse(frames, FPS, false);
        assertEquals(1, analysis.subjectJumps);
        assertEquals(0, analysis.pops);

        List<FilmFrame> late = chase(300);
        for (int i = 150; i < late.size(); i++) {
            late.get(i).vehicle = 7;
        }
        for (int i = 180; i < late.size(); i++) {
            late.get(i).camX += 2.0;
        }
        assertEquals(1, FilmMeasures.analyse(late, FPS, false).pops, "a snap half a second after the jump is the camera's own");
    }

    @Test
    void aSuddenStepOrAnotherWorldIsASubjectJump() {
        List<FilmFrame> frames = chase(300);
        for (int i = 200; i < frames.size(); i++) {
            shiftSubject(frames.get(i), 1.5);
        }
        assertEquals(1, FilmMeasures.analyse(frames, FPS, false).subjectJumps, "a block and a half in one frame from a walk");

        List<FilmFrame> portal = chase(300);
        for (int i = 0; i < portal.size(); i++) {
            portal.get(i).dimension = i < 100 ? "minecraft:overworld" : "minecraft:the_nether";
        }
        assertEquals(1, FilmMeasures.analyse(portal, FPS, false).subjectJumps);
    }

    @Test
    void fastSteadyFlightIsNoJump() {
        List<FilmFrame> frames = new ArrayList<>();
        double z = 0.0;
        for (int i = 0; i < 600; i++) {
            // Speeding up from 6 to 42 blocks a second over the ten seconds, as rockets do
            z += (0.1 + 0.6 * i / 600.0);
            FilmFrame frame = subjectAt(i, 0.0, 120.0, z);
            frame.camY = 123.0;
            frame.camZ = z - 8.0;
            lookAt(frame, frame.aimX, frame.aimY, frame.aimZ);
            frames.add(frame);
        }
        FilmMeasures.Analysis analysis = FilmMeasures.analyse(frames, FPS, false);
        assertEquals(0, analysis.subjectJumps);
        assertTrue(analysis.faults.isEmpty(), analysis.faults.toString());
    }

    @Test
    void aCutAnsweringAPortalIsNotJudgedAsTheDirectors() {
        List<FilmFrame> frames = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            double z = i / FPS * 4.0;
            FilmFrame frame = subjectAt(i, 0.0, 64.0, z);
            frame.dimension = i < 200 ? "minecraft:overworld" : "minecraft:the_nether";
            // The new shot three frames after arriving, from nearly the same angle, after a shot of only a second
            frame.shot = i < 140 ? 0 : i < 203 ? 1 : 2;
            frame.camX = i < 140 ? 0 : i < 203 ? -6 : -6;
            frame.camY = 66;
            frame.camZ = i < 140 ? z - 6 : i < 203 ? z : z - 1;
            lookAt(frame, frame.aimX, frame.aimY, frame.aimZ);
            frames.add(frame);
        }
        FilmMeasures.Analysis analysis = FilmMeasures.analyse(frames, FPS, true);
        assertEquals(2, analysis.cuts);
        assertEquals(0, analysis.jumpCuts);
        assertTrue(analysis.faults.isEmpty(), analysis.faults.toString());
    }

}
