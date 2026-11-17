package comedymonkey.dailies.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Numbers against their range and addresses against what an address can be */
class ChecksTest {

    @Test
    void aNumberInRangeIsTaken() {
        Checks.Number number = Checks.number("7", 1, 20, true);
        assertTrue(number.ok());
        assertEquals(7.0, number.value());
        assertTrue(Checks.number(" 20 ", 1, 20, true).ok());
        assertTrue(Checks.number("1", 1, 20, true).ok());
        assertTrue(Checks.number("10,5", 1.0, 256.0, false).ok());
        assertEquals(10.5, Checks.number("10,5", 1.0, 256.0, false).value());
    }

    @Test
    void aNumberOutOfRangeIsRefusedWithTheNearestAllowed() {
        Checks.Number high = Checks.number("99", 1, 20, true);
        assertFalse(high.ok());
        assertEquals(Checks.NumberProblem.TOO_HIGH, high.problem());
        assertEquals(20.0, high.nearest());
        Checks.Number low = Checks.number("-3", 1, 20, true);
        assertEquals(Checks.NumberProblem.TOO_LOW, low.problem());
        assertEquals(1.0, low.nearest());
    }

    @Test
    void onlyWholeNumbersWhereOnlyThoseAreAllowed() {
        Checks.Number half = Checks.number("7.5", 1, 20, true);
        assertEquals(Checks.NumberProblem.NOT_WHOLE, half.problem());
        assertEquals(8.0, half.nearest());
        assertTrue(Checks.number("7.5", 1, 20, false).ok());
        assertTrue(Checks.number("7.0", 1, 20, true).ok());
    }

    @Test
    void wordsAreNotNumbers() {
        assertEquals(Checks.NumberProblem.NOT_A_NUMBER, Checks.number("lots", 1, 20, true).problem());
        assertEquals(Checks.NumberProblem.NOT_A_NUMBER, Checks.number("", 1, 20, true).problem());
        assertEquals(Checks.NumberProblem.NOT_A_NUMBER, Checks.number(null, 1, 20, true).problem());
        assertEquals(Checks.NumberProblem.NOT_A_NUMBER, Checks.number("NaN", 1, 20, false).problem());
        assertEquals(Checks.NumberProblem.NOT_A_NUMBER, Checks.number("Infinity", 1, 20, false).problem());
    }

    @Test
    void clampingKeepsToTheRangeAndToWholeNumbers() {
        assertEquals(20.0, Checks.clamp(99, 1, 20, true));
        assertEquals(1.0, Checks.clamp(-5, 1, 20, true));
        assertEquals(7.0, Checks.clamp(7.4, 1, 20, true));
        assertEquals(7.4, Checks.clamp(7.4, 1, 20, false));
        assertEquals(256.0, Checks.clamp(1000.0, 1.0, 256.0, false));
    }

    @Test
    void addressesThatAreWrittenProperly() {
        assertEquals(Checks.AddressProblem.NONE, Checks.url("http://192.168.188.50:8000"));
        assertEquals(Checks.AddressProblem.NONE, Checks.url("http://192.168.188.50:11434/"));
        assertEquals(Checks.AddressProblem.NONE, Checks.url("https://whisper.example.org/v1/audio?x=1"));
        assertEquals(Checks.AddressProblem.NONE, Checks.url("HTTP://localhost"));
        assertEquals(Checks.AddressProblem.NONE, Checks.url("http://[::1]:8000/v1"));
        assertEquals(Checks.AddressProblem.NONE, Checks.url("  http://my-server.lan:80  "));
    }

    @Test
    void anEmptyAddressIsEmptyAndNotWrong() {
        assertEquals(Checks.AddressProblem.EMPTY, Checks.url(""));
        assertEquals(Checks.AddressProblem.EMPTY, Checks.url("   "));
        assertEquals(Checks.AddressProblem.EMPTY, Checks.url(null));
        assertEquals(Checks.AddressProblem.EMPTY, Checks.host(""));
    }

    @Test
    void addressesThatAreNot() {
        assertEquals(Checks.AddressProblem.NO_SCHEME, Checks.url("192.168.188.50:8000"));
        assertEquals(Checks.AddressProblem.NO_SCHEME, Checks.url("localhost"));
        assertEquals(Checks.AddressProblem.NOT_HTTP, Checks.url("ftp://example.org"));
        assertEquals(Checks.AddressProblem.NOT_HTTP, Checks.url("file:///C:/secrets"));
        assertEquals(Checks.AddressProblem.NO_HOST, Checks.url("http://"));
        assertEquals(Checks.AddressProblem.NO_HOST, Checks.url("http://:8000"));
        assertEquals(Checks.AddressProblem.BAD_PORT, Checks.url("http://localhost:99999"));
        assertEquals(Checks.AddressProblem.BAD_PORT, Checks.url("http://localhost:0"));
        assertEquals(Checks.AddressProblem.BAD_PORT, Checks.url("http://localhost:port"));
        assertEquals(Checks.AddressProblem.BAD_PORT, Checks.url("http://localhost:"));
        assertEquals(Checks.AddressProblem.BAD_CHARACTERS, Checks.url("http://my server:8000"));
        assertEquals(Checks.AddressProblem.BAD_CHARACTERS, Checks.url("http://999.1.1.1"));
        assertEquals(Checks.AddressProblem.BAD_CHARACTERS, Checks.url("http://exa$mple.org"));
        assertEquals(Checks.AddressProblem.BAD_CHARACTERS, Checks.url("http://example..org"));
    }

    @Test
    void theBareNameOrNumberOfAMachine() {
        assertEquals(Checks.AddressProblem.NONE, Checks.host("127.0.0.1"));
        assertEquals(Checks.AddressProblem.NONE, Checks.host("0.0.0.0"));
        assertEquals(Checks.AddressProblem.NONE, Checks.host("localhost"));
        assertEquals(Checks.AddressProblem.NONE, Checks.host("::1"));
        assertEquals(Checks.AddressProblem.BAD_CHARACTERS, Checks.host("http://127.0.0.1"));
        assertEquals(Checks.AddressProblem.BAD_CHARACTERS, Checks.host("256.0.0.1"));
        assertEquals(Checks.AddressProblem.BAD_PORT, Checks.host("127.0.0.1:25595"));
    }

    @Test
    void whichSettingsHoldAddresses() {
        assertEquals(Checks.AddressKind.URL, Checks.addressKind("momentsWhisperUrl"));
        assertEquals(Checks.AddressKind.URL, Checks.addressKind("momentsOllamaUrl"));
        assertEquals(Checks.AddressKind.HOST, Checks.addressKind("mcpBindAddress"));
        assertNull(Checks.addressKind("mcpPort"));
        assertNull(Checks.addressKind("momentsWhisperModel"));
    }

}
