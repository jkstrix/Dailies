package comedymonkey.dailies.smartcamera;

import java.util.UUID;

/**
 * Everything one Smart Camera keyframe says: who to film and how. The keyframe type of the editor holds one of these;
 * the solvers read it every step. Values outside the ranges in the comments are clamped when a keyframe is made or
 * loaded. Free of Minecraft types, so the solvers can be tested on their own.
 */
public class SmartCameraSettings {

    /** The entity to film (a player, or any entity by its UUID) */
    public UUID target;
    public SmartCameraMode mode = SmartCameraMode.CHASE;

    // Chase (the director uses these as the base of its chase shots)
    /** Blocks behind the subject, 1 to 64 */
    public float distance = 6.0f;
    /** Blocks above the aim point, -32 to 64. Also used by orbit */
    public float height = 2.0f;
    /** Degrees around the subject relative to its heading: 0 behind, 90 to its right, 180 in front */
    public float sideAngle = 0.0f;
    /** Seconds the camera takes to catch up, 0 to 5 */
    public float positionSmoothing = 0.35f;
    public SmartCameraHeading headingSource = SmartCameraHeading.MOVEMENT;
    /** Degrees of turn the chase camera ignores, so it does not swing at every wobble, 0 to 90 */
    public float headingDeadBand = 15.0f;
    /** How much wider and higher the chase camera goes in flight and at speed, 0 (not at all) to 2 */
    public float flightWiden = 0.6f;

    // Tripod
    /** Where a fixed tripod's eye is */
    public double positionX;
    public double positionY;
    public double positionZ;
    /** Whether the tripod picks its own place (and moves, by a cut, when the subject leaves its view) */
    public boolean autoPlace = true;
    /** Seconds the camera takes to turn after the subject, 0 to 5 */
    public float aimSmoothing = 0.25f;
    /** How far the subject may wander from its place in the frame before a tripod turns, a share of half the frame, 0 to 0.8 */
    public float deadZone = 0.12f;

    // Orbit
    /** Horizontal distance, 1 to 128 */
    public float radius = 8.0f;
    /** The sign is the direction, -360 to 360 */
    public float degreesPerSecond = 20.0f;
    /** The camera's yaw at the keyframe's tick */
    public float startAngle = 0.0f;

    // Director
    /** 0.5 to 60; never under 1.5 in use */
    public float minShotSeconds = 3.0f;
    /** At least minShotSeconds, up to 120 */
    public float maxShotSeconds = 10.0f;
    public boolean allowChase = true;
    public boolean allowTripod = true;
    public boolean allowOrbit = true;
    public boolean allowOverShoulder = true;
    public boolean allowSideTracking = true;
    /** A wide shot whenever the subject comes into a new kind of place */
    public boolean establishingShots = true;
    public int seed = 0;

    // Framing, in every mode
    /** Where the subject sits in the frame, -1 (left) to 1 (right) */
    public float screenX = 0.0f;
    /** -1 (bottom) to 1 (top) */
    public float screenY = 0.0f;
    /** 0 to 1 */
    public float leadRoom = 0.25f;
    public boolean autoZoom = false;
    /** Fraction of the frame's height the subject fills with auto zoom, 0.05 to 1 */
    public float subjectScreenHeight = 0.3f;
    public float minFov = 30.0f;
    public float maxFov = 90.0f;
    /** The FOV while auto zoom is off; 0 leaves the FOV alone */
    public float fixedFov = 0.0f;
    public boolean frameVehicle = true;
    public boolean avoidObstructions = true;
    /** Seconds to blend from the previous camera when this keyframe takes effect; 0 is a cut */
    public float transitionSeconds = 0.5f;

    public SmartCameraSettings() {
    }

    public SmartCameraSettings(UUID target) {
        this.target = target;
    }

    public Vec position() {
        return new Vec(this.positionX, this.positionY, this.positionZ);
    }

    /** A copy to alter, as the director does for each of its shots */
    public SmartCameraSettings copy() {
        SmartCameraSettings copy = new SmartCameraSettings(this.target);
        copy.mode = this.mode;
        copy.distance = this.distance;
        copy.height = this.height;
        copy.sideAngle = this.sideAngle;
        copy.positionSmoothing = this.positionSmoothing;
        copy.headingSource = this.headingSource;
        copy.headingDeadBand = this.headingDeadBand;
        copy.flightWiden = this.flightWiden;
        copy.positionX = this.positionX;
        copy.positionY = this.positionY;
        copy.positionZ = this.positionZ;
        copy.autoPlace = this.autoPlace;
        copy.aimSmoothing = this.aimSmoothing;
        copy.deadZone = this.deadZone;
        copy.radius = this.radius;
        copy.degreesPerSecond = this.degreesPerSecond;
        copy.startAngle = this.startAngle;
        copy.minShotSeconds = this.minShotSeconds;
        copy.maxShotSeconds = this.maxShotSeconds;
        copy.allowChase = this.allowChase;
        copy.allowTripod = this.allowTripod;
        copy.allowOrbit = this.allowOrbit;
        copy.allowOverShoulder = this.allowOverShoulder;
        copy.allowSideTracking = this.allowSideTracking;
        copy.establishingShots = this.establishingShots;
        copy.seed = this.seed;
        copy.screenX = this.screenX;
        copy.screenY = this.screenY;
        copy.leadRoom = this.leadRoom;
        copy.autoZoom = this.autoZoom;
        copy.subjectScreenHeight = this.subjectScreenHeight;
        copy.minFov = this.minFov;
        copy.maxFov = this.maxFov;
        copy.fixedFov = this.fixedFov;
        copy.frameVehicle = this.frameVehicle;
        copy.avoidObstructions = this.avoidObstructions;
        copy.transitionSeconds = this.transitionSeconds;
        return copy;
    }

}
