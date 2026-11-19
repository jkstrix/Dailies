package comedymonkey.dailies.playback;

import comedymonkey.dailies.file.Records;

import java.io.IOException;

/**
 * What play does on running from one segment into the next. The world is already as the next segment's snapshot
 * describes it, so the snapshot is stepped over, its chunks and all ({@link Records#CHUNKS} names a listing that is not
 * even read): applying it again would put back every entity as it was at the snapshot and start whatever lives by its
 * own clock (a lightning bolt, a firework) over. Only a segment that says it does not follow on from the one before has
 * the world built again from its snapshot. Nothing here touches the game.
 */
final class SegmentCrossing {

    enum Next {
        /** The record the reader is on comes after the snapshot and is to be played */
        PLAY_THIS,
        /** The snapshot is behind; play goes on with the record after it */
        PLAY_ON,
        /** The segment does not follow on: the world has to be built again from its snapshot */
        REBUILD
    }

    private SegmentCrossing() {
    }

    /** Reads a segment just entered by play up to the end of its snapshot, or to the first record that is not part of one */
    static Next enter(Records.Reader reader) throws IOException {
        boolean inSnapshot = false;
        while (reader.next()) {
            int kind = reader.kind();
            if (kind == Records.SNAPSHOT_BEGIN) {
                inSnapshot = true;
            } else if (kind == Records.DISCONTINUITY && inSnapshot) {
                return Next.REBUILD;
            } else if (kind == Records.SNAPSHOT_END) {
                return Next.PLAY_ON;
            } else if (!inSnapshot) {
                return Next.PLAY_THIS;
            }
        }
        return Next.PLAY_ON;
    }

}
