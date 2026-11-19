package comedymonkey.dailies.playback;

import comedymonkey.dailies.file.Records;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Play running from one segment into the next steps over the next one's snapshot (PLAY-11) */
class SegmentCrossingTest {

    private static final byte[] BOLT = "spawn the lightning bolt".getBytes(StandardCharsets.UTF_8);
    private static final byte[] AFTER = "what happened after the snapshot".getBytes(StandardCharsets.UTF_8);

    /** A segment as the recorder writes it: a snapshot with a world, its chunks, an entity and lids, then the ticks */
    private static Records.Reader segment(boolean afresh) {
        Records.Writer records = new Records.Writer();
        records.number(Records.SNAPSHOT_BEGIN, 2458);
        if (afresh) {
            records.record(Records.DISCONTINUITY);
        }
        records.record(Records.WORLD, new byte[32]);
        records.record(Records.PACKET, "login".getBytes(StandardCharsets.UTF_8));
        records.record(Records.CHUNKS);
        records.record(Records.SHARED_PACKET, new byte[32]);
        records.record(Records.BUNDLE_BEGIN);
        records.record(Records.PACKET, BOLT);
        records.record(Records.BUNDLE_END);
        records.record(Records.SNAPSHOT_END);
        records.record(Records.PACKET, AFTER);
        records.number(Records.TICK, 1);
        return new Records.Reader(new ByteArrayInputStream(records.take()));
    }

    @Test
    void playRunningIntoASegmentThatFollowsOnStepsOverItsSnapshot() throws IOException {
        try (Records.Reader reader = segment(false)) {
            assertEquals(SegmentCrossing.Next.PLAY_ON, SegmentCrossing.enter(reader));
            // The first record play is given is the one after the snapshot: nothing in the snapshot is applied again
            reader.next();
            assertEquals(Records.PACKET, reader.kind());
            assertEquals(new String(AFTER, StandardCharsets.UTF_8), new String(reader.payload(), 0, reader.length(), StandardCharsets.UTF_8));
            reader.next();
            assertEquals(Records.TICK, reader.kind());
            assertFalse(reader.next());
        }
    }

    @Test
    void aSegmentThatDoesNotFollowOnHasTheWorldBuiltAgain() throws IOException {
        try (Records.Reader reader = segment(true)) {
            assertEquals(SegmentCrossing.Next.REBUILD, SegmentCrossing.enter(reader));
        }
    }

    @Test
    void aSegmentWithoutASnapshotIsPlayedFromItsFirstRecord() throws IOException {
        Records.Writer records = new Records.Writer();
        records.record(Records.PACKET, AFTER);
        records.number(Records.TICK, 1);
        try (Records.Reader reader = new Records.Reader(new ByteArrayInputStream(records.take()))) {
            assertEquals(SegmentCrossing.Next.PLAY_THIS, SegmentCrossing.enter(reader));
            assertEquals(Records.PACKET, reader.kind());
        }
        try (Records.Reader reader = new Records.Reader(new ByteArrayInputStream(new byte[0]))) {
            assertEquals(SegmentCrossing.Next.PLAY_ON, SegmentCrossing.enter(reader));
        }
    }

}
