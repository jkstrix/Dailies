package comedymonkey.dailies.windows;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The registry of compats and their claims: which claim takes a screen, and what a recording's list says is missing */
class CompatClaimsTest {

    private static final String READ_SCREEN = "com.example.LetterReadScreen";
    private static final String BOOK_SCREEN = "com.example.BookScreen";
    private static final String CONTAINER_SCREEN = "net.minecraft.client.gui.screens.inventory.AbstractContainerScreen";

    private static CompatClaims.Claim claim(String compat, String name, CompatClaims.Kind kind, String key) {
        return new CompatClaims.Claim(compat, 1, name, kind, key, new Object());
    }

    @Test
    void compatIdsAndVersionsAreChecked() {
        CompatClaims claims = new CompatClaims();
        assertThrows(IllegalArgumentException.class, () -> claims.add("noNamespace", 1, new Object()));
        assertThrows(IllegalArgumentException.class, () -> claims.add("Mod:Upper", 1, new Object()));
        assertThrows(IllegalArgumentException.class, () -> claims.add("dailies:mine", 1, new Object()));
        assertThrows(IllegalArgumentException.class, () -> claims.add("minecraft:mine", 1, new Object()));
        assertThrows(IllegalArgumentException.class, () -> claims.add("postrider:screens", 0, new Object()));
        Object compat = new Object();
        claims.add("postrider:screens", 2, compat);
        assertThrows(IllegalArgumentException.class, () -> claims.add("postrider:screens", 3, new Object()));
        assertSame(compat, claims.compat("postrider:screens").compat());
        assertEquals(2, claims.compat("postrider:screens").version());
        assertEquals(1, claims.compats().size());
    }

    @Test
    void onlyDailiesOwnCompatsAreInItsNamespace() {
        CompatClaims claims = new CompatClaims();
        claims.add("dailies:vanilla", 1, new Object(), true);
        assertEquals(1, claims.compat("dailies:vanilla").version());
        assertThrows(IllegalArgumentException.class, () -> claims.add("dailies:vanilla", 1, new Object(), true));
        // Dailies' own registering keeps to its namespace too
        assertThrows(IllegalArgumentException.class, () -> claims.add("minecraft:books", 1, new Object(), true));
        assertThrows(IllegalArgumentException.class, () -> claims.add("postrider:screens", 1, new Object(), true));
    }

    @Test
    void claimNamesAreCheckedAndKeptPerCompat() {
        CompatClaims claims = new CompatClaims();
        claims.add("postrider:screens", 1, new Object());
        assertThrows(IllegalArgumentException.class, () -> claims.claim(claim("postrider:screens", "Has Spaces", CompatClaims.Kind.OVERLAY, "")));
        assertThrows(IllegalArgumentException.class, () -> claims.claim(claim("other:compat", "x", CompatClaims.Kind.OVERLAY, "")));
        assertNull(claims.claim(claim("postrider:screens", "letter_read", CompatClaims.Kind.SCREEN, READ_SCREEN)));
        assertThrows(IllegalArgumentException.class, () -> claims.claim(claim("postrider:screens", "letter_read", CompatClaims.Kind.OVERLAY, "")));
        CompatClaims.Claim taken = claims.claim("postrider:screens", "letter_read");
        assertNotNull(taken);
        assertEquals("postrider:screens/letter_read", taken.drawnAs());
        assertEquals(List.of(taken), claims.claimsOf("postrider:screens"));
    }

    @Test
    void aScreenGoesToTheClosestClaim() {
        CompatClaims claims = new CompatClaims();
        claims.add("a:compat", 1, new Object());
        claims.add("b:compat", 1, new Object());
        claims.claim(claim("a:compat", "read", CompatClaims.Kind.SCREEN, READ_SCREEN));
        claims.claim(claim("a:compat", "press", CompatClaims.Kind.MENU, "mymod:press"));
        claims.claim(claim("b:compat", "all_containers", CompatClaims.Kind.SCREEN, CONTAINER_SCREEN));

        // Its own class first
        assertEquals("read", claims.screenFor(List.of(READ_SCREEN, "net.minecraft.client.gui.screens.Screen"), null).name());
        // Then its menu's type, before a class it extends
        assertEquals("press", claims.screenFor(List.of("mymod.PressScreen", CONTAINER_SCREEN), "mymod:press").name());
        assertEquals("press", claims.screenFor(List.of("mymod.PressScreen", CONTAINER_SCREEN), "MyMod:Press").name());
        // Then the nearest class it extends
        assertEquals("all_containers", claims.screenFor(List.of("mymod.OtherScreen", CONTAINER_SCREEN), "mymod:other").name());
        // Nothing claims it: Dailies' own window recording may take it
        assertNull(claims.screenFor(List.of(BOOK_SCREEN), null));
        assertNull(claims.screenFor(List.of(), null));
    }

    @Test
    void aScreenOrWindowHasOneClaimOnly() {
        CompatClaims claims = new CompatClaims();
        claims.add("a:compat", 1, new Object());
        claims.add("b:compat", 1, new Object());
        assertNull(claims.claim(claim("a:compat", "read", CompatClaims.Kind.SCREEN, READ_SCREEN)));
        assertNotNull(claims.claim(claim("b:compat", "read", CompatClaims.Kind.SCREEN, READ_SCREEN)));
        assertEquals("a:compat", claims.screenFor(List.of(READ_SCREEN), null).compatId());

        // A window is drawn whole by one compat, or by Dailies with one compat's extras, never both
        assertNull(claims.claim(claim("a:compat", "mailbox", CompatClaims.Kind.EXTRAS, "postrider:mailbox")));
        assertNotNull(claims.claim(claim("b:compat", "mailbox", CompatClaims.Kind.MENU, "postrider:mailbox")));
        assertNotNull(claims.claim(claim("b:compat", "mailbox_extras", CompatClaims.Kind.EXTRAS, "postrider:mailbox")));
        assertEquals("a:compat", claims.extrasFor("postrider:mailbox").compatId());
        assertNull(claims.screenFor(List.of("com.example.MailboxScreen"), "postrider:mailbox"));
        assertNull(claims.extrasFor("minecraft:furnace"));
        assertNull(claims.extrasFor(null));

        // Overlays are not keyed: each compat has its own
        assertNull(claims.claim(claim("a:compat", "goggles", CompatClaims.Kind.OVERLAY, "")));
        assertNull(claims.claim(claim("b:compat", "goggles", CompatClaims.Kind.OVERLAY, "")));
        assertEquals(2, claims.overlays().size());
    }

    @Test
    void aRecordingsMissingCompatsAreSaidOnce() {
        CompatClaims claims = new CompatClaims();
        claims.add("postrider:screens", 1, new Object());
        claims.add("create:dailies", 3, new Object());
        Map<String, Integer> listed = new TreeMap<>(Map.of("postrider:screens", 1, "create:dailies", 4, "jade:dailies", 1));

        List<String> notes = claims.notesFor(listed);
        assertTrue(notes.contains("missing jade:dailies"), notes.toString());
        assertTrue(notes.contains("newer create:dailies 4 3"), notes.toString());
        assertEquals(2, notes.size(), notes.toString());
        // Once in this game, however often recordings that list them are opened
        assertEquals(List.of(), claims.notesFor(listed));
        assertEquals(List.of(), claims.notesFor(Map.of()));
        assertEquals(List.of(), claims.notesFor(null));
        assertEquals(List.of("missing other:compat"), claims.notesFor(Map.of("other:compat", 2)));
    }

}
