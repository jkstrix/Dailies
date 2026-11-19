package comedymonkey.dailiescompat.createextras.client;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.railwayteam.railways.api.bogeymenu.v0.entry.BogeyEntry;
import com.railwayteam.railways.api.bogeymenu.v0.entry.CategoryEntry;
import com.railwayteam.railways.content.bogey_menu.BogeyMenuScreen;
import com.railwayteam.railways.content.cycle_menu.RadialTagCycleMenu;
import com.railwayteam.railways.content.switches.TrainHUDSwitchExtension;
import comedymonkey.dailiescompat.createextras.state.ExtrasStates;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Steam 'n' Rails' play screens and overlay that only the player's game knows:
 * <ul>
 * <li>the bogey menu ({@link #BOGEYS}), opened by its key while railway casing is held: the category shown, the bogey
 * style picked, the list's scroll and how long it had been open; drawn with Steam 'n' Rails' own menu, which in playback
 * never sends the style picked;</li>
 * <li>the tag wheel ({@link #TAG_WHEEL}), opened by its key while an item of a cycled tag (track, say) is held, a selection
 * wheel used in play: the tag, the items round it, the one under the pointer, the scroll, and how far it had grown in;
 * drawn with Steam 'n' Rails' own wheel, held by a screen of the compat's that never ticks, clicks or closes it, so the
 * item it picks as it closes is never picked;</li>
 * <li>the switch on the train's bar ({@link #SWITCH}), which Steam 'n' Rails adds to Create's while a train is driven
 * towards a track switch: recorded as drawn ({@link Page}). Create's own bar is the Create compat's.</li>
 * </ul>
 */
final class RailsParts {

    static final String BOGEYS = "bogey_menu";
    static final String TAG_WHEEL = "tag_wheel";
    static final String SWITCH = "train_switch";

    private static final String MANAGER = "com.railwayteam.railways.impl.bogeymenu.v0.BogeyMenuManagerImpl";

    private RailsParts() {
    }

    // --- The bogey menu ---

    static byte[] captureBogeys(Screen screen) {
        int category = Reflect.get(screen, "categoryIndex");
        BogeyEntry picked = Reflect.get(screen, "selectedBogey");
        float scroll = Reflect.get(screen, "scrollOffs");
        int ticksOpen = Reflect.get(screen, "ticksOpen");
        String style = "";
        if (picked != null && picked.bogeyStyle() != null) {
            style = picked.bogeyStyle().id.toString();
        }
        return ExtrasStates.bogeys(new ExtrasStates.Bogeys(category, style, scroll, ticksOpen));
    }

    static @Nullable Screen buildBogeys(byte[] state) {
        ExtrasStates.Bogeys recorded = ExtrasStates.readBogeys(state);
        List<CategoryEntry> categories = categories();
        if (categories.isEmpty()) {
            return null;
        }
        return new RecordedBogeys(recorded, categories.get(Math.clamp(recorded.category(), 0, categories.size() - 1)));
    }

    private static List<CategoryEntry> categories() {
        Class<?> manager = Reflect.find(MANAGER);
        if (manager == null) {
            return List.of();
        }
        List<CategoryEntry> categories = Reflect.getStatic(manager, "CATEGORIES");
        return categories == null ? List.of() : categories;
    }

    static final class RecordedBogeys extends BogeyMenuScreen {
        private final ExtrasStates.Bogeys recorded;

        RecordedBogeys(ExtrasStates.Bogeys recorded, CategoryEntry category) {
            this.recorded = recorded;
            // The category is picked before the list is laid out, as turning the category scroll picks it
            Reflect.set(this, "categoryIndex", Math.max(0, recorded.category()));
            Reflect.set(this, "selectedCategory", category);
        }

        @Override
        protected void init() {
            super.init();
            CategoryEntry category = Reflect.get(this, "selectedCategory");
            for (BogeyEntry entry : category.getBogeyEntryList()) {
                if (entry.bogeyStyle() != null && entry.bogeyStyle().id.toString().equals(this.recorded.style())) {
                    Reflect.set(this, "selectedBogey", entry);
                }
            }
            Reflect.call(this, "scrollTo", new Class<?>[]{float.class}, this.recorded.scroll());
            Reflect.set(this, "ticksOpen", this.recorded.ticksOpen());
        }

        /** Counts the ticks it has been open, and plays its sound: neither in playback */
        @Override
        public void tick() {
        }

        @Override
        public void onClose() {
        }
    }

    // --- The tag wheel ---

    static byte[] captureTagWheel(Screen screen) {
        TagKey<Item> tag = Reflect.get(screen, "tag");
        List<Item> cycle = Reflect.get(screen, "cycle");
        CompoundTag stackTag = Reflect.get(screen, "stackTag");
        int hovered = Reflect.get(screen, "hoveredSlot");
        boolean scrolling = Reflect.get(screen, "scrollMode");
        int scrolledTo = Reflect.get(screen, "scrollSlot");
        int ticksOpen = Reflect.get(screen, "ticksOpen");
        List<String> items = new ArrayList<>(cycle.size());
        for (Item item : cycle) {
            items.add(BuiltInRegistries.ITEM.getKey(item).toString());
        }
        return ExtrasStates.wheel(new ExtrasStates.Wheel(tag.location().toString(), items, hovered, ticksOpen, scrolling, scrolledTo,
            stackTag == null ? "" : stackTag.toString()));
    }

    static @Nullable Screen buildTagWheel(byte[] state) {
        ExtrasStates.Wheel recorded = ExtrasStates.readWheel(state);
        ResourceLocation id = ResourceLocation.tryParse(recorded.subject());
        if (id == null) {
            return null;
        }
        List<Item> items = new ArrayList<>();
        for (String each : recorded.items()) {
            ItemStack stack = Texts.stack(each);
            if (!stack.isEmpty()) {
                items.add(stack.getItem());
            }
        }
        CompoundTag stackTag = null;
        if (!recorded.tag().isEmpty()) {
            try {
                stackTag = TagParser.parseTag(recorded.tag());
            } catch (CommandSyntaxException unreadable) {
                stackTag = null;
            }
        }
        RadialTagCycleMenu wheel = Reflect.make(RadialTagCycleMenu.class, new Class<?>[]{TagKey.class, List.class, CompoundTag.class},
            TagKey.create(Registries.ITEM, id), items, stackTag);
        Reflect.set(wheel, "ticksOpen", recorded.ticksOpen());
        Reflect.set(wheel, "hoveredSlot", recorded.picked());
        Reflect.set(wheel, "scrollMode", recorded.scrolling());
        Reflect.set(wheel, "scrollSlot", recorded.scrolledTo());
        return new Held(wheel);
    }

    // --- The switch on the train's bar ---

    /** Steam 'n' Rails' own drawing of its switch, run onto a page while it would draw on the recorder's bar */
    static byte @Nullable [] captureSwitch() {
        if (TrainHUDSwitchExtension.switchState == null) {
            return null;
        }
        Page page = new Page();
        TrainHUDSwitchExtension.renderOverlay(page, 1.0F, page.guiWidth(), page.guiHeight());
        return page.state();
    }

}
