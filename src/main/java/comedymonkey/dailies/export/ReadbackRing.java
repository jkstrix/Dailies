package comedymonkey.dailies.export;

/**
 * Which of a few read-back buffers on the graphics card each frame goes into, and when it is taken out. A frame's
 * picture is copied into the next buffer as soon as it is drawn, which the card does in its own time, and only taken
 * out {@code lag} frames later, by when the copy is long done: the game never stands waiting for the card, and the
 * card is drawing the next frame while the last one is still on its way. Frames come out in the order they went in,
 * every one of them. No Minecraft here.
 */
final class ReadbackRing {

    private final int slots;
    private final int lag;
    private long issued = 0;
    private long collected = 0;

    /**
     * @param slots how many buffers there are
     * @param lag   how many frames are left in flight after each one is put in; less than {@code slots}
     */
    ReadbackRing(int slots, int lag) {
        if (slots < 1 || lag < 0 || lag >= slots) {
            throw new IllegalArgumentException("A ring of " + slots + " buffers cannot hold " + lag + " frames back");
        }
        this.slots = slots;
        this.lag = lag;
    }

    int slots() {
        return this.slots;
    }

    /** Frames put in and not yet taken out */
    int pending() {
        return (int) (this.issued - this.collected);
    }

    /** Whether the next frame needs the oldest one out first, because every buffer is in use */
    boolean full() {
        return this.pending() >= this.slots;
    }

    /** The buffer for the next frame; only when not {@link #full()} */
    int issue() {
        if (this.full()) {
            throw new IllegalStateException("Every read-back buffer is in use");
        }
        return (int) (this.issued++ % this.slots);
    }

    /** Whether the oldest frame should be taken out now: more than {@code lag} are in flight, or all of them when draining */
    boolean due(boolean draining) {
        return this.pending() > (draining ? 0 : this.lag);
    }

    /** The buffer holding the oldest frame, which is taken out */
    int collect() {
        if (this.pending() == 0) {
            throw new IllegalStateException("No frame is waiting to be read back");
        }
        return (int) (this.collected++ % this.slots);
    }

    /** The number of the next frame {@link #collect()} hands out, counted from the first */
    long nextCollected() {
        return this.collected;
    }

}
