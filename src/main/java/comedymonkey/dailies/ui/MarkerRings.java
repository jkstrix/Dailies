package comedymonkey.dailies.ui;

import comedymonkey.dailies.file.RecordingManifest;
import comedymonkey.dailies.playback.Playback;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Markers in the world (specification REC-32). A marker that stored where the recorder stood is shown, while its
 * recording is watched, as a ring on the ground at that spot in the marker's colour, with a short post standing in
 * it so that it can be found from far off and from the side. In the editor the pointer over a ring shows the
 * marker's description, and a click jumps to the tick it was dropped on. Rings are hidden by whatever stands in
 * front of them, can be switched off (the "showMarkersInWorld" setting, in the editor's Viewer panel), and are
 * never in an exported picture.
 */
public final class MarkerRings {

    private static final double RADIUS = 0.6;
    /** A ring never looks smaller than this many pixels across its radius, however far away it is */
    private static final double LEAST_PIXELS = 9.0;
    private static final double POST = 1.6;
    /** The post never looks shorter than this, and carries a diamond at its top: from far off that is what is seen */
    private static final double LEAST_POST_PIXELS = 34.0;
    private static final float HEAD_PIXELS = 5.0F;
    private static final int SIDES = 40;
    /** Just clear of the ground, so the ring does not flicker in and out of it */
    private static final double LIFT = 0.04;

    /**
     * A ring as it was last drawn.
     *
     * @param marker the marker's place in the recording's list
     * @param colour 0xRRGGBB
     * @param radius in blocks, as drawn
     */
    public record Ring(int marker, int tick, double x, double y, double z, int colour, String description, double radius, double post) {
    }

    private static volatile List<Ring> drawn = List.of();

    private MarkerRings() {
    }

    static boolean shown() {
        return EditorState.setting("showMarkersInWorld", true);
    }

    static void nothingDrawn() {
        drawn = List.of();
    }

    static void draw(WorldLines.Batch batch) {
        RecordingManifest manifest = Playback.manifest();
        Minecraft minecraft = Minecraft.getInstance();
        if (manifest == null || minecraft.level == null || !shown()) {
            drawn = List.of();
            return;
        }
        String dimension = minecraft.level.dimension().location().toString();
        List<Ring> rings = new ArrayList<>();
        for (int index = 0; index < manifest.markers.size(); index++) {
            RecordingManifest.Marker marker = manifest.markers.get(index);
            if (marker.position == null || marker.position.length < 3 || marker.dimension != null && !marker.dimension.equals(dimension)) {
                continue;
            }
            double x = marker.position[0];
            double y = marker.position[1] + LIFT;
            double z = marker.position[2];
            double radius = Math.max(RADIUS, batch.blocksFor(LEAST_PIXELS, x, y, z));
            int colour = marker.colour & 0xFFFFFF;
            List<Vec3> circle = new ArrayList<>(SIDES + 1);
            for (int side = 0; side <= SIDES; side++) {
                double angle = Math.PI * 2.0 * side / SIDES;
                circle.add(new Vec3(x + Math.cos(angle) * radius, y, z + Math.sin(angle) * radius));
            }
            batch.line(circle, 3.0F, colour, 1.0F, false);
            double post = post(batch, x, y, z);
            batch.segment(new Vec3(x, y, z), new Vec3(x, y + post, z), 2.5F, colour, 1.0F);
            batch.diamond(new Vec3(x, y + post, z), HEAD_PIXELS, colour, 1.0F);
            rings.add(new Ring(index, marker.tick, x, y, z, colour, marker.description == null ? "" : marker.description, radius, post));
        }
        drawn = List.copyOf(rings);
    }

    private static double post(WorldLines.Batch batch, double x, double y, double z) {
        return Math.max(POST, batch.blocksFor(LEAST_POST_PIXELS, x, y, z));
    }

    /** The rings drawn in the last frame; none when markers are switched off, hidden, or an export is drawing */
    public static List<Ring> drawn() {
        return drawn;
    }

    /** The ring a line of sight meets first, or null. Never one behind a block */
    static @Nullable Ring at(@Nullable ViewPick.Ray ray) {
        if (ray == null) {
            return null;
        }
        Ring nearest = null;
        double best = Double.MAX_VALUE;
        for (Ring ring : drawn) {
            double reach = ring.radius() * 1.25;
            double distance = -1.0;
            // The ring lies flat: where the line of sight comes down through it
            if (Math.abs(ray.direction().y) > 1.0e-6) {
                double along = (ring.y() - ray.from().y) / ray.direction().y;
                if (along > 0.0) {
                    Vec3 met = ray.at(along);
                    double dx = met.x - ring.x();
                    double dz = met.z - ring.z();
                    if (dx * dx + dz * dz <= reach * reach) {
                        distance = along;
                    }
                }
            }
            if (distance < 0.0) {
                // Seen from the side: the post, taken as the ball round its middle
                Vec3 middle = new Vec3(ring.x(), ring.y() + ring.post() / 2.0, ring.z());
                double along = middle.subtract(ray.from()).dot(ray.direction());
                if (along > 0.0 && ray.at(along).distanceTo(middle) <= Math.max(reach, ring.post() / 2.0)) {
                    distance = along;
                }
            }
            if (distance >= 0.0 && distance < best) {
                best = distance;
                nearest = ring;
            }
        }
        if (nearest != null) {
            BlockHitResult wall = ViewPick.block(ray);
            if (wall != null && wall.getLocation().distanceTo(ray.from()) < best - 0.75) {
                return null;
            }
        }
        return nearest;
    }

    /** Where the middle of a ring is on the screen that is open, in that screen's own measure: {x, y}, or null when it is behind the camera */
    public static double @Nullable [] onScreen(Ring ring) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen == null) {
            return null;
        }
        return WorldLines.onScreen(new Vec3(ring.x(), ring.y(), ring.z()), minecraft.screen.width, minecraft.screen.height);
    }

    /** What the pointer over a ring says: the description, when there is one, and the moment it marks */
    public static Component describe(Ring ring) {
        String time = Controls.time(ring.tick());
        return ring.description().isBlank() ? Kit.t("marker.tip", time) : Kit.t("marker.tip.described", ring.description(), time);
    }

}
