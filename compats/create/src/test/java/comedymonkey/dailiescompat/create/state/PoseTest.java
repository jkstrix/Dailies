package comedymonkey.dailiescompat.create.state;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A value box's place on its block survives being recorded: the transform Create's slot gave, read out of the game's
 * matrix as the compat reads it, written, read back and made a matrix again, puts the box's corner in the same spot.
 */
class PoseTest {

    @Test
    void aSlotsTransformComesBackAsTheSameMatrix() {
        // As a sided slot on a block's top: moved to the face, turned to face up, made half size
        Matrix4f placed = new Matrix4f().translate(0.5F, 0.78125F, 0.5625F).rotateY((float) Math.toRadians(180)).rotateX((float) Math.toRadians(90))
            .scale(0.5F);
        float[] pose = new float[16];
        placed.get(pose);
        States.Box box = new States.Box(0L, pose, 1 / 64.0F, -1, false, "VALUE_BOX_HOVER_6PX", States.TEXT, "{\"text\":\"64\"}", "", "", null);
        States.Box read = States.readBoxes(States.boxes(List.of(box))).get(0);
        Matrix4f again = new Matrix4f().set(read.pose());
        for (Vector3f corner : List.of(new Vector3f(), new Vector3f(1, 0, 0), new Vector3f(0, 1, 0), new Vector3f(-0.5F, 0.25F, 2))) {
            Vector3f expected = placed.transformPosition(new Vector3f(corner));
            Vector3f actual = again.transformPosition(new Vector3f(corner));
            assertEquals(expected.x, actual.x, 1e-6);
            assertEquals(expected.y, actual.y, 1e-6);
            assertEquals(expected.z, actual.z, 1e-6);
        }
    }

}
