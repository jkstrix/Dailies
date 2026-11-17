package comedymonkey.dailies.ui;

import comedymonkey.dailies.playback.Viewer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

import java.util.List;

/**
 * Builds the small "Viewer" strip of the editor (specification PLAY-2 and PLAY-3): how fast the camera flies, its
 * field of view and roll with a way back to the usual, riding along with an entity and leaving it again, and the two
 * flight settings.
 */
final class ViewerForms {

    private ViewerForms() {
    }

    static void build(Form form) {
        form.clear();
        form.add(new Gadget.Note(Kit.t("viewer.how")));
        form.add(new Gadget.Pair(Kit.t("viewer.speed"), new Gadget.Slide(0.25, 400.0, true, Viewer::blocksPerSecond, Viewer::setBlocksPerSecond,
                Viewer::setBlocksPerSecond, value -> Kit.t("viewer.speed.value", Kit.number(value)).getString())).tip(Kit.t("viewer.speed.tip")));
        form.add(new Gadget.Pair(Kit.t("viewer.fov"), new Gadget.Line(
                new Gadget.Slide(10.0, 150.0, false, () -> Viewer.fov() > 0.0 ? Viewer.fov() : ViewPick.fieldOfView(), Viewer::setFov, Viewer::setFov,
                        value -> Kit.t("viewer.degrees", Kit.number(Math.rint(value))).getString()),
                Gadget.Push.icon(Kit.Icon.CROSS, Kit.t("viewer.fov.reset"), () -> Viewer.setFov(0.0)))).tip(Kit.t("viewer.fov.tip")));
        form.add(new Gadget.Pair(Kit.t("viewer.roll"), new Gadget.Line(
                new Gadget.Slide(-180.0, 180.0, false, Viewer::roll, value -> Viewer.setRoll((float) value), value -> Viewer.setRoll((float) value),
                        value -> Kit.t("viewer.degrees", Kit.number(Math.rint(value))).getString()),
                Gadget.Push.icon(Kit.Icon.CROSS, Kit.t("viewer.roll.reset"), () -> Viewer.setRoll(0.0F)))).tip(Kit.t("viewer.roll.tip")));
        form.add(new Gadget.Note(() -> {
            Entity ridden = Viewer.riding();
            return ridden == null ? Component.empty() : Kit.t("viewer.riding", ridden.getName().getString());
        }, Controls.PARCHMENT));
        form.add(new Gadget.Line(
                new Gadget.Push(Kit.t("viewer.ride"), () -> {
                    EditorState.pick = new EditorState.Pick(Kit.t("pick.ride"), false, false, Viewer::ride, null, List.of());
                    EditorState.sideFolded = false;
                }).tip(Kit.t("viewer.ride.tip")),
                new Gadget.Push(Kit.t("viewer.leave"), () -> Viewer.ride(null)).when(() -> Viewer.riding() != null).tip(Kit.t("viewer.leave.tip"))));
        form.add(new Gadget.Check(Kit.t("viewer.path"), () -> EditorState.setting("showCameraPath", true),
                on -> EditorState.saveSetting("showCameraPath", on)).tip(Kit.t("viewer.path.tip")));
        form.add(new Gadget.Check(Kit.t("viewer.markers"), () -> EditorState.setting("showMarkersInWorld", true),
                on -> EditorState.saveSetting("showMarkersInWorld", on)).tip(Kit.t("viewer.markers.tip")));
        form.add(new Gadget.Check(Kit.t("viewer.momentum"), () -> EditorState.setting("flightMomentum", true),
                on -> EditorState.saveSetting("flightMomentum", on)).tip(Kit.t("viewer.momentum.tip")));
        form.add(new Gadget.Check(Kit.t("viewer.look_relative"), () -> EditorState.setting("flightLookRelative", false),
                on -> EditorState.saveSetting("flightLookRelative", on)).tip(Kit.t("viewer.look_relative.tip")));
    }

}
