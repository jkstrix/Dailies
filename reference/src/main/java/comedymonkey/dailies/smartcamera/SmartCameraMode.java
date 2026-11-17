package comedymonkey.dailies.smartcamera;

public enum SmartCameraMode {
    /** Follows behind the subject */
    CHASE,
    /** Stands still and turns to keep the subject in frame */
    TRIPOD,
    /** Circles the subject */
    ORBIT,
    /** Cuts between its own chase, tripod and orbit shots */
    DIRECTOR
}
