package comedymonkey.dailiescompat.supplementaries.state;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each claim's state comes back as it was written, with what playback draws from it, and the same state writes the
 * same bytes (Dailies writes a state again only when its bytes change).
 */
class SuppStatesTest {

    @Test
    void aSignPostKeepsBothSignsLinesTheLineTypedOnAndItsSavedData() {
        SuppStates.TextHolder post = new SuppStates.TextHolder("supplementaries:way_sign", 123456789L, "supplementaries:way_sign[waterlogged=false]",
            new byte[]{10, 0, 0, 1, 2, 3}, List.of(List.of("North ⇧"), List.of("Mine")), 1, 0, 3, 1, 17);
        SuppStates.TextHolder read = SuppStates.readTextHolder(SuppStates.textHolder(post));
        assertEquals(post, read);
        assertEquals("Mine", read.lines().get(1).get(0));
        assertEquals(1, read.holder());
        assertEquals(3, read.cursor());
        assertEquals(1, read.selection());
        assertArrayEquals(new byte[]{10, 0, 0, 1, 2, 3}, read.data());
        assertArrayEquals(SuppStates.textHolder(post), SuppStates.textHolder(read));
    }

    @Test
    void aDoormatKeepsItsThreeLinesAndAnEmptyOne() {
        SuppStates.TextHolder doormat = new SuppStates.TextHolder("supplementaries:doormat", -7L, "supplementaries:doormat[facing=north]", new byte[0],
            List.of(List.of("Welcome", "", "home")), 0, 2, 4, 4, 0);
        SuppStates.TextHolder read = SuppStates.readTextHolder(SuppStates.textHolder(doormat));
        assertEquals(doormat, read);
        assertEquals(List.of("Welcome", "", "home"), read.lines().get(0));
        assertEquals(2, read.line());
    }

    @Test
    void aBlackboardKeepsEachJoinedBoardsPixelsAndTheGapsBetween() {
        byte[] first = new byte[SuppStates.PIXELS];
        byte[] second = new byte[SuppStates.PIXELS];
        first[0] = 1;
        first[255] = 15;
        second[17] = 4;
        List<byte[]> boards = new ArrayList<>(Arrays.asList(first, null, null, second));
        SuppStates.Blackboard board = new SuppStates.Blackboard(2, 2, boards, 3, (byte) 4, true);
        SuppStates.Blackboard read = SuppStates.readBlackboard(SuppStates.blackboard(board));
        assertEquals(board, read);
        assertArrayEquals(first, read.board(0, 0));
        assertNull(read.board(0, 1));
        assertEquals(4, read.board(1, 1)[17]);
        assertEquals(3, read.used());
        assertEquals(4, read.colour());
        assertTrue(read.undo());
        assertArrayEquals(SuppStates.blackboard(board), SuppStates.blackboard(read));
    }

    @Test
    void aBlackboardOfTheWrongShapeIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new SuppStates.Blackboard(2, 1, List.of(new byte[SuppStates.PIXELS]), 0, (byte) 0, false));
        assertThrows(IllegalArgumentException.class, () -> new SuppStates.Blackboard(1, 1, List.of(new byte[12]), 0, (byte) 0, false));
    }

    @Test
    void aSpeakerKeepsItsMessageCursorModeAndReach() {
        SuppStates.Speaker speaker = new SuppStates.Speaker("Dinner is ready", 6, 15, "STATUS_MESSAGE", 24.5);
        SuppStates.Speaker read = SuppStates.readSpeaker(SuppStates.speaker(speaker));
        assertEquals(speaker, read);
        assertEquals("STATUS_MESSAGE", read.mode());
        assertEquals(24.5, read.volume());
        assertArrayEquals(SuppStates.speaker(speaker), SuppStates.speaker(read));
    }

    @Test
    void aLecternBookKeepsItsStyledPagesThePageTheSigningAndTheInk() {
        SuppStates.LecternBook book = new SuppStates.LecternBook(List.of("§4Red§r words", "", "Page three"), 2, 5, 1, true, "Ledger", "Steve", 3, 1);
        SuppStates.LecternBook read = SuppStates.readLecternBook(SuppStates.lecternBook(book));
        assertEquals(book, read);
        assertEquals("§4Red§r words", read.pages().get(0));
        assertEquals(2, read.page());
        assertTrue(read.signing());
        assertEquals("Ledger", read.title());
        assertEquals(3, read.ink());
        assertArrayEquals(SuppStates.lecternBook(book), SuppStates.lecternBook(read));
    }

    @Test
    void aPresentKeepsWhoItIsForItsMessageAndWhetherItWasPacked() {
        SuppStates.Present present = new SuppStates.Present("Alex", "Happy\nbirthday", true);
        SuppStates.Present read = SuppStates.readPresent(SuppStates.present(present));
        assertEquals(present, read);
        assertEquals("Happy\nbirthday", read.description());
        assertTrue(read.packed());
        assertArrayEquals(SuppStates.present(present), SuppStates.present(read));
    }

    @Test
    void aCannonWindowKeepsItsAimAsTypedAndItsPower() {
        SuppStates.Cannon cannon = new SuppStates.Cannon("30", "-45", (byte) 3);
        SuppStates.Cannon read = SuppStates.readCannon(SuppStates.cannon(cannon));
        assertEquals(cannon, read);
        assertEquals("-45", read.yaw());
        assertEquals(3, read.power());
    }

    @Test
    void theRedMerchantKeepsItsOffersAsTheirCodecWroteThemAndThePick() {
        SuppStates.Merchant merchant = new SuppStates.Merchant(new byte[]{3, 1, 4, 1, 5}, 2, 140, true, false, 4, 6);
        SuppStates.Merchant read = SuppStates.readMerchant(SuppStates.merchant(merchant));
        assertEquals(merchant, read);
        assertArrayEquals(new byte[]{3, 1, 4, 1, 5}, read.offers());
        assertEquals(4, read.scroll());
        assertEquals(6, read.picked());
        assertArrayEquals(SuppStates.merchant(merchant), SuppStates.merchant(read));
    }

    @Test
    void theSlimeKeepsItsTicksAndFadesOverItsLastSeventy() {
        SuppStates.Slimed slimed = new SuppStates.Slimed(35);
        assertEquals(slimed, SuppStates.readSlimed(SuppStates.slimed(slimed)));
        assertEquals(1.0F, SuppStates.slimeAlpha(200, 0.5F));
        assertEquals(0.5F, SuppStates.slimeAlpha(35, 0.0F), 1.0E-6F);
        assertEquals(0.0F, SuppStates.slimeAlpha(0, 0.5F));
    }

    @Test
    void aQuiversRowKeepsItsItemsThePickAndWhereItIsMoved() {
        SuppStates.Selectable row = new SuppStates.Selectable(List.of(new byte[]{1, 2}, new byte[0], new byte[]{9}), 2, -10, 25);
        SuppStates.Selectable read = SuppStates.readSelectable(SuppStates.selectable(row));
        assertEquals(row, read);
        assertEquals(3, read.items().size());
        assertEquals(0, read.items().get(1).length);
        assertEquals(2, read.picked());
        assertEquals(25, read.offsetY());
        assertArrayEquals(SuppStates.selectable(row), SuppStates.selectable(read));
    }

    @Test
    void theCannonsControlsKeepWhatIsShownAndTheChargeBetweenTicks() {
        SuppStates.CannonHud hud = new SuppStates.CannonHud(false, SuppStates.CANNON_AIMING, true, 1, true, SuppStates.CROSSHAIR_HIT, new byte[]{7}, new byte[]{8, 8},
            "8667ba71-b85a-4004-af54-457a9734eed7", 0.25F, 0.5F, 0.0F, 0.2F, (byte) 2);
        SuppStates.CannonHud read = SuppStates.readCannonHud(SuppStates.cannonHud(hud));
        assertEquals(hud, read);
        assertEquals(SuppStates.CROSSHAIR_HIT, read.crosshair());
        assertArrayEquals(new byte[]{8, 8}, read.fuel());
        assertEquals(0.375F, read.cooldown(0.5F), 1.0E-6F);
        assertEquals(0.1F, read.firing(0.5F), 1.0E-6F);
        assertArrayEquals(SuppStates.cannonHud(hud), SuppStates.cannonHud(read));
    }

    @Test
    void theCannonsTrajectoryKeepsItsShotAndWhatTheAimMet() {
        SuppStates.Trajectory trajectory = new SuppStates.Trajectory(42L, 12.5F, -3.0F, -0.4F, 31.25, false, 0.05F, 0.99F, 1.2F, 0.6F, 0, SuppStates.HIT_BLOCK,
            10.5, -60.0, 3.25, 1, 99L);
        SuppStates.Trajectory read = SuppStates.readTrajectory(SuppStates.trajectory(trajectory));
        assertEquals(trajectory, read);
        assertFalse(read.miss());
        assertEquals(31.25, read.time());
        assertEquals(99L, read.hitBlock());
    }

    @Test
    void theSlingshotsOutlineKeepsItsBlockAndColour() {
        SuppStates.Outline outline = new SuppStates.Outline(-123L, 0x66FF0000);
        assertEquals(outline, SuppStates.readOutline(SuppStates.outline(outline)));
    }

    @Test
    void theLineAboveTheHotbarKeepsItsTextAndFadesOverItsLastTwentyTicks() {
        SuppStates.Status status = new SuppStates.Status("{\"translate\":\"message.supplementaries.cannon.maneuver\"}", 10, false);
        SuppStates.Status read = SuppStates.readStatus(SuppStates.status(status));
        assertEquals(status, read);
        assertEquals(127, read.alpha(0.0F));
        assertEquals(255, new SuppStates.Status("x", 60, true).alpha(0.5F));
        assertEquals(0, new SuppStates.Status("x", 0, true).alpha(0.5F));
    }

    @Test
    void aDamagedRecordIsRefusedRatherThanReadWrong() {
        byte[] whole = SuppStates.speaker(new SuppStates.Speaker("Hello", 0, 0, "CHAT", 16.0));
        assertThrows(IllegalArgumentException.class, () -> SuppStates.readSpeaker(Arrays.copyOf(whole, whole.length - 3)));
        assertThrows(IllegalArgumentException.class, () -> SuppStates.readTextHolder(new byte[]{0x7F, 0x7F, 0x7F, 0x7F}));
    }

    @Test
    void aRecordFromALaterVersionIsReadAsFarAsThisOneKnows() {
        byte[] whole = SuppStates.present(new SuppStates.Present("Alex", "", false));
        byte[] longer = Arrays.copyOf(whole, whole.length + 4);
        assertEquals(new SuppStates.Present("Alex", "", false), SuppStates.readPresent(longer));
    }

}
