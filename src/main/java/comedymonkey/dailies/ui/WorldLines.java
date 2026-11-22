package comedymonkey.dailies.ui;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.export.Export;
import comedymonkey.dailies.playback.Playback;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Draws what the editor shows in the world itself and not over it: the camera's path (specification EDIT-8) and the
 * rings where markers were dropped (REC-32). Both are lines, and lines in a world are hard to read: the game's own
 * are a fixed size in blocks, so a hair far away and a plank close by. These are a fixed width on the screen
 * however far off they are, with a soft edge so that they do not crawl, and a dark border under a bright middle so
 * that they read against a noon sky and in a cave alike. They are hidden by whatever stands in front of them, they
 * thin out with distance, and they are never drawn while an export is taking pictures.
 */
final class WorldLines {

    /** Points nearer the lens than this are cut off */
    private static final double NEAR = 0.08;
    /** Lines keep their full strength up to here, then fade */
    private static final double FADE_FROM = 48.0;
    private static final double FADE_TO = 192.0;
    private static final float FAINTEST = 0.3F;
    private static final int BORDER = 0x101010;
    /** How far the dark border reaches past the bright middle, and the soft edge past that, in pixels */
    private static final float BORDER_PIXELS = 1.25F;
    private static final float SOFT_PIXELS = 1.0F;

    private static boolean failureLogged = false;
    /** The frame's corners on their way to the card, and the buffer on the card they go into; both kept and grown */
    private static @Nullable ByteBufferBuilder corners = null;
    private static @Nullable GpuBuffer vertexBuffer = null;

    private WorldLines() {
    }

    static void init() {
        // After everything of the world, where the picture and its depths are whole, whatever the graphics settings
        NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent.AfterLevel event) ->
            draw(Minecraft.getInstance().gameRenderer.mainCamera(), event.getModelViewMatrix()));
    }

    /** Whether anything of Dailies may be drawn into the world just now */
    static boolean allowed() {
        Minecraft minecraft = Minecraft.getInstance();
        return Playback.loaded() && !Export.active() && !Notices.hidden() && !comedymonkey.dailies.ui.HudHidden.get() && minecraft.level != null;
    }

    private static void draw(Camera camera, Matrix4fc modelView) {
        if (!allowed()) {
            MarkerRings.nothingDrawn();
            PathPreview.nothingDrawn();
            return;
        }
        try {
            Batch batch = new Batch(camera);
            MarkerRings.draw(batch);
            PathPreview.draw(batch);
            batch.finish(modelView);
        } catch (RuntimeException | LinkageError e) {
            if (!failureLogged) {
                failureLogged = true;
                Host.LOGGER.error("Dailies: the camera path and marker rings could not be drawn. Said once", e);
            }
        }
    }

    // ------------------------------------------------------------------ Where things are on the screen

    /** How many blocks one pixel covers, one block in front of the lens */
    static double blocksPerPixel() {
        Minecraft minecraft = Minecraft.getInstance();
        double tangent = Math.tan(Math.toRadians(Math.max(1.0, Math.min(170.0, ViewPick.fieldOfView())) / 2.0));
        double[] shift = comedymonkey.dailies.looks.LooksRenderer.viewShift();
        return 2.0 * tangent / Math.max(1, minecraft.getWindow().getHeight()) / (shift == null ? 1.0 : shift[0]);
    }

    /** Lines are drawn a little heavier on a large window, so that they look the same on it */
    static float pixelScale() {
        return Math.max(1.0F, Minecraft.getInstance().getWindow().getHeight() / 1080.0F);
    }

    /**
     * Where a point of the world is on the screen, in the measure a screen of the given size uses: the opposite of
     * {@link ViewPick#through}.
     *
     * @return {x, y, distance in front of the lens}, or null when the point is behind the lens
     */
    static double @Nullable [] onScreen(Vec3 point, int screenWidth, int screenHeight) {
        Minecraft minecraft = Minecraft.getInstance();
        Camera camera = minecraft.gameRenderer.mainCamera();
        if (!camera.isInitialized()) {
            return null;
        }
        Vec3 from = point.subtract(camera.position());
        Vector3fc look = camera.forwardVector();
        Vector3fc above = camera.upVector();
        Vector3fc left = camera.leftVector();
        double ahead = from.x * look.x() + from.y * look.y() + from.z * look.z();
        if (ahead < NEAR) {
            return null;
        }
        double tangent = Math.tan(Math.toRadians(Math.max(1.0, Math.min(170.0, ViewPick.fieldOfView())) / 2.0));
        double aspect = (double) minecraft.getWindow().getWidth() / Math.max(1, minecraft.getWindow().getHeight());
        double sideways = (from.x * left.x() + from.y * left.y() + from.z * left.z()) / ahead;
        double upwards = (from.x * above.x() + from.y * above.y() + from.z * above.z()) / ahead;
        double across = -sideways / (tangent * aspect);
        double up = upwards / tangent;
        // A picture held to its frame is shown smaller and to one side, clear of the panels
        double[] shift = comedymonkey.dailies.looks.LooksRenderer.viewShift();
        if (shift != null) {
            across = across * shift[0] + shift[1];
            up = up * shift[0] + shift[2];
        }
        return new double[]{(across + 1.0) / 2.0 * screenWidth, (1.0 - up) / 2.0 * screenHeight, ahead};
    }

    // ------------------------------------------------------------------ A frame's worth of lines

    /**
     * Collects lines and marks for one frame and draws them together: every dark border first, then every bright
     * middle, so that where two lines cross neither border cuts through the other's middle.
     */
    static final class Batch {
        private final Vec3 eye;
        private final double lookX;
        private final double lookY;
        private final double lookZ;
        private final double perPixel;
        private final float scale;
        private final FloatList borders = new FloatList();
        private final FloatList middles = new FloatList();

        Batch(Camera camera) {
            this.eye = camera.position();
            Vector3fc look = camera.forwardVector();
            this.lookX = look.x();
            this.lookY = look.y();
            this.lookZ = look.z();
            this.perPixel = blocksPerPixel();
            this.scale = pixelScale();
        }

        Vec3 eye() {
            return this.eye;
        }

        /** How far in front of the lens a point of the world is; negative behind it */
        double ahead(double x, double y, double z) {
            return (x - this.eye.x) * this.lookX + (y - this.eye.y) * this.lookY + (z - this.eye.z) * this.lookZ;
        }

        /** The size in blocks of something that should look this many pixels large at this point */
        double blocksFor(double pixels, double x, double y, double z) {
            return pixels * this.scale * this.perPixel * Math.max(NEAR, this.ahead(x, y, z));
        }

        /**
         * A line through the points, in order.
         *
         * @param pixels the width of the bright middle
         * @param colour 0xRRGGBB
         * @param dashed whether every other stretch between two points is left out
         */
        void line(List<Vec3> points, float pixels, int colour, float alpha, boolean dashed) {
            for (int i = 0; i + 1 < points.size(); i++) {
                if (!dashed || i % 2 == 0) {
                    this.segment(points.get(i), points.get(i + 1), pixels, colour, alpha);
                }
            }
        }

        void segment(Vec3 from, Vec3 to, float pixels, int colour, float alpha) {
            double ax = from.x - this.eye.x;
            double ay = from.y - this.eye.y;
            double az = from.z - this.eye.z;
            double bx = to.x - this.eye.x;
            double by = to.y - this.eye.y;
            double bz = to.z - this.eye.z;
            double aheadA = ax * this.lookX + ay * this.lookY + az * this.lookZ;
            double aheadB = bx * this.lookX + by * this.lookY + bz * this.lookZ;
            if (aheadA < NEAR && aheadB < NEAR) {
                return;
            }
            // The part behind the lens is cut off, or its width would turn inside out
            if (aheadA < NEAR) {
                double share = (NEAR - aheadA) / (aheadB - aheadA);
                ax += (bx - ax) * share;
                ay += (by - ay) * share;
                az += (bz - az) * share;
                aheadA = NEAR;
            } else if (aheadB < NEAR) {
                double share = (NEAR - aheadB) / (aheadA - aheadB);
                bx += (ax - bx) * share;
                by += (ay - by) * share;
                bz += (az - bz) * share;
                aheadB = NEAR;
            }
            // Sideways on the screen: square to the line and to the line of sight at its middle
            double mx = (ax + bx) / 2.0;
            double my = (ay + by) / 2.0;
            double mz = (az + bz) / 2.0;
            double dx = bx - ax;
            double dy = by - ay;
            double dz = bz - az;
            double sx = dy * mz - dz * my;
            double sy = dz * mx - dx * mz;
            double sz = dx * my - dy * mx;
            double length = Math.sqrt(sx * sx + sy * sy + sz * sz);
            if (length < 1.0e-9) {
                // Seen end on: a dot would be all there is of it
                return;
            }
            sx /= length;
            sy /= length;
            sz /= length;

            float half = pixels * this.scale / 2.0F;
            float border = half + BORDER_PIXELS * this.scale;
            float soft = SOFT_PIXELS * this.scale;
            float fadeA = fade(aheadA) * alpha;
            float fadeB = fade(aheadB) * alpha;
            double unitA = this.perPixel * aheadA;
            double unitB = this.perPixel * aheadB;
            float borderAlpha = 0.8F;
            this.strips(this.borders, ax, ay, az, bx, by, bz, sx, sy, sz, unitA, unitB, border, soft, BORDER, fadeA * borderAlpha, fadeB * borderAlpha);
            this.strips(this.middles, ax, ay, az, bx, by, bz, sx, sy, sz, unitA, unitB, half, soft * 0.75F, colour, fadeA, fadeB);
        }

        /** A band along a line: solid out to {@code half} pixels either side, then thinning to nothing over {@code soft} more */
        private void strips(FloatList into, double ax, double ay, double az, double bx, double by, double bz, double sx, double sy, double sz, double unitA, double unitB,
                            float half, float soft, int colour, float alphaA, float alphaB) {
            float[] offsets = {-(half + soft), -half, half, half + soft};
            float[] strength = {0.0F, 1.0F, 1.0F, 0.0F};
            for (int strip = 0; strip < 3; strip++) {
                double a0 = offsets[strip] * unitA;
                double a1 = offsets[strip + 1] * unitA;
                double b0 = offsets[strip] * unitB;
                double b1 = offsets[strip + 1] * unitB;
                into.vertex(ax + sx * a0, ay + sy * a0, az + sz * a0, colour, alphaA * strength[strip]);
                into.vertex(ax + sx * a1, ay + sy * a1, az + sz * a1, colour, alphaA * strength[strip + 1]);
                into.vertex(bx + sx * b1, by + sy * b1, bz + sz * b1, colour, alphaB * strength[strip + 1]);
                into.vertex(bx + sx * b0, by + sy * b0, bz + sz * b0, colour, alphaB * strength[strip]);
            }
        }

        /**
         * A diamond that faces the lens, the mark of a keyframe.
         *
         * @param pixels how far it reaches from its middle
         */
        void diamond(Vec3 at, float pixels, int colour, float alpha) {
            double x = at.x - this.eye.x;
            double y = at.y - this.eye.y;
            double z = at.z - this.eye.z;
            double ahead = x * this.lookX + y * this.lookY + z * this.lookZ;
            if (ahead < NEAR) {
                return;
            }
            Camera camera = Minecraft.getInstance().gameRenderer.mainCamera();
            Vector3fc up = camera.upVector();
            Vector3fc left = camera.leftVector();
            double unit = this.perPixel * ahead * this.scale;
            float strength = fade(ahead) * alpha;
            this.facing(this.borders, x, y, z, up, left, unit * (pixels + BORDER_PIXELS * 1.5F), BORDER, strength * 0.85F);
            this.facing(this.middles, x, y, z, up, left, unit * pixels, colour, strength);
        }

        private void facing(FloatList into, double x, double y, double z, Vector3fc up, Vector3fc left, double reach, int colour, float alpha) {
            into.vertex(x + up.x() * reach, y + up.y() * reach, z + up.z() * reach, colour, alpha);
            into.vertex(x + left.x() * reach, y + left.y() * reach, z + left.z() * reach, colour, alpha);
            into.vertex(x - up.x() * reach, y - up.y() * reach, z - up.z() * reach, colour, alpha);
            into.vertex(x - left.x() * reach, y - left.y() * reach, z - left.z() * reach, colour, alpha);
        }

        private static float fade(double ahead) {
            if (ahead <= FADE_FROM) {
                return 1.0F;
            }
            return (float) Math.max(FAINTEST, 1.0 - (ahead - FADE_FROM) / (FADE_TO - FADE_FROM) * (1.0 - FAINTEST));
        }

        void finish(Matrix4fc modelView) {
            int count = this.borders.size + this.middles.size;
            if (count == 0) {
                return;
            }
            if (corners == null) {
                corners = new ByteBufferBuilder(DefaultVertexFormat.POSITION_COLOR.getVertexSize() * Math.max(4096, count));
            }
            BufferBuilder builder = new BufferBuilder(corners, PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_COLOR);
            // Every border before every middle, in one draw: the card draws them in the order given
            this.borders.addTo(builder);
            this.middles.addTo(builder);
            try (MeshData mesh = builder.build()) {
                if (mesh != null) {
                    drawMesh(mesh, modelView);
                }
            }
        }
    }

    /**
     * Draws the frame's lines over the finished world: blended, both faces, hidden by the world and hiding nothing,
     * so that what is drawn after is not cut by a line's soft edge (the game's own see-through quads)
     */
    private static void drawMesh(MeshData mesh, Matrix4fc modelView) {
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        if (main.getColorTextureView() == null || main.getDepthTextureView() == null) {
            return;
        }
        ByteBuffer vertices = mesh.vertexBuffer();
        int size = vertices.remaining();
        GpuDevice device = RenderSystem.getDevice();
        if (vertexBuffer == null || vertexBuffer.size() < size) {
            if (vertexBuffer != null) {
                vertexBuffer.close();
            }
            long room = Long.highestOneBit(Math.max(65536L, size) - 1L) << 1;
            vertexBuffer = device.createBuffer(() -> "Dailies world lines", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, room);
        }
        CommandEncoder encoder = device.createCommandEncoder();
        GpuBufferSlice slice = vertexBuffer.slice(0L, size);
        encoder.writeToBuffer(slice, vertices);
        int indexCount = mesh.drawState().indexCount();
        RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        GpuBuffer indexBuffer = indices.getBuffer(indexCount);
        GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(modelView));
        try (RenderPass pass = encoder.createRenderPass(() -> "Dailies world lines", main.getColorTextureView(), Optional.empty(),
            main.getDepthTextureView(), OptionalDouble.empty())) {
            pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.DEBUG_QUADS));
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transforms);
            pass.setVertexBuffer(0, slice);
            pass.setIndexBuffer(indexBuffer, indices.type());
            pass.drawIndexed(indexCount, 1, 0, 0, 0);
        }
    }

    /** Corners waiting to be drawn: x, y, z, then the colour as one number */
    private static final class FloatList {
        private float[] values = new float[3072];
        private int[] colours = new int[1024];
        private int size = 0;

        void vertex(double x, double y, double z, int colour, float alpha) {
            if (this.size == this.colours.length) {
                this.values = java.util.Arrays.copyOf(this.values, this.values.length * 2);
                this.colours = java.util.Arrays.copyOf(this.colours, this.colours.length * 2);
            }
            this.values[this.size * 3] = (float) x;
            this.values[this.size * 3 + 1] = (float) y;
            this.values[this.size * 3 + 2] = (float) z;
            int strength = Math.max(0, Math.min(255, Math.round(alpha * 255.0F)));
            this.colours[this.size] = strength << 24 | colour & 0xFFFFFF;
            this.size++;
        }

        void addTo(BufferBuilder builder) {
            for (int i = 0; i < this.size; i++) {
                builder.addVertex(this.values[i * 3], this.values[i * 3 + 1], this.values[i * 3 + 2]).setColor(this.colours[i]);
            }
        }
    }

}
