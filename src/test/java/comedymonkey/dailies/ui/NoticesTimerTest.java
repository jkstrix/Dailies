package comedymonkey.dailies.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/** Where the recording timer's plate goes for each choice of the recordingTimer setting (REC-4) */
class NoticesTimerTest {

    private static final int SCREEN_WIDTH = 480;
    private static final int SCREEN_HEIGHT = 270;
    private static final int WIDTH = 50;
    private static final int HEIGHT = 14;

    @Test
    void eachCornerKeepsClearOfTheEdges() {
        assertArrayEquals(new int[]{4, 4}, Notices.place("TOP_LEFT", SCREEN_WIDTH, SCREEN_HEIGHT, WIDTH, HEIGHT));
        assertArrayEquals(new int[]{480 - 4 - 50, 4}, Notices.place("TOP_RIGHT", SCREEN_WIDTH, SCREEN_HEIGHT, WIDTH, HEIGHT));
        assertArrayEquals(new int[]{4, 270 - 4 - 14}, Notices.place("BOTTOM_LEFT", SCREEN_WIDTH, SCREEN_HEIGHT, WIDTH, HEIGHT));
        assertArrayEquals(new int[]{480 - 4 - 50, 270 - 4 - 14}, Notices.place("BOTTOM_RIGHT", SCREEN_WIDTH, SCREEN_HEIGHT, WIDTH, HEIGHT));
    }

    @Test
    void theLampAloneSitsTopLeft() {
        assertArrayEquals(new int[]{4, 4}, Notices.place("LAMP_ONLY", SCREEN_WIDTH, SCREEN_HEIGHT, 14, HEIGHT));
    }
}
