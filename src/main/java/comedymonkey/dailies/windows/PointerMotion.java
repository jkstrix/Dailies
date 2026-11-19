package comedymonkey.dailies.windows;

import comedymonkey.dailies.playback.Playback;

/**
 * How a recorded pointer moves in playback (specification PLAY-14, PLAY-20): written only a few times a second, it is
 * shown gliding from where it was shown to where it was written next, over the time between the two, in recording time,
 * so it moves smoothly at any playback rate and export frame rate. For the recorder's windows and a compat's screens.
 */
final class PointerMotion {

    private float fromX;
    private float fromY;
    private float toX;
    private float toY;
    private double fromTick;
    private double span = 1.0;
    private boolean known = false;
    private double lastSample = 0.0;

    /**
     * A new place for the pointer has been reached in the recording.
     *
     * @param jump whether to put it there at once: a jump, or a window that has just opened
     */
    void moved(float x, float y, boolean jump) {
        double now = Playback.position();
        if (jump || !this.known) {
            this.fromX = x;
            this.fromY = y;
            this.span = 1.0;
        } else {
            // From where it is shown now, on its way to the place written before
            float[] shown = this.at(now);
            this.fromX = shown[0];
            this.fromY = shown[1];
            this.span = Math.max(1.0, Math.min(WindowCapture.POINTER_TICKS, now - this.lastSample));
        }
        this.toX = x;
        this.toY = y;
        this.fromTick = now - 1.0;
        this.lastSample = now;
        this.known = true;
    }

    /** Where the pointer is shown at a moment of the recording, in the units it was written in */
    float[] at(double time) {
        if (!this.known || Playback.paused()) {
            return new float[]{this.toX, this.toY};
        }
        double t = Math.max(0.0, Math.min(1.0, (time - this.fromTick) / this.span));
        return new float[]{(float) (this.fromX + (this.toX - this.fromX) * t), (float) (this.fromY + (this.toY - this.fromY) * t)};
    }

    /** Forgets where it was, so the next place is taken at once */
    void forget() {
        this.known = false;
    }

    boolean known() {
        return this.known;
    }

}
