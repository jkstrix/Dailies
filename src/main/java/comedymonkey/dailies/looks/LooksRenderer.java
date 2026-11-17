package comedymonkey.dailies.looks;

import com.mojang.blaze3d.systems.RenderSystem;
import comedymonkey.dailies.Dailies;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.edit.EntityOverride;
import comedymonkey.dailies.edit.Looks;
import comedymonkey.dailies.edit.Scene;
import comedymonkey.dailies.editor.EditorSession;
import comedymonkey.dailies.export.Export;
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
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Pre event) -> {
            Drawn.frame();
            frame();
        });
        // After whoever sets the field of view, and before whoever reads what it came to
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOW, (ViewportEvent.ComputeFov event) -> framedFov(event));
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

            @Override
            public void beforeDescribe() {
                // A block shown by a keyframe is not the recording's, and must not end up in a trimmed recording's snapshot
                BlockChanges.putAllBack(Minecraft.getInstance().level);
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

    /**
     * The shape the picture is held to in the editor's view: width divided by height, or 0 when the scene has no
     * frame of its own, an export is drawing (its pictures are that shape already) or the interface is hidden.
     */
    public static double framedRatio() {
        Looks now = looks;
        if (now == null || Export.active() || Minecraft.getInstance().options.hideGui) {
            return 0.0;
        }
        return now.frameRatio();
    }

    // ------------------------------------------------------------------ The frame among the editor's panels

    /** The part of the window the editor's panels leave clear, as shares of it: {left, top, right, bottom} */
    private static volatile double[] clearOfPanels = null;
    private static volatile long clearSaidAt = -10;
    private static volatile boolean projectionHooked = false;

    /**
     * Said by the editor on every frame it is on screen: the part of the window its panels leave clear, as shares
     * of the window. A framed picture is then shown whole inside that part and not under the panels.
     */
    public static void clearOfPanels(double left, double top, double right, double bottom) {
        if (right - left > 0.05 && bottom - top > 0.05) {
            clearOfPanels = new double[]{Math.max(0.0, left), Math.max(0.0, top), Math.min(1.0, right), Math.min(1.0, bottom)};
            clearSaidAt = Drawn.frames();
        }
    }

    private static double windowRatio() {
        com.mojang.blaze3d.platform.Window window = Minecraft.getInstance().getWindow();
        return window.getHeight() > 0 ? (double) window.getWidth() / window.getHeight() : 16.0 / 9.0;
    }

    /**
     * Where the framed picture is shown, as shares of the window: {left, top, right, bottom}. The largest frame of
     * the scene's shape that fits in the middle of the window, or, while the editor is up, of the part its panels
     * leave clear. Null when the scene has no frame of its own.
     */
    public static double @Nullable [] frameRect() {
        double ratio = framedRatio();
        if (ratio <= 0.0) {
            return null;
        }
        double window = windowRatio();
        double[] area = clearOfPanels;
        if (area == null || !projectionHooked || Drawn.frames() - clearSaidAt > 3) {
            double[] middle = Looks.clearPart(ratio, window);
            return middle;
        }
        double areaWidth = area[2] - area[0];
        double areaHeight = area[3] - area[1];
        double width = areaWidth;
        double height = width * window / ratio;
        if (height > areaHeight) {
            height = areaHeight;
            width = height * ratio / window;
        }
        double left = area[0] + (areaWidth - width) / 2.0;
        double top = area[1] + (areaHeight - height) / 2.0;
        return new double[]{left, top, left + width, top + height};
    }

    /**
     * How the picture is made smaller and moved to stand in {@link #frameRect}: {scale, across, up}, the last two in
     * the measure where the window runs from -1 to 1 each way. Null when it stands where the game draws it.
     */
    public static double @Nullable [] viewShift() {
        double[] rect = frameRect();
        if (rect == null || !projectionHooked) {
            return null;
        }
        double[] middle = Looks.clearPart(framedRatio(), windowRatio());
        double scale = (rect[2] - rect[0]) / (middle[2] - middle[0]);
        double across = (rect[0] + rect[2]) - 1.0;
        double up = 1.0 - (rect[1] + rect[3]);
        if (Math.abs(scale - 1.0) < 1.0e-6 && Math.abs(across) < 1.0e-6 && Math.abs(up) < 1.0e-6) {
            return null;
        }
        return new double[]{scale, across, up};
    }

    /** For the hook on the game's projection: {@link #viewShift}, and the note that the hook is there */
    public static double @Nullable [] projectionShift() {
        projectionHooked = true;
        return looks == null ? null : viewShift();
    }

    /**
     * A frame wider than the window is shown between bars above and below. The view is drawn that much taller, so
     * that what is left between the bars is exactly the picture an export of that shape will hold.
     */
    private static void framedFov(ViewportEvent.ComputeFov event) {
        double ratio = framedRatio();
        if (ratio <= 0.0 || !event.usedConfiguredFov()) {
            return;
        }
        try {
            com.mojang.blaze3d.platform.Window window = Minecraft.getInstance().getWindow();
            if (window.getHeight() > 0) {
                event.setFOV(Looks.fovBehindBars(event.getFOV(), ratio, (double) window.getWidth() / window.getHeight()));
            }
        } catch (Throwable t) {
            failOnce("fitting the view to the scene's frame", t);
        }
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
                comedymonkey.dailies.compat.create.FlywheelLooks.frame(false);
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
            comedymonkey.dailies.compat.create.FlywheelLooks.frame(true);
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
                || !now.showActionBar && layer.equals(VanillaGuiLayers.OVERLAY_MESSAGE)
                || !now.showScoreboard && layer.equals(VanillaGuiLayers.SCOREBOARD_SIDEBAR)
                || !now.showHotbar && (layer.equals(VanillaGuiLayers.HOTBAR) || layer.equals(VanillaGuiLayers.SELECTED_ITEM_NAME))
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
        if (entity instanceof Player ? !now.showPlayers : !now.showEntities) {
            return true;
        }
        Set<EntityType<?>> hidden = hiddenEntityTypes;
        return !hidden.isEmpty() && hidden.contains(entity.getType()) || override(now, entity).hide();
    }

    /** Whether any entity at all may be hidden just now, so that asking about each one is worth it */
    public static boolean hidesAnyEntity() {
        Looks now = looks;
        return now != null && (!now.showEntities || !now.showPlayers || anyOverrides || !hiddenEntityTypes.isEmpty());
    }

    /** Whether the terrain, and the block entities in it, are left out */
    public static boolean hidesBlocks() {
        Looks now = looks;
        return now != null && !now.showBlocks;
    }

    public static boolean hidesBeaconBeams() {
        Looks now = looks;
        return now != null && !now.showBeaconBeams;
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
