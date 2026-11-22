package comedymonkey.dailies.moments;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan principle 7: anything that uses a model sits behind momentsAiEnabled, the bundled sound classifier of voice
 * bursts included (specification MOM-27, MOM-32). momentsBurstLabels is only the finer switch.
 */
class MomentsConfigTest {

    @Test
    void theBurstLabelsWaitForTheAiSwitch() {
        // As Dailies arrives: labels on, AI off, so the classifier stays silent
        assertFalse(MomentsConfig.burstLabels(false, true), "the sound classifier runs with AI off");
        assertTrue(MomentsConfig.burstLabels(true, true), "with AI on and the labels on, the sound classifier does not run");
        assertFalse(MomentsConfig.burstLabels(true, false), "the finer switch does not turn the sound classifier off");
        assertFalse(MomentsConfig.burstLabels(false, false), "the sound classifier runs with both switches off");
    }
}
