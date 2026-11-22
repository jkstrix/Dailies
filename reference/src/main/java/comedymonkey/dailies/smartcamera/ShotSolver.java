package comedymonkey.dailies.smartcamera;

/**
 * One way of filming: chase, orbit, tripod, or the director that cuts between them. A solver is started once (it
 * places the camera with no smoothing, as after a cut) and then stepped once per tick of the recording. Everything it
 * does follows from what it is given, so the same recording and settings always give the same camera.
 */
public interface ShotSolver {

    /**
     * What a solver is given each step.
     *
     * @param settings the settings to film by (the director hands its shots altered copies)
     * @param dt       seconds since the last step, 0 for the first
     * @param fov      the vertical field of view the frame is drawn with, degrees
     * @param aspect   the frame's width over its height
     * @param tick     the recording's tick
     * @param elapsed  seconds since the keyframe (or the director's shot) began
     * @param rider    the person filmed, when what is framed is more than them (a craft they ride): the camera must
     *                 see them, not just the craft
     * @param seeing   the world as it hides the rider: with the framed craft's own blocks (envelope, frame) in it,
     *                 which {@code world} leaves out so that the camera may stand close
     */
    record Input(SubjectSample subject, Motion motion, CameraWorld world, SmartCameraSettings settings, double dt, double fov, double aspect, double tick,
                 double elapsed, Vec rider, CameraWorld seeing) {

        public Input with(SmartCameraSettings other, double otherElapsed) {
            return new Input(this.subject, this.motion, this.world, other, this.dt, this.fov, this.aspect, this.tick, otherElapsed, this.rider, this.seeing);
        }

        /** How much of the rider a camera there sees, with the craft in the way */
        public double riderSeen(Vec eye) {
            return CameraProbe.boxSeen(this.seeing, eye, this.rider.add(0.0, -1.35, 0.0), 1.8, 0.6);
        }
    }

    /**
     * Where the camera is after a step.
     *
     * @param eye           the camera's eye
     * @param obstructed    whether blocks cut the line of sight short this step
     * @param targetScreenX where the solver meant the subject to be on screen, lead room included
     * @param cut           whether this step jumped to a new place (a tripod moving, a director's cut): frames must not
     *                      be blended across it
     */
    record Pose(Vec eye, double yaw, double pitch, boolean obstructed, double targetScreenX, double targetScreenY, boolean cut) {
    }

    Pose start(Input input);

    Pose step(Input input);

    /** For the director: whether this shot has stopped working (its subject has left it for good) */
    default boolean wantsCut() {
        return false;
    }

}
