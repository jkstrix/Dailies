package comedymonkey.dailies.looks;

import com.mojang.blaze3d.systems.RenderSystem;
import comedymonkey.dailies.Dailies;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.edit.EntityOverride;
import comedymonkey.dailies.edit.Looks;
import comedymonkey.dailies.edit.Scene;
import comedymonkey.dailies.editor.EditorSession;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.playback.PlaybackExtensions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;
import net.neoforged.neoforge.client.event.RenderNameTagEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.TriState;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Makes a scene's looks change what is drawn (specification chapter V, "Looks that are not on the timeline"): what is
 * shown and hidden, how each entity appears, the weather, fog, sky and brightness, and the guides over the frame. It
 * also keeps block change keyframes showing (see {@link BlockChanges}).
 * <p>
 * Nothing here does anything in a live world. Once per frame the looks of the scene being shown are looked up, and
 * every hook reads them from there, so a look left at its default costs one comparison. The same hooks run in
 * playback and in export (EXP-9); only the guides are left out of exported pictures.
 */
public final class LooksRenderer {

    /** Far enough that nothing is ever fogged, and apart so no renderer divides by zero */
    private static final float NO_FOG_START = 1.0e6F;
    private static final float NO_FOG_END = 2.0e6F;

    /** The layers that make up the recorder's HUD when riding along */
    private static final Set<ResourceLocation> HUD = Set.of(VanillaGuiLayers.HOTBAR, VanillaGuiLayers.CROSSHAIR, VanillaGuiLayers.PLAYER_HEALTH,
            VanillaGuiLayers.ARMOR_LEVEL, VanillaGuiLayers.FOOD_LEVEL, VanillaGuiLayers.VEHICLE_HEALTH, VanillaGuiLayers.AIR_LEVEL,
            VanillaGuiLayers.JUMP_METER, VanillaGuiLayers.EXPERIENCE_BAR, VanillaGuiLayers.EXPERIENCE_LEVEL, VanillaGuiLayers.SELECTED_ITEM_NAME,
            VanillaGuiLayers.SPECTATOR_TOOLTIP, VanillaGuiLayers.EFFECTS, VanillaGuiLayers.SCOREBOARD_SIDEBAR, VanillaGuiLayers.TAB_LIST);

    /** The looks of the scene being shown, or null whenever no recording is on screen */
    private static volatile Looks looks = null;
    private static volatile boolean anyOverrides = false;
    private static volatile Set<EntityType<?>> hiddenEntityTypes = Set.of();
    private static volatile Set<ParticleType<?>> hiddenParticleTypes = Set.of();
    private static List<String> seenEntityTypes = List.of();
    private static List<String> seenParticleTypes = List.of();
    private static boolean particlesShown = true;

    private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();

    private LooksRenderer() {
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Pre event) -> frame());
        NeoForge.EVENT_BUS.addListener((RenderGuiLayerEvent.Pre event) -> guiLayer(event));
        NeoForge.EVENT_BUS.addListener((RenderNameTagEvent event) -> nameTag(event));
        NeoForge.EVENT_BUS.addListener((RenderHandEvent event) -> {
            Looks now = looks;
            if (now != null && !now.showHeldItems) {
                event.setCanceled(true);
            }
        });
        NeoForge.EVENT_BUS.addListener((RenderHighlightEvent.Block event) -> {
            Looks now = looks;
            if (now != null && !now.showBlockOutline) {
                event.setCanceled(true);
            }
        });
        NeoForge.EVENT_BUS.addListener((ViewportEvent.RenderFog event) -> fog(event));
        NeoForge.EVENT_BUS.addListener((ViewportEvent.ComputeFogColor event) -> fogColour(event));

        Playback.onOpened(LooksRenderer::forget);
        Playback.onClosing(LooksRenderer::forget);
        PlaybackExtensions.register("dailies:looks", new PlaybackExtensions.Extension() {
            @Override
            public void play(byte[] data, int from, int length, boolean skipping) {
                // Looks write nothing into recordings
            }

            @Override
            public void reset() {
                // The world is rebuilt from the recording, so whatever was changed in it is gone
                BlockChanges.forget();
            }
        });

        IEventBus modBus = ModList.get().getModContainerById(Dailies.ID).map(ModContainer::getEventBus).orElse(null);
        if (modBus != null) {
            modBus.addListener((RegisterGuiLayersEvent event) -> event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(Dailies.ID, "frame_guides"),
                    FrameGuides::draw));
        } else {
            failOnce("registering the frame guides", new IllegalStateException("The mod's event bus was not found"));
        }
    }

    /** The looks of the scene being shown, or null whenever no recording is on screen */
    static @Nullable Looks current() {
        return looks;
    }

    private static void forget() {
        looks = null;
        anyOverrides = false;
        hiddenEntityTypes = Set.of();
        hiddenParticleTypes = Set.of();
        seenEntityTypes = List.of();
        seenParticleTypes = List.of();
        particlesShown = true;
        BlockChanges.forget();
    }

    /** Once per drawn frame, after the game's ticks and before the picture: take up the scene's looks as they are now */
    private static void frame() {
        try {
            if (!Playback.loaded() || !EditorSession.open()) {
                if (looks != null) {
                    forget();
                }
                return;
            }
            Minecraft minecraft = Minecraft.getInstance();
            Scene scene = EditorSession.scene();
            Looks now = scene.looks();
            anyOverrides = !now.entityOverrides().isEmpty();
            if (!now.hiddenEntityTypes.equals(seenEntityTypes)) {
                seenEntityTypes = List.copyOf(now.hiddenEntityTypes);
                Set<EntityType<?>> types = new HashSet<>();
                for (String id : seenEntityTypes) {
                    ResourceLocation key = ResourceLocation.tryParse(id);
                    if (key != null) {
                        BuiltInRegistries.ENTITY_TYPE.getOptional(key).ifPresent(types::add);
                    }
                }
                hiddenEntityTypes = Set.copyOf(types);
            }
            if (!now.hiddenParticleTypes.equals(seenParticleTypes)) {
                seenParticleTypes = List.copyOf(now.hiddenParticleTypes);
                Set<ParticleType<?>> types = new HashSet<>();
                for (String id : seenParticleTypes) {
                    ResourceLocation key = ResourceLocation.tryParse(id);
                    if (key != null) {
                        BuiltInRegistries.PARTICLE_TYPE.getOptional(key).ifPresent(types::add);
                    }
                }
                hiddenParticleTypes = Set.copyOf(types);
            }
            if (particlesShown && !now.showParticles) {
                // Those already in the air go at once; new ones are turned away where they are added
                minecraft.particleEngine.iterateParticles(Particle::remove);
            }
            particlesShown = now.showParticles;
            looks = now;
            BlockChanges.frame(minecraft, scene);
        } catch (Throwable t) {
            // For example the recording closing between two of the questions above
            looks = null;
            failOnce("reading the scene's looks", t);
        }
    }

    // ------------------------------------------------------------------ What is drawn

    private static void guiLayer(RenderGuiLayerEvent.Pre event) {
        Looks now = looks;
        if (now == null) {
            return;
        }
        ResourceLocation layer = event.getName();
        if (!now.showChat && layer.equals(VanillaGuiLayers.CHAT)
                || !now.showBossBars && layer.equals(VanillaGuiLayers.BOSS_OVERLAY)
                || !now.showTitles && (layer.equals(VanillaGuiLayers.TITLE) || layer.equals(VanillaGuiLayers.OVERLAY_MESSAGE))
                || !now.showHud && HUD.contains(layer)) {
            event.setCanceled(true);
        }
    }

    private static void nameTag(RenderNameTagEvent event) {
        Looks now = looks;
        if (now == null) {
            return;
        }
        try {
            if (!now.showNameTags) {
                event.setCanRender(TriState.FALSE);
                return;
            }
            if (!anyOverrides) {
                return;
            }
            EntityOverride override = now.entityOverride(event.getEntity().getUUID());
            if (override.hideNametag()) {
                event.setCanRender(TriState.FALSE);
            } else if (override.nameOverride() != null) {
                event.setContent(Component.literal(override.nameOverride()));
                if (!(event.getEntity() instanceof Player)) {
                    // A mob has no name tag of its own to rename, so the new name is given one
                    event.setCanRender(TriState.TRUE);
                }
            }
        } catch (Throwable t) {
            failOnce("changing a name tag", t);
        }
    }

    /** Whether new particles of every kind are turned away */
    public static boolean dropsParticles() {
        Looks now = looks;
        return now != null && !now.showParticles;
    }

    /** Whether a new particle of this kind is turned away */
    public static boolean dropsParticle(ParticleOptions particle) {
        Looks now = looks;
        if (now == null) {
            return false;
        }
        Set<ParticleType<?>> hidden = hiddenParticleTypes;
        return !now.showParticles || !hidden.isEmpty() && hidden.contains(particle.getType());
    }

    /** Whether this entity's shadow is the viewer's own and is not to be drawn */
    public static boolean hidesShadow(Entity entity) {
        Looks now = looks;
        if (now == null || now.showOwnShadow) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        return entity == minecraft.player || entity == minecraft.getCameraEntity();
    }

    // ------------------------------------------------------------------ Entities

    private static EntityOverride override(Looks now, Entity entity) {
        try {
            return anyOverrides ? now.entityOverride(entity.getUUID()) : EntityOverride.NONE;
        } catch (Throwable t) {
            failOnce("looking up the changes to an entity", t);
            return EntityOverride.NONE;
        }
    }

    /** Whether this entity is not drawn at all: its kind is filtered out, or it was hidden by itself */
    public static boolean hidesEntity(Entity entity) {
        Looks now = looks;
        if (now == null) {
            return false;
        }
        Set<EntityType<?>> hidden = hiddenEntityTypes;
        return !hidden.isEmpty() && hidden.contains(entity.getType()) || override(now, entity).hide();
    }

    /** Whether this entity is drawn with the glowing outline, given whether the recording says so */
    public static boolean glows(Entity entity, boolean recorded) {
        Looks now = looks;
        if (now == null || !anyOverrides) {
            return recorded;
        }
        return switch (override(now, entity).glowing()) {
            case FORCE_GLOW -> true;
            case FORCE_NO_GLOW -> false;
            case DEFAULT -> recorded;
        };
    }

    public static boolean hidesCape(Entity entity) {
        Looks now = looks;
        return now != null && anyOverrides && override(now, entity).hideCape();
    }

    public static boolean hidesArmour(Entity entity) {
        Looks now = looks;
        return now != null && anyOverrides && override(now, entity).hideArmour();
    }

    // ------------------------------------------------------------------ The world's look

    private static @Nullable Looks.Weather weather(Level level) {
        Looks now = looks;
        if (now == null || now.weather == Looks.Weather.AS_RECORDED || level != Minecraft.getInstance().level) {
            return null;
        }
        return now.weather;
    }

    /** How hard it rains in the world being shown (0 to 1), given what the recording says */
    public static float rain(Level level, float recorded) {
        Looks.Weather weather = weather(level);
        return weather == null ? recorded : weather == Looks.Weather.CLEAR ? 0.0F : 1.0F;
    }

    /** How much of a thunderstorm there is in the world being shown (0 to 1), given what the recording says */
    public static float thunder(Level level, float recorded) {
        Looks.Weather weather = weather(level);
        return weather == null ? recorded : weather == Looks.Weather.THUNDER ? 1.0F : 0.0F;
    }

    private static void fog(ViewportEvent.RenderFog event) {
        Looks now = looks;
        if (now == null || now.fogMode == Looks.FogMode.AS_RECORDED || event.getMode() != FogRenderer.FogMode.FOG_TERRAIN) {
            return;
        }
        if (now.fogMode == Looks.FogMode.OFF) {
            event.setNearPlaneDistance(NO_FOG_START);
            event.setFarPlaneDistance(NO_FOG_END);
        } else {
            float start = (float) now.fogStart;
            event.setNearPlaneDistance(start);
            event.setFarPlaneDistance(Math.max((float) now.fogEnd, start + 0.01F));
        }
        // Cancelling is how this event is told to use the distances set on it
        event.setCanceled(true);
    }

    private static void fogColour(ViewportEvent.ComputeFogColor event) {
        Looks now = looks;
        if (now == null || now.fogMode != Looks.FogMode.CUSTOM) {
            return;
        }
        int colour = now.fogColour;
        event.setRed((colour >> 16 & 0xFF) / 255.0F);
        event.setGreen((colour >> 8 & 0xFF) / 255.0F);
        event.setBlue((colour & 0xFF) / 255.0F);
    }

    /** Whether the sky, sun, moon, stars and clouds are left out */
    public static boolean skyOff() {
        Looks now = looks;
        return now != null && !now.sky;
    }

    /**
     * Called where the sky would be drawn, straight after the picture has been cleared and before anything is in it.
     *
     * @return true when the sky is off: the picture has been filled with the background colour and no sky is to be drawn
     */
    public static boolean replaceSky() {
        Looks now = looks;
        if (now == null || now.sky) {
            return false;
        }
        try {
            int colour = now.backgroundColour;
            // No alpha, as the game clears it, so an export can keep the background see-through
            RenderSystem.clearColor((colour >> 16 & 0xFF) / 255.0F, (colour >> 8 & 0xFF) / 255.0F, (colour & 0xFF) / 255.0F, 0.0F);
            RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT, Minecraft.ON_OSX);
        } catch (Throwable t) {
            failOnce("filling the background where the sky was", t);
        }
        return true;
    }

    /** Whether everything is lit at full brightness */
    public static boolean nightVision() {
        Looks now = looks;
        return now != null && now.nightVision;
    }

    // ------------------------------------------------------------------

    /** Logs a failure the first time its kind happens, and never again */
    static void failOnce(String what, Throwable problem) {
        if (FAILED.add(what)) {
            Host.LOGGER.error("Dailies: {} failed, so a scene's looks may not all show. Said once", what, problem);
        }
    }

}
