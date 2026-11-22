package comedymonkey.dailies.export;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** How the platforms with an encoder are named to someone without one (EXP-8) */
class EncoderPlatformsTest {

    @Test
    void noneIsSaidPlainly() {
        assertEquals("no platform", Encoder.words(List.of()));
    }

    @Test
    void oneIsItself() {
        assertEquals("Windows (64-bit Intel or AMD)", Encoder.words(List.of("Windows (64-bit Intel or AMD)")));
    }

    @Test
    void severalAreListedWithAnAndBeforeTheLast() {
        assertEquals("A and B", Encoder.words(List.of("A", "B")));
        assertEquals("A, B and C", Encoder.words(List.of("A", "B", "C")));
    }
}
