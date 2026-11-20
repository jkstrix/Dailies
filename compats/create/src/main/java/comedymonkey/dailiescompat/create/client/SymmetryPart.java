package comedymonkey.dailiescompat.create.client;

import com.simibubi.create.content.equipment.symmetryWand.SymmetryWandScreen;
import com.simibubi.create.content.equipment.symmetryWand.mirror.CrossPlaneMirror;
import com.simibubi.create.content.equipment.symmetryWand.mirror.PlaneMirror;
import com.simibubi.create.content.equipment.symmetryWand.mirror.SymmetryMirror;
import com.simibubi.create.content.equipment.symmetryWand.mirror.TriplePlaneMirror;
import comedymonkey.dailiescompat.create.state.States;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

/**
 * The wand of symmetry's screen, opened by using the wand while sneaking: which mirror it places and how it is turned,
 * sent when the screen closes. Recorded as those two, and drawn with Create's own screen on a wand of its own, which
 * never sends anything. Where the mirror stands is not part of the screen.
 */
final class SymmetryPart {

    static final String NAME = "symmetry_wand";

    private SymmetryPart() {
    }

    static byte[] capture(Screen screen) {
        SymmetryMirror mirror = Reflect.get(screen, "currentElement");
        int kind = mirror instanceof TriplePlaneMirror ? 2 : mirror instanceof CrossPlaneMirror ? 1 : 0;
        return States.symmetry(new States.Symmetry(kind, mirror == null ? 0 : mirror.getOrientationIndex()));
    }

    static Screen build(byte[] state) {
        return new Recorded(States.readSymmetry(state));
    }

    static final class Recorded extends SymmetryWandScreen implements RecordedScreen {
        Recorded(States.Symmetry recorded) {
            super(Texts.stack("create:wand_of_symmetry"), InteractionHand.MAIN_HAND);
            // Before the screen lays itself out from it
            SymmetryMirror mirror = switch (recorded.mirror()) {
                case 1 -> new CrossPlaneMirror(Vec3.ZERO);
                case 2 -> new TriplePlaneMirror(Vec3.ZERO);
                default -> new PlaneMirror(Vec3.ZERO);
            };
            mirror.setOrientation(recorded.orientation());
            Reflect.set(this, "currentElement", mirror);
        }

        @Override
        public void removed() {
            // Never sends the mirror
        }
    }

}
