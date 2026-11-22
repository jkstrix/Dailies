package comedymonkey.dailies.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import comedymonkey.dailies.edit.EntityOverride;
import comedymonkey.dailies.edit.Looks;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Builds the "Looks" tab of the editor's side panel (specification chapter V, "Looks that are not on the timeline"):
 * every setting of the scene's looks, in the three groups the specification gives them, each as the control that
 * suits its type, and under the first group the entity filter and the changes made to single entities. The settings
 * are read from {@link Looks#describe}, so one added there appears here without anything being done.
 */
final class LooksForms {

    private static final List<String> DRAWN = List.of("show_hud", "show_windows", "show_pumpkin_overlay", "show_chat", "show_boss_bars", "show_titles", "show_name_tags", "show_particles",
            "show_held_items", "show_block_outline", "show_own_shadow", "show_scoreboard", "show_action_bar", "show_hotbar", "show_blocks", "show_entities",
            "show_players", "show_beacon_beams", "void_spill", "head_bob", "voice_chat");
    private static final List<String> LISTS = List.of("hidden_entity_types", "hidden_particle_types");
    private static final List<String> WORLD = List.of("weather", "fog_mode", "fog_start", "fog_end", "fog_colour", "sky", "background_colour", "night_vision",
            "server_resource_packs", "shaders");
    private static final List<String> FRAME = List.of("fov", "roll", "aspect_guide", "custom_aspect", "letterbox", "thirds_guide", "centre_guide");
    private static final List<String> GLOWS = List.of("default", "force_glow", "force_no_glow");
    /** Settings that act through another mod: shown unusable, with a tip saying so, when that mod is not here */
    private static final Map<String, java.util.function.BooleanSupplier> NEEDS_MOD = Map.of(
            "voice_chat", comedymonkey.dailies.compat.voicechat.VoiceChatLook::available,
            "shaders", comedymonkey.dailies.compat.iris.IrisShaders::available);

    private LooksForms() {
    }

    private static Looks looks() {
        return EditorState.scene().looks();
    }

    /** A word for what the tab should hold now; when it changes the tab is built afresh */
    static String shape() {
        Looks looks = looks();
        return EditorState.history().state().currentSceneIndex() + "|" + looks.hiddenEntityTypes + "|" + looks.hiddenParticleTypes + "|"
                + looks.entityOverrides().keySet() + "|" + EditorState.overrideEntity + "|" + Looks.fieldNames().size();
    }

    static void build(Form form) {
        form.clear();
        JsonObject described = looks().describe();
        Set<String> shown = new LinkedHashSet<>();

        form.add(new Gadget.Heading(Kit.t("looks.group.drawn")));
        rows(form, described, DRAWN, shown);
        for (String list : LISTS) {
            if (described.has(list)) {
                shown.add(list);
                buildList(form, list);
            }
        }
        buildEntities(form);

        form.add(new Gadget.Heading(Kit.t("looks.group.world")));
        rows(form, described, WORLD, shown);
        form.add(new Gadget.Heading(Kit.t("looks.group.frame")));
        rows(form, described, FRAME, shown);

        List<String> others = new ArrayList<>();
        for (String name : described.keySet()) {
            if (!shown.contains(name)) {
                others.add(name);
            }
        }
        if (!others.isEmpty()) {
            form.add(new Gadget.Heading(Kit.t("looks.group.other")));
            rows(form, described, others, shown);
        }
    }

    // ------------------------------------------------------------------ The settings

    private static void rows(Form form, JsonObject described, List<String> names, Set<String> shown) {
        for (String name : names) {
            JsonElement entry = described.get(name);
            if (entry == null || !entry.isJsonObject()) {
                continue;
            }
            shown.add(name);
            JsonObject about = entry.getAsJsonObject();
            String type = about.has("type") ? about.get("type").getAsString() : "";
            Component label = Kit.named("looks." + name, Kit.pretty(name));
            Component tip = Kit.named("looks." + name + ".tip", about.has("description") ? about.get("description").getAsString() : Kit.pretty(name));
            java.util.function.BooleanSupplier needs = NEEDS_MOD.get(name);
            if (needs != null && !needs.getAsBoolean()) {
                // The mod it works through is not here: shown, so it can be found, and not usable
                Component missing = Kit.named("looks." + name + ".missing", Kit.pretty(name));
                if (type.equals("boolean")) {
                    form.add(new Gadget.Check(label, () -> looks().get(name).getAsBoolean(), on -> { }).tip(missing).when(() -> false));
                } else {
                    form.add(new Gadget.Pair(label, new Gadget.Cycle<>(List.of(looks().get(name).getAsString()), id -> Kit.named("looks.choice." + id, id),
                            () -> looks().get(name).getAsString(), id -> { })).tip(missing).when(() -> false));
                }
                continue;
            }
            switch (type) {
                case "boolean" -> form.add(new Gadget.Check(label, () -> looks().get(name).getAsBoolean(), on -> set(name, new JsonPrimitive(on))).tip(tip));
                case "enum" -> {
                    List<String> values = new ArrayList<>();
                    if (about.has("values") && about.get("values").isJsonArray()) {
                        about.get("values").getAsJsonArray().forEach(value -> values.add(value.getAsString()));
                    }
                    form.add(new Gadget.Pair(label, new Gadget.Cycle<>(values, id -> Kit.named("looks.choice." + id, id),
                            () -> looks().get(name).getAsString(), id -> set(name, new JsonPrimitive(id)))).tip(tip));
                }
                case "number" -> {
                    double min = about.has("min") ? about.get("min").getAsDouble() : -1.0e15;
                    double max = about.has("max") ? about.get("max").getAsDouble() : 1.0e15;
                    form.add(new Gadget.Pair(label, Entry.number(() -> looks().get(name).getAsDouble(), min, max, false,
                            value -> change(name, new JsonPrimitive(value)))).tip(tip));
                }
                case "colour" -> form.add(new Gadget.Pair(label, new Entry(() -> looks().get(name).getAsString(), typed -> {
                    String text = typed.trim();
                    return change(name, new JsonPrimitive(text.startsWith("#") || text.isEmpty() ? text : "#" + text));
                }).swatch(() -> colour(looks().get(name)))).tip(tip));
                case "string_list" -> buildList(form, name);
                default -> form.add(new Gadget.Pair(label, new Entry(() -> looks().get(name).toString(), typed -> change(name, new JsonPrimitive(typed)))).tip(tip));
            }
        }
    }

    private static int colour(JsonElement value) {
        try {
            String text = value.getAsString();
            return Integer.parseInt(text.startsWith("#") ? text.substring(1) : text, 16);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Changes one setting as one step to undo. Returns null when it was done, else why it was refused */
    private static @Nullable Component change(String name, JsonElement value) {
        try {
            if (!value.equals(looks().get(name))) {
                JsonObject values = new JsonObject();
                values.add(name, value);
                EditorState.history().setLooks(values);
            }
            return null;
        } catch (IllegalArgumentException | IllegalStateException refused) {
            return Component.literal(refused.getMessage() == null ? refused.toString() : refused.getMessage());
        }
    }

    private static void set(String name, JsonElement value) {
        Component why = change(name, value);
        if (why != null) {
            EditorState.say(why);
        }
    }

    // ------------------------------------------------------------------ Kinds that are not drawn

    private static void buildList(Form form, String name) {
        boolean entities = name.equals("hidden_entity_types");
        boolean particles = name.equals("hidden_particle_types");
        Component label = Kit.named("looks." + name, Kit.pretty(name));
        Component tip = Kit.named("looks." + name + ".tip", Kit.pretty(name));
        form.add(new Gadget.Words(() -> label, Controls.PARCHMENT).tip(tip));
        Entry draft = new Entry(() -> entities ? EditorState.entityTypeDraft : particles ? EditorState.particleTypeDraft : "", typed -> {
            if (entities) {
                EditorState.entityTypeDraft = typed.trim();
            } else if (particles) {
                EditorState.particleTypeDraft = typed.trim();
            }
            return null;
        });
        draft.hint(Kit.t(entities ? "looks.list.entity_hint" : "looks.list.hint"));
        draft.onEnter(() -> addToList(name));
        if (entities || particles) {
            form.add(new Gadget.Line(draft.tip(tip), new Gadget.Push(Kit.t("looks.list.add"), () -> addToList(name)).wide(36).tip(Kit.t("looks.list.add.tip"))));
        }
        for (String id : list(name)) {
            form.add(new Gadget.Line(
                    new Gadget.Words(() -> Component.literal(id), Controls.PARCHMENT_DIM),
                    Gadget.Push.icon(Kit.Icon.CROSS, Kit.t("looks.list.remove", id), () -> {
                        List<String> fewer = new ArrayList<>(list(name));
                        fewer.remove(id);
                        setList(name, fewer);
                    })));
        }
    }

    private static List<String> list(String name) {
        List<String> ids = new ArrayList<>();
        JsonElement value = looks().get(name);
        if (value.isJsonArray()) {
            value.getAsJsonArray().forEach(id -> ids.add(id.getAsString()));
        }
        return ids;
    }

    private static void setList(String name, List<String> ids) {
        if (name.equals("hidden_entity_types")) {
            EditorState.history().setRenderFilter(ids, null);
        } else if (name.equals("hidden_particle_types")) {
            EditorState.history().setRenderFilter(null, ids);
        }
    }

    private static void addToList(String name) {
        boolean entities = name.equals("hidden_entity_types");
        String typed = entities ? EditorState.entityTypeDraft : EditorState.particleTypeDraft;
        if (typed.isBlank()) {
            EditorState.say(Kit.t("looks.list.empty"));
            return;
        }
        ResourceLocation id = ResourceLocation.tryParse(typed.trim().toLowerCase(java.util.Locale.ROOT));
        if (id == null) {
            EditorState.say(Kit.t("looks.list.bad_id", typed));
            return;
        }
        boolean known = entities ? BuiltInRegistries.ENTITY_TYPE.containsKey(id) : BuiltInRegistries.PARTICLE_TYPE.containsKey(id);
        hide(name, id.toString());
        if (entities) {
            EditorState.entityTypeDraft = "";
        } else {
            EditorState.particleTypeDraft = "";
        }
        if (!known) {
            // Kept all the same: it may belong to a mod that is in the recording and not in this game
            EditorState.say(Kit.t("looks.list.unknown", id.toString()));
        }
    }

    private static void hide(String name, String id) {
        List<String> more = new ArrayList<>(list(name));
        if (!more.contains(id)) {
            more.add(id);
            setList(name, more);
        }
    }

    // ------------------------------------------------------------------ Single entities

    private static void buildEntities(Form form) {
        form.add(new Gadget.Words(() -> Kit.t("looks.entity.heading"), Controls.PARCHMENT).tip(Kit.t("looks.entity.heading.tip")));
        form.add(new Gadget.Push(Kit.t("looks.entity.pick"), () -> {
            EditorState.pick = new EditorState.Pick(Kit.t("pick.entity"), false, false, LooksForms::choose, null, List.of());
            EditorState.sideFolded = false;
        }).tip(Kit.t("looks.entity.pick.tip")));

        UUID chosen = EditorState.overrideEntity;
        if (chosen != null) {
            form.add(new Gadget.Note(() -> Kit.t("looks.entity.chosen", EditorState.overrideEntityName), Controls.PARCHMENT));
            form.add(new Gadget.Check(Kit.t("looks.entity.hide"), () -> override(chosen).hide(), on -> entity(chosen, "hide", new JsonPrimitive(on)))
                    .tip(Kit.t("looks.entity.hide.tip")));
            form.add(new Gadget.Pair(Kit.t("looks.entity.name"), new Entry(() -> {
                String name = override(chosen).nameOverride();
                return name == null ? "" : name;
            }, typed -> {
                try {
                    EditorState.history().setEntityOverride(chosen, one("name_override", new JsonPrimitive(typed.trim())));
                    return null;
                } catch (IllegalArgumentException | IllegalStateException refused) {
                    return Component.literal(String.valueOf(refused.getMessage()));
                }
            }).hint(Kit.t("looks.entity.name.hint"))).tip(Kit.t("looks.entity.name.tip")));
            form.add(new Gadget.Pair(Kit.t("looks.entity.glow"), new Gadget.Cycle<>(GLOWS, id -> Kit.t("looks.entity.glow." + id),
                    () -> override(chosen).glowing().name().toLowerCase(java.util.Locale.ROOT), id -> entity(chosen, "glowing", new JsonPrimitive(id))))
                    .tip(Kit.t("looks.entity.glow.tip")));
            form.add(new Gadget.Check(Kit.t("looks.entity.hide_nametag"), () -> override(chosen).hideNametag(), on -> entity(chosen, "hide_nametag", new JsonPrimitive(on)))
                    .tip(Kit.t("looks.entity.hide_nametag.tip")));
            form.add(new Gadget.Check(Kit.t("looks.entity.hide_cape"), () -> override(chosen).hideCape(), on -> entity(chosen, "hide_cape", new JsonPrimitive(on)))
                    .tip(Kit.t("looks.entity.hide_cape.tip")));
            form.add(new Gadget.Check(Kit.t("looks.entity.hide_armour"), () -> override(chosen).hideArmour(), on -> entity(chosen, "hide_armour", new JsonPrimitive(on)))
                    .tip(Kit.t("looks.entity.hide_armour.tip")));
            String type = EditorState.overrideEntityType;
            form.add(new Gadget.Line(
                    new Gadget.Push(Kit.t("looks.entity.hide_kind"), () -> hide("hidden_entity_types", type)).when(() -> !type.isEmpty())
                            .tip(Kit.t("looks.entity.hide_kind.tip", type)),
                    new Gadget.Push(Kit.t("looks.entity.reset"), () -> reset(chosen)).when(() -> !override(chosen).isNone())
                            .tip(Kit.t("looks.entity.reset.tip"))));
        }

        Map<UUID, EntityOverride> changed = looks().entityOverrides();
        if (!changed.isEmpty()) {
            form.add(new Gadget.Words(() -> Kit.t("looks.entity.changed", changed.size()), Controls.PARCHMENT_DIM));
            for (UUID id : changed.keySet()) {
                String name = KeyframeForms.entityName(id.toString());
                form.add(new Gadget.Line(
                        new Gadget.Push(Component.literal(name), () -> {
                            EditorState.overrideEntity = id;
                            EditorState.overrideEntityName = name;
                            EditorState.overrideEntityType = typeOf(id);
                        }).lit(() -> id.equals(EditorState.overrideEntity)).tip(Kit.t("looks.entity.edit.tip")),
                        Gadget.Push.icon(Kit.Icon.CROSS, Kit.t("looks.entity.reset.tip"), () -> reset(id))));
            }
        }
    }

    private static void choose(Entity entity) {
        EditorState.overrideEntity = entity.getUUID();
        EditorState.overrideEntityName = entity.getName().getString() + " (" + entity.getType().getDescription().getString() + ")";
        EditorState.overrideEntityType = String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
        EditorState.tab = EditorState.TAB_LOOKS;
    }

    private static String typeOf(UUID id) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null) {
            for (Entity entity : minecraft.level.entitiesForRendering()) {
                if (entity.getUUID().equals(id)) {
                    return String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
                }
            }
        }
        return "";
    }

    private static EntityOverride override(UUID entity) {
        return looks().entityOverride(entity);
    }

    private static JsonObject one(String field, JsonElement value) {
        JsonObject changes = new JsonObject();
        changes.add(field, value);
        return changes;
    }

    private static void entity(UUID entity, String field, JsonElement value) {
        EditorState.history().setEntityOverride(entity, one(field, value));
    }

    /** Shows the entity as recorded again */
    private static void reset(UUID entity) {
        if (override(entity).isNone()) {
            return;
        }
        JsonObject changes = new JsonObject();
        changes.addProperty("hide", false);
        changes.addProperty("name_override", "");
        changes.addProperty("glowing", "default");
        changes.addProperty("hide_nametag", false);
        changes.addProperty("hide_cape", false);
        changes.addProperty("hide_armour", false);
        EditorState.history().setEntityOverride(entity, changes);
    }

}
