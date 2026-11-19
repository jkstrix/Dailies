package comedymonkey.dailiescompat.jade.state;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Jade's box comes back as it was written, every kind of element in it, with where playback puts it; the same box writes
 * the same bytes (Dailies writes a state again only when its bytes change); and a damaged or later record is read safely.
 */
class JadeStatesTest {

    private static final String STYLE = "{\"backgroundColor\":\"#131313\",\"borderColor\":[\"#383838\",\"#383838\",\"#242424\",\"#242424\"]}";

    private static JadeStates.Element element(int kind, float width, float height, String tag, List<String> texts, int[] ints, float[] floats,
                                              List<JadeStates.Element> children, List<JadeStates.Line> lines) {
        return new JadeStates.Element(kind, 0, 0.0F, 0.0F, width, height, tag, texts, ints, floats, children, lines);
    }

    private static JadeStates.Element text(String json) {
        return element(JadeStates.TEXT, 40.0F, 8.0F, "minecraft:object_name", List.of(json), new int[0], new float[0], List.of(), List.of());
    }

    /** A chest's box: its name, a view group's box of three items, the mod's name, and the chest's icon */
    private static JadeStates.Element chest() {
        JadeStates.Element diamonds = element(JadeStates.ITEM, 18.0F, 18.0F, "", List.of("{\"id\":\"minecraft:diamond\"}", "12"), new int[]{12},
            new float[]{1.0F}, List.of(), List.of());
        JadeStates.Element iron = element(JadeStates.ITEM, 18.0F, 18.0F, "", List.of("{\"id\":\"minecraft:iron_ingot\"}"), new int[]{320},
            new float[]{0.5F}, List.of(), List.of());
        JadeStates.Element logs = new JadeStates.Element(JadeStates.ITEM, 1, 1.5F, -2.0F, 18.0F, 18.0F, "minecraft:item_storage",
            List.of("{\"id\":\"minecraft:oak_log\"}"), new int[]{16}, new float[]{1.0F}, List.of(), List.of());
        JadeStates.Element group = element(JadeStates.BOX, 60.0F, 22.0F, "", List.of(STYLE), new int[]{2, 2, 2, 2, -1, 0}, new float[]{0.0F, 54.0F, 18.0F},
            List.of(), List.of(new JadeStates.Line(0, 2, List.of(diamonds, iron, logs))));
        JadeStates.Element icon = element(JadeStates.ITEM, 18.0F, 18.0F, "minecraft:core_root_icon", List.of("{\"id\":\"minecraft:chest\"}"),
            new int[]{1}, new float[]{1.0F}, List.of(), List.of());
        return element(JadeStates.BOX, 90.0F, 50.0F, "jade:root", List.of(STYLE), new int[]{4, 4, 4, 4, -1, 1}, new float[]{0.0F, 64.0F, 42.0F},
            List.of(icon), List.of(new JadeStates.Line(0, 2, List.of(text("{\"text\":\"Chest\",\"color\":\"white\"}"))),
                new JadeStates.Line(0, 2, List.of(group)), new JadeStates.Line(0, 2, List.of()),
                new JadeStates.Line(-1, 0, List.of(text("{\"text\":\"Minecraft\",\"color\":\"blue\",\"italic\":true}")))));
    }

    private static JadeStates.Box box(JadeStates.Element root) {
        return new JadeStates.Box(380, 4, 92, 52, 1.0F, 0.6F, 854, 480, 0.5F, 0.0F, false, 0.7F, 0, "jade:dark", "ab".repeat(32), 0.0F, 0.0F, true, root);
    }

    @Test
    void aChestsBoxComesBackWhole() {
        JadeStates.Box box = box(chest());
        byte[] bytes = JadeStates.box(box);
        JadeStates.Box read = JadeStates.readBox(bytes);
        assertEquals(box, read);
        assertArrayEquals(bytes, JadeStates.box(read), "the same box writes the same bytes");
        JadeStates.Element root = read.root();
        assertEquals(JadeStates.BOX, root.kind());
        assertEquals(4, root.lines().size());
        assertTrue(root.lines().get(2).elements().isEmpty(), "an empty line stays");
        assertEquals(-1, root.lines().get(3).marginTop());
        assertEquals(1, root.number(5, 0), "the hint at more details");
        assertEquals("minecraft:core_root_icon", root.children().getFirst().tag());
        JadeStates.Element logs = root.lines().get(1).elements().getFirst().lines().getFirst().elements().get(2);
        assertEquals(1, logs.align());
        assertEquals(1.5F, logs.moveX());
        assertEquals(-2.0F, logs.moveY());
        assertEquals("12", root.lines().get(1).elements().getFirst().lines().getFirst().elements().getFirst().text(1), "an item's count text");
        assertEquals("", logs.text(1), "no count text");
        assertEquals(320, root.lines().get(1).elements().getFirst().lines().getFirst().elements().get(1).number(0, 1),
            "a count past what the game's JSON for an item takes, as Jade adds up a chest's stacks");
    }

    @Test
    void aFurnacesBarAndAPigsHeartsComeBack() {
        JadeStates.Element fluid = element(JadeStates.FLUID, 16.0F, 16.0F, "", List.of("{\"type\":\"minecraft:lava\",\"amount\":1000}"), new int[0],
            new float[0], List.of(), List.of());
        JadeStates.Element bar = element(JadeStates.PROGRESS, 100.0F, 14.0F, "minecraft:furnace", List.of(STYLE, "{\"text\":\"1,000 mB\"}"),
            new int[]{0, JadeStates.SIMPLE_BAR, 0xFFAA4400, 0xFF884400, -1, 1, 3, 1, 0}, new float[]{0.375F}, List.of(fluid), List.of());
        JadeStates.Element slim = element(JadeStates.PROGRESS, 20.0F, 8.0F, "", List.of(STYLE), new int[]{1, JadeStates.SLIM_BAR, 0xFF00FF00, 0xFF00FF00, 0, 1, 3, 1, 1},
            new float[]{1.0F}, List.of(), List.of());
        JadeStates.Element hearts = element(JadeStates.HEALTH, 41.0F, 9.0F, "minecraft:entity_health", List.of(), new int[]{5, 1, 5}, new float[]{7.5F},
            List.of(), List.of());
        JadeStates.Element armour = element(JadeStates.ARMOR, 30.0F, 9.0F, "", List.of("40"), new int[]{1, 1, 1}, new float[]{40.0F}, List.of(), List.of());
        JadeStates.Element sprite = element(JadeStates.SPRITE, 12.0F, 12.0F, "", List.of("jade:harvest"), new int[]{12, 12}, new float[0], List.of(), List.of());
        JadeStates.Element rule = element(JadeStates.RULE, 10.0F, 4.0F, "", List.of(), new int[]{0xFFA0A0A0}, new float[0], List.of(), List.of());
        JadeStates.Element spacer = element(JadeStates.SPACER, 3.0F, 0.0F, "", List.of(), new int[0], new float[0], List.of(), List.of());
        JadeStates.Element scaled = element(JadeStates.TEXT, 20.0F, 5.5F, "", List.of("{\"text\":\"x2\"}"), new int[0], new float[]{0.5F, 100.0F}, List.of(),
            List.of());
        JadeStates.Element compound = element(JadeStates.COMPOUND, 18.0F, 18.0F, "", List.of(), new int[0], new float[0], List.of(sprite, scaled), List.of());
        JadeStates.Element other = element(JadeStates.OTHER, 30.0F, 10.0F, "someplugin:gauge", List.of("someplugin.client.GaugeElement"), new int[0],
            new float[0], List.of(), List.of());
        JadeStates.Element root = element(JadeStates.BOX, 120.0F, 80.0F, "jade:root", List.of(STYLE), new int[]{4, 4, 4, 4, 1, 0}, new float[]{0.25F, 100.0F, 70.0F},
            List.of(), List.of(new JadeStates.Line(0, 2, List.of(bar, slim)), new JadeStates.Line(0, 2, List.of(hearts, armour, rule, spacer)),
                new JadeStates.Line(0, 2, List.of(compound, other))));
        JadeStates.Box box = new JadeStates.Box(10, 20, 124, 84, 0.75F, 1.0F, 1920, 1080, 0.0F, 1.0F, true, 1.0F, 1, "jade:light", "", 0.6F, 0.42F, false, root);
        JadeStates.Box read = JadeStates.readBox(JadeStates.box(box));
        assertEquals(box, read);
        JadeStates.Element readBar = read.root().lines().getFirst().elements().getFirst();
        assertEquals(0.375F, readBar.fraction(0, 0.0F));
        assertEquals(0xFFAA4400, readBar.number(2, 0));
        assertEquals("{\"text\":\"1,000 mB\"}", readBar.text(1));
        assertEquals(JadeStates.FLUID, readBar.children().getFirst().kind());
        assertEquals(5, read.root().lines().get(1).elements().getFirst().number(2, 0), "five hearts");
        assertEquals(7.5F, read.root().lines().get(1).elements().getFirst().fraction(0, 0.0F));
        assertEquals("someplugin.client.GaugeElement", read.root().lines().get(2).elements().get(1).text(0), "a plugin's element is named");
        assertEquals(0.6F, read.breakingFade());
        assertEquals(0.42F, read.breaking());
        assertTrue(read.square());
        assertEquals(1, read.iconMode());
        assertEquals(0.25F, read.root().fraction(0, 0.0F), "the box's own progress");
    }

    @Test
    void theBoxKeepsToItsAnchorOnAnotherSizeOfScreen() {
        // Top middle, as Jade puts it by default: the same distance from the middle across, the same from the top
        JadeStates.Box top = new JadeStates.Box(381, 4, 92, 52, 1.0F, 1.0F, 854, 480, 0.5F, 0.0F, false, 0.7F, 0, "jade:dark", "", 0.0F, 0.0F, true, chest());
        assertEquals(381, top.xOn(854));
        assertEquals(381 + 533, top.xOn(1920));
        assertEquals(4, top.yOn(1080));
        // Bottom left: kept to the left edge, and as far above the bottom as it was
        JadeStates.Box corner = new JadeStates.Box(2, 420, 92, 52, 1.0F, 1.0F, 854, 480, 0.0F, 1.0F, false, 0.7F, 0, "jade:dark", "", 0.0F, 0.0F, true, chest());
        assertEquals(2, corner.xOn(1920));
        assertEquals(420 + 600, corner.yOn(1080));
    }

    @Test
    void aChangedBoxWritesOtherBytes() {
        byte[] before = JadeStates.box(box(chest()));
        JadeStates.Box faded = new JadeStates.Box(380, 4, 92, 52, 1.0F, 0.9F, 854, 480, 0.5F, 0.0F, false, 0.7F, 0, "jade:dark", "ab".repeat(32), 0.0F, 0.0F,
            true, chest());
        assertNotEquals(box(chest()), faded);
        assertFalse(java.util.Arrays.equals(before, JadeStates.box(faded)), "a box fading in is written again");
    }

    @Test
    void anElementsListsAreCopied() {
        int[] ints = {4, 4, 4, 4, -1, 0};
        float[] floats = {0.0F, 1.0F, 2.0F};
        JadeStates.Element made = element(JadeStates.BOX, 1.0F, 1.0F, "", List.of(STYLE), ints, floats, List.of(), List.of());
        ints[0] = 99;
        floats[0] = 99.0F;
        assertEquals(4, made.number(0, 0));
        assertEquals(0.0F, made.fraction(0, 1.0F));
        made.ints()[1] = 99;
        assertEquals(4, made.number(1, 0), "what the record hands out is a copy");
        assertEquals(-7, made.number(9, -7), "past the end, the default");
        assertEquals(2.5F, made.fraction(-1, 2.5F));
        assertEquals("", made.text(3));
    }

    @Test
    void aDamagedRecordIsRefused() {
        byte[] whole = JadeStates.box(box(chest()));
        assertThrows(IllegalArgumentException.class, () -> JadeStates.readBox(java.util.Arrays.copyOf(whole, whole.length / 2)));
        assertThrows(IllegalArgumentException.class, () -> JadeStates.readBox(new byte[0]));
        // A list claiming more entries than any box has
        byte[] huge = whole.clone();
        int textsAt = rootTextsAt(whole);
        huge[textsAt] = 0x7F;
        assertThrows(IllegalArgumentException.class, () -> JadeStates.readBox(huge));
    }

    @Test
    void elementsNestedTooDeepAreRefused() {
        JadeStates.Element deep = text("{\"text\":\"x\"}");
        for (int i = 0; i <= JadeStates.DEEPEST; i++) {
            deep = element(JadeStates.COMPOUND, 1.0F, 1.0F, "", List.of(), new int[0], new float[0], List.of(deep), List.of());
        }
        JadeStates.Box box = box(deep);
        assertThrows(RuntimeException.class, () -> JadeStates.box(box));
    }

    @Test
    void whatALaterVersionAddsAtTheEndIsLeftUnread() throws IOException {
        byte[] whole = JadeStates.box(box(chest()));
        ByteArrayOutputStream later = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(later)) {
            out.write(whole);
            out.writeInt(12345);
            out.writeUTF("a field of a later version");
        }
        assertEquals(box(chest()), JadeStates.readBox(later.toByteArray()));
    }

    /** Where the root element's count of texts is written: after the box's own fields, and the root's kind, side, place, size and tag */
    private static int rootTextsAt(byte[] whole) {
        int at = 4 * 4 + 4 + 4 + 4 + 4 + 4 + 4 + 1 + 4 + 1;
        at += 4 + "jade:dark".length();
        at += 4 + 64;
        at += 4 + 4 + 1;
        at += 1 + 1 + 4 * 4;
        at += 4 + "jade:root".length();
        return at;
    }

}
