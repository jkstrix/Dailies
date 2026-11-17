package comedymonkey.dailies.mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import comedymonkey.dailies.edit.EditHistory;
import comedymonkey.dailies.edit.EntityOverride;
import comedymonkey.dailies.edit.Looks;
import comedymonkey.dailies.mcp.McpArgs;
import comedymonkey.dailies.mcp.McpProtocol;
import comedymonkey.dailies.mcp.McpSchema;
import comedymonkey.dailies.mcp.McpToolException;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The MCP tools for the current scene's looks, the settings that are not on the timeline: what is drawn, the world's
 * look and the frame, the changes made to single entities, and the kinds of entity and particle that are left out.
 * Every change goes through the editor's {@link EditHistory}, so it can be undone with editor_undo. The tool names and
 * arguments are those of the specification's appendix A.
 */
final class LooksTools {

    /** Arguments of entity_overrides_set that the editor has an override for, and the override's own name */
    private static final Map<String, String> OVERRIDES = Map.of(
        "hide_during_export", "hide",
        "hide_nametag", "hide_nametag",
        "name_override", "name_override",
        "glowing", "glowing",
        "hide_cape", "hide_cape");

    /** Arguments of entity_overrides_set that are accepted but have nothing to act on */
    private static final Map<String, String> UNSUPPORTED = Map.of(
        "hide_team_prefix", "team prefixes cannot be hidden per entity",
        "hide_team_suffix", "team suffixes cannot be hidden per entity",
        "hide_below_name", "the text below a name cannot be hidden per entity",
        "mute_voice", "Dailies does not record voice chat",
        "audio_source", "sound is always heard from the camera",
        "hide_all_spectators", "there is no setting that hides every spectator; hide them one by one with hide_during_export");

    private LooksTools() {
    }

    static void register(McpProtocol.Registrar registrar) {
        EditingTools.add(registrar, "visuals_get",
            "The current scene's looks, the settings that are not on the timeline: what is drawn (HUD, hotbar, scoreboard, action bar, chat, boss bars, titles, name tags, "
                + "particles, held items, sky, and blocks, entities, players and beacon beams as a whole), weather, fog, night vision, field of view, roll, the frame shape "
                + "(a guide, or with 'letterbox' the shape of the picture itself, which exports then come out in) and the framing guides. Each setting comes with its type, its value, what it allows and what it does.",
            McpSchema.object(), (args, history) -> {
                JsonObject json = new JsonObject();
                json.addProperty("scene", history.state().currentSceneIndex());
                json.addProperty("scene_name", history.scene().name());
                json.add("settings", history.scene().looks().describe());
                return json;
            });

        EditingTools.add(registrar, "visuals_set",
            "Change one or more settings of the current scene's looks. 'values' maps setting names from visuals_get to new values: true or false, a number, the name of a choice, "
                + "a colour as \"#RRGGBB\" or as [r, g, b] with parts from 0 to 1, or a list of ids. If any name or value is wrong, nothing is changed. Can be undone.",
            McpSchema.object().obj("values", "Setting name to new value, eg. {\"night_vision\": true, \"weather\": \"clear\"}").required("values"),
            (args, history) -> {
                JsonObject values = args.object("values");
                history.setLooks(values);
                Looks looks = history.scene().looks();
                JsonObject now = new JsonObject();
                for (String name : values.keySet()) {
                    now.add(name, looks.get(name));
                }
                JsonObject json = new JsonObject();
                json.addProperty("scene", history.state().currentSceneIndex());
                json.add("values", now);
                return EditingTools.changed(json, history);
            }, "values");

        EditingTools.add(registrar, "entity_overrides_get",
            "The changes the current scene makes to how single entities are shown: hidden, renamed, glowing outline forced on or off, name tag, cape or armour hidden. "
                + "Give 'uuid' for one entity, or leave it out for every entity that has a change.",
            McpSchema.object().string("uuid", "UUID of the entity, from replay_list_entities. Left out, all entities with a change are listed"),
            (args, history) -> {
                Looks looks = history.scene().looks();
                if (args.has("uuid")) {
                    UUID uuid = args.uuid("uuid");
                    return describe(uuid, looks.entityOverride(uuid));
                }
                JsonArray list = new JsonArray();
                for (Map.Entry<UUID, EntityOverride> entry : looks.entityOverrides().entrySet()) {
                    list.add(describe(entry.getKey(), entry.getValue()));
                }
                JsonObject json = new JsonObject();
                json.addProperty("scene", history.state().currentSceneIndex());
                json.add("overrides", list);
                return json;
            }, "uuid");

        EditingTools.add(registrar, "entity_overrides_set",
            "Change how one entity is shown in the current scene. Only the options given change; the others stay. Can be undone. "
                + "The options about team text, voice, the audio source and spectators have nothing to act on in Dailies: they are accepted and reported back under 'ignored'.",
            McpSchema.object()
                .string("uuid", "UUID of the entity, from replay_list_entities")
                .bool("hide_during_export", "Do not draw this entity at all. It is hidden in playback as well as in exported video")
                .bool("hide_nametag", "Do not draw the entity's name tag")
                .string("name_override", "Name to show in place of the entity's own. An empty string goes back to its own name")
                .string("glowing", "The glowing outline: default (as recorded), force_glow or force_no_glow")
                .bool("hide_cape", "Do not draw the player's cape")
                .bool("hide_team_prefix", "Ignored")
                .bool("hide_team_suffix", "Ignored")
                .bool("hide_below_name", "Ignored")
                .bool("mute_voice", "Ignored")
                .bool("audio_source", "Ignored")
                .bool("hide_all_spectators", "Ignored"),
            LooksTools::setOverrides,
            "uuid", "hide_during_export", "hide_nametag", "name_override", "glowing", "hide_cape", "hide_team_prefix", "hide_team_suffix", "hide_below_name",
            "mute_voice", "audio_source", "hide_all_spectators");

        EditingTools.add(registrar, "render_filter_get", "The kinds of entity and of particle the current scene leaves out of the picture, as lists of ids.",
            McpSchema.object(), (args, history) -> filter(history));

        EditingTools.add(registrar, "render_filter_set",
            "Set which kinds of entity and of particle the current scene leaves out of the picture. A list that is given takes the place of the old one, "
                + "an empty list shows all of that sort again, and a list left out stays as it is. Can be undone.",
            McpSchema.object()
                .stringArray("entities", "Ids of the entity types to leave out, eg. [\"minecraft:item\", \"minecraft:bat\"]")
                .stringArray("particles", "Ids of the particle types to leave out, eg. [\"minecraft:rain\"]"),
            (args, history) -> {
                List<String> entities = ids(args.optStringList("entities"), "entities", BuiltInRegistries.ENTITY_TYPE,
                    "entity type. replay_list_entities shows the types of the entities near the camera");
                List<String> particles = ids(args.optStringList("particles"), "particles", BuiltInRegistries.PARTICLE_TYPE, "particle type");
                if (entities == null && particles == null) {
                    throw new McpToolException("Give 'entities', 'particles' or both. An empty list shows everything of that sort again");
                }
                history.setRenderFilter(entities, particles);
                return EditingTools.changed(filter(history), history);
            }, "entities", "particles");
    }

    private static JsonElement setOverrides(McpArgs args, EditHistory history) {
        JsonObject changes = new JsonObject();
        JsonArray ignored = new JsonArray();
        for (String name : args.names()) {
            if (!args.has(name)) {
                continue;
            }
            String field = OVERRIDES.get(name);
            if (field != null) {
                if (name.equals("name_override") || name.equals("glowing")) {
                    changes.addProperty(field, args.string(name));
                } else {
                    changes.addProperty(field, args.bool(name));
                }
            } else if (UNSUPPORTED.containsKey(name)) {
                args.bool(name);
                ignored.add(name + ": " + UNSUPPORTED.get(name));
            }
        }
        if (changes.size() == 0 && ignored.isEmpty()) {
            throw new McpToolException("Give at least one option to change, eg. hide_nametag=true. entity_overrides_get shows what is set now");
        }
        JsonObject json;
        if (changes.size() > 0) {
            if (!args.has("uuid")) {
                throw new McpToolException("Give 'uuid': the UUID of the entity to change, from replay_list_entities");
            }
            UUID uuid = args.uuid("uuid");
            history.setEntityOverride(uuid, changes);
            json = describe(uuid, history.scene().looks().entityOverride(uuid));
            EditingTools.changed(json, history);
        } else {
            json = new JsonObject();
            json.addProperty("changed", false);
        }
        if (!ignored.isEmpty()) {
            json.add("ignored", ignored);
        }
        return json;
    }

    private static JsonObject describe(UUID uuid, EntityOverride override) {
        JsonObject json = new JsonObject();
        json.addProperty("uuid", uuid.toString());
        json.addProperty("has_override", !override.isNone());
        json.addProperty("hide_during_export", override.hide());
        json.addProperty("hide_nametag", override.hideNametag());
        json.addProperty("name_override", override.nameOverride() == null ? "" : override.nameOverride());
        json.addProperty("glowing", override.glowing().name().toLowerCase(java.util.Locale.ROOT));
        json.addProperty("hide_cape", override.hideCape());
        json.addProperty("hide_armour", override.hideArmour());
        return json;
    }

    private static JsonObject filter(EditHistory history) {
        Looks looks = history.scene().looks();
        JsonObject json = new JsonObject();
        json.addProperty("scene", history.state().currentSceneIndex());
        json.add("entities", array(looks.hiddenEntityTypes));
        json.add("particles", array(looks.hiddenParticleTypes));
        return json;
    }

    private static JsonArray array(List<String> texts) {
        JsonArray array = new JsonArray();
        for (String text : texts) {
            array.add(text);
        }
        return array;
    }

    /** The ids in full ("bat" becomes "minecraft:bat"), each checked to be a type the game knows; null stays null */
    private static List<String> ids(List<String> given, String argument, Registry<?> registry, String what) {
        if (given == null) {
            return null;
        }
        List<String> ids = new ArrayList<>();
        for (String text : given) {
            ResourceLocation id = ResourceLocation.tryParse(text.trim());
            if (id == null || !registry.containsKey(id)) {
                throw new McpToolException("'" + argument + "' has \"" + text + "\", which is not the id of a known " + what);
            }
            if (!ids.contains(id.toString())) {
                ids.add(id.toString());
            }
        }
        return ids;
    }

}
