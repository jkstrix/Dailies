package comedymonkey.dailiescompat.create.client;

import com.simibubi.create.content.trains.schedule.Schedule;
import comedymonkey.dailiescompat.create.state.States;
import net.createmod.catnip.animation.LerpedFloat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * A train schedule's window is opened by the server with the schedule item in it, so Dailies records and draws it
 * itself; but the schedule being edited is the window's own until it closes, when it is sent. Left alone, playback would
 * show the schedule as it was when opened. These extras keep the schedule as the window holds it (in Create's own saved
 * form) and how far its list is scrolled, and put both back on the window Dailies builds. An entry's editor open over
 * the list is not kept: playback shows the list.
 */
final class ScheduleExtras {

    static final String NAME = "schedule";

    /** The state each window was last given, so a schedule is read and laid out again only when it changes */
    private static final Map<Screen, byte[]> APPLIED = new WeakHashMap<>();
    private static Method init = null;

    private ScheduleExtras() {
    }

    private static HolderLookup.Provider registries() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level != null ? minecraft.level.registryAccess() : RegistryAccess.EMPTY;
    }

    static byte[] capture(AbstractContainerScreen<?> screen) {
        Schedule schedule = Reflect.get(screen, "schedule");
        LerpedFloat scroll = Reflect.get(screen, "scroll");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            NbtIo.write(schedule.write(registries()), out);
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
        return States.schedule(new States.Schedule(bytes.toByteArray(), scroll == null ? 0.0F : scroll.getValue()));
    }

    static void apply(AbstractContainerScreen<?> screen, byte[] state) {
        States.Schedule recorded = States.readSchedule(state);
        if (APPLIED.get(screen) != state) {
            CompoundTag tag;
            try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(recorded.saved()))) {
                tag = NbtIo.read(in, NbtAccounter.unlimitedHeap());
            } catch (IOException damaged) {
                throw new UncheckedIOException(damaged);
            }
            Reflect.set(screen, "schedule", Schedule.fromTag(registries(), tag));
            // The window lays its entries out again, as it does itself whenever one is added or removed
            layOutAgain(screen);
            APPLIED.put(screen, state);
        }
        LerpedFloat scroll = Reflect.get(screen, "scroll");
        if (scroll != null) {
            scroll.startWithValue(recorded.scroll());
        }
    }

    private static void layOutAgain(Screen screen) {
        try {
            if (init == null) {
                Method found = Screen.class.getDeclaredMethod("init");
                found.setAccessible(true);
                init = found;
            }
            init.invoke(screen);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("the schedule's window could not be laid out again", e);
        }
    }

}
