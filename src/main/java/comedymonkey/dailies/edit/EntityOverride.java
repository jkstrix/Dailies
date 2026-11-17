package comedymonkey.dailies.edit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * The changes made to how one entity is shown (specification chapter V, "What is drawn"): hidden altogether, renamed,
 * glowing outline forced on or off, name tag, cape or armour hidden. Never changes; {@link #with} makes a changed copy.
 */
public record EntityOverride(boolean hide, @Nullable String nameOverride, Glow glowing, boolean hideNametag, boolean hideCape, boolean hideArmour) {

    /** What to do about an entity's glowing outline */
    public enum Glow {
        /** As recorded */
        DEFAULT,
        FORCE_GLOW,
        FORCE_NO_GLOW
    }

    /** No change at all: the entity is shown as recorded */
    public static final EntityOverride NONE = new EntityOverride(false, null, Glow.DEFAULT, false, false, false);

    /** The names {@link #with} accepts */
    public static final List<String> FIELDS = List.of("hide", "name_override", "glowing", "hide_nametag", "hide_cape", "hide_armour");

    public EntityOverride {
        if (glowing == null) {
            glowing = Glow.DEFAULT;
        }
        if (nameOverride != null && nameOverride.isEmpty()) {
            nameOverride = null;
        }
    }

    /** Whether this changes nothing, so need not be kept */
    public boolean isNone() {
        return this.equals(NONE);
    }

    /**
     * A copy with the given fields changed and the others kept. An empty "name_override" removes the new name.
     *
     * @throws IllegalArgumentException for a field that does not exist or a value of the wrong type
     */
    public EntityOverride with(JsonObject changes) {
        boolean hide = this.hide;
        String name = this.nameOverride;
        Glow glow = this.glowing;
        boolean hideNametag = this.hideNametag;
        boolean hideCape = this.hideCape;
        boolean hideArmour = this.hideArmour;
        for (Map.Entry<String, JsonElement> entry : changes.entrySet()) {
            String field = entry.getKey();
            JsonElement value = entry.getValue();
            switch (field) {
                case "hide" -> hide = JsonFields.bool(value, field);
                case "name_override" -> name = value.isJsonNull() ? null : JsonFields.text(value, field);
                case "glowing" -> glow = JsonFields.choice(value, field, Glow.values(), JsonFields::id);
                case "hide_nametag" -> hideNametag = JsonFields.bool(value, field);
                case "hide_cape" -> hideCape = JsonFields.bool(value, field);
                case "hide_armour" -> hideArmour = JsonFields.bool(value, field);
                default -> throw new IllegalArgumentException("There is no entity override called '" + field + "'. The overrides are: " + String.join(", ", FIELDS));
            }
        }
        return new EntityOverride(hide, name, glow, hideNametag, hideCape, hideArmour);
    }

    /** {@link #with} for JSON text */
    public EntityOverride withText(String changes) {
        return this.with(JsonFields.object(changes, "changes"));
    }

    /** {@link #toJson} as JSON text */
    public String toJsonText() {
        return this.toJson().toString();
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("hide", this.hide);
        json.addProperty("name_override", this.nameOverride == null ? "" : this.nameOverride);
        json.addProperty("glowing", JsonFields.id(this.glowing));
        json.addProperty("hide_nametag", this.hideNametag);
        json.addProperty("hide_cape", this.hideCape);
        json.addProperty("hide_armour", this.hideArmour);
        return json;
    }

    /** Reads what can be read; fields that are unknown or wrong are left as recorded */
    public static EntityOverride fromJson(JsonObject json) {
        EntityOverride override = NONE;
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            JsonObject one = new JsonObject();
            one.add(entry.getKey(), entry.getValue());
            try {
                override = override.with(one);
            } catch (IllegalArgumentException ignored) {
                // From a newer or damaged file
            }
        }
        return override;
    }

}
