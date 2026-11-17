package comedymonkey.dailies.export;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import comedymonkey.dailies.Dailies;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.command.MarkWords;
import comedymonkey.dailies.editor.EditorSession;
import comedymonkey.dailies.moments.Moments;
import comedymonkey.dailies.moments.MomentsTranscript;
import comedymonkey.dailies.playback.Playback;
import net.minecraft.Util;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Draws captions into the picture (specification EXP-15): into each exported frame when the export asks for it, and
 * into the editor's view only while the preview is switched on. Font, size, colour, outline and where they sit come
 * from the settings; the size is a share of the picture's height, so a 4K export has the same captions as a 1080p one.
 */
public final class CaptionBurner {

    private static final ResourceLocation UNIFORM = ResourceLocation.withDefaultNamespace("uniform");
    private static final ResourceLocation DEFAULT = ResourceLocation.withDefaultNamespace("default");

    // --- The preview's captions for the open recording, worked out away from the client thread ---
    private static volatile @Nullable List<Captions.Cue> previewCues = null;
    private static volatile @Nullable String previewFor = null;
    private static volatile long previewStamp = Long.MIN_VALUE;
    private static volatile long previewAskedAt = 0;
    private static volatile boolean previewLoading = false;

    private CaptionBurner() {
    }

    public static void init() {
        // Worked out whether or not the interface is drawn this frame, so the preview is ready the moment it is
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.RenderFrameEvent.Pre event) -> {
            try {
                if (!Export.active() && Playback.loaded() && EditorSession.open() && Host.get().setting("captionPreview", false)) {
                    String open = Playback.recordingId();
                    if (open != null) {
                        refreshPreview(open);
                    }
                }
            } catch (RuntimeException e) {
                Host.LOGGER.debug("Dailies: the caption preview could not be brought up to date", e);
            }
        });
        IEventBus modBus = ModList.get().getModContainerById(Dailies.ID).map(ModContainer::getEventBus).orElse(null);
        if (modBus != null) {
            modBus.addListener((RegisterGuiLayersEvent event) -> event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(Dailies.ID, "caption_preview"),
                CaptionBurner::preview));
        }
    }

    // ------------------------------------------------------------------ The look

    /** How captions look, from the settings */
    record Look(Captions.Style style, ResourceLocation font, double size, int colour, int outline, boolean outlined, String place) {
        static Look fromSettings(@Nullable Captions.Style style) {
            Host.Mod host = Host.get();
            String styleSetting = host.setting("captionStyle", "LINES");
            Captions.Style chosen = style != null ? style : Captions.style(styleSetting);
            String font = host.setting("captionFont", "DEFAULT").toUpperCase(Locale.ROOT);
            Integer colour = colourOf(host.setting("captionColour", "white"));
            String outlineSetting = host.setting("captionOutline", "black");
            Integer outline = outlineSetting.isBlank() ? null : colourOf(outlineSetting);
            double size = host.setting("captionSize", 5.0);
            String place = host.setting("captionPosition", "BOTTOM").toUpperCase(Locale.ROOT);
            // Word by word is meant to be read at a glance: larger, in the middle of the lower part
            if (chosen == Captions.Style.WORD_BY_WORD) {
                size *= 1.6;
            }
            return new Look(chosen, font.equals("UNIFORM") ? UNIFORM : DEFAULT, size, colour == null ? 0xFFFFFF : colour, outline == null ? 0 : outline, outline != null, place);
        }
    }

    /** The marker colours' names and #RRGGBB, and black and grey, which captions want and markers do not */
    static @Nullable Integer colourOf(String word) {
        String name = word.trim().toLowerCase(Locale.ROOT);
        if (name.equals("black")) {
            return 0x000000;
        }
        if (name.equals("grey") || name.equals("gray")) {
            return 0x808080;
        }
        return MarkWords.colour(word);
    }

    // ------------------------------------------------------------------ Into an exported frame

    /**
     * Draws a caption into the export's picture, after the game has drawn it and before it is read. Client thread,
     * on the render thread's own time between the frame and its reading.
     */
    static void drawIntoFrame(Minecraft minecraft, RenderTarget target, String text, @Nullable Captions.Style style) {
        Look look = Look.fromSettings(style);
        target.bindWrite(true);
        RenderSystem.backupProjectionMatrix();
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        try {
            // Pixels of the picture, whatever the interface scale
            RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0.0F, target.width, target.height, 0.0F, 1000.0F, 21000.0F), VertexSorting.ORTHOGRAPHIC_Z);
            modelView.identity();
            modelView.translate(0.0F, 0.0F, -11000.0F);
            RenderSystem.applyModelViewMatrix();
            RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
            GuiGraphics graphics = new GuiGraphics(minecraft, minecraft.renderBuffers().bufferSource());
            draw(graphics, minecraft.font, 0, 0, target.width, target.height, text, look);
            graphics.flush();
        } finally {
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
        }
    }

    /** Draws the text in the box, rows centred, as the look says */
    static void draw(GuiGraphics graphics, Font font, int left, int top, int width, int height, String text, Look look) {
        float scale = (float) Math.max(0.25, height * look.size() / 100.0 / font.lineHeight);
        int room = Math.max(10, (int) (width * 0.9 / scale));
        Style style = Style.EMPTY.withFont(look.font());
        List<FormattedCharSequence> rows = new ArrayList<>();
        for (String line : text.split("\n")) {
            rows.addAll(font.split(Component.literal(line).withStyle(style), room));
        }
        if (rows.isEmpty()) {
            return;
        }
        float rowHeight = font.lineHeight + 1;
        float block = rows.size() * rowHeight;
        float boxHeight = height / scale;
        float margin = boxHeight * 0.06F;
        float firstY = switch (look.place()) {
            case "TOP" -> margin;
            case "MIDDLE" -> (boxHeight - block) / 2.0F;
            default -> look.style() == Captions.Style.WORD_BY_WORD ? boxHeight * 0.72F - block / 2.0F : boxHeight - margin - block;
        };
        int colour = 0xFF000000 | look.colour();
        int outline = 0xFF000000 | look.outline();
        graphics.pose().pushPose();
        graphics.pose().translate(left, top, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        float boxWidth = width / scale;
        for (int i = 0; i < rows.size(); i++) {
            FormattedCharSequence row = rows.get(i);
            float x = (boxWidth - font.width(row)) / 2.0F;
            float y = firstY + i * rowHeight;
            if (look.outlined()) {
                font.drawInBatch8xOutline(row, x, y, colour, outline, graphics.pose().last().pose(), graphics.bufferSource(), 0xF000F0);
            } else {
                graphics.drawString(font, row, (int) x, (int) y, colour, true);
            }
        }
        graphics.pose().popPose();
    }

    // ------------------------------------------------------------------ The editor's preview

    private static void preview(GuiGraphics graphics, DeltaTracker delta) {
        try {
            if (Export.active() || !Playback.loaded() || !EditorSession.open() || !Host.get().setting("captionPreview", false)) {
                return;
            }
            String open = Playback.recordingId();
            if (open == null) {
                return;
            }
            List<Captions.Cue> cues = previewCues;
            if (cues == null || !open.equals(previewFor)) {
                return;
            }
            Look look = Look.fromSettings(null);
            String text = Captions.at(cues, Playback.frameTime() / 20.0, look.style(), Host.get().setting("captionSpeakerNames", true));
            if (text == null) {
                return;
            }
            int width = graphics.guiWidth();
            int height = graphics.guiHeight();
            int left = 0;
            int top = 0;
            int right = width;
            int bottom = height;
            double[] framed = comedymonkey.dailies.looks.LooksRenderer.frameRect();
            if (framed != null) {
                left = (int) Math.round(width * framed[0]);
                top = (int) Math.round(height * framed[1]);
                right = (int) Math.round(width * framed[2]);
                bottom = (int) Math.round(height * framed[3]);
            }
            draw(graphics, Minecraft.getInstance().font, left, top, right - left, bottom - top, text, look);
        } catch (RuntimeException e) {
            Host.LOGGER.debug("Dailies: the caption preview could not be drawn", e);
        }
    }

    /** Works the open recording's captions out again when it, or what Moments knows of it, has changed */
    private static void refreshPreview(String open) {
        long now = System.currentTimeMillis();
        if (previewLoading || now - previewAskedAt < 2000) {
            return;
        }
        long stamp = Moments.timelineStamp() * 31 + Playback.total();
        if (open.equals(previewFor) && stamp == previewStamp) {
            return;
        }
        previewAskedAt = now;
        previewLoading = true;
        int total = Playback.total();
        CompletableFuture.supplyAsync(() -> {
            MomentsTranscript.Transcript transcript = Moments.transcriptFor(open, 0, total);
            // The preview runs in recording time: twenty ticks a second
            return Captions.cues(transcript, tick -> tick / 20.0, total / 20.0);
        }, Util.ioPool()).whenComplete((cues, error) -> {
            if (error != null) {
                Host.LOGGER.debug("Dailies: the captions could not be worked out for the preview", error);
            } else {
                previewCues = cues;
                previewFor = open;
                previewStamp = stamp;
            }
            previewLoading = false;
        });
    }

    /** Whether the preview has captions for the open recording, for the scripted test */
    public static boolean previewReady() {
        return previewCues != null && Objects.equals(previewFor, Playback.recordingId());
    }

}
