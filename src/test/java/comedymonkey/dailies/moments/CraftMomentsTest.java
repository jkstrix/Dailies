package comedymonkey.dailies.moments;

import comedymonkey.dailies.moments.track.CraftRules;
import comedymonkey.dailies.moments.track.SourceMerge;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Craft and contraption moments (plan 2.1): the detection rules of the event table, and two sources becoming one event */
class CraftMomentsTest {

    @Test
    void aCraftIsTheirsOnlyCloseByAndSoonAfterAnAssembly() {
        long used = 10_000;
        assertTrue(CraftRules.assembledByPlayer(used, used + 4_999, 15.9));
        assertTrue(CraftRules.assembledByPlayer(used, used + 5_000, 16.0));
        assertFalse(CraftRules.assembledByPlayer(used, used + 5_001, 3), "more than five seconds later");
        assertFalse(CraftRules.assembledByPlayer(used, used + 1_000, 16.5), "more than sixteen blocks away");
        assertFalse(CraftRules.assembledByPlayer(0, 1_000, 1), "no assembler was used at all");
        assertFalse(CraftRules.assembledByPlayer(used, used - 10, 1), "before the assembler was used");
    }

    @Test
    void assemblingIsAnAssemblerBlockOrTheSableCommand() {
        assertTrue(CraftRules.assemblesCraft("sable assemble area 1 2 3 4 5 6"));
        assertTrue(CraftRules.assemblesCraft("/Sable  Assemble"));
        assertFalse(CraftRules.assemblesCraft("sable"));
        assertFalse(CraftRules.assemblesCraft("sable disassemble"));
        assertFalse(CraftRules.assemblesCraft("say sable assemble"));
        assertTrue(CraftRules.isAssembler("simulated:physics_assembler"));
        assertTrue(CraftRules.isAssembler("create:cart_assembler"));
        assertFalse(CraftRules.isAssembler("minecraft:stone"));
        assertFalse(CraftRules.isAssembler(null));
        assertTrue(CraftRules.isContraption("create:contraption"));
        assertTrue(CraftRules.isContraption("create:carriage_contraption"));
        assertFalse(CraftRules.isContraption("create:seat"));
        assertFalse(CraftRules.isContraption("othermod:contraption"));
    }

    @Test
    void aCraftFliesFromEightBlocksUp() {
        assertFalse(CraftRules.flying(7.99));
        assertTrue(CraftRules.flying(8));
        assertFalse(CraftRules.flying(Double.POSITIVE_INFINITY), "an unknown ground is never a flight");
    }

    @Test
    void aCraftIsLostWhenRemovedAtSpeedOrHalfGoneAndOnlyNearby() {
        assertTrue(CraftRules.lost(true, 8.0, 0, 100, true));
        assertFalse(CraftRules.lost(true, 7.9, 0, 100, true), "put away while slow");
        assertFalse(CraftRules.lost(true, 30, 0, 100, false), "the player was nowhere near: it went out of range");
        assertTrue(CraftRules.lost(false, 0, 49, 100, true), "lost most of its blocks");
        assertFalse(CraftRules.lost(false, 0, 50, 100, true), "lost exactly half");
        assertFalse(CraftRules.lostBlocks(1, 6), "too small to tell");
    }

    @Test
    void anAltitudeRecordBeatsTheBestByTenAtMostOnceAMinute() {
        CraftRules.Best altitude = new CraftRules.Best(true, Double.NaN);
        assertFalse(altitude.offer(70, 0), "the first height is where it starts");
        assertFalse(altitude.offer(79.9, 1_000));
        assertTrue(altitude.offer(80, 2_000));
        assertFalse(altitude.offer(95, 30_000), "within the minute");
        assertEquals(95, altitude.best(), "the best still follows");
        assertTrue(altitude.offer(95, 62_000), "a minute later, ten above the last record");
        assertFalse(altitude.offer(100, 200_000), "only five above the last record");

        CraftRules.Best stored = new CraftRules.Best(true, 150);
        assertFalse(stored.offer(155, 0), "a best kept from an earlier session counts");
        assertTrue(stored.offer(160, 1));
    }

    @Test
    void aSpeedRecordBeatsTheBestByATenthAndIgnoresDrifting() {
        CraftRules.Best speed = new CraftRules.Best(false, Double.NaN);
        assertFalse(speed.offer(2, 0), "drifting");
        assertTrue(Double.isNaN(speed.best()), "drifting does not even start the best");
        assertFalse(speed.offer(10, 0));
        assertFalse(speed.offer(10.9, 1_000));
        assertTrue(speed.offer(11.1, 2_000));
        assertFalse(speed.offer(20, 3_000), "within the minute");
    }

    @Test
    void theGameAndTheServerReportingOneThingBecomeOneEvent() {
        SourceMerge merge = new SourceMerge();
        merge.remember("e1", "craft_assembled", "a", "game", 1_000);
        assertNull(merge.find("craft_assembled", "a", "game", 2_000), "two reports from the game are two things");
        assertNull(merge.find("craft_lost", "a", "server", 2_000), "another type");
        assertNull(merge.find("craft_assembled", "b", "server", 2_000), "another craft");
        assertNull(merge.find("craft_assembled", "a", "server", 6_001), "more than five seconds later");
        SourceMerge.Entry joined = merge.find("craft_assembled", "a", "server", 6_000);
        assertNotNull(joined);
        assertEquals("e1", joined.eventId());
        merge.joined(joined, "server");
        assertNull(merge.find("craft_assembled", "a", "server", 6_000), "an event that both have reported is not joined again");
        assertNull(merge.find("craft_assembled", "a", "game", 6_000));
    }

    @Test
    void aReportWithoutACraftMatchesAnyCraft() {
        SourceMerge merge = new SourceMerge();
        merge.remember("e1", "craft_lost", null, "server", 0);
        SourceMerge.Entry found = merge.find("craft_lost", "x", "game", 4_000);
        assertNotNull(found);
        merge.remember("e2", "craft_lost", "y", "game", 10_000);
        assertNotNull(merge.find("craft_lost", null, "server", 11_000));
        assertNull(merge.find("craft_lost", null, "server", 30_000), "forgotten long after");
    }

    @Test
    void theNewestMatchingEventIsJoined() {
        SourceMerge merge = new SourceMerge();
        merge.remember("old", "craft_assembled", null, "game", 0);
        merge.remember("new", "craft_assembled", null, "game", 3_000);
        assertEquals("new", merge.find("craft_assembled", null, "server", 4_000).eventId());
    }

}
