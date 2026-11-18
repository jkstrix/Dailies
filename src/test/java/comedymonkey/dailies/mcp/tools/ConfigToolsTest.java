package comedymonkey.dailies.mcp.tools;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What config_get shows of a password, and which settings config_set leaves to the settings screen */
class ConfigToolsTest {

    private static final String TOKEN = "Zq3v9XkJ2mP7rT1wY5bN8cL4dF6gH0sA_e-uIoWxV2k";

    @Test
    void thePasswordIsNeverShownOnlyWhetherItIsSet() {
        JsonObject set = new JsonObject();
        CoreTools.describeValue(set, "mcpToken", true, TOKEN, "");
        assertTrue(set.get("set").getAsBoolean());
        assertFalse(set.has("value"));
        assertFalse(set.has("default"));
        assertFalse(set.toString().contains(TOKEN), set.toString());
        assertTrue(set.get("settings_screen_only").getAsBoolean());

        JsonObject unset = new JsonObject();
        CoreTools.describeValue(unset, "mcpToken", true, "", "");
        assertFalse(unset.get("set").getAsBoolean());
        JsonObject blank = new JsonObject();
        CoreTools.describeValue(blank, "mcpToken", true, "   ", "");
        assertFalse(blank.get("set").getAsBoolean());
    }

    @Test
    void anOrdinarySettingShowsItsValue() {
        JsonObject described = new JsonObject();
        CoreTools.describeValue(described, "defaultFileName", false, "my_clip", "{date}_{time}");
        assertEquals("my_clip", described.get("value").getAsString());
        assertEquals("{date}_{time}", described.get("default").getAsString());
        assertFalse(described.has("set"));
        assertFalse(described.has("settings_screen_only"));

        // The server's address is not a secret, but is still only shown, not changed
        JsonObject bind = new JsonObject();
        CoreTools.describeValue(bind, "mcpBind", false, "127.0.0.1", "127.0.0.1");
        assertEquals("127.0.0.1", bind.get("value").getAsString());
        assertTrue(bind.get("settings_screen_only").getAsBoolean());
    }

    @Test
    void theServersOwnSettingsAreForTheSettingsScreen() {
        for (String key : new String[]{"mcpEnabled", "mcpPort", "mcpBind", "mcpToken", "mcpAllowedClients", "mcpCertificateFingerprint"}) {
            assertTrue(CoreTools.screenOnly(key, false), key);
        }
        // A password anywhere else is no different
        assertTrue(CoreTools.screenOnly("somePassword", true));
        assertFalse(CoreTools.screenOnly("momentsWhisperUrl", false));
        assertFalse(CoreTools.screenOnly("autoStart", false));
    }

}
