package comedymonkey.dailiescompat.jade.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.RegistryOps;
import org.jetbrains.annotations.Nullable;

/**
 * How the state keeps what Jade draws: text as the game's own JSON for a component (so its colours and translations come
 * back as they were), and items, fluids, styles and themes as the JSON their own codecs make, with the world's registries
 * so an item's enchantments and the rest come back too.
 */
final class Json {

    private Json() {
    }

    private static HolderLookup.Provider registries() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level != null ? minecraft.level.registryAccess() : RegistryAccess.EMPTY;
    }

    private static RegistryOps<JsonElement> ops() {
        return registries().createSerializationContext(JsonOps.INSTANCE);
    }

    static String text(FormattedText text) {
        Component component = text instanceof Component whole ? whole : Component.literal(text.getString());
        return write(ComponentSerialization.CODEC, component);
    }

    static Component readText(String json) {
        if (json == null || json.isEmpty()) {
            return Component.empty();
        }
        Component read = read(ComponentSerialization.CODEC, json);
        return read == null ? Component.literal(json) : read;
    }

    /** What the codec makes of the value, or empty when it cannot (a plugin's own kind of style, say) */
    static <T> String write(Codec<T> codec, T value) {
        try {
            return codec.encodeStart(ops(), value).result().map(JsonElement::toString).orElse("");
        } catch (RuntimeException notOurs) {
            return "";
        }
    }

    /** The value the codec reads from the JSON, or null when it is empty or unreadable */
    static <T> @Nullable T read(Codec<T> codec, String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return codec.parse(ops(), JsonParser.parseString(json)).result().orElse(null);
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

}
