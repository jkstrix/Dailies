package comedymonkey.dailiescompat.create.client;

import com.simibubi.create.content.trains.schedule.IScheduleInput;
import com.simibubi.create.content.trains.schedule.Schedule;
import com.simibubi.create.content.trains.schedule.ScheduleMenu;
import com.simibubi.create.content.trains.schedule.condition.ScheduleWaitCondition;
import com.simibubi.create.content.trains.schedule.destination.ScheduleInstruction;
import com.simibubi.create.foundation.gui.AllIcons;
import com.simibubi.create.foundation.gui.widget.CompositeWidget;
import com.simibubi.create.foundation.gui.widget.IconButton;
import com.simibubi.create.foundation.gui.widget.Indicator;
import com.simibubi.create.foundation.gui.widget.Label;
import com.simibubi.create.foundation.gui.widget.SelectionScrollInput;
import com.simibubi.create.foundation.utility.CreateLang;
import comedymonkey.dailiescompat.create.state.States;
import net.createmod.catnip.animation.LerpedFloat;
import net.createmod.catnip.data.Pair;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * A train schedule's window is opened by the server with the schedule item in it, so Dailies records and draws it
 * itself; but the schedule being edited is the window's own until it closes, when it is sent. Left alone, playback would
 * show the schedule as it was when opened. These extras keep the schedule as the window holds it (in Create's own saved
 * form) and how far its list is scrolled, and an entry's editor open over the list (the entry with what its fields hold
 * as typed, and the items in its slots), and put them back on the window Dailies builds. The editor is opened there as
 * Create opens it, without the messages Create's own opening sends about its slots, and put away again without them.
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

    private static byte[] bytes(CompoundTag tag) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            NbtIo.write(tag, out);
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
        return bytes.toByteArray();
    }

    private static CompoundTag tag(byte[] bytes) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            return NbtIo.read(in, NbtAccounter.unlimitedHeap());
        } catch (IOException damaged) {
            throw new UncheckedIOException(damaged);
        }
    }

    static byte[] capture(AbstractContainerScreen<?> screen) {
        Schedule schedule = Reflect.get(screen, "schedule");
        LerpedFloat scroll = Reflect.get(screen, "scroll");
        byte[] saved = bytes(schedule.write(registries()));
        float scrolled = scroll == null ? 0.0F : scroll.getValue();
        ScheduleInstruction instruction = Reflect.get(screen, "editingDestination");
        ScheduleWaitCondition condition = Reflect.get(screen, "editingCondition");
        if (instruction == null && condition == null) {
            return States.schedule(new States.Schedule(saved, scrolled));
        }
        // The entry as the editor holds it: Create's saved form, with what the editor's fields hold put into its data,
        // as Create puts them when the editor closes
        CompoundTag entry = instruction != null ? instruction.write(registries()) : condition.write(registries());
        Reflect.call(Reflect.get(screen, "editorSubWidgets"), "save", CompoundTag.class, entry.getCompound("Data"));
        IScheduleInput editing = instruction != null ? instruction : condition;
        ScheduleMenu menu = (ScheduleMenu) screen.getMenu();
        List<String> slots = new ArrayList<>();
        for (int i = 0; i < editing.slotsTargeted(); i++) {
            slots.add(Texts.item(menu.ghostInventory.getStackInSlot(i)));
        }
        return States.schedule(new States.Schedule(saved, scrolled, instruction != null ? States.Schedule.INSTRUCTION : States.Schedule.CONDITION,
            bytes(entry), Reflect.get(screen, "editorDelete") != null, slots));
    }

    static void apply(AbstractContainerScreen<?> screen, byte[] state) {
        States.Schedule recorded = States.readSchedule(state);
        if (APPLIED.get(screen) != state) {
            closeEditor(screen);
            Reflect.set(screen, "schedule", Schedule.fromTag(registries(), tag(recorded.saved())));
            // The window lays its entries out again, as it does itself whenever one is added or removed
            layOutAgain(screen);
            if (recorded.editing() != States.Schedule.NOT_EDITING) {
                openEditor(screen, recorded);
            }
            APPLIED.put(screen, state);
        }
        LerpedFloat scroll = Reflect.get(screen, "scroll");
        if (scroll != null) {
            scroll.startWithValue(recorded.scroll());
        }
    }

    /**
     * Opens the entry's editor as ScheduleScreen.startEditing does, but for the slots' messages to the server: the
     * window's other buttons hidden, the type picker, the confirm and delete buttons, and the entry's own fields
     */
    private static void openEditor(AbstractContainerScreen<?> screen, States.Schedule recorded) {
        CompoundTag entry = tag(recorded.entry());
        boolean instruction = recorded.editing() == States.Schedule.INSTRUCTION;
        IScheduleInput field = instruction ? ScheduleInstruction.fromTag(registries(), entry) : ScheduleWaitCondition.fromTag(registries(), entry);
        ScheduleMenu menu = (ScheduleMenu) screen.getMenu();
        for (int i = 0; i < field.slotsTargeted(); i++) {
            ItemStack item = i < recorded.slots().size() ? Texts.stack(recorded.slots().get(i)) : ItemStack.EMPTY;
            field.setItem(i, item);
            menu.ghostInventory.setStackInSlot(i, item);
        }
        for (String button : new String[]{"confirmButton", "cyclicButton", "skipProgress", "resetProgress"}) {
            ((AbstractWidget) Reflect.get(screen, button)).visible = false;
        }
        ((Indicator) Reflect.get(screen, "cyclicIndicator")).visible = false;

        int left = screen.getGuiLeft();
        int top = screen.getGuiTop();
        SelectionScrollInput scrollInput = new SelectionScrollInput(left + 56, top + 65, 143, 16);
        Label scrollInputLabel = new Label(left + 59, top + 69, CommonComponents.EMPTY).withShadow();
        IconButton editorConfirm = new IconButton(left + 56 + 168, top + 65 + 22, AllIcons.I_CONFIRM);
        IconButton editorDelete = recorded.deletable() ? new IconButton(left + 56 - 45, top + 65 + 22, AllIcons.I_TRASH) : null;
        menu.slotsActive = true;
        menu.targetSlotsActive = field.slotsTargeted();

        if (instruction) {
            Reflect.set(screen, "editingDestination", field);
        } else {
            Reflect.set(screen, "editingCondition", field);
        }
        Reflect.call(screen, "updateEditorSubwidgets", IScheduleInput.class, field);
        List<? extends Pair<ResourceLocation, ?>> types = instruction ? Schedule.INSTRUCTION_TYPES : Schedule.CONDITION_TYPES;
        int startIndex = 0;
        for (int i = 0; i < types.size(); i++) {
            if (types.get(i).getFirst().equals(field.getId())) {
                startIndex = i;
            }
        }
        scrollInput.forOptions(instruction ? Schedule.getTypeOptions(Schedule.INSTRUCTION_TYPES) : Schedule.getTypeOptions(Schedule.CONDITION_TYPES))
            .titled(CreateLang.translateDirect(instruction ? "schedule.instruction_type" : "schedule.condition_type"))
            .writingTo(scrollInputLabel)
            .setState(startIndex);
        Reflect.set(screen, "scrollInput", scrollInput);
        Reflect.set(screen, "scrollInputLabel", scrollInputLabel);
        Reflect.set(screen, "editorConfirm", editorConfirm);
        Reflect.set(screen, "editorDelete", editorDelete);

        CompositeWidget editor = Reflect.get(screen, "editorSubWidgets");
        editor.add(scrollInput);
        editor.add(scrollInputLabel);
        editor.add(editorConfirm);
        if (editorDelete != null) {
            editor.add(editorDelete);
        }
    }

    /**
     * Puts an editor opened for playback away as ScheduleScreen.stopEditing does, but without telling the server about its
     * slots or saving its fields into the entry: the window is laid out afresh from the recorded schedule straight after
     */
    private static void closeEditor(AbstractContainerScreen<?> screen) {
        if (Reflect.get(screen, "editingDestination") == null && Reflect.get(screen, "editingCondition") == null) {
            return;
        }
        // Before anything lays the window out: its own laying out puts an open editor away by telling the server
        Reflect.set(screen, "editingDestination", null);
        Reflect.set(screen, "editingCondition", null);
        for (String widget : new String[]{"scrollInput", "scrollInputLabel", "editorConfirm", "editorDelete"}) {
            Object held = Reflect.get(screen, widget);
            if (held instanceof GuiEventListener listener) {
                Reflect.call(screen, "removeWidget", GuiEventListener.class, listener);
            }
        }
        ((CompositeWidget) Reflect.get(screen, "editorSubWidgets")).clear();
        Reflect.set(screen, "editorConfirm", null);
        Reflect.set(screen, "editorDelete", null);
        ((ScheduleMenu) screen.getMenu()).slotsActive = false;
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
