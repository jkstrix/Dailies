package comedymonkey.dailies.edit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The settings of a scene that are not on the timeline (specification chapter V, "Looks that are not on the
 * timeline"): what is drawn, the world's look and the frame. Saved with the scene, applied in playback and export.
 * <p>
 * Each setting is a plain field for the game to read, and can also be read and set by its snake_case name with the
 * value checked ({@link #get}, {@link #setAll}, {@link #describe}), which is what the settings panel and the MCP
 * tools use. Changes per entity are kept separately, see {@link #entityOverride}.
 */
public final class Looks {

    /** What the weather is made to be */
    public enum Weather {
        AS_RECORDED, CLEAR, RAIN, THUNDER
    }

    /** What is done about fog */
    public enum FogMode {
        AS_RECORDED,
        OFF,
        /** Fog from {@link #fogStart} to {@link #fogEnd} in {@link #fogColour} */
        CUSTOM
    }

    /** The frame shape drawn over the view as a guide */
    public enum AspectGuide {
        OFF("off", 0.0),
        WIDE_16_9("16:9", 16.0 / 9.0),
        TALL_9_16("9:16", 9.0 / 16.0),
        CLASSIC_4_3("4:3", 4.0 / 3.0),
        SQUARE_1_1("1:1", 1.0),
        CINEMA_21_9("21:9", 21.0 / 9.0),
        /** Uses {@link #customAspect} */
        CUSTOM("custom", 0.0);

        private final String id;
        private final double ratio;

        AspectGuide(String id, double ratio) {
            this.id = id;
            this.ratio = ratio;
        }

        /** The name used in JSON and shown to people, for example "16:9" */
        public String id() {
            return this.id;
        }
    }

    // What is drawn
    public boolean showHud = true;
    public boolean showChat = true;
    public boolean showBossBars = true;
    public boolean showTitles = true;
    public boolean showNameTags = true;
    public boolean showParticles = true;
    /** Held items in first person */
    public boolean showHeldItems = true;
    public boolean showBlockOutline = true;
    /** The viewer's own shadow */
    public boolean showOwnShadow = true;
    /** The scoreboard down the side of the screen */
    public boolean showScoreboard = true;
    /** The line of words above the hotbar */
    public boolean showActionBar = true;
    /** The hotbar, the game's own and the recorder's when riding along */
    public boolean showHotbar = true;
    /** The terrain and everything built in it, block entities included */
    public boolean showBlocks = true;
    /** Everything that is an entity and not a player */
    public boolean showEntities = true;
    public boolean showPlayers = true;
    public boolean showBeaconBeams = true;
    /** Ids of entity types that are not drawn, for example "minecraft:bat" */
    public List<String> hiddenEntityTypes = new ArrayList<>();
    /** Ids of particle types that are not drawn, for example "minecraft:rain" */
    public List<String> hiddenParticleTypes = new ArrayList<>();

    // The world's look
    public Weather weather = Weather.AS_RECORDED;
    public FogMode fogMode = FogMode.AS_RECORDED;
    /** Blocks from the camera at which custom fog begins */
    public double fogStart = 0.0;
    /** Blocks from the camera at which custom fog hides everything; never less than {@link #fogStart} */
    public double fogEnd = 192.0;
    /** 0xRRGGBB */
    public int fogColour = 0xC0D8FF;
    public boolean sky = true;
    /** 0xRRGGBB, drawn in place of the sky when it is off (for keying) */
    public int backgroundColour = 0x00FF00;
    /** Full brightness without the potion's tint */
    public boolean nightVision = false;
    /** The resource packs the server gave the recorder, as recorded (PLAY-17). Off shows the viewer's own packs alone */
    public boolean serverResourcePacks = true;

    // The frame
    /** Field of view in degrees when no track sets it; 0 leaves the game's own alone */
    public double fov = 0.0;
    /** Camera roll in degrees, added when no camera keyframe sets it */
    public double roll = 0.0;
    public AspectGuide aspectGuide = AspectGuide.OFF;
    /** Width divided by height, used by {@link AspectGuide#CUSTOM} */
    public double customAspect = 2.39;
    /** The frame shape is the picture itself: black bars in the editor, the true size in an export */
    public boolean letterbox = false;
    /** Rule-of-thirds lines, only ever shown in the editor */
    public boolean thirdsGuide = false;
    /** Centre cross, only ever shown in the editor */
    public boolean centreGuide = false;

    private final Map<UUID, EntityOverride> entityOverrides = new TreeMap<>();

    /** The width divided by the height of the aspect guide, or 0 when the guide is off */
    public double aspectRatio() {
        return this.aspectGuide == AspectGuide.CUSTOM ? this.customAspect : this.aspectGuide.ratio;
    }

    /** The width divided by the height the picture is held to, or 0 when the frame shape is only a guide */
    public double frameRatio() {
        return this.letterbox ? this.aspectRatio() : 0.0;
    }

    /**
     * The size an export of the asked size comes out at: the largest picture of the frame's shape that fits inside
     * it, with even sides, since most video encoders take no others. The asked size itself when the frame shape is
     * only a guide.
     *
     * @return {width, height}
     */
    public int[] frameSize(int width, int height) {
        double ratio = this.frameRatio();
        if (ratio <= 0.0 || width <= 0 || height <= 0) {
            return new int[]{width, height};
        }
        if (ratio > (double) width / height) {
            return new int[]{width, Math.max(2, Math.min(height, even(width / ratio)))};
        }
        return new int[]{Math.max(2, Math.min(width, even(height * ratio))), height};
    }

    private static int even(double pixels) {
        return (int) Math.round(pixels / 2.0) * 2;
    }

    /**
     * The part of a view of the given shape that the frame leaves clear, as shares of the view from 0 to 1.
     *
     * @param ratio      width divided by height of the frame; 0 or less for none
     * @param viewRatio  width divided by height of the view
     * @return {left, top, right, bottom}
     */
    public static double[] clearPart(double ratio, double viewRatio) {
        if (ratio <= 0.0 || viewRatio <= 0.0) {
            return new double[]{0.0, 0.0, 1.0, 1.0};
        }
        if (ratio < viewRatio) {
            // Narrower than the view: bars down the sides
            double inner = ratio / viewRatio;
            return new double[]{(1.0 - inner) / 2.0, 0.0, (1.0 + inner) / 2.0, 1.0};
        }
        double inner = viewRatio / ratio;
        return new double[]{0.0, (1.0 - inner) / 2.0, 1.0, (1.0 + inner) / 2.0};
    }

    /**
     * The field of view a view must be drawn with so that what shows between the bars of a frame wider than the view
     * is what a picture of the frame's own shape would hold at the wanted field of view. A frame narrower than the
     * view needs nothing: its bars only cut the sides off.
     *
     * @param fov degrees up and down that the framed picture is to have
     */
    public static double fovBehindBars(double fov, double ratio, double viewRatio) {
        if (ratio <= 0.0 || viewRatio <= 0.0 || ratio <= viewRatio || !(fov > 0.0 && fov < 180.0)) {
            return fov;
        }
        double widened = Math.toDegrees(2.0 * Math.atan(Math.tan(Math.toRadians(fov) / 2.0) * ratio / viewRatio));
        return Math.min(widened, 179.0);
    }

    // ------------------------------------------------------------------ Per entity

    /** The changes made to one entity; {@link EntityOverride#NONE} when there are none */
    public EntityOverride entityOverride(UUID entity) {
        return this.entityOverrides.getOrDefault(entity, EntityOverride.NONE);
    }

    /** Sets the changes made to one entity. Setting {@link EntityOverride#NONE} forgets the entity */
    public void setEntityOverride(UUID entity, EntityOverride override) {
        if (override == null || override.isNone()) {
            this.entityOverrides.remove(entity);
        } else {
            this.entityOverrides.put(entity, override);
        }
    }

    /** Every entity that has a change, in UUID order. Cannot be changed through this view */
    public Map<UUID, EntityOverride> entityOverrides() {
        return Collections.unmodifiableMap(this.entityOverrides);
    }

    /** The individual entities that are hidden */
    public Set<UUID> hiddenEntities() {
        Set<UUID> hidden = new TreeSet<>();
        for (Map.Entry<UUID, EntityOverride> entry : this.entityOverrides.entrySet()) {
            if (entry.getValue().hide()) {
                hidden.add(entry.getKey());
            }
        }
        return hidden;
    }

    /** Hides or shows one individual entity, keeping its other changes */
    public void setEntityHidden(UUID entity, boolean hidden) {
        EntityOverride old = this.entityOverride(entity);
        this.setEntityOverride(entity, new EntityOverride(hidden, old.nameOverride(), old.glowing(), old.hideNametag(), old.hideCape(), old.hideArmour()));
    }

    // ------------------------------------------------------------------ Settings by name

    /** The names of all settings, in the order a panel would show them */
    public static List<String> fieldNames() {
        return List.copyOf(FIELDS.keySet());
    }

    /**
     * The value of one setting as JSON: true/false, a number, an enum's id, a colour as "#RRGGBB" or a list of
     * strings.
     *
     * @throws IllegalArgumentException when there is no such setting
     */
    public JsonElement get(String name) {
        return field(name).get(this);
    }

    /** Sets one setting; see {@link #setAll} */
    public void set(String name, JsonElement value) {
        JsonObject values = new JsonObject();
        values.add(name, value);
        this.setAll(values);
    }

    /**
     * Sets several settings at once, by name. Colours are accepted as "#RRGGBB" or as [r, g, b] with parts from 0 to
     * 1. Nothing is changed if any name or value is wrong.
     *
     * @throws IllegalArgumentException naming the setting that is wrong and what it allows
     */
    public void setAll(JsonObject values) {
        Looks changed = this.copy();
        for (Map.Entry<String, JsonElement> entry : values.entrySet()) {
            field(entry.getKey()).set(changed, entry.getValue());
        }
        changed.validate();
        this.takeFrom(changed);
    }

    /** {@link #get} as JSON text: true, 40.0, "rain", "#FF8000" or ["minecraft:bat"] */
    public String getText(String name) {
        return this.get(name).toString();
    }

    /** {@link #setAll} for JSON text such as {"night_vision": true} */
    public void setAllText(String values) {
        this.setAll(JsonFields.object(values, "values"));
    }

    /** {@link #describe} as JSON text */
    public String describeText() {
        return this.describe().toString();
    }

    /** {@link #toJson} as JSON text */
    public String toJsonText() {
        return this.toJson().toString();
    }

    /** {@link #fromJson} for JSON text */
    public static Looks fromJsonText(String json) {
        return fromJson(JsonFields.object(json, "looks"));
    }

    /**
     * Every setting with its type, its value and what it allows, for a panel or an agent to read:
     * {"fog_mode": {"type": "enum", "value": "off", "values": [...], "description": "..."}, ...}.
     */
    public JsonObject describe() {
        JsonObject json = new JsonObject();
        for (Field field : FIELDS.values()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("type", field.type);
            entry.add("value", field.get(this));
            field.limits(entry);
            entry.addProperty("description", field.description);
            json.add(field.name, entry);
        }
        return json;
    }

    /** Checks the rules that involve more than one setting */
    public void validate() {
        if (this.fogEnd < this.fogStart) {
            throw new IllegalArgumentException("'fog_end' must not be less than 'fog_start' (" + JsonFields.plain(this.fogStart) + "), got "
                    + JsonFields.plain(this.fogEnd));
        }
    }

    /** A separate object with the same settings */
    public Looks copy() {
        Looks copy = new Looks();
        copy.takeFrom(this);
        return copy;
    }

    private void takeFrom(Looks other) {
        for (Field field : FIELDS.values()) {
            field.set(this, field.get(other));
        }
        this.entityOverrides.clear();
        this.entityOverrides.putAll(other.entityOverrides);
    }

    /** All settings by name, and the per-entity changes under "entity_overrides" by UUID */
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        for (Field field : FIELDS.values()) {
            json.add(field.name, field.get(this));
        }
        JsonObject overrides = new JsonObject();
        for (Map.Entry<UUID, EntityOverride> entry : this.entityOverrides.entrySet()) {
            overrides.add(entry.getKey().toString(), entry.getValue().toJson());
        }
        json.add("entity_overrides", overrides);
        return json;
    }

    /** Reads what can be read; settings that are missing, unknown or wrong keep their defaults */
    public static Looks fromJson(@Nullable JsonObject json) {
        Looks looks = new Looks();
        if (json == null) {
            return looks;
        }
        for (Field field : FIELDS.values()) {
            if (json.has(field.name)) {
                try {
                    field.set(looks, json.get(field.name));
                } catch (IllegalArgumentException ignored) {
                    // From a newer or damaged file
                }
            }
        }
        if (looks.fogEnd < looks.fogStart) {
            looks.fogEnd = looks.fogStart;
        }
        JsonElement overrides = json.get("entity_overrides");
        if (overrides != null && overrides.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : overrides.getAsJsonObject().entrySet()) {
                try {
                    if (entry.getValue().isJsonObject()) {
                        looks.setEntityOverride(JsonFields.uuid(entry.getKey(), "uuid"), EntityOverride.fromJson(entry.getValue().getAsJsonObject()));
                    }
                } catch (IllegalArgumentException ignored) {
                    // Not a UUID
                }
            }
        }
        return looks;
    }

    // ------------------------------------------------------------------ The table of settings

    private static Field field(String name) {
        Field field = FIELDS.get(name);
        if (field == null) {
            throw new IllegalArgumentException("There is no visual setting called '" + name + "'. The settings are: " + String.join(", ", FIELDS.keySet()));
        }
        return field;
    }

    /** One setting: its name, how to read and write it, and what it allows */
    private abstract static class Field {
        final String name;
        final String type;
        final String description;

        Field(String name, String type, String description) {
            this.name = name;
            this.type = type;
            this.description = description;
        }

        abstract JsonElement get(Looks looks);

        abstract void set(Looks looks, JsonElement value);

        void limits(JsonObject entry) {
        }
    }

    private static final Map<String, Field> FIELDS = new LinkedHashMap<>();

    private static void toggle(String name, String description, Function<Looks, Boolean> getter, BiConsumer<Looks, Boolean> setter) {
        FIELDS.put(name, new Field(name, "boolean", description) {
            @Override
            JsonElement get(Looks looks) {
                return new JsonPrimitive(getter.apply(looks));
            }

            @Override
            void set(Looks looks, JsonElement value) {
                setter.accept(looks, JsonFields.bool(value, name));
            }
        });
    }

    private static void number(String name, double min, double max, String description, Function<Looks, Double> getter, BiConsumer<Looks, Double> setter) {
        FIELDS.put(name, new Field(name, "number", description) {
            @Override
            JsonElement get(Looks looks) {
                return new JsonPrimitive(getter.apply(looks));
            }

            @Override
            void set(Looks looks, JsonElement value) {
                setter.accept(looks, JsonFields.number(value, name, min, max));
            }

            @Override
            void limits(JsonObject entry) {
                entry.addProperty("min", min);
                entry.addProperty("max", max);
            }
        });
    }

    private static <E extends Enum<E>> void choice(String name, E[] values, Function<E, String> ids, String description, Function<Looks, E> getter,
                                                   BiConsumer<Looks, E> setter) {
        FIELDS.put(name, new Field(name, "enum", description) {
            @Override
            JsonElement get(Looks looks) {
                return new JsonPrimitive(ids.apply(getter.apply(looks)));
            }

            @Override
            void set(Looks looks, JsonElement value) {
                setter.accept(looks, JsonFields.choice(value, name, values, ids));
            }

            @Override
            void limits(JsonObject entry) {
                entry.add("values", JsonFields.array(JsonFields.ids(values, ids)));
            }
        });
    }

    private static void colour(String name, String description, Function<Looks, Integer> getter, BiConsumer<Looks, Integer> setter) {
        FIELDS.put(name, new Field(name, "colour", description + " Given as \"#RRGGBB\" or as [r, g, b] with parts from 0 to 1") {
            @Override
            JsonElement get(Looks looks) {
                return new JsonPrimitive(JsonFields.colourText(getter.apply(looks)));
            }

            @Override
            void set(Looks looks, JsonElement value) {
                setter.accept(looks, JsonFields.colour(value, name));
            }
        });
    }

    private static void list(String name, String description, Function<Looks, List<String>> getter, BiConsumer<Looks, List<String>> setter) {
        FIELDS.put(name, new Field(name, "string_list", description) {
            @Override
            JsonElement get(Looks looks) {
                JsonArray array = JsonFields.array(getter.apply(looks));
                return array;
            }

            @Override
            void set(Looks looks, JsonElement value) {
                setter.accept(looks, JsonFields.texts(value, name));
            }
        });
    }

    static {
        toggle("show_hud", "Draw the recorder's HUD", looks -> looks.showHud, (looks, value) -> looks.showHud = value);
        toggle("show_chat", "Draw chat", looks -> looks.showChat, (looks, value) -> looks.showChat = value);
        toggle("show_boss_bars", "Draw boss bars", looks -> looks.showBossBars, (looks, value) -> looks.showBossBars = value);
        toggle("show_titles", "Draw titles and subtitles", looks -> looks.showTitles, (looks, value) -> looks.showTitles = value);
        toggle("show_name_tags", "Draw name tags", looks -> looks.showNameTags, (looks, value) -> looks.showNameTags = value);
        toggle("show_particles", "Draw particles", looks -> looks.showParticles, (looks, value) -> looks.showParticles = value);
        toggle("show_held_items", "Draw held items in first person", looks -> looks.showHeldItems, (looks, value) -> looks.showHeldItems = value);
        toggle("show_block_outline", "Draw the outline of the block looked at", looks -> looks.showBlockOutline,
                (looks, value) -> looks.showBlockOutline = value);
        toggle("show_own_shadow", "Draw the viewer's own shadow", looks -> looks.showOwnShadow, (looks, value) -> looks.showOwnShadow = value);
        toggle("show_scoreboard", "Draw the scoreboard at the side of the screen", looks -> looks.showScoreboard,
                (looks, value) -> looks.showScoreboard = value);
        toggle("show_action_bar", "Draw the line of words above the hotbar", looks -> looks.showActionBar, (looks, value) -> looks.showActionBar = value);
        toggle("show_hotbar", "Draw the hotbar: the game's own, and the recorder's when riding along", looks -> looks.showHotbar,
                (looks, value) -> looks.showHotbar = value);
        toggle("show_blocks", "Draw the terrain and everything built in it. When off only the sky, entities and particles are left",
                looks -> looks.showBlocks, (looks, value) -> looks.showBlocks = value);
        toggle("show_entities", "Draw entities other than players", looks -> looks.showEntities, (looks, value) -> looks.showEntities = value);
        toggle("show_players", "Draw players", looks -> looks.showPlayers, (looks, value) -> looks.showPlayers = value);
        toggle("show_beacon_beams", "Draw the beams of beacons", looks -> looks.showBeaconBeams, (looks, value) -> looks.showBeaconBeams = value);
        list("hidden_entity_types", "Ids of entity types that are not drawn, for example [\"minecraft:bat\"]. An empty list draws them all",
                looks -> looks.hiddenEntityTypes, (looks, value) -> looks.hiddenEntityTypes = value);
        list("hidden_particle_types", "Ids of particle types that are not drawn, for example [\"minecraft:rain\"]. An empty list draws them all",
                looks -> looks.hiddenParticleTypes, (looks, value) -> looks.hiddenParticleTypes = value);

        choice("weather", Weather.values(), JsonFields::id, "The weather shown, whatever was recorded", looks -> looks.weather,
                (looks, value) -> looks.weather = value);
        choice("fog_mode", FogMode.values(), JsonFields::id, "Fog as recorded, none, or custom (fog_start, fog_end, fog_colour)", looks -> looks.fogMode,
                (looks, value) -> looks.fogMode = value);
        number("fog_start", 0, 10000, "Blocks from the camera at which custom fog begins", looks -> looks.fogStart, (looks, value) -> looks.fogStart = value);
        number("fog_end", 0, 10000, "Blocks from the camera at which custom fog hides everything; not less than fog_start", looks -> looks.fogEnd,
                (looks, value) -> looks.fogEnd = value);
        colour("fog_colour", "Colour of custom fog.", looks -> looks.fogColour, (looks, value) -> looks.fogColour = value);
        toggle("sky", "Draw the sky. When off, background_colour is drawn in its place", looks -> looks.sky, (looks, value) -> looks.sky = value);
        colour("background_colour", "Plain colour drawn in place of the sky when it is off, for keying.", looks -> looks.backgroundColour,
                (looks, value) -> looks.backgroundColour = value);
        toggle("night_vision", "Full brightness without the potion's tint", looks -> looks.nightVision, (looks, value) -> looks.nightVision = value);

        toggle("server_resource_packs", "Use the server's resource packs as they were when recorded. When off, only your own packs are used",
                looks -> looks.serverResourcePacks, (looks, value) -> looks.serverResourcePacks = value);

        FIELDS.put("fov", new Field("fov", "number", "Field of view in degrees when no track sets it: 0 leaves the game's own alone, otherwise 1 to 170") {
            @Override
            JsonElement get(Looks looks) {
                return new JsonPrimitive(looks.fov);
            }

            @Override
            void set(Looks looks, JsonElement value) {
                boolean number = value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
                double fov = number ? value.getAsDouble() : Double.NaN;
                if (!number || fov != 0.0 && !(fov >= 1.0 && fov <= 170.0)) {
                    throw new IllegalArgumentException("'fov' must be 0 (leave alone) or a number from 1 to 170, got " + JsonFields.show(value));
                }
                looks.fov = fov;
            }

            @Override
            void limits(JsonObject entry) {
                entry.addProperty("min", 0);
                entry.addProperty("max", 170);
            }
        });
        number("roll", -180, 180, "Camera roll in degrees when no camera keyframe sets it", looks -> looks.roll, (looks, value) -> looks.roll = value);
        choice("aspect_guide", AspectGuide.values(), AspectGuide::id, "Frame shape drawn over the view as a guide, or the shape of the picture itself with letterbox; custom uses custom_aspect",
                looks -> looks.aspectGuide, (looks, value) -> looks.aspectGuide = value);
        number("custom_aspect", 0.1, 10, "Width divided by height of the custom aspect guide, for example 2.39", looks -> looks.customAspect,
                (looks, value) -> looks.customAspect = value);
        toggle("letterbox", "Make the frame shape the picture itself: black bars in the editor, and exports come out in that shape",
                looks -> looks.letterbox, (looks, value) -> looks.letterbox = value);
        toggle("thirds_guide", "Rule-of-thirds lines, shown only in the editor, never in exports", looks -> looks.thirdsGuide,
                (looks, value) -> looks.thirdsGuide = value);
        toggle("centre_guide", "Centre cross, shown only in the editor, never in exports", looks -> looks.centreGuide,
                (looks, value) -> looks.centreGuide = value);
    }

}
