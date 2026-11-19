package comedymonkey.dailiescompat.aeronautics.client;

import comedymonkey.dailiescompat.aeronautics.state.AeroStates;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.simulated_team.simulated.content.entities.diagram.DiagramConfig;
import dev.simulated_team.simulated.content.entities.diagram.DiagramEntity;
import dev.simulated_team.simulated.content.entities.diagram.screen.DiagramScreen;
import dev.simulated_team.simulated.network.packets.contraption_diagram.DiagramDataPacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;

/**
 * Simulated's contraption diagram: a picture of the craft the diagram hangs on, with the forces on it that the server
 * sends while it is open. Recorded as the diagram's entity, its settings (which way it looks, the forces shown, merged or
 * not, the centre of mass and the sticky note), the forces and mass the server last sent, and the paper of forces slid
 * out or not; drawn in playback with Simulated's own screen on the playback world's craft, as the recording holds it,
 * with the recorded forces, never asking the server for its own.
 */
final class DiagramPart {

    static final String NAME = "diagram";

    /**
     * The screen last built: a diagram draws the craft through framebuffers of its own, made each time it is laid out and
     * let go of only as it closes, so the same screen is laid out again for each new state rather than a new one made
     */
    private static @Nullable RecordedDiagram built = null;
    private static @Nullable Method containing = null;

    private DiagramPart() {
    }

    static byte[] capture(Screen screen) {
        DiagramEntity entity = Reflect.get(screen, "diagram");
        DiagramConfig config = Reflect.get(screen, "config");
        DiagramDataPacket data = Reflect.get(screen, "serverData");
        boolean paperOut = Reflect.get(screen, "paperVisible");
        float paperOffset = Reflect.get(screen, "paperOffset");
        float tabOffset = Reflect.get(screen, "tabOffset");
        return AeroStates.diagram(new AeroStates.Diagram(entity.getId(), configBytes(config), data == null ? new byte[0] : forcesBytes(data), paperOut,
            paperOffset, tabOffset));
    }

    private static byte[] configBytes(DiagramConfig config) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            DiagramConfig.STREAM_CODEC.encode(buffer, config);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    private static byte[] forcesBytes(DiagramDataPacket data) {
        Minecraft minecraft = Minecraft.getInstance();
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), minecraft.level.registryAccess());
        try {
            DiagramDataPacket.CODEC.encode(buffer, data);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    /** The diagram as the playback world holds it, on its craft; null when either is not there */
    static @Nullable Screen build(byte[] state) {
        AeroStates.Diagram recorded = AeroStates.readDiagram(state);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !(minecraft.level.getEntity(recorded.entity()) instanceof DiagramEntity entity)) {
            return null;
        }
        Object found = subLevel(entity);
        if (!(found instanceof ClientSubLevel subLevel) || removed(found)) {
            return null;
        }
        RecordedDiagram screen = built;
        if (screen == null || Reflect.get(screen, "diagram") != entity || screen.subLevel != subLevel) {
            screen = new RecordedDiagram(entity, subLevel);
            built = screen;
        }
        ByteBuf configBuffer = Unpooled.wrappedBuffer(recorded.config());
        Reflect.set(screen, "config", DiagramConfig.STREAM_CODEC.decode(configBuffer));
        Reflect.call(screen, "updateViewportOrientation", new Class<?>[0]);
        if (recorded.forces().length > 0) {
            RegistryFriendlyByteBuf forces = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(recorded.forces()), minecraft.level.registryAccess());
            screen.updateData(DiagramDataPacket.CODEC.decode(forces));
        }
        Reflect.set(screen, "paperVisible", recorded.paperOut());
        Reflect.set(screen, "paperOffset", recorded.paperOffset());
        Reflect.set(screen, "lastPaperOffset", recorded.paperOffset());
        Reflect.set(screen, "tabOffset", recorded.tabOffset());
        Reflect.set(screen, "lastTabOffset", recorded.tabOffset());
        return screen;
    }

    /** The craft an entity is on, as Sable's helper finds it, which the diagram itself asks the same way */
    private static @Nullable Object subLevel(Entity entity) {
        try {
            Object helper = Reflect.getStatic(Class.forName("dev.ryanhcode.sable.Sable"), "HELPER");
            Method method = containing;
            if (method == null) {
                method = helper.getClass().getMethod("getContaining", Entity.class);
                containing = method;
            }
            return method.invoke(helper, entity);
        } catch (ReflectiveOperationException none) {
            return null;
        }
    }

    /** Whether Sable has let go of a craft, asked by name as everything else of Sable's here */
    private static boolean removed(Object subLevel) {
        try {
            return (boolean) subLevel.getClass().getMethod("isRemoved").invoke(subLevel);
        } catch (ReflectiveOperationException | RuntimeException unknown) {
            return true;
        }
    }

    static final class RecordedDiagram extends DiagramScreen {
        RecordedDiagram(DiagramEntity entity, ClientSubLevel subLevel) {
            super(entity, subLevel);
        }

        /** Asks the server for forces and saves its settings to it: a screen drawn from a recording does neither */
        @Override
        public void tick() {
        }

        /**
         * Closes the game's screen when its craft goes; a screen drawn from a recording must never close the screen it is
         * drawn under, so it lets go of its framebuffers alone
         */
        @Override
        public void onClose() {
            Reflect.call(this, "freeFramebuffers", new Class<?>[0]);
        }
    }

}
