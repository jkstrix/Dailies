package comedymonkey.dailies.smartcamera;

import java.util.UUID;

/**
 * Everything one Smart Camera keyframe says: who to film and how. The keyframe type of the editor holds one of these;
 * {@link SmartCameraSolver} reads it every frame. Values outside the ranges in the comments are clamped when a
 * keyframe is made or loaded.
 */
public class SmartCameraSettings {

    /** The entity to film (a player) */
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

    // Tripod
    public final org.joml.Vector3d position = new org.joml.Vector3d();
    /** Seconds the camera takes to turn after the subject, 0 to 5 */
    public float aimSmoothing = 0.25f;

    // Orbit
    /** Horizontal distance, 1 to 128 */
    public float radius = 8.0f;
    /** The sign is the direction, -360 to 360 */
    public float degreesPerSecond = 20.0f;
    /** The camera's yaw at the keyframe's tick */
    public float startAngle = 0.0f;

    // Director
    /** 0.5 to 60 */
    public float minShotSeconds = 3.0f;
    /** At least minShotSeconds, up to 120 */
    public float maxShotSeconds = 8.0f;
    public boolean allowChase = true;
    public boolean allowTripod = true;
    public boolean allowOrbit = true;
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

}
