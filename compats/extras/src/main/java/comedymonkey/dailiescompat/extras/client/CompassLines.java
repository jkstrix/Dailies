package comedymonkey.dailiescompat.extras.client;

import comedymonkey.dailies.api.DailiesApi;
import comedymonkey.dailiescompat.extras.state.ExtrasStates;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The lines Explorer's Compass and Nature's Compass show in a corner while one is held: searching, with the structure or
 * biome and the radius; found, with where and how far; or not found. Each works its lines out from the held compass and
 * the player's place as it draws, so the lines recorded are the ones it drew: while its layer is drawn, the compat's
 * mixins note each string with where it went ({@link ExtrasMarks}). In playback they are drawn as the compasses draw
 * them, with a shadow, from the same side and as far down, and the compasses' own layers, which would work theirs out
 * from the watcher's game, are held back.
 */
final class CompassLines {

    static final String EXPLORERS = "explorers_compass";
    static final String NATURES = "natures_compass";

    private static final Identifier[] LAYERS = {Identifier.fromNamespaceAndPath("explorerscompass", "explorers_compass"),
        Identifier.fromNamespaceAndPath("naturescompass", "natures_compass")};

    /** What each compass's layer drew last, and whether it has drawn since the last tick was recorded */
    private static final List<List<ExtrasStates.Line>> last = new ArrayList<>(List.of(List.of(), List.of()));
    private static final boolean[] drawnSinceTick = new boolean[2];
    private static final int[] ticksUndrawn = new int[2];

    private CompassLines() {
    }

    private static int compass(Identifier layer) {
        for (int i = 0; i < LAYERS.length; i++) {
            if (LAYERS[i].equals(layer)) {
                return i;
            }
        }
        return -1;
    }

    // --- Recording ---

    static void layerStarts(RenderGuiLayerEvent.Pre event) {
        int compass = compass(event.getName());
        if (compass < 0) {
            return;
        }
        if (DailiesApi.isPlayback()) {
            // The compass would work its lines out from the watcher's game: the recorder's come from the recording
            event.setCanceled(true);
            return;
        }
        ExtrasMarks.layerStarts(compass);
    }

    static void layerEnds(RenderGuiLayerEvent.Post event) {
        int compass = compass(event.getName());
        if (compass < 0) {
            return;
        }
        List<ExtrasMarks.Line> drawn = ExtrasMarks.layerEnds(compass);
        int width = event.getGuiGraphics().guiWidth();
        List<ExtrasStates.Line> lines = new ArrayList<>(drawn.size());
        for (ExtrasMarks.Line line : drawn) {
            // A line drawn from its right end keeps its distance from the screen's right edge
            lines.add(new ExtrasStates.Line(line.text(), line.right() ? width - line.x() : line.x(), line.y(), line.colour(), line.right()));
        }
        last.set(compass, lines);
        drawnSinceTick[compass] = true;
    }

    static byte @Nullable [] captureExplorers() {
        return capture(ExtrasMarks.EXPLORERS);
    }

    static byte @Nullable [] captureNatures() {
        return capture(ExtrasMarks.NATURES);
    }

    private static byte @Nullable [] capture(int compass) {
        // A layer that stops being drawn (the HUD hidden, a world left) stops showing: a frame or two may pass between ticks
        if (drawnSinceTick[compass]) {
            drawnSinceTick[compass] = false;
            ticksUndrawn[compass] = 0;
        } else if (++ticksUndrawn[compass] > 2) {
            last.set(compass, List.of());
        }
        List<ExtrasStates.Line> lines = last.get(compass);
        return lines.isEmpty() ? null : ExtrasStates.lines(new ExtrasStates.Lines(lines));
    }

    // --- Playback ---

    /** As the compasses' RenderUtils draw each string: with a shadow, from its left end or its right end */
    static void draw(GuiGraphicsExtractor graphics, byte[] state, float partialTick) {
        Font font = Minecraft.getInstance().font;
        for (ExtrasStates.Line line : ExtrasStates.readLines(state).lines()) {
            int x = line.right() ? graphics.guiWidth() - line.fromSide() - font.width(line.text()) : line.fromSide();
            graphics.text(font, line.text(), x, line.y(), line.colour(), true);
        }
    }

}
