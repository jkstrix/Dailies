package comedymonkey.dailies.edit;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The settings of a scene that are not on the timeline (specification chapter V, "Looks that are not on the timeline") */
class LooksTest {

    private static final UUID FIRST = UUID.fromString("0b1e6f0e-5a52-4c61-9a37-0c1d2e3f4a5b");
    private static final UUID SECOND = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");

    private static String refused(Looks looks, String values) {
        return assertThrows(IllegalArgumentException.class, () -> looks.setAllText(values)).getMessage();
    }

    @Test
    void everySettingOfTheSpecificationIsThere() {
        List<String> names = Looks.fieldNames();
        for (String name : List.of(
                "show_hud", "show_chat", "show_boss_bars", "show_titles", "show_name_tags", "show_particles", "show_held_items", "show_block_outline",
                "show_own_shadow", "show_scoreboard", "show_action_bar", "show_hotbar", "show_blocks", "show_entities", "show_players", "show_beacon_beams",
                "hidden_entity_types", "hidden_particle_types",
                "weather", "fog_mode", "fog_start", "fog_end", "fog_colour", "sky", "background_colour", "night_vision",
                "server_resource_packs", "fov", "roll", "aspect_guide", "custom_aspect", "letterbox", "thirds_guide", "centre_guide", "void_spill", "head_bob",
                "voice_chat", "shaders")) {
            assertTrue(names.contains(name), name);
        }
        assertEquals(38, names.size());
    }

    @Test
    void theDefaultsChangeNothingAboutTheRecording() {
        Looks looks = new Looks();
        assertTrue(looks.showHud && looks.showChat && looks.showBossBars && looks.showTitles && looks.showNameTags && looks.showParticles
                && looks.showHeldItems && looks.showBlockOutline && looks.showOwnShadow && looks.sky);
        assertEquals(Looks.Weather.AS_RECORDED, looks.weather);
        assertEquals(Looks.FogMode.AS_RECORDED, looks.fogMode);
        assertFalse(looks.nightVision);
        assertTrue(looks.serverResourcePacks);
        assertEquals(0.0, looks.fov);
        assertEquals(0.0, looks.roll);
        assertEquals(Looks.AspectGuide.OFF, looks.aspectGuide);
        assertEquals(0.0, looks.aspectRatio());
        assertTrue(looks.hiddenEntityTypes.isEmpty() && looks.entityOverrides().isEmpty());
        // Voice chat's icons only when asked for, and the viewer's own shaders setting left alone
        assertFalse(looks.voiceChat);
        assertEquals(Looks.Shaders.AS_GAME, looks.shaders);
    }

    @Test
    void voiceChatAndShadersAreSetByNameAndSurviveSavingAndLoading() {
        Looks looks = new Looks();
        assertEquals("false", looks.getText("voice_chat"));
        assertEquals("\"as_game\"", looks.getText("shaders"));

        looks.setAllText("{\"voice_chat\": true, \"shaders\": \"off\"}");
        assertTrue(looks.voiceChat);
        assertEquals(Looks.Shaders.OFF, looks.shaders);
        Looks loaded = Looks.fromJsonText(looks.toJsonText());
        assertTrue(loaded.voiceChat);
        assertEquals(Looks.Shaders.OFF, loaded.shaders);
        assertEquals(looks.toJsonText(), loaded.toJsonText());

        looks.setAllText("{\"shaders\": \"ON\"}");
        assertEquals(Looks.Shaders.ON, Looks.fromJsonText(looks.toJsonText()).shaders);

        assertEquals("'shaders' must be one of as_game, on, off, got \"bright\"", refused(looks, "{\"shaders\": \"bright\"}"));
        assertEquals("'voice_chat' must be true or false, got \"yes\"", refused(looks, "{\"voice_chat\": \"yes\"}"));
        assertEquals(Looks.Shaders.ON, looks.shaders);

        // A scene saved before these looks existed, or with a value this version does not know, gets the defaults
        Looks old = Looks.fromJsonText("{\"night_vision\": true, \"shaders\": \"sepia\"}");
        assertFalse(old.voiceChat);
        assertEquals(Looks.Shaders.AS_GAME, old.shaders);
        assertTrue(old.nightVision);

        String described = looks.describeText();
        assertTrue(described.contains("\"shaders\":{\"type\":\"enum\",\"value\":\"on\",\"values\":[\"as_game\",\"on\",\"off\"],"), described);
        assertTrue(described.contains("\"voice_chat\":{\"type\":\"boolean\",\"value\":true,"), described);
    }

    @Test
    void settingsAreReadAndSetByName() {
        Looks looks = new Looks();
        looks.setAllText("{\"show_chat\": false, \"weather\": \"RAIN\", \"fog_mode\": \"custom\", \"fog_start\": 4, \"fog_end\": 32.5,"
                + " \"fog_colour\": [1, 0.5, 0], \"background_colour\": \"#0000ff\", \"fov\": 40, \"roll\": -15, \"aspect_guide\": \"21:9\","
                + " \"hidden_entity_types\": [\"minecraft:bat\"], \"thirds_guide\": true}");
        assertFalse(looks.showChat);
        assertEquals(Looks.Weather.RAIN, looks.weather);
        assertEquals(Looks.FogMode.CUSTOM, looks.fogMode);
        assertEquals(4.0, looks.fogStart);
        assertEquals(32.5, looks.fogEnd);
        assertEquals(0xFF8000, looks.fogColour);
        assertEquals(0x0000FF, looks.backgroundColour);
        assertEquals(40.0, looks.fov);
        assertEquals(-15.0, looks.roll);
        assertEquals(Looks.AspectGuide.CINEMA_21_9, looks.aspectGuide);
        assertEquals(21.0 / 9.0, looks.aspectRatio(), 1e-12);
        assertEquals(List.of("minecraft:bat"), looks.hiddenEntityTypes);
        assertTrue(looks.thirdsGuide);

        assertEquals("\"rain\"", looks.getText("weather"));
        assertEquals("\"#FF8000\"", looks.getText("fog_colour"));
        assertEquals("false", looks.getText("show_chat"));
        assertEquals("\"21:9\"", looks.getText("aspect_guide"));
        assertEquals("40.0", looks.getText("fov"));
        assertEquals("[\"minecraft:bat\"]", looks.getText("hidden_entity_types"));

        looks.setAllText("{\"aspect_guide\": \"custom\", \"custom_aspect\": 2.0}");
        assertEquals(2.0, looks.aspectRatio());
        looks.setAllText("{\"fov\": 0}");
        assertEquals(0.0, looks.fov);
    }

    @Test
    void nothingIsChangedIfAnyValueIsInvalid() {
        Looks looks = new Looks();
        String before = looks.toJsonText();

        assertEquals("'weather' must be one of as_recorded, clear, rain, thunder, got \"hail\"",
                refused(looks, "{\"show_chat\": false, \"night_vision\": true, \"weather\": \"hail\"}"));
        assertEquals(before, looks.toJsonText());

        String message = refused(looks, "{\"sky\": false, \"lens_flare\": true}");
        assertTrue(message.startsWith("There is no visual setting called 'lens_flare'") && message.contains("show_hud, show_chat"), message);
        assertEquals(before, looks.toJsonText());

        message = refused(looks, "{\"sky\": false, \"fog_start\": 300}");
        assertTrue(message.startsWith("'fog_end' must not be less than 'fog_start' (300)"), message);
        assertEquals(before, looks.toJsonText());

        assertEquals("'fov' must be 0 (leave alone) or a number from 1 to 170, got 0.5", refused(looks, "{\"fov\": 0.5}"));
        assertEquals("'roll' must be a number from -180 to 180, got 200", refused(looks, "{\"roll\": 200}"));
        assertEquals("'sky' must be true or false, got \"no\"", refused(looks, "{\"sky\": \"no\"}"));
        assertTrue(refused(looks, "{\"fog_colour\": [2, 0, 0]}").startsWith("'fog_colour' must be a colour"));
        assertTrue(refused(looks, "{\"fog_colour\": \"blue\"}").startsWith("'fog_colour' must be a colour"));
        assertTrue(refused(looks, "{\"hidden_entity_types\": \"minecraft:bat\"}").startsWith("'hidden_entity_types' must be a list of strings"));
        assertTrue(refused(looks, "[1, 2]").startsWith("'values' must be a JSON object"));
        assertTrue(refused(looks, "{broken").startsWith("'values' must be a JSON object"));
        assertThrows(IllegalArgumentException.class, () -> looks.getText("lens_flare"));
        assertEquals(before, looks.toJsonText());
    }

    @Test
    void theDescriptionGivesTypeValueAndLimitsOfEverySetting() {
        Looks looks = new Looks();
        looks.nightVision = true;
        String described = looks.describeText();
        int last = -1;
        for (String name : Looks.fieldNames()) {
            int at = described.indexOf("\"" + name + "\":{\"type\":\"");
            assertTrue(at > last, name + " is missing or out of order");
            int value = described.indexOf("\"value\":" + looks.getText(name) + ",", at);
            assertTrue(value > at, name + " has no value");
            assertTrue(described.indexOf("\"description\":\"", value) > value, name + " has no description");
            last = at;
        }
        assertTrue(described.contains("\"night_vision\":{\"type\":\"boolean\",\"value\":true,\"description\":"), described);
        assertTrue(described.contains("\"fog_mode\":{\"type\":\"enum\",\"value\":\"as_recorded\",\"values\":[\"as_recorded\",\"off\",\"custom\"],"), described);
        assertTrue(described.contains("\"values\":[\"off\",\"16:9\",\"9:16\",\"4:3\",\"1:1\",\"21:9\",\"custom\"]"), described);
        assertTrue(described.contains("\"roll\":{\"type\":\"number\",\"value\":0.0,\"min\":-180.0,\"max\":180.0,"), described);
        assertTrue(described.contains("\"fog_colour\":{\"type\":\"colour\",\"value\":\"#C0D8FF\","), described);
        assertTrue(described.contains("\"hidden_entity_types\":{\"type\":\"string_list\",\"value\":[],"), described);
    }

    @Test
    void theValuesShownCanBeSetBackUnchanged() {
        Looks looks = new Looks();
        looks.setAllText("{\"fog_mode\": \"custom\", \"fog_colour\": \"#123456\", \"hidden_particle_types\": [\"minecraft:rain\"], \"roll\": 12.5}");
        String values = Looks.fieldNames().stream().map(name -> "\"" + name + "\":" + looks.getText(name)).collect(Collectors.joining(",", "{", "}"));
        Looks other = new Looks();
        other.setAllText(values);
        assertEquals(looks.toJsonText(), other.toJsonText());
        assertEquals(0x123456, other.fogColour);
        assertEquals(List.of("minecraft:rain"), other.hiddenParticleTypes);
    }

    @Test
    void entitiesCanBeChangedOneByOne() {
        Looks looks = new Looks();
        assertEquals(EntityOverride.NONE, looks.entityOverride(FIRST));

        looks.setEntityOverride(FIRST, looks.entityOverride(FIRST).withText("{\"name_override\": \"Hero\", \"glowing\": \"force_glow\", \"hide_cape\": true}"));
        looks.setEntityOverride(SECOND, looks.entityOverride(SECOND).withText("{\"hide\": true, \"hide_armour\": true}"));
        assertEquals(new EntityOverride(false, "Hero", EntityOverride.Glow.FORCE_GLOW, false, true, false), looks.entityOverride(FIRST));
        assertEquals(Set.of(SECOND), looks.hiddenEntities());

        // Only the given options change
        looks.setEntityOverride(FIRST, looks.entityOverride(FIRST).withText("{\"hide_nametag\": true, \"glowing\": \"force_no_glow\"}"));
        assertEquals(new EntityOverride(false, "Hero", EntityOverride.Glow.FORCE_NO_GLOW, true, true, false), looks.entityOverride(FIRST));

        looks.setEntityHidden(FIRST, true);
        assertEquals(Set.of(FIRST, SECOND), looks.hiddenEntities());
        assertEquals("Hero", looks.entityOverride(FIRST).nameOverride());

        // An entity with nothing changed is forgotten
        looks.setEntityOverride(SECOND, looks.entityOverride(SECOND).withText("{\"hide\": false, \"hide_armour\": false}"));
        assertEquals(Set.of(FIRST), looks.entityOverrides().keySet());
        looks.setEntityOverride(FIRST, looks.entityOverride(FIRST).withText("{\"name_override\": \"\"}"));
        assertNull(looks.entityOverride(FIRST).nameOverride());
        assertEquals("{\"hide\":true,\"name_override\":\"\",\"glowing\":\"force_no_glow\",\"hide_nametag\":true,\"hide_cape\":true,\"hide_armour\":false}",
                looks.entityOverride(FIRST).toJsonText());
    }

    @Test
    void entityOverrideErrorsNameTheField() {
        String message = assertThrows(IllegalArgumentException.class, () -> EntityOverride.NONE.withText("{\"glowing\": \"pink\"}")).getMessage();
        assertEquals("'glowing' must be one of default, force_glow, force_no_glow, got \"pink\"", message);
        message = assertThrows(IllegalArgumentException.class, () -> EntityOverride.NONE.withText("{\"hide\": 1}")).getMessage();
        assertEquals("'hide' must be true or false, got 1", message);
        message = assertThrows(IllegalArgumentException.class, () -> EntityOverride.NONE.withText("{\"hide_hat\": true}")).getMessage();
        assertTrue(message.contains("'hide_hat'") && message.contains("hide_armour"), message);
    }

    @Test
    void looksSurviveSavingAndLoading() {
        Looks looks = new Looks();
        looks.setAllText("{\"show_particles\": false, \"weather\": \"clear\", \"sky\": false, \"night_vision\": true, \"custom_aspect\": 1.85,"
                + " \"centre_guide\": true, \"hidden_particle_types\": [\"minecraft:rain\", \"minecraft:smoke\"]}");
        looks.setEntityOverride(FIRST, new EntityOverride(true, null, EntityOverride.Glow.DEFAULT, false, false, false));
        Looks loaded = Looks.fromJsonText(looks.toJsonText());
        assertEquals(looks.toJsonText(), loaded.toJsonText());
        assertFalse(loaded.showParticles);
        assertEquals(1.85, loaded.customAspect);
        assertEquals(List.of("minecraft:rain", "minecraft:smoke"), loaded.hiddenParticleTypes);
        assertTrue(loaded.entityOverride(FIRST).hide());
        // A copy is separate
        Looks copy = looks.copy();
        copy.sky = true;
        copy.hiddenParticleTypes.add("minecraft:flame");
        copy.setEntityHidden(FIRST, false);
        assertFalse(looks.sky);
        assertEquals(2, looks.hiddenParticleTypes.size());
        assertTrue(looks.entityOverride(FIRST).hide());
    }

    @Test
    void theServersResourcePacksAreUsedUnlessTheSceneSaysNot() {
        Looks looks = new Looks();
        assertEquals("true", looks.getText("server_resource_packs"));
        assertTrue(looks.describeText().contains("\"server_resource_packs\":{\"type\":\"boolean\",\"value\":true,\"description\":"), looks.describeText());
        looks.setAllText("{\"server_resource_packs\": false}");
        assertFalse(looks.serverResourcePacks);
        // Saved with the scene, and a separate copy stays separate
        assertFalse(Looks.fromJsonText(looks.toJsonText()).serverResourcePacks);
        Looks copy = looks.copy();
        copy.serverResourcePacks = true;
        assertFalse(looks.serverResourcePacks);
        // A scene saved before the setting existed uses the packs
        assertTrue(Looks.fromJsonText("{\"night_vision\": true}").serverResourcePacks);
        assertEquals("'server_resource_packs' must be true or false, got \"no\"", refused(looks, "{\"server_resource_packs\": \"no\"}"));
    }

}
