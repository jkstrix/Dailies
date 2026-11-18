package comedymonkey.dailies.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Settings on their way to the file: a save that ends must take away only its own change */
class PendingValuesTest {

    @Test
    void aChangeStandsUntilItsSaveIsDone() {
        PendingValues pending = new PendingValues();
        PendingValues.Ticket ticket = pending.put("binDays", 12);
        assertEquals(12, pending.get("binDays"));
        assertFalse(pending.isEmpty());
        pending.done("binDays", ticket);
        assertNull(pending.get("binDays"));
        assertTrue(pending.isEmpty());
    }

    @Test
    void theEndOfAnEarlierSaveLeavesANewerChange() {
        PendingValues pending = new PendingValues();
        PendingValues.Ticket first = pending.put("binDays", 12);
        PendingValues.Ticket second = pending.put("binDays", 120);
        pending.done("binDays", first);
        assertEquals(120, pending.get("binDays"));
        pending.done("binDays", second);
        assertNull(pending.get("binDays"));
    }

    @Test
    void anEqualValueChangedAgainIsStillTwoChanges() {
        // On, off and on again: the end of the first save must not take away the last, which is still to be written
        PendingValues pending = new PendingValues();
        PendingValues.Ticket on = pending.put("showCameraPath", true);
        pending.put("showCameraPath", false);
        PendingValues.Ticket again = pending.put("showCameraPath", true);
        pending.done("showCameraPath", on);
        assertEquals(true, pending.get("showCameraPath"));
        pending.done("showCameraPath", again);
        assertNull(pending.get("showCameraPath"));
    }

    @Test
    void settingsAreKeptApart() {
        PendingValues pending = new PendingValues();
        PendingValues.Ticket days = pending.put("binDays", 3);
        pending.put("retentionDays", 9);
        pending.done("binDays", days);
        assertNull(pending.get("binDays"));
        assertEquals(9, pending.get("retentionDays"));
        // A ticket of one setting does nothing to another
        pending.done("retentionDays", days);
        assertEquals(9, pending.get("retentionDays"));
    }
}
