package comedymonkey.dailiescompat.aeronautics.client;

import com.simibubi.create.content.equipment.goggles.GogglesItem;
import com.simibubi.create.foundation.item.TooltipHelper;
import com.simibubi.create.foundation.utility.CreateLang;
import comedymonkey.dailiescompat.aeronautics.state.AeroStates;
import dev.simulated_team.simulated.Simulated;
import dev.simulated_team.simulated.content.blocks.physics_assembler.PhysicsAssemblerGUIHandler;
import dev.simulated_team.simulated.content.blocks.redstone.linked_typewriter.LinkedTypewriterInteractionHandler;
import dev.simulated_team.simulated.content.blocks.steering_wheel.SteeringWheelHandler;
import dev.simulated_team.simulated.content.blocks.throttle_lever.ThrottleLeverHandler;
import dev.simulated_team.simulated.data.SimLang;
import dev.simulated_team.simulated.index.SimClickInteractions;
import dev.simulated_team.simulated.service.SimConfigService;
import dev.simulated_team.simulated.util.hold_interaction.HoldInteractionManager;
import net.createmod.catnip.lang.FontHelper.Palette;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Simulated's overlays over play, worked out on the player's own game and drawn by its HUD: the box shown while a linked
 * typewriter waits for a key to bind to a link ({@link #BINDING}), and the readout shown while a lever or wheel is held
 * and moved with the mouse ({@link #LEVER}: the physics assembler's lever, the steering wheel's turn, seen only through
 * goggles, and the throttle lever's signal). Each is recorded as what was drawn and drawn again as Simulated draws it,
 * with its own textures; the assembler's and the throttle's by Simulated's own drawing, handed the recorded values.
 */
final class SimulatedHud {

    static final String BINDING = "typewriter_binding";
    static final String LEVER = "lever_readout";

    /** A throttle lever's handler of the compat's own, never held, that draws the recorded readout */
    private static @Nullable ThrottleLeverHandler throttle = null;

    private SimulatedHud() {
    }

    // --- The linked typewriter's binding ---

    static byte @Nullable [] captureBinding() {
        Minecraft minecraft = Minecraft.getInstance();
        // As LinkedTypewriterItemBindHandler.renderOverlay decides whether to draw
        if (minecraft.options.hideGui || LinkedTypewriterInteractionHandler.getMode() != LinkedTypewriterInteractionHandler.Mode.BINDING_FROM_ITEM) {
            return null;
        }
        List<Component> lines = new ArrayList<>();
        lines.add(CreateLang.translateDirect("linked_controller.bind_mode").withStyle(ChatFormatting.GOLD));
        lines.addAll(TooltipHelper.cutTextComponent(SimLang.translate("linked_typewriter.bind_item").component(), Palette.ALL_GRAY));
        return AeroStates.binding(new AeroStates.Binding(Texts.jsons(lines)));
    }

    /** As LinkedTypewriterItemBindHandler.renderOverlay draws it */
    static void drawBinding(GuiGraphics graphics, byte[] state) {
        List<Component> lines = Texts.reads(AeroStates.readBinding(state).lines());
        Font font = Minecraft.getInstance().font;
        int width = 0;
        for (Component line : lines) {
            width = Math.max(width, font.width(line));
        }
        int x = graphics.guiWidth() / 3 - width / 2;
        int y = graphics.guiHeight() - lines.size() * 9 - 24;
        graphics.renderComponentTooltip(font, lines, x, y);
    }

    // --- A lever or wheel held ---

    static byte @Nullable [] captureLever() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || minecraft.player == null) {
            return null;
        }
        // As HoldInteractionManager.renderOverlay hands the drawing to whatever is held
        Object held = Reflect.getStatic(HoldInteractionManager.class, "active");
        if (held instanceof PhysicsAssemblerGUIHandler) {
            return AeroStates.lever(new AeroStates.Lever(AeroStates.ASSEMBLER,
                new float[]{PhysicsAssemblerGUIHandler.lastAnimatedValue, PhysicsAssemblerGUIHandler.animatedValue}, false));
        }
        if (held instanceof SteeringWheelHandler) {
            // Drawn only through goggles
            if (!GogglesItem.isWearingGoggles(minecraft.player)) {
                return null;
            }
            float limit = Reflect.getStatic(SteeringWheelHandler.class, "angleLimit");
            int sign = Reflect.getStatic(SteeringWheelHandler.class, "angleSgn");
            float angle = Reflect.getStatic(SteeringWheelHandler.class, "effectiveAngle");
            int across = SimConfigService.INSTANCE.client().blockConfig.steeringWheelXOffset.get();
            int down = SimConfigService.INSTANCE.client().blockConfig.steeringWheelYOffset.get();
            return AeroStates.lever(new AeroStates.Lever(AeroStates.WHEEL, new float[]{across, down, limit, sign, angle}, false));
        }
        if (held instanceof ThrottleLeverHandler lever) {
            int signal = Reflect.get(lever, "signal");
            float last = Reflect.get(lever, "lastAnimatedValue");
            float now = Reflect.get(lever, "animatedValue");
            boolean inverted = Reflect.get(lever, "inverted");
            return AeroStates.lever(new AeroStates.Lever(AeroStates.THROTTLE, new float[]{signal, last, now}, inverted));
        }
        return null;
    }

    static void drawLever(GuiGraphics graphics, byte[] state) {
        AeroStates.Lever lever = AeroStates.readLever(state);
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        switch (lever.kind()) {
            case AeroStates.ASSEMBLER -> {
                // Simulated's own drawing reads the lever's travel from two fields of its handler: swapped in only while it draws
                float last = PhysicsAssemblerGUIHandler.lastAnimatedValue;
                float now = PhysicsAssemblerGUIHandler.animatedValue;
                try {
                    PhysicsAssemblerGUIHandler.lastAnimatedValue = lever.number(0);
                    PhysicsAssemblerGUIHandler.animatedValue = lever.number(1);
                    SimClickInteractions.PHYSICS_ASSEMBLER_MANAGER.renderOverlay(graphics, width, height, false);
                } finally {
                    PhysicsAssemblerGUIHandler.lastAnimatedValue = last;
                    PhysicsAssemblerGUIHandler.animatedValue = now;
                }
            }
            case AeroStates.WHEEL -> drawWheel(graphics, lever, width);
            case AeroStates.THROTTLE -> {
                ThrottleLeverHandler handler = throttle;
                if (handler == null) {
                    handler = new ThrottleLeverHandler();
                    throttle = handler;
                }
                Reflect.set(handler, "signal", Math.round(lever.number(0)));
                Reflect.set(handler, "lastAnimatedValue", lever.number(1));
                Reflect.set(handler, "animatedValue", lever.number(2));
                Reflect.set(handler, "inverted", lever.flag());
                handler.renderOverlay(graphics, width, height, false);
            }
            default -> {
            }
        }
    }

    /**
     * As SteeringWheelHandler.renderOverlay draws its readout, from the recorded turn and the recorder's own offsets for
     * it, with the goggles already known to be on
     */
    private static void drawWheel(GuiGraphics graphics, AeroStates.Lever lever, int width) {
        Minecraft minecraft = Minecraft.getInstance();
        ResourceLocation texture = Simulated.path("textures/gui/steering_wheel.png");
        float angleLimit = lever.number(2);
        int angleSign = Math.round(lever.number(3));
        float effectiveAngle = lever.number(4);
        int x = (width - 223) / 2 + Math.round(lever.number(0));
        int y = 10 + Math.round(lever.number(1));
        graphics.blit(texture, x, y, 0.0F, 0.0F, 223, 31, 256, 256);
        float offset = SteeringWheelHandler.wrapDegrees(angleLimit) * 0.56F;
        int activeWidth = (int) Math.abs(offset);
        int centerX = x + 111 - 4;
        float realDegrees = angleSign * -effectiveAngle;
        if (Math.abs(angleLimit) <= 180.0F) {
            int leftDeadZoneWidth = centerX - x - activeWidth + 4;
            if (leftDeadZoneWidth > 0) {
                graphics.blit(texture, x, y, 0.0F, 32.0F, leftDeadZoneWidth, 31, 256, 256);
            }
            int rightSideStart = centerX + activeWidth + 8;
            int rightDeadZoneWidth = x + 223 - rightSideStart;
            if (rightDeadZoneWidth > 0) {
                graphics.blit(texture, rightSideStart, y, rightSideStart - x, 32.0F, rightDeadZoneWidth, 31, 256, 256);
            }
        } else {
            if (realDegrees <= -180.0F) {
                int rightSideStart = centerX - activeWidth + 4;
                int rightDeadZoneWidth = x + 223 - rightSideStart;
                if (rightDeadZoneWidth > 0) {
                    graphics.blit(texture, rightSideStart, y, rightSideStart - x, 32.0F, rightDeadZoneWidth, 31, 256, 256);
                }
            }
            if (realDegrees >= 180.0F) {
                int leftDeadZoneWidth = centerX - x + activeWidth + 4;
                if (leftDeadZoneWidth > 0) {
                    graphics.blit(texture, x, y, 0.0F, 32.0F, leftDeadZoneWidth, 31, 256, 256);
                }
            }
        }
        if (Math.abs(angleLimit) > 180.0F) {
            if (-realDegrees >= 180.0F) {
                graphics.blit(texture, (int) (centerX + offset) + 2, y + 10, 239.0F, 0.0F, 6, 20, 256, 256);
            }
            if (-realDegrees <= -180.0F) {
                graphics.blit(texture, (int) (centerX - offset) + 2, y + 10, 239.0F, 0.0F, 6, 20, 256, 256);
            }
        } else {
            graphics.blit(texture, (int) (centerX + offset) + 2, y + 10, 239.0F, 0.0F, 6, 20, 256, 256);
            graphics.blit(texture, (int) (centerX - offset) + 2, y + 10, 239.0F, 0.0F, 6, 20, 256, 256);
        }
        float degrees = Math.abs(angleLimit) <= 180.0F ? Mth.clamp(realDegrees, -180.0F, 180.0F) : SteeringWheelHandler.wrapDegrees(realDegrees);
        int markerX = (int) (centerX - degrees * 0.56F) + 1;
        graphics.blit(texture, markerX, y + 11, 224.0F, 0.0F, 9, 18, 256, 256);
        String text = (int) (-realDegrees) + "°";
        int textWidth = minecraft.font.width(text);
        int centeredX = markerX + 6 - textWidth / 2;
        for (int xoff = -1; xoff < 2; xoff++) {
            for (int yoff = -1; yoff < 2; yoff++) {
                if (xoff != 0 || yoff != 0) {
                    graphics.drawString(minecraft.font, text, centeredX + xoff, y + yoff, 0x2B2117, false);
                }
            }
        }
        graphics.drawString(minecraft.font, text, centeredX, y, 0x886539, false);
    }

}
