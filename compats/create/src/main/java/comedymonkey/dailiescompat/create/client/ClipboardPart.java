package comedymonkey.dailiescompat.create.client;

import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.equipment.clipboard.ClipboardContent;
import com.simibubi.create.content.equipment.clipboard.ClipboardEntry;
import com.simibubi.create.content.equipment.clipboard.ClipboardOverrides.ClipboardType;
import com.simibubi.create.content.equipment.clipboard.ClipboardScreen;
import comedymonkey.dailiescompat.create.state.States;
import net.minecraft.client.gui.font.TextFieldHelper;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Create's clipboard, being read or written: a screen only the player's game knows, which sends what was written when it
 * closes. Recorded as the page open (its lines, ticked or not, with their items), which page of how many, the line
 * being written and the cursor in it, a few times a second while it changes. In playback Create's own screen draws a
 * clipboard holding only that page, at its place among the others, and never sends it.
 */
final class ClipboardPart {

    static final String NAME = "clipboard";

    private ClipboardPart() {
    }

    static byte[] capture(Screen screen) {
        ClipboardScreen clipboard = (ClipboardScreen) screen;
        List<ClipboardEntry> entries = Reflect.get(clipboard, "currentEntries");
        List<?> pages = Reflect.get(clipboard, "pages");
        int editing = Reflect.getInt(clipboard, "editingIndex");
        TextFieldHelper typing = Reflect.get(clipboard, "editContext");
        List<States.Entry> recorded = new ArrayList<>(entries.size());
        for (ClipboardEntry entry : entries) {
            recorded.add(new States.Entry(entry.checked, entry.text.getString(), Texts.item(entry.icon), entry.itemAmount));
        }
        boolean readOnly = Reflect.get(clipboard, "readonly");
        return States.clipboard(new States.Clipboard(recorded, Reflect.getInt(clipboard, "currentPage"), pages.size(), editing,
            typing == null ? 0 : typing.getCursorPos(), typing == null ? 0 : typing.getSelectionPos(), readOnly));
    }

    static Screen build(byte[] state) {
        States.Clipboard recorded = States.readClipboard(state);
        int page = Math.max(0, recorded.page());
        List<List<ClipboardEntry>> pages = new ArrayList<>();
        for (int i = 0; i < Math.max(page + 1, recorded.pages()); i++) {
            pages.add(new ArrayList<>());
        }
        for (States.Entry entry : recorded.entries()) {
            ClipboardEntry made = new ClipboardEntry(entry.checked(), Component.literal(entry.text()));
            if (!entry.item().isEmpty()) {
                made.displayItem(Texts.stack(entry.item()), entry.amount());
            }
            pages.get(page).add(made);
        }
        ClipboardContent content = new ClipboardContent(ClipboardType.WRITTEN, pages, recorded.readOnly(), page, Optional.empty());
        DataComponentMap components = DataComponentMap.builder().set(AllDataComponents.CLIPBOARD_CONTENT, content).build();
        return new Recorded(components, recorded);
    }

    static final class Recorded extends ClipboardScreen implements RecordedScreen {
        private final States.Clipboard recorded;

        Recorded(DataComponentMap components, States.Clipboard recorded) {
            super(0, components, null);
            this.recorded = recorded;
            this.putBack();
        }

        @Override
        protected void init() {
            super.init();
            this.putBack();
        }

        /** The line being written and the cursor in it, as recorded */
        private void putBack() {
            int editing = this.recorded.editing();
            List<ClipboardEntry> entries = Reflect.get(this, "currentEntries");
            Reflect.setInt(this, "editingIndex", editing >= 0 && editing < entries.size() ? editing : -1);
            TextFieldHelper typing = Reflect.get(this, "editContext");
            if (typing != null && editing >= 0 && editing < entries.size()) {
                // The cursor, then the selection's other end; both kept within the line
                typing.setSelectionRange(this.recorded.cursor(), this.recorded.selection());
            }
        }

        @Override
        public void tick() {
        }

        @Override
        public void removed() {
            // Never sends what was written
        }
    }

}
