package comedymonkey.dailies.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A token typed in by hand must be long enough to be worth having; empty asks the mod to make one */
class SettingPasswordTest {

    @Test
    void tooShortIsRefusedWithTheLengthNeeded() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> Setting.password("mcpToken", "hunter2", 24));
        assertTrue(refused.getMessage().contains("24"), refused.getMessage());
        // Spaces around it do not count towards its length
        assertThrows(IllegalArgumentException.class, () -> Setting.password("mcpToken", "   short-token-with-pad   ", 24));
    }

    @Test
    void longEnoughIsKeptWithoutTheSpacesAroundIt() {
        assertEquals("abcdefghijklmnopqrstuvwx", Setting.password("mcpToken", " abcdefghijklmnopqrstuvwx\n", 24));
    }

    @Test
    void emptyIsAlwaysAllowed() {
        assertEquals("", Setting.password("mcpToken", "", 24));
        assertEquals("", Setting.password("mcpToken", "   ", 24));
        assertEquals("", Setting.password("mcpToken", null, 24));
    }

}
