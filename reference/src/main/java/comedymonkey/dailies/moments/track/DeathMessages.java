package comedymonkey.dailies.moments.track;

import com.google.gson.JsonObject;
import comedymonkey.dailies.moments.MJson;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Reads the structured arguments of the game's death messages ("death.*" translations) and of the advancement
 * announcements ("chat.type.advancement.*"). The rendered text is kept, but who died and who killed whom comes
 * from the arguments: arg 0 is the victim, arg 1 the killer (when the message has one), arg 2 the weapon.
 */
public final class DeathMessages {

    private DeathMessages() {
    }

    /**
     * @param name display name as it appeared in the message
     * @param uuid entity uuid, when the message carried it or the tab list knows the player
     * @param type entity type id, when known
     */
    public record Party(String name, @Nullable UUID uuid, @Nullable String type, boolean isPlayer) {
        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("name", this.name);
            json.addProperty("uuid", this.uuid == null ? null : this.uuid.toString());
            json.addProperty("type", this.type);
            json.addProperty("is_player", this.isPlayer);
            return json;
        }
    }

    public record DeathInfo(String key, String text, Party victim, @Nullable Party killer, @Nullable String weapon, @Nullable String weaponName) {
        public void applyTo(JsonObject metadata) {
            metadata.addProperty("death_key", this.key);
            metadata.addProperty("message", this.text);
            metadata.add("victim", this.victim.toJson());
            if (this.killer != null) {
                metadata.add("killer", this.killer.toJson());
                metadata.addProperty("killer_is_player", this.killer.isPlayer());
            }
            if (this.weaponName != null) {
                metadata.addProperty("weapon", this.weapon != null ? this.weapon : this.weaponName);
                metadata.addProperty("weapon_name", this.weaponName);
            }
        }
    }

    public record AdvancementInfo(String key, String text, Party player, String title) {}

    /** The first translatable with the given key prefix in the component or its children */
    @Nullable
    private static TranslatableContents findTranslatable(Component component, String prefix, int depth) {
        if (component.getContents() instanceof TranslatableContents translatable) {
            if (translatable.getKey().startsWith(prefix)) {
                return translatable;
            }
            // Servers and mods like to wrap messages, eg. in a "%s" decoration
            if (depth < 3) {
                for (Object arg : translatable.getArgs()) {
                    if (arg instanceof Component argComponent) {
                        TranslatableContents found = findTranslatable(argComponent, prefix, depth + 1);
                        if (found != null) return found;
                    }
                }
            }
        }
        if (depth < 3) {
            for (Component sibling : component.getSiblings()) {
                TranslatableContents found = findTranslatable(sibling, prefix, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Nullable
    public static DeathInfo parseDeath(@Nullable Component message) {
        if (message == null) {
            return null;
        }
        TranslatableContents contents = findTranslatable(message, "death.", 0);
        if (contents == null || contents.getArgs().length == 0) {
            return null;
        }
        Object[] args = contents.getArgs();
        Party victim = party(args[0]);
        Party killer = args.length > 1 ? party(args[1]) : null;

        String weapon = null;
        String weaponName = null;
        if (args.length > 2) {
            weaponName = argText(args[2]);
            if (args[2] instanceof Component component) {
                HoverEvent hover = findHover(component, 0);
                HoverEvent.ItemStackInfo item = hover == null ? null : hover.getValue(HoverEvent.Action.SHOW_ITEM);
                if (item != null) {
                    ItemStack stack = item.getItemStack();
                    weapon = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                }
            }
        }
        return new DeathInfo(contents.getKey(), message.getString(), victim, killer, weapon, weaponName);
    }

    @Nullable
    public static AdvancementInfo parseAdvancement(@Nullable Component message) {
        if (message == null) {
            return null;
        }
        TranslatableContents contents = findTranslatable(message, "chat.type.advancement.", 0);
        if (contents == null || contents.getArgs().length < 2) {
            return null;
        }
        String title = argText(contents.getArgs()[1]).trim();
        if (title.startsWith("[") && title.endsWith("]") && title.length() > 2) {
            title = title.substring(1, title.length() - 1);
        }
        return new AdvancementInfo(contents.getKey(), message.getString(), party(contents.getArgs()[0]), title);
    }

    private static String argText(Object arg) {
        return arg instanceof Component component ? component.getString() : String.valueOf(arg);
    }

    @Nullable
    private static HoverEvent findHover(Component component, int depth) {
        HoverEvent hover = component.getStyle().getHoverEvent();
        if (hover != null) {
            return hover;
        }
        if (depth < 3) {
            if (component.getContents() instanceof TranslatableContents translatable) {
                for (Object arg : translatable.getArgs()) {
                    if (arg instanceof Component argComponent) {
                        HoverEvent found = findHover(argComponent, depth + 1);
                        if (found != null) return found;
                    }
                }
            }
            for (Component sibling : component.getSiblings()) {
                HoverEvent found = findHover(sibling, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Work out who a name argument refers to: from the entity the name links to, else from the tab list */
    private static Party party(Object arg) {
        String name = argText(arg).trim();
        UUID uuid = null;
        String type = null;
        boolean isPlayer = false;

        if (arg instanceof Component component) {
            HoverEvent hover = findHover(component, 0);
            HoverEvent.EntityTooltipInfo entity = hover == null ? null : hover.getValue(HoverEvent.Action.SHOW_ENTITY);
            if (entity != null) {
                uuid = entity.id;
                type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.type).toString();
                isPlayer = entity.type == EntityType.PLAYER;
            }
        }

        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection != null) {
            if (uuid != null && isPlayer) {
                PlayerInfo info = connection.getPlayerInfo(uuid);
                if (info != null) {
                    name = info.getProfile().getName();
                }
            } else if (uuid == null) {
                // No entity link (plugins often strip it): match the plain or team-decorated name against the tab list
                PlayerInfo match = null;
                for (PlayerInfo info : connection.getOnlinePlayers()) {
                    String profileName = info.getProfile().getName();
                    Component display = info.getTabListDisplayName();
                    if (name.equals(profileName) || (display != null && name.equals(display.getString()))) {
                        match = info;
                        break;
                    }
                    if (name.contains(profileName) && (match == null || profileName.length() > match.getProfile().getName().length())) {
                        match = info;
                    }
                }
                if (match != null) {
                    uuid = match.getProfile().getId();
                    name = match.getProfile().getName();
                    type = "minecraft:player";
                    isPlayer = true;
                }
            }
        }
        return new Party(name, uuid, type, isPlayer);
    }

}
