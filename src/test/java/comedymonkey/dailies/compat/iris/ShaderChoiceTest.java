package comedymonkey.dailies.compat.iris;

import comedymonkey.dailies.edit.Looks;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** When the shaders look switches Iris, and what it puts back */
class ShaderChoiceTest {

    @Test
    void asTheGameHasItNeverSwitches() {
        ShaderChoice choice = new ShaderChoice();
        assertNull(choice.step(Looks.Shaders.AS_GAME, true));
        assertNull(choice.step(Looks.Shaders.AS_GAME, false));
        assertNull(choice.step(null, true));
        assertNull(choice.restore(false));
        assertNull(choice.own());
    }

    @Test
    void itSwitchesOnlyWhenTheSceneWantsSomethingElseAndOnlyOnce() {
        ShaderChoice choice = new ShaderChoice();
        // The viewer has shaders on; the scene wants them off
        assertEquals(Boolean.FALSE, choice.step(Looks.Shaders.OFF, true));
        assertEquals(Boolean.TRUE, choice.own());
        // Every frame after: Iris now has what the scene wants, so nothing is reloaded again
        assertNull(choice.step(Looks.Shaders.OFF, false));
        assertNull(choice.step(Looks.Shaders.OFF, false));
        // The scene leaves it to the game again: the viewer's own setting comes back
        assertEquals(Boolean.TRUE, choice.step(Looks.Shaders.AS_GAME, false));
        assertNull(choice.own());
        assertNull(choice.step(Looks.Shaders.AS_GAME, true));
    }

    @Test
    void aSceneThatWantsWhatIrisHasChangesNothingAndOwesNothing() {
        ShaderChoice choice = new ShaderChoice();
        assertNull(choice.step(Looks.Shaders.ON, true));
        assertNull(choice.own());
        assertNull(choice.restore(true));
    }

    @Test
    void theViewersOwnSettingIsKeptHoweverOftenTheSceneChangesItsMind() {
        ShaderChoice choice = new ShaderChoice();
        assertEquals(Boolean.TRUE, choice.step(Looks.Shaders.ON, false));
        assertEquals(Boolean.FALSE, choice.step(Looks.Shaders.OFF, true));
        assertEquals(Boolean.TRUE, choice.step(Looks.Shaders.ON, false));
        assertEquals(Boolean.FALSE, choice.own());
        // The recording closes: no scene is shown, and the viewer's own setting goes back
        assertEquals(Boolean.FALSE, choice.step(null, true));
        assertNull(choice.own());
    }

    @Test
    void puttingBackWhatIsAlreadySoReloadsNothing() {
        ShaderChoice choice = new ShaderChoice();
        assertEquals(Boolean.TRUE, choice.step(Looks.Shaders.ON, false));
        assertEquals(Boolean.FALSE, choice.step(Looks.Shaders.OFF, true));
        // The viewer's own was off, and Iris is off now
        assertNull(choice.restore(false));
        assertNull(choice.own());
    }

    @Test
    void theGameClosingPutsBackWhatTheViewerHad() {
        ShaderChoice choice = new ShaderChoice();
        choice.step(Looks.Shaders.OFF, true);
        assertEquals(Boolean.TRUE, choice.restore(false));
        // Once given back, nothing more is owed
        assertNull(choice.restore(false));
    }

}
