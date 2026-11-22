package comedymonkey.dailies.ui;

import comedymonkey.dailies.edit.Evaluator;
import comedymonkey.dailies.edit.Interpolation;
import comedymonkey.dailies.edit.Keyframe;
import comedymonkey.dailies.edit.LinkedRecording;
import comedymonkey.dailies.edit.Scene;
import comedymonkey.dailies.editor.EditorSession;
import comedymonkey.dailies.editor.PointOfView;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The scene's points of view as lanes on the timeline (PLAY-19, EDIT-11): one for the scene's own recording and one
 * for each linked recording, each showing where that recording has footage, and brightest where the scene shows it.
 * Clicking a lane puts a point of view keyframe there, showing that recording from that tick. A scene without linked
 * recordings has no lanes.
 */
final class PointOfViewLanes {

    static final int LANE = 10;

    private PointOfViewLanes() {
    }

    /** The scene's own recording first (null), then each linked one */
    private static List<@Nullable LinkedRecording> lanes(Scene scene) {
        List<LinkedRecording> lanes = new ArrayList<>();
        if (!scene.linked().isEmpty()) {
            lanes.add(null);
            lanes.addAll(scene.linked());
        }
        return lanes;
    }

    static int height() {
        if (!EditorSession.open() || EditorState.timelineFolded) {
            return 0;
        }
        int count = lanes(EditorState.scene()).size();
        return count == 0 ? 0 : count * LANE + 2;
    }

    /** The lane at this height, its index among {@link #lanes}, or -1 */
    private static int laneAt(int top, double mouseY, int count) {
        if (mouseY < top || mouseY >= top + count * LANE) {
            return -1;
        }
        return (int) ((mouseY - top) / LANE);
    }

    private static String idOf(@Nullable LinkedRecording lane) {
        return lane == null ? String.valueOf(PointOfView.homeId()) : lane.recordingId();
    }

    private static Component nameOf(@Nullable LinkedRecording lane) {
        return lane == null ? Kit.t("pov.own") : Component.literal(lane.label());
    }

    /**
     * @param across turns a tick into a position along the lanes
     */
    static void draw(GuiGraphics graphics, Font font, int columnLeft, int laneLeft, int laneRight, int top, int total, java.util.function.DoubleUnaryOperator across) {
        Scene scene = EditorState.scene();
        List<LinkedRecording> lanes = lanes(scene);
        if (lanes.isEmpty()) {
            return;
        }
        String home = PointOfView.homeId();
        List<Evaluator.Switch> switches = Evaluator.pointOfViewSwitches(scene, 0, total, home);
        for (int index = 0; index < lanes.size(); index++) {
            LinkedRecording lane = lanes.get(index);
            int y = top + index * LANE;
            graphics.fill(laneLeft, y, laneRight + 1, y + LANE - 1, index % 2 == 0 ? 0x40000000 : 0x28000000);
            Kit.text(graphics, font, Kit.clip(font, nameOf(lane).getString(), laneLeft - columnLeft - 8), columnLeft + 4, y + 1,
                    String.valueOf(PointOfView.shownId()).equals(idOf(lane)) ? Controls.BRASS_BRIGHT : Controls.PARCHMENT_DIM);
            // Where this recording has footage, in the scene's ticks
            for (int[] stretch : PointOfView.coverage(idOf(lane))) {
                int x0 = (int) Math.round(across.applyAsDouble(stretch[0]));
                int x1 = (int) Math.round(across.applyAsDouble(stretch[1]));
                graphics.fill(Math.max(laneLeft, x0), y + 3, Math.min(laneRight + 1, Math.max(x0 + 1, x1)), y + LANE - 4, 0x70C9A75A);
            }
            // Where the scene shows it
            String wanted = lane == null ? null : lane.recordingId();
            int from = 0;
            String shown = null;
            for (int s = 0; s <= switches.size(); s++) {
                int to = s < switches.size() ? switches.get(s).tick() : total;
                if (java.util.Objects.equals(shown, wanted) && to > from) {
                    int x0 = (int) Math.round(across.applyAsDouble(from));
                    int x1 = (int) Math.round(across.applyAsDouble(to));
                    graphics.fill(Math.max(laneLeft, x0), y + 2, Math.min(laneRight + 1, Math.max(x0 + 1, x1)), y + LANE - 3, Controls.BRASS_BRIGHT);
                }
                if (s < switches.size()) {
                    shown = switches.get(s).recordingId();
                    from = switches.get(s).tick();
                }
            }
        }
    }

    static boolean over(int laneLeft, int laneRight, int top, double mouseX, double mouseY) {
        int count = lanes(EditorState.scene()).size();
        return count > 0 && mouseX >= laneLeft - 2 && mouseX <= laneRight + 2 && laneAt(top, mouseY, count) >= 0;
    }

    /** A click on a lane: from this tick the scene shows that recording */
    static void press(int top, double mouseY, int tick) {
        Scene scene = EditorState.scene();
        List<LinkedRecording> lanes = lanes(scene);
        int index = laneAt(top, mouseY, lanes.size());
        String home = PointOfView.homeId();
        if (index < 0 || home == null) {
            return;
        }
        LinkedRecording lane = lanes.get(index);
        String id = idOf(lane);
        EditorState.history().setKeyframe(new Keyframe.PointOfView(tick, Interpolation.HOLD, id, nameOf(lane).getString()));
    }

    static @Nullable Component tipAt(int top, double mouseY) {
        List<LinkedRecording> lanes = lanes(EditorState.scene());
        int index = laneAt(top, mouseY, lanes.size());
        return index < 0 ? null : Kit.t("pov.lane.tip", nameOf(lanes.get(index)));
    }

}
