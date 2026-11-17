package comedymonkey.dailies.packs;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which server resource packs are in force at a tick, given what a recording says happened (REC-19, PLAY-17) */
class PackLogTest {

    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    private record Timed(int tick, byte[] record) {
    }

    private static PackLog.Pack pack(UUID id, String content) {
        return new PackLog.Pack(id, "https://example.test/" + content + ".zip", "", false, "", content);
    }

    /** What a jump does: everything recorded up to and including the tick, in order */
    private static List<String> contentsAt(List<Timed> recorded, int tick) {
        PackLog log = new PackLog();
        for (Timed timed : recorded) {
            if (timed.tick <= tick) {
                // Records are read out of the middle of a larger buffer
                byte[] padded = new byte[timed.record.length + 5];
                System.arraycopy(timed.record, 0, padded, 3, timed.record.length);
                log.apply(padded, 3, timed.record.length);
            }
        }
        return log.contents();
    }

    @Test
    void pushesAndPopsGiveThePacksInForceAtEveryTick() {
        List<Timed> recorded = new ArrayList<>();
        recorded.add(new Timed(0, PackLog.state(List.of())));
        recorded.add(new Timed(40, PackLog.pushed(pack(A, "one"), 0)));
        recorded.add(new Timed(60, PackLog.pushed(pack(B, "two"), 1)));
        recorded.add(new Timed(60, PackLog.notApplied(pack(C, ""), "DECLINED")));
        recorded.add(new Timed(100, PackLog.popped(A)));
        recorded.add(new Timed(140, PackLog.pushed(pack(C, "one"), 1)));
        recorded.add(new Timed(180, PackLog.popped(B)));
        recorded.add(new Timed(180, PackLog.popped(C)));

        assertEquals(List.of(), contentsAt(recorded, 39));
        assertEquals(List.of("one"), contentsAt(recorded, 40));
        assertEquals(List.of("one", "two"), contentsAt(recorded, 60));
        assertEquals(List.of("one", "two"), contentsAt(recorded, 99));
        assertEquals(List.of("two"), contentsAt(recorded, 100));
        assertEquals(List.of("two", "one"), contentsAt(recorded, 140));
        assertEquals(List.of(), contentsAt(recorded, 180));
    }

    @Test
    void aSnapshotSaysWhatIsInForceWhateverCameBefore() {
        PackLog log = new PackLog();
        log.push(pack(A, "one"), 0);
        byte[] state = PackLog.state(List.of(pack(B, "two"), pack(C, "three")));
        assertTrue(log.apply(state, 0, state.length));
        assertEquals(List.of("two", "three"), log.contents());
        assertEquals(B, log.inForce().get(0).id());

        // A jump lands in a later segment: only its snapshot and what follows are read
        List<Timed> segment = List.of(new Timed(200, state), new Timed(230, PackLog.popped(B)));
        assertEquals(List.of("two", "three"), contentsAt(segment, 229));
        assertEquals(List.of("three"), contentsAt(segment, 230));
    }

    @Test
    void aPackPushedAgainUnderTheSameIdReplacesTheOldOne() {
        PackLog log = new PackLog();
        log.push(pack(A, "one"), 0);
        log.push(pack(B, "two"), 1);
        log.push(pack(A, "newer"), 2);
        assertEquals(List.of("two", "newer"), log.contents());
        // A place past the end is the end, and before the start is the start
        log.push(pack(C, "top"), 99);
        log.push(pack(A, "bottom"), -4);
        assertEquals(List.of("bottom", "two", "top"), log.contents());
    }

    @Test
    void aPackThatWasNotAppliedIsNotInForce() {
        PackLog log = new PackLog();
        byte[] declined = PackLog.notApplied(new PackLog.Pack(A, "https://example.test/a.zip", "abc", true, "{\"text\":\"Please\"}", ""), "DECLINED");
        assertFalse(log.apply(declined, 0, declined.length));
        assertTrue(log.inForce().isEmpty());
        assertEquals(1, log.refusals().size());
        PackLog.Refusal refusal = log.refusals().get(0);
        assertEquals("DECLINED", refusal.reason());
        assertEquals(A, refusal.pack().id());
        assertEquals("https://example.test/a.zip", refusal.pack().url());
        assertEquals("abc", refusal.pack().hash());
        assertTrue(refusal.pack().required());
        assertEquals("{\"text\":\"Please\"}", refusal.pack().prompt());
        // Taking off a pack that is not on changes nothing
        byte[] popped = PackLog.popped(A);
        assertFalse(log.apply(popped, 0, popped.length));
    }

    @Test
    void everythingAboutAPushSurvivesBeingWritten() {
        String longPrompt = "{\"text\":\"" + "word ".repeat(20_000) + "\"}";
        PackLog.Pack pushed = new PackLog.Pack(A, "https://example.test/päck.zip", "0123456789abcdef0123456789abcdef01234567", true, longPrompt, "key");
        PackLog log = new PackLog();
        byte[] record = PackLog.pushed(pushed, 0);
        assertTrue(log.apply(record, 0, record.length));
        assertEquals(pushed, log.inForce().get(0));
    }

    @Test
    void recordsThatCannotBeReadChangeNothing() {
        PackLog log = new PackLog();
        log.push(pack(A, "one"), 0);
        byte[] record = PackLog.pushed(pack(B, "two"), 1);
        // Cut short, of a kind from a later version, and empty
        assertFalse(log.apply(record, 0, record.length - 3));
        assertFalse(log.apply(new byte[]{99, 1, 2, 3}, 0, 4));
        assertFalse(log.apply(new byte[0], 0, 0));
        assertEquals(List.of("one"), log.contents());
    }

}
