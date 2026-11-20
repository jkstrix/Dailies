package comedymonkey.dailiescompat.create.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsBlock;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsHandler;
import com.simibubi.create.content.redstone.link.controller.LinkedControllerClientHandler;
import com.simibubi.create.content.trains.TrainHUD;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.foundation.gui.AllGuiTextures;
import com.simibubi.create.foundation.item.TooltipHelper;
import com.simibubi.create.foundation.utility.ControlsUtil;
import com.simibubi.create.foundation.utility.CreateLang;
import comedymonkey.dailiescompat.create.state.States;
import net.createmod.catnip.animation.LerpedFloat;
import net.createmod.catnip.lang.FontHelper.Palette;
import net.createmod.catnip.math.AngleHelper;
import net.createmod.catnip.placement.PlacementClient;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Create's lines and bars over play that are worked out on the player's own game and drawn by Create's HUD layers or the
 * game's: the line above the hotbar Create puts there itself ({@link #STATUS}: what a click with super glue or the
 * schematic and quill will do, "too far", a key bound, a train's controls taken), the bar shown while a train is driven
 * ({@link #TRAIN}: speed, throttle, the prompt above it and the arrow), and the lines shown while a linked controller
 * waits for a key to bind ({@link #BIND}). Each is recorded as what was drawn and drawn again as Create draws it.
 */
final class CreateHud {

    static final String STATUS = "status_line";
    static final String TRAIN = "train_hud";
    static final String BIND = "linked_controller_bind";

    private CreateHud() {
    }

    // --- The status line ---

    static byte @Nullable [] captureStatus() {
        Minecraft minecraft = Minecraft.getInstance();
        Component noted = CreateMarks.status();
        if (noted == null || minecraft.options.hideGui) {
            return null;
        }
        Component shown = Reflect.get(minecraft.gui, "overlayMessageString");
        int ticksLeft = Reflect.getInt(minecraft.gui, "overlayMessageTime");
        // Shown while the game still holds the very line Create put there, and it has not run out
        if (shown != noted || ticksLeft <= 0 || shown.getString().isEmpty()) {
            return null;
        }
        boolean animated = Reflect.get(minecraft.gui, "animateOverlayMessageColor");
        return States.status(new States.Status(Texts.json(shown), ticksLeft, animated));
    }

    /** As the game's Gui draws its line above the hotbar */
    static void drawStatus(GuiGraphics graphics, byte[] state, float partialTick) {
        States.Status status = States.readStatus(state);
        int alpha = status.alpha(partialTick);
        if (alpha <= 8) {
            return;
        }
        Component text = Texts.read(status.text());
        Font font = Minecraft.getInstance().font;
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(graphics.guiWidth() / 2.0F, graphics.guiHeight() - 68.0F, 0.0F);
            float left = status.ticksLeft() - partialTick;
            int colour = status.animated() ? Mth.hsvToRgb(left / 50.0F, 0.7F, 0.6F) & 0xFF_FFFF : 0xFF_FFFF;
            int width = font.width(text);
            graphics.drawStringWithBackdrop(font, text, -width / 2, -4, width, colour | (alpha << 24 & 0xFF00_0000));
        } finally {
            graphics.pose().popPose();
        }
    }

    // --- A train being driven ---

    static byte @Nullable [] captureTrain() {
        Minecraft minecraft = Minecraft.getInstance();
        // As TrainHUD.renderOverlay decides whether to draw
        if (minecraft.options.hideGui || minecraft.gameMode == null || minecraft.gameMode.getPlayerMode() == GameType.SPECTATOR) {
            return null;
        }
        if (!(ControlsHandler.getContraption() instanceof CarriageContraptionEntity cce)) {
            return null;
        }
        Carriage carriage = cce.getCarriage();
        Entity camera = minecraft.getCameraEntity();
        BlockPos localPos = ControlsHandler.getControlsPos();
        if (carriage == null || camera == null || localPos == null) {
            return null;
        }
        LerpedFloat speed = Reflect.getStatic(TrainHUD.class, "displayedSpeed");
        LerpedFloat throttle = Reflect.getStatic(TrainHUD.class, "displayedThrottle");
        LerpedFloat promptSize = Reflect.getStatic(TrainHUD.class, "displayedPromptSize");
        Component prompt = TrainHUD.currentPrompt;

        // The arrow, as TrainHUD works it out
        StructureBlockInfo info = cce.getContraption().getBlocks().get(localPos);
        Direction initialOrientation = cce.getInitialOrientation().getCounterClockWise();
        boolean inverted = false;
        if (info != null && info.state().hasProperty(ControlsBlock.FACING)) {
            inverted = !info.state().getValue(ControlsBlock.FACING).equals(initialOrientation);
        }
        boolean reversing = ControlsHandler.currentlyPressed.contains(1);
        inverted ^= reversing;
        int angleOffset = (ControlsHandler.currentlyPressed.contains(2) ? -45 : 0) + (ControlsHandler.currentlyPressed.contains(3) ? 45 : 0);
        if (reversing) {
            angleOffset *= -1;
        }
        float snapSize = 22.5F;
        float diff = AngleHelper.getShortestAngleDiff(camera.getYRot(), cce.yaw) + (inverted ? -90 : 90);
        if (Math.abs(diff) < 60) {
            diff = 0;
        }
        float snappedAngle = (snapSize * Math.round((diff + angleOffset) / snapSize)) % 360.0F;
        return States.trainHud(new States.TrainHud(speed.getValue(), throttle.getValue(), promptSize.getValue(), prompt == null ? "" : Texts.json(prompt),
            TrainHUD.currentPromptShadow, snappedAngle));
    }

    /** As TrainHUD.renderOverlay draws it */
    static void drawTrain(GuiGraphics graphics, byte[] state) {
        States.TrainHud hud = States.readTrainHud(state);
        Minecraft minecraft = Minecraft.getInstance();
        PoseStack poseStack = graphics.pose();
        poseStack.pushPose();
        try {
            poseStack.translate(graphics.guiWidth() / 2 - 91, graphics.guiHeight() - 29, 0);
            AllGuiTextures.TRAIN_HUD_FRAME.render(graphics, -2, 1);
            AllGuiTextures.TRAIN_HUD_SPEED_BG.render(graphics, 0, 0);
            int w = (int) (AllGuiTextures.TRAIN_HUD_SPEED.getWidth() * hud.speed());
            int h = AllGuiTextures.TRAIN_HUD_SPEED.getHeight();
            graphics.blit(AllGuiTextures.TRAIN_HUD_SPEED.location, 0, 0, 0, AllGuiTextures.TRAIN_HUD_SPEED.getStartX(),
                AllGuiTextures.TRAIN_HUD_SPEED.getStartY(), w, h, 256, 256);

            int promptSize = (int) hud.promptSize();
            if (promptSize > 1) {
                poseStack.pushPose();
                poseStack.translate(promptSize / -2.0F + 91, -27, 0);
                AllGuiTextures.TRAIN_PROMPT_L.render(graphics, -3, 0);
                AllGuiTextures.TRAIN_PROMPT_R.render(graphics, promptSize, 0);
                graphics.blit(AllGuiTextures.TRAIN_PROMPT.location, 0, 0, 0, AllGuiTextures.TRAIN_PROMPT.getStartX() + (128 - promptSize / 2.0F),
                    AllGuiTextures.TRAIN_PROMPT.getStartY(), promptSize, AllGuiTextures.TRAIN_PROMPT.getHeight(), 256, 256);
                poseStack.popPose();

                Font font = minecraft.font;
                Component prompt = hud.prompt().isEmpty() ? null : Texts.read(hud.prompt());
                if (prompt != null && font.width(prompt) < promptSize - 10) {
                    poseStack.pushPose();
                    poseStack.translate(font.width(prompt) / -2.0F + 82, -27, 100);
                    graphics.drawString(font, prompt, 9, 4, 0x544D45, hud.promptShadow());
                    poseStack.popPose();
                }
            }

            AllGuiTextures.TRAIN_HUD_DIRECTION.render(graphics, 77, -20);
            w = (int) (AllGuiTextures.TRAIN_HUD_THROTTLE.getWidth() * (1 - hud.throttle()));
            int invW = AllGuiTextures.TRAIN_HUD_THROTTLE.getWidth() - w;
            graphics.blit(AllGuiTextures.TRAIN_HUD_THROTTLE.location, invW, 0, 0, AllGuiTextures.TRAIN_HUD_THROTTLE.getStartX() + invW,
                AllGuiTextures.TRAIN_HUD_THROTTLE.getStartY(), w, h, 256, 256);
            AllGuiTextures.TRAIN_HUD_THROTTLE_POINTER.render(graphics, Math.max(1, AllGuiTextures.TRAIN_HUD_THROTTLE.getWidth() - w) - 3, -2);

            poseStack.translate(91, -9, 0);
            poseStack.scale(0.925F, 0.925F, 1);
            PlacementClient.textured(poseStack, 0, 0, 1, hud.angle());
        } finally {
            poseStack.popPose();
        }
    }

    // --- A linked controller being bound ---

    static byte @Nullable [] captureBind() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || LinkedControllerClientHandler.MODE != LinkedControllerClientHandler.Mode.BIND) {
            return null;
        }
        // As LinkedControllerClientHandler.renderOverlay words it, with the recorder's own keys
        Object[] keys = new Object[6];
        List<KeyMapping> controls = ControlsUtil.getControls();
        for (int i = 0; i < controls.size() && i < keys.length; i++) {
            keys[i] = controls.get(i).getTranslatedKeyMessage().getString();
        }
        List<Component> lines = new ArrayList<>();
        lines.add(CreateLang.translateDirect("linked_controller.bind_mode").withStyle(ChatFormatting.GOLD));
        lines.addAll(TooltipHelper.cutTextComponent(CreateLang.translateDirect("linked_controller.press_keybind", keys), Palette.ALL_GRAY));
        return States.bindMode(new States.BindMode(Texts.jsons(lines)));
    }

    static void drawBind(GuiGraphics graphics, byte[] state) {
        List<Component> lines = Texts.reads(States.readBindMode(state).lines());
        if (lines.isEmpty()) {
            return;
        }
        Font font = Minecraft.getInstance().font;
        int width = 0;
        for (Component line : lines) {
            width = Math.max(width, font.width(line));
        }
        int height = lines.size() * font.lineHeight;
        graphics.renderComponentTooltip(font, lines, graphics.guiWidth() / 3 - width / 2, graphics.guiHeight() - height - 24);
    }

}
