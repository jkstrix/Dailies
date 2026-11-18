package comedymonkey.dailies.export;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A few frame buffers that go round between the game, which fills them, and the writer, which hands them to the
 * encoder and gives them back. Nothing is allocated per frame: a 4K frame is tens of megabytes, and making a new one
 * sixty times a second kept the garbage collector busy and copied every picture twice. No Minecraft here.
 */
final class FramePool {

    /** One frame's bytes, with the size of the picture they hold */
    static final class Frame {
        final byte[] data;
        int length;
        int width;
        int height;
        long number;

        Frame(int capacity) {
            this.data = new byte[capacity];
        }
    }

    /** Handed to the writer to say there are no more frames */
    static final Frame END = new Frame(0);

    private final BlockingQueue<Frame> free;
    private final BlockingQueue<Frame> full;
    private final int count;
    private final int capacity;
    /** Frames made so far: they are made as they are first needed, so a pool of many costs nothing until it is used */
    private final AtomicInteger made = new AtomicInteger();

    FramePool(int count, int capacity) {
        this.count = count;
        this.capacity = capacity;
        this.free = new ArrayBlockingQueue<>(count);
        // One more place than there are frames, so the end can always be said
        this.full = new ArrayBlockingQueue<>(count + 1);
    }

    /**
     * An empty buffer for the game to fill: a free one, a new one while fewer than the pool's number have been made,
     * or null when none came free in time (the writer, or a sink holding frames, is behind)
     */
    Frame take(long timeoutMillis) throws InterruptedException {
        Frame frame = this.free.poll();
        if (frame != null) {
            return frame;
        }
        for (int now = this.made.get(); now < this.count; now = this.made.get()) {
            if (this.made.compareAndSet(now, now + 1)) {
                return new Frame(this.capacity);
            }
        }
        return this.free.poll(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    /** A filled buffer, for the writer. Never waits: every buffer has a place */
    void send(Frame frame) {
        if (!this.full.offer(frame)) {
            throw new IllegalStateException("More frames were sent than the pool holds");
        }
    }

    /** The next filled buffer, waiting for one; {@link #END} when there are no more */
    Frame next() throws InterruptedException {
        return this.full.take();
    }

    /** A buffer the writer, or a sink it was handed to, has finished with. Any thread */
    void giveBack(Frame frame) {
        if (frame != END) {
            this.free.offer(frame);
        }
    }

    /** No more frames. Never waits */
    void end() {
        this.full.offer(END);
    }

    /** Everything waiting for the writer is thrown away and the end said, after a failure */
    void abandon() {
        Frame waiting;
        while ((waiting = this.full.poll()) != null) {
            this.giveBack(waiting);
        }
        this.end();
    }

    int freeCount() {
        return this.free.size();
    }

}
