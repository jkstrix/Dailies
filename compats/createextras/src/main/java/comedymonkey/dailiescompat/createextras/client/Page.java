package comedymonkey.dailiescompat.createextras.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import comedymonkey.dailiescompat.createextras.state.ExtrasStates;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * An overlay recorded as drawn. At the end of each recorded tick the mod's own drawing is run, in its own order, onto a
 * page of the compat's that draws nothing and notes each thing it is handed (a filled rectangle, part of a texture, a
 * sprite, a line of text), with the turn and place the mod's drawing had set; playback hands the same to the game again,
 * in the same order. So what a mod worked out from the recorder's game (a jetpack's air, a throttle's travel, a line being
 * typed) is never worked out again from the playback world or the watcher's player.
 * <p>
 * The page keeps the screen's size in GUI units as it was. Drawn on a screen of another size, the whole overlay is kept as
 * far from the edges it sat by (the left, right or middle; the top, foot or middle), and a rectangle that filled the whole
 * screen fills the whole of the new one.
 */
final class Page extends GuiGraphics {

    private final List<ExtrasStates.Op> ops = new ArrayList<>();

    Page() {
        super(Minecraft.getInstance(), Minecraft.getInstance().renderBuffers().bufferSource());
    }

    /** What was drawn on the page, or null when nothing was */
    byte @Nullable [] state() {
        if (this.ops.isEmpty()) {
            return null;
        }
        return ExtrasStates.drawn(new ExtrasStates.Drawn(this.guiWidth(), this.guiHeight(), this.ops));
    }

    private float[] turnAndPlace() {
        Matrix4f m = this.pose().last().pose();
        return new float[]{m.m00(), m.m01(), m.m10(), m.m11(), m.m30(), m.m31(), m.m32()};
    }

    private void note(int kind, String text, int... numbers) {
        this.ops.add(new ExtrasStates.Op(kind, this.turnAndPlace(), numbers, text));
    }

    /** Draws what is waiting to be drawn, on the game's GUI: the page has nothing waiting, and draws nothing */
    @Override
    public void flush() {
    }

    // --- What the page notes: the game's calls every other drawing call ends in ---

    @Override
    public void fill(RenderType type, int minX, int minY, int maxX, int maxY, int z, int colour) {
        this.note(ExtrasStates.FILL, "", minX, minY, maxX, maxY, z, colour);
    }

    @Override
    public void blit(ResourceLocation texture, int x, int y, int blitOffset, float u, float v, int width, int height, int textureWidth, int textureHeight) {
        this.note(ExtrasStates.BLIT, texture.toString(), x, y, width, height, Float.floatToIntBits(u), Float.floatToIntBits(v), width, height,
            textureWidth, textureHeight, blitOffset);
    }

    @Override
    public void blit(ResourceLocation texture, int x, int y, int width, int height, float u, float v, int uWidth, int vHeight, int textureWidth,
        int textureHeight) {
        this.note(ExtrasStates.BLIT, texture.toString(), x, y, width, height, Float.floatToIntBits(u), Float.floatToIntBits(v), uWidth, vHeight,
            textureWidth, textureHeight, 0);
    }

    @Override
    public void blitSprite(ResourceLocation sprite, int x, int y, int blitOffset, int width, int height) {
        this.note(ExtrasStates.SPRITE, sprite.toString(), x, y, width, height, blitOffset);
    }

    @Override
    public int drawString(Font font, @Nullable String text, int x, int y, int colour, boolean dropShadow) {
        if (text == null) {
            return 0;
        }
        this.note(ExtrasStates.TEXT, Texts.json(Component.literal(text)), x, y, colour, dropShadow ? 1 : 0);
        return x + font.width(text) + (dropShadow ? 1 : 0);
    }

    @Override
    public int drawString(Font font, FormattedCharSequence text, int x, int y, int colour, boolean dropShadow) {
        this.note(ExtrasStates.TEXT, Texts.json(component(text)), x, y, colour, dropShadow ? 1 : 0);
        return x + font.width(text) + (dropShadow ? 1 : 0);
    }

    /** NeoForge's own way in, at a place between pixels: noted at the pixel it starts in */
    @Override
    public int drawString(Font font, @Nullable String text, float x, float y, int colour, boolean dropShadow) {
        return this.drawString(font, text, (int) x, (int) y, colour, dropShadow);
    }

    @Override
    public int drawString(Font font, FormattedCharSequence text, float x, float y, int colour, boolean dropShadow) {
        return this.drawString(font, text, (int) x, (int) y, colour, dropShadow);
    }

    @Override
    public int drawString(Font font, Component text, int x, int y, int colour, boolean dropShadow) {
        this.note(ExtrasStates.TEXT, Texts.json(text), x, y, colour, dropShadow ? 1 : 0);
        return x + font.width(text) + (dropShadow ? 1 : 0);
    }

    @Override
    public void drawCenteredString(Font font, Component text, int x, int y, int colour) {
        this.drawString(font, text, x - font.width(text) / 2, y, colour, true);
    }

    /** A line already laid out for drawing, back as text: each run of one style as a part of its own */
    static Component component(FormattedCharSequence text) {
        MutableComponent whole = Component.empty();
        StringBuilder run = new StringBuilder();
        Style[] style = {Style.EMPTY};
        text.accept((index, charStyle, codePoint) -> {
            if (!charStyle.equals(style[0]) && !run.isEmpty()) {
                whole.append(Component.literal(run.toString()).setStyle(style[0]));
                run.setLength(0);
            }
            style[0] = charStyle;
            run.appendCodePoint(codePoint);
            return true;
        });
        if (!run.isEmpty()) {
            whole.append(Component.literal(run.toString()).setStyle(style[0]));
        }
        return whole;
    }

    // --- Drawing again ---

    /** Draws a recorded page again on the game's own GUI drawing, in the order it was drawn */
    static void draw(GuiGraphics graphics, byte[] state) {
        ExtrasStates.Drawn drawn = ExtrasStates.readDrawn(state);
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        float[] shift = ExtrasStates.shift(drawn, width, height);
        Font font = Minecraft.getInstance().font;
        PoseStack pose = graphics.pose();
        // The add-ons' textures have see-through parts, drawn as the game's HUD draws them
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        try {
            drawOps(graphics, drawn, shift, font, pose, width, height);
        } finally {
            RenderSystem.disableBlend();
        }
    }

    private static void drawOps(GuiGraphics graphics, ExtrasStates.Drawn drawn, float[] shift, Font font, PoseStack pose, int width, int height) {
        for (ExtrasStates.Op op : drawn.ops()) {
            int[] n = op.numbers();
            float[] p = op.pose();
            pose.pushPose();
            try {
                if (ExtrasStates.fillsScreen(op, drawn)) {
                    graphics.fill(RenderType.gui(), 0, 0, width, height, n[4], n[5]);
                    continue;
                }
                pose.translate(shift[0], shift[1], 0.0F);
                pose.last().pose().mul(new Matrix4f(p[0], p[1], 0, 0, p[2], p[3], 0, 0, 0, 0, 1, 0, p[4], p[5], p[6], 1));
                switch (op.kind()) {
                    case ExtrasStates.FILL -> graphics.fill(RenderType.gui(), n[0], n[1], n[2], n[3], n[4], n[5]);
                    case ExtrasStates.BLIT -> {
                        ResourceLocation texture = ResourceLocation.tryParse(op.text());
                        if (texture != null) {
                            graphics.blit(texture, n[0], n[1], n[2], n[3], op.decimal(4), op.decimal(5), n[6], n[7], n[8], n[9]);
                        }
                    }
                    case ExtrasStates.SPRITE -> {
                        ResourceLocation sprite = ResourceLocation.tryParse(op.text());
                        if (sprite != null) {
                            graphics.blitSprite(sprite, n[0], n[1], n[4], n[2], n[3]);
                        }
                    }
                    case ExtrasStates.TEXT -> graphics.drawString(font, Texts.read(op.text()), n[0], n[1], n[2], n[3] != 0);
                    default -> {
                        // A kind a later version draws: left out
                    }
                }
            } finally {
                pose.popPose();
            }
        }
    }

}
