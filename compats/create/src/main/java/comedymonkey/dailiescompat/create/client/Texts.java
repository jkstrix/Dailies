package comedymonkey.dailiescompat.create.client;

import com.simibubi.create.foundation.gui.AllIcons;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * How the states keep what Create draws: text as the game's own JSON for a component (so its colours, translations and
 * key names come back as they were), items by id, and Create's icons by the name of their constant in AllIcons.
 */
final class Texts {

    private static Map<AllIcons, String> iconNames = null;
    private static final Map<String, AllIcons> ICONS = new java.util.concurrent.ConcurrentHashMap<>();

    private Texts() {
    }

    private static HolderLookup.Provider registries() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level != null ? minecraft.level.registryAccess() : RegistryAccess.EMPTY;
    }

    static String json(FormattedText text) {
        Component component = text instanceof Component whole ? whole : Component.literal(text.getString());
        return Component.Serializer.toJson(component, registries());
    }

    static List<String> jsons(List<? extends FormattedText> texts) {
        List<String> out = new ArrayList<>(texts.size());
        for (FormattedText text : texts) {
            out.add(json(text));
        }
        return out;
    }

    static MutableComponent read(String json) {
        if (json == null || json.isEmpty()) {
            return Component.empty();
        }
        try {
            MutableComponent read = Component.Serializer.fromJson(json, registries());
            return read == null ? Component.empty() : read;
        } catch (RuntimeException unreadable) {
            return Component.literal(json);
        }
    }

    static List<Component> reads(List<String> jsons) {
        List<Component> out = new ArrayList<>(jsons.size());
        for (String json : jsons) {
            out.add(read(json));
        }
        return out;
    }

    /** An item's id, or empty for no item */
    static String item(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /** One of the item of this id, or nothing when the id is empty or no such item is in this game */
    static ItemStack stack(String id) {
        ResourceLocation key = id == null || id.isEmpty() ? null : ResourceLocation.tryParse(id);
        if (key == null) {
            return ItemStack.EMPTY;
        }
        return BuiltInRegistries.ITEM.getOptional(key).map(ItemStack::new).orElse(ItemStack.EMPTY);
    }

    /** The name of Create's icon, or empty when it is not one of AllIcons' own */
    static synchronized String icon(@Nullable AllIcons icon) {
        if (icon == null) {
            return "";
        }
        if (iconNames == null) {
            Map<AllIcons, String> names = new IdentityHashMap<>();
            for (Field field : AllIcons.class.getFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == AllIcons.class) {
                    try {
                        names.put((AllIcons) field.get(null), field.getName());
                    } catch (IllegalAccessException ignored) {
                        // Public constants: never
                    }
                }
            }
            iconNames = names;
        }
        return iconNames.getOrDefault(icon, "");
    }

    /** Create's icon of this name, or null when this Create has none such */
    static @Nullable AllIcons icon(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        return ICONS.computeIfAbsent(name, key -> {
            try {
                Field field = AllIcons.class.getField(key);
                return field.getType() == AllIcons.class ? (AllIcons) field.get(null) : null;
            } catch (ReflectiveOperationException none) {
                return null;
            }
        });
    }

}
