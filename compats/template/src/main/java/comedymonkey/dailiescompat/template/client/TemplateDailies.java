package comedymonkey.dailiescompat.template.client;

import comedymonkey.dailies.api.DailiesApi;
import comedymonkey.dailies.api.compat.Claims;
import comedymonkey.dailies.api.compat.DailiesCompat;
import comedymonkey.dailies.api.compat.DailiesCompats;
import comedymonkey.dailies.api.compat.DisplayTest;
import comedymonkey.dailies.api.compat.OverlayPart;
import comedymonkey.dailies.api.compat.ScreenPart;
import comedymonkey.dailies.api.compat.ServerHalf;
import comedymonkey.dailiescompat.template.TemplateMod;
import comedymonkey.dailiescompat.template.HomeFact;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The template compat itself: the only class that refers to Dailies, loaded only when Dailies is (see
 * {@link TemplateClient}). It records the example screen (its count) and the example overlay (the line it shows), draws
 * them again in playback, and passes the server half's fact to the overlay. Each state is the least that draws the
 * part again, and the whole of it: Dailies writes it when it changes and keeps it for snapshots, jumps and trims.
 */
final class TemplateDailies implements DailiesCompat {

    static final String ID = TemplateMod.ID + ":screens";
    /** Raise when a state's bytes change shape; {@link #build} and the overlay read every version written before */
    static final int VERSION = 1;

    private TemplateDailies() {
    }

    static void register() {
        DailiesCompats.register(new TemplateDailies());
        // Asks the server half for its fact as the game joins a world; the answer comes to the server half's handler below
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn event) -> {
            HomeOverlay.home = null;
            DailiesCompats.askServer(HomeFact.PLUGIN, HomeFact.VERSION);
        });
    }

    static boolean inPlayback() {
        return DailiesApi.isPlayback();
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int version() {
        return VERSION;
    }

    @Override
    public void claim(Claims claims) {
        claims.screen("note", ExampleScreen.class, new ScreenPart() {
            @Override
            public byte[] capture(Screen screen) {
                return ByteBuffer.allocate(4).putInt(((ExampleScreen) screen).count()).array();
            }

            @Override
            public @Nullable Screen build(byte[] state, int version) {
                return state.length < 4 ? null : new ExampleScreen(ByteBuffer.wrap(state).getInt(), true);
            }

            @Override
            public int[] corner(Screen screen) {
                ExampleScreen example = (ExampleScreen) screen;
                return new int[]{example.left(), example.top()};
            }
        });
        claims.overlay("home", new OverlayPart() {
            @Override
            public byte @Nullable [] capture() {
                String line = HomeOverlay.line(Minecraft.getInstance());
                return line == null ? null : line.getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public void draw(GuiGraphics graphics, byte[] state, int version, float partialTick) {
                HomeOverlay.draw(graphics, new String(state, StandardCharsets.UTF_8));
            }
        });
        claims.serverHalf(HomeFact.PLUGIN, new ServerHalf() {
            @Override
            public void received(int version, byte[] data) {
                HomeOverlay.home = HomeFact.read(data);
            }
            // In playback the overlay draws the line it recorded, so the fact is not needed again there
        });
    }

    @Override
    public DisplayTest displayTest() {
        return new DisplayTest() {
            @Override
            public void stage(Stage stage) throws Exception {
                stage.command("item replace entity @s weapon.mainhand with " + TemplateMod.ID + ":example_note");
            }

            @Override
            public List<Display> displays() {
                return List.of(
                    // Holding the note shows the overlay
                    new Display("the example overlay", ID + "/home", stage -> {
                    }),
                    new Display("the example screen", ID + "/note", stage -> {
                        stage.onClient(() -> Minecraft.getInstance().setScreen(new ExampleScreen(0, false)));
                        stage.waitTicks(5);
                        stage.onClient(() -> {
                            if (Minecraft.getInstance().screen instanceof ExampleScreen screen) {
                                screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast).findFirst().ifPresent(Button::onPress);
                            }
                        });
                    }));
            }
        };
    }

}
