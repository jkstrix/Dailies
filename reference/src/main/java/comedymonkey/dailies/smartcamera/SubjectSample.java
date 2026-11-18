package comedymonkey.dailies.smartcamera;

/**
 * What the camera solvers know of the subject at one step, in world coordinates. The game makes one from the entity
 * (see {@link SmartCameraSubject#sample}); the tests make them up.
 *
 * @param feet       the entity's feet
 * @param aim        the point aimed at: the chest of someone on their own, the middle of what is framed otherwise
 * @param height     the height of what is framed
 * @param size       its largest extent, which auto zoom fits into the frame
 * @param extra      how far the edge of what is framed is from the aim point (0 for someone on their own): distances are
 *                   measured from there, and blocks closer than that are the subject's own
 * @param bodyYaw    the way the body (or the craft's nose) faces
 * @param riding     a passenger, or aboard a craft
 * @param gliding    flying with an elytra
 * @param flying     flying the way creative players fly
 * @param airborne   off the ground without riding anything
 * @param swimming   in water
 * @param framed     what else is framed: none, vehicle, contraption or physics_object
 */
public record SubjectSample(Vec feet, Vec aim, double height, double size, double extra, double bodyYaw, boolean riding, boolean gliding, boolean flying,
                            boolean airborne, boolean swimming, String framed) {

    public static final String FRAMED_NONE = "none";

    /** Someone on foot on the ground, for tests */
    public static SubjectSample walking(Vec feet, double bodyYaw) {
        return new SubjectSample(feet, feet.add(0.0, 1.35, 0.0), 1.8, 1.8, 0.0, bodyYaw, false, false, false, false, false, FRAMED_NONE);
    }

    public SubjectSample at(Vec newFeet) {
        return new SubjectSample(newFeet, newFeet.add(this.aim.sub(this.feet)), this.height, this.size, this.extra, this.bodyYaw, this.riding, this.gliding,
            this.flying, this.airborne, this.swimming, this.framed);
    }

    public SubjectSample facing(double yaw) {
        return new SubjectSample(this.feet, this.aim, this.height, this.size, this.extra, yaw, this.riding, this.gliding, this.flying, this.airborne,
            this.swimming, this.framed);
    }

    public SubjectSample withFlight(boolean newGliding, boolean newFlying, boolean newAirborne) {
        return new SubjectSample(this.feet, this.aim, this.height, this.size, this.extra, this.bodyYaw, this.riding, newGliding, newFlying, newAirborne,
            this.swimming, this.framed);
    }

    public SubjectSample riding(double newSize, String newFramed) {
        return new SubjectSample(this.feet, this.feet.add(0.0, newSize * 0.5, 0.0), newSize, newSize, newSize * 0.5, this.bodyYaw, true, false, false, false,
            false, newFramed);
    }

    public boolean framesMore() {
        return !FRAMED_NONE.equals(this.framed);
    }

}
