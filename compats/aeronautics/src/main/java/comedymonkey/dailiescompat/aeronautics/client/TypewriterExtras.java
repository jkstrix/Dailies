package comedymonkey.dailiescompat.aeronautics.client;

import com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler.Frequency;
import comedymonkey.dailiescompat.aeronautics.state.AeroStates;
import dev.simulated_team.simulated.content.blocks.redstone.linked_typewriter.LinkedTypewriterEntries;
import dev.simulated_team.simulated.content.blocks.redstone.linked_typewriter.screen.EntryModifierScreen;
import dev.simulated_team.simulated.content.blocks.redstone.linked_typewriter.screen.LinkedTypewriterScreen;
import net.createmod.catnip.animation.LerpedFloat;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Extras for the linked typewriter's window, which the server opens and Dailies draws itself (its slots, the item on the
 * pointer, the pointer): what the window holds that the server never sent. Its keys as they are being set, which the
 * server is sent only when the window closes; the list of keys, opened in place of the keyboard, and how far it is
 * scrolled; and a key's entry opened over either, for which key, waiting or not for a key to be pressed. Put back on the
 * window Dailies builds in playback as Simulated opens them, but without the messages its opening sends about the entry's
 * two slots (the slots themselves come from the window's own records).
 */
final class TypewriterExtras {

    static final String NAME = "linked_typewriter";
    static final String MENU = "simulated:linked_typewriter";

    private TypewriterExtras() {
    }

    static byte @Nullable [] capture(AbstractContainerScreen<?> screen) {
        if (!(screen instanceof LinkedTypewriterScreen typewriter)) {
            return null;
        }
        List<AeroStates.Key> keys = new ArrayList<>();
        for (LinkedTypewriterEntries.KeyboardEntry entry : typewriter.getNewEntries().getEntries()) {
            keys.add(new AeroStates.Key(entry.glfwKeyCode, Texts.item(entry.getFirstAsItemStack()), Texts.item(entry.getSecondAsItemStack())));
        }
        // The window keeps its keys in a map: sorted, the same keys write the same bytes
        keys.sort(Comparator.comparingInt(AeroStates.Key::code));
        Object list = Reflect.get(typewriter, "keyEditorScreen");
        boolean listOpen = Reflect.get(list, "active");
        LerpedFloat scroll = Reflect.get(list, "lerpedScroll");
        EntryModifierScreen modifier = typewriter.modifier;
        boolean entryOpen = modifier.modifying && modifier.psuedoEntry != null;
        boolean waiting = entryOpen && modifier.promptWidget != null && (boolean) Reflect.get(modifier.promptWidget, "bindingActive");
        return AeroStates.typewriter(new AeroStates.Typewriter(keys, listOpen, scroll.getValue(), entryOpen,
            entryOpen ? modifier.psuedoEntry.glfwKeyCode : -1, waiting));
    }

    static void apply(AbstractContainerScreen<?> screen, byte[] state) {
        if (!(screen instanceof LinkedTypewriterScreen typewriter)) {
            return;
        }
        AeroStates.Typewriter recorded = AeroStates.readTypewriter(state);
        Object list = Reflect.get(typewriter, "keyEditorScreen");
        boolean keysChanged = setKeys(typewriter, recorded.keys());
        boolean listOpen = Reflect.get(list, "active");
        if (recorded.listOpen() != listOpen) {
            // As its button opens it and its tick button closes it: only widgets are moved, nothing is sent
            typewriter.switchScreen(recorded.listOpen());
        } else if (keysChanged && listOpen) {
            Reflect.call(list, "rebuildWrappers", new Class<?>[0]);
        }
        if (recorded.listOpen()) {
            ((LerpedFloat) Reflect.get(list, "lerpedScroll")).startWithValue(recorded.scroll());
            Reflect.set(list, "scroll", Math.round(recorded.scroll()));
        }
        setEntry(typewriter, recorded);
    }

    /** The keys as recorded, put in the window's own unsaved keys when they differ; whether they did */
    private static boolean setKeys(LinkedTypewriterScreen typewriter, List<AeroStates.Key> keys) {
        LinkedTypewriterEntries entries = typewriter.getNewEntries();
        List<AeroStates.Key> now = new ArrayList<>();
        for (LinkedTypewriterEntries.KeyboardEntry entry : entries.getEntries()) {
            now.add(new AeroStates.Key(entry.glfwKeyCode, Texts.item(entry.getFirstAsItemStack()), Texts.item(entry.getSecondAsItemStack())));
        }
        now.sort(Comparator.comparingInt(AeroStates.Key::code));
        if (now.equals(keys)) {
            return false;
        }
        entries.getKeyMap().clear();
        for (AeroStates.Key key : keys) {
            entries.setKey(key.code(), new LinkedTypewriterEntries.KeyboardEntry(Frequency.of(Texts.stack(key.first())), Frequency.of(Texts.stack(key.second())),
                key.code(), typewriter.clientBe.getBlockPos()));
        }
        return true;
    }

    /** A key's entry opened over the window, or closed, as recorded: its fields set as opening it sets them, with nothing sent */
    private static void setEntry(LinkedTypewriterScreen typewriter, AeroStates.Typewriter recorded) {
        EntryModifierScreen modifier = typewriter.modifier;
        if (!recorded.entryOpen()) {
            if (modifier.modifying) {
                modifier.modifying = false;
                modifier.psuedoEntry = null;
                keysActive(typewriter, true);
            }
            return;
        }
        if (!modifier.modifying || modifier.psuedoEntry == null) {
            modifier.psuedoEntry = pseudoEntry(modifier);
            modifier.modifying = true;
            keysActive(typewriter, false);
        }
        if (modifier.psuedoEntry != null) {
            modifier.psuedoEntry.glfwKeyCode = recorded.entryKey();
        }
        if (modifier.promptWidget != null) {
            Reflect.set(modifier.promptWidget, "bindingActive", recorded.waitingForKey());
        }
    }

    /** The keyboard's keys and buttons greyed out under an entry, or lit again, as Simulated does */
    private static void keysActive(LinkedTypewriterScreen typewriter, boolean active) {
        Reflect.call(typewriter, "switchStates", new Class<?>[]{boolean.class}, active);
    }

    private static EntryModifierScreen.@Nullable PsuedoKeyboardEntry pseudoEntry(EntryModifierScreen modifier) {
        try {
            Constructor<EntryModifierScreen.PsuedoKeyboardEntry> made = EntryModifierScreen.PsuedoKeyboardEntry.class.getDeclaredConstructor(EntryModifierScreen.class);
            made.setAccessible(true);
            return made.newInstance(modifier);
        } catch (ReflectiveOperationException none) {
            return null;
        }
    }

}
