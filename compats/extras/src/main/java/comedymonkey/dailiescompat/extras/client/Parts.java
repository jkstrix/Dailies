package comedymonkey.dailiescompat.extras.client;

import comedymonkey.dailies.api.compat.OverlayPart;
import comedymonkey.dailies.api.compat.ScreenPart;
import comedymonkey.dailies.api.compat.WindowExtras;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** The compat's parts, made from what each class gives: nothing here refers to any of the mods */
final class Parts {

    private Parts() {
    }

    /** Where a screen's window starts, for the pointer: a window's own corner, or null to keep the pointer as a share of the screen */
    interface Corner {
        int @Nullable [] of(Screen screen);
    }

    static ScreenPart screen(Function<Screen, byte @Nullable []> capture, Function<byte[], @Nullable Screen> build, int ticks) {
        return screen(capture, build, ticks, screen -> screen instanceof AbstractContainerScreen<?> container ? new int[]{container.getGuiLeft(), container.getGuiTop()} : null);
    }

    static ScreenPart screen(Function<Screen, byte @Nullable []> capture, Function<byte[], @Nullable Screen> build, int ticks, Corner corner) {
        return new ScreenPart() {
            @Override
            public byte @Nullable [] capture(Screen screen) {
                return capture.apply(screen);
            }

            @Override
            public @Nullable Screen build(byte[] state, int version) {
                return build.apply(state);
            }

            @Override
            public int @Nullable [] corner(Screen screen) {
                return corner.of(screen);
            }

            @Override
            public int ticksBetweenWrites() {
                return ticks;
            }
        };
    }

    /** An overlay drawn with the moment between ticks */
    interface Draw {
        void draw(GuiGraphics graphics, byte[] state, float partialTick);
    }

    static OverlayPart overlay(Supplier<byte @Nullable []> capture, Draw draw, int ticks) {
        return new OverlayPart() {
            @Override
            public byte @Nullable [] capture() {
                return capture.get();
            }

            @Override
            public void draw(GuiGraphics graphics, byte[] state, int version, float partialTick) {
                draw.draw(graphics, state, partialTick);
            }

            @Override
            public int ticksBetweenWrites() {
                return ticks;
            }
        };
    }

    static WindowExtras extras(Function<AbstractContainerScreen<?>, byte @Nullable []> capture, BiConsumer<AbstractContainerScreen<?>, byte[]> apply, int ticks) {
        return new WindowExtras() {
            @Override
            public byte @Nullable [] capture(AbstractContainerScreen<?> screen) {
                return capture.apply(screen);
            }

            @Override
            public void apply(AbstractContainerScreen<?> screen, byte[] state, int version) {
                apply.accept(screen, state);
            }

            @Override
            public int ticksBetweenWrites() {
                return ticks;
            }
        };
    }

}
