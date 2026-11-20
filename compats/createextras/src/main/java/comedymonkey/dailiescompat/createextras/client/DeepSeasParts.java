package comedymonkey.dailiescompat.createextras.client;

import com.maxenonyme.AbyssDimension.client.PDAManager;
import com.maxenonyme.createsubmarine.submarine.block.entity.CommandSubBlockEntity;
import com.maxenonyme.createsubmarine.submarine.client.CommandSubClientHandler;
import com.maxenonyme.createsubmarine.submarine.client.ImplosionCinematics;
import com.maxenonyme.createsubmarine.submarine.client.SonarContact;
import com.maxenonyme.createsubmarine.submarine.client.SonarScan;
import com.maxenonyme.createsubmarine.submarine.client.SonarScreen;
import com.maxenonyme.createsubmarine.submarine.client.SonarView;
import comedymonkey.dailiescompat.createextras.state.ExtrasStates;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Create Deep Seas' play screens and overlays (with its Abyss and High Seas parts), from a sweep of its jar:
 * <ul>
 * <li>the sonar's screen ({@link #SONAR}), opened from a command sub's console: the view as set on the console (panned,
 * zoomed, turned), how far below the floor was, and the marks on the map as the recorder's sonar found them (mines,
 * hostiles, creatures, vessels, with their names). Drawn with Create Deep Seas' own screen on the playback world's console,
 * its view set on it as recorded and the recorded marks put on its map before each frame; the map's picture of the sea
 * floor is the sonar's own picture of the playback world, which holds the same floor;</li>
 * <li>the PDA's message sliding in over play in the Abyss ({@link #PDA}), recorded as drawn ({@link Page});</li>
 * <li>the blackout and the words shown as a sub implodes ({@link #IMPLOSION}), recorded as drawn;</li>
 * <li>a boat engine's throttle while it is held and moved with the mouse ({@link #THROTTLE}), a hold of Create
 * Aeronautics' kind drawn by High Seas' own handler: recorded as drawn;</li>
 * <li>a command sub's console as the player works it in the world ({@link #CONSOLE}): the widget the pointer is on and the
 * number being typed into it, which the console's own drawing reads from the player's game; recorded, and put back for
 * the console's drawing in the playback world only while overlays show ({@link InWorld}).</li>
 * </ul>
 * The electrolyzer's and the boat engine's windows are the server's, which Dailies draws itself. Left out: the wind,
 * buoy and seaglide effects over the screen (how the world feels, not interface), the watermark, drawn only in a
 * development game, the welcome, update, safety and missing-library notices and the hull strength settings (menus, not
 * play), the debug drawing, and lines the servers send (the anchor's, the engine's, the helm's, a diffuser's), which come
 * in packets the recording keeps.
 */
final class DeepSeasParts {

    static final String SONAR = "sonar";
    static final String PDA = "pda";
    static final String IMPLOSION = "implosion";
    static final String THROTTLE = "engine_throttle";
    static final String CONSOLE = "console";

    private static final String HOLDS = "dev.simulated_team.simulated.util.hold_interaction.HoldInteractionManager";
    private static final String ENGINE_THROTTLE = "com.maxenonyme.highseas.client.EngineThrottleHandler";

    /** The console's state drawn over the last frame, and the frame */
    private static byte @Nullable [] console = null;
    private static long consoleAt = Long.MIN_VALUE;
    /** What the console's handler held before the recorded state was put in, to put back once the world is drawn */
    private static @Nullable Object[] kept = null;

    private DeepSeasParts() {
    }

    // --- The sonar ---

    static byte[] captureSonar(Screen screen) {
        CommandSubBlockEntity console = Reflect.get(screen, "console");
        SonarScan scan = details().get(console);
        List<ExtrasStates.Contact> contacts = new ArrayList<>();
        float floor = 0.0F;
        if (scan != null) {
            floor = scan.floor();
            for (SonarContact contact : scan.contacts()) {
                contacts.add(new ExtrasStates.Contact(contact.u(), contact.v(), contact.kind(), contact.name() == null ? "" : Texts.json(contact.name())));
            }
        }
        return ExtrasStates.sonar(new ExtrasStates.Sonar(console.getBlockPos().asLong(), console.sonarPanX, console.sonarPanZ, console.sonarZoom,
            console.sonarYaw, console.sonarOrbit, floor, contacts));
    }

    private static Map<CommandSubBlockEntity, SonarScan> details() {
        return Reflect.getStatic(SonarView.class, "DETAILS");
    }

    /** Built on the playback world's console, with the view set on it as recorded: the sonar draws its map from it */
    static @Nullable Screen buildSonar(byte[] state) {
        ExtrasStates.Sonar recorded = ExtrasStates.readSonar(state);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !(minecraft.level.getBlockEntity(BlockPos.of(recorded.pos())) instanceof CommandSubBlockEntity console)) {
            return null;
        }
        console.sonarPanX = recorded.panX();
        console.sonarPanZ = recorded.panZ();
        console.sonarZoom = recorded.zoom();
        console.sonarYaw = recorded.yaw();
        console.sonarOrbit = recorded.orbit();
        return new RecordedSonar(console, recorded);
    }

    static final class RecordedSonar extends SonarScreen {
        private final CommandSubBlockEntity console;
        private final ExtrasStates.Sonar recorded;

        RecordedSonar(CommandSubBlockEntity console, ExtrasStates.Sonar recorded) {
            super(console);
            this.console = console;
            this.recorded = recorded;
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            // The sonar finds its marks again from the playback world after each frame: the recorded ones go back first
            SonarScan scan = details().get(this.console);
            if (scan != null) {
                List<SonarContact> contacts = scan.contacts();
                contacts.clear();
                for (ExtrasStates.Contact contact : this.recorded.contacts()) {
                    contacts.add(new SonarContact(contact.u(), contact.v(), contact.kind(), Texts.read(contact.name())));
                }
                Reflect.set(scan, "floor", this.recorded.floor());
            }
            super.render(graphics, mouseX, mouseY, partialTick);
        }

        /** Closes itself when the console is out of the player's reach: never in playback */
        @Override
        public void tick() {
        }

        @Override
        public void onClose() {
        }
    }

    // --- The PDA, the implosion and the engine's throttle, recorded as drawn ---

    static byte @Nullable [] capturePda() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui) {
            return null;
        }
        Page page = new Page();
        PDAManager.renderOverlay(page, 1.0F);
        return page.state();
    }

    static byte @Nullable [] captureImplosion() {
        int blackout = Reflect.getStatic(ImplosionCinematics.class, "blackout");
        if (blackout <= 0) {
            return null;
        }
        Page page = new Page();
        Reflect.callStatic(ImplosionCinematics.class, "cover", new Class<?>[]{GuiGraphics.class, float.class}, page, 1.0F);
        return page.state();
    }

    static byte @Nullable [] captureThrottle() {
        Minecraft minecraft = Minecraft.getInstance();
        Class<?> holds = Reflect.find(HOLDS);
        if (holds == null || minecraft.options.hideGui || minecraft.player == null) {
            return null;
        }
        // As Simulated's hold manager hands the drawing to whatever is held
        Object held = Reflect.getStatic(holds, "active");
        if (held == null || !held.getClass().getName().equals(ENGINE_THROTTLE)) {
            return null;
        }
        Page page = new Page();
        Reflect.call(held, "renderOverlay", new Class<?>[]{GuiGraphics.class, int.class, int.class, boolean.class}, page, page.guiWidth(), page.guiHeight(),
            false);
        return page.state();
    }

    // --- The console in the world ---

    static byte @Nullable [] captureConsole() {
        CommandSubBlockEntity hovered = Reflect.getStatic(CommandSubClientHandler.class, "hoveredConsole");
        CommandSubBlockEntity typing = Reflect.getStatic(CommandSubClientHandler.class, "typingConsole");
        if (hovered == null && typing == null) {
            return null;
        }
        int widget = Reflect.getStatic(CommandSubClientHandler.class, "hoveredWidget");
        float u = Reflect.getStatic(CommandSubClientHandler.class, "hoveredU");
        float v = Reflect.getStatic(CommandSubClientHandler.class, "hoveredV");
        StringBuilder typed = Reflect.getStatic(CommandSubClientHandler.class, "typed");
        return ExtrasStates.console(new ExtrasStates.Console(hovered == null ? ExtrasStates.NOWHERE : hovered.getBlockPos().asLong(), widget, u, v,
            typing == null ? ExtrasStates.NOWHERE : typing.getBlockPos().asLong(), typing == null ? "" : typed.toString()));
    }

    /** Notes the console's state drawn over this frame, for the playback world's console to be drawn with next frame */
    static void noteConsole(byte[] state) {
        console = state;
        consoleAt = InWorld.frame();
    }

    /**
     * Before the world is drawn in playback (after the console's handler has worked out what the watcher's camera points
     * at): the recorded widget and typing while overlays show, and none otherwise. Only while the world is drawn: the
     * typing is taken out again before the game reads a key
     */
    static void putConsole() {
        Minecraft minecraft = Minecraft.getInstance();
        StringBuilder typed = Reflect.getStatic(CommandSubClientHandler.class, "typed");
        kept = new Object[]{Reflect.getStatic(CommandSubClientHandler.class, "typingConsole"), typed.toString()};
        CommandSubBlockEntity hovered = null;
        CommandSubBlockEntity typing = null;
        int widget = -1;
        float u = 0.0F;
        float v = 0.0F;
        String text = "";
        if (console != null && InWorld.noted(consoleAt) && minecraft.level != null) {
            ExtrasStates.Console recorded = ExtrasStates.readConsole(console);
            hovered = at(minecraft, recorded.hovered());
            typing = at(minecraft, recorded.typing());
            widget = hovered == null ? -1 : recorded.widget();
            u = recorded.u();
            v = recorded.v();
            text = recorded.typed();
        }
        Reflect.setStatic(CommandSubClientHandler.class, "hoveredConsole", hovered);
        Reflect.setStatic(CommandSubClientHandler.class, "hoveredWidget", widget);
        Reflect.setStatic(CommandSubClientHandler.class, "hoveredU", u);
        Reflect.setStatic(CommandSubClientHandler.class, "hoveredV", v);
        Reflect.setStatic(CommandSubClientHandler.class, "typingConsole", typing);
        typed.setLength(0);
        typed.append(text);
    }

    /** Once the world is drawn: the typing as the handler held it, so no key is ever read into a console in playback */
    static void takeConsoleBack() {
        if (kept == null) {
            return;
        }
        StringBuilder typed = Reflect.getStatic(CommandSubClientHandler.class, "typed");
        Reflect.setStatic(CommandSubClientHandler.class, "typingConsole", kept[0]);
        typed.setLength(0);
        typed.append((String) kept[1]);
        kept = null;
    }

    private static @Nullable CommandSubBlockEntity at(Minecraft minecraft, long pos) {
        if (pos == ExtrasStates.NOWHERE || minecraft.level == null) {
            return null;
        }
        return minecraft.level.getBlockEntity(BlockPos.of(pos)) instanceof CommandSubBlockEntity found ? found : null;
    }

}
