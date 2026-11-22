package comedymonkey.dailies.effects;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.capture.Recorder;
import comedymonkey.dailies.capture.SnapshotExtensions;
import comedymonkey.dailies.looks.LooksRenderer;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.playback.PlaybackExtensions;
import comedymonkey.dailies.playback.Viewer;
import comedymonkey.dailies.ui.HudHidden;
import java.nio.ByteBuffer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jspecify.annotations.Nullable;

/**
 * What the recorder saw of their own state, shown through their eyes in playback: the overlays and screen effects the
 * game draws from the player it is played by (frost, the carved pumpkin, the spyglass, fire, water, a block in the
 * face, darkness, nausea, the portal's wobble, the sleep fade, a totem going off). The game draws them from
 * {@code minecraft.player}, which in playback is the viewer's own invisible spectator; Dailies draws them from the
 * recorded player the camera rides with instead.
 * <p>
 * The owner's rules (PLAN-EFFECTS.md): only through someone's eyes in first person, and only with the scene's Show HUD
 * look on (and the HUD not hidden), as a let's play shows them; never in third person, with the camera flying free, or
 * with Show HUD off. Outside playback nothing here does anything.
 * <p>
 * Most of it is read from the recorded player as the game keeps it on every player (effects, frozen ticks, being on
 * fire, eyes in water). Two values only the recorder's own game works out are kept here a tick at a time as the game
 * keeps them on its own player (the spin of the warp, how used to water the eyes are), and one is recorded: the portal
 * intensity, written only when it changes and in every snapshot.
 */
public final class RecorderEyes {

    /**
     * The extension record of the recorder's own state that the server keeps from their game: the portal intensity (a
     * float) and whether they are on fire (a byte; the server tells everyone else, but the burning player's own game
     * works it out for itself, so the recording would hold nothing of it)
     */
    public static final String PORTAL = "dailies:own_state";

    // The portal intensity as recorded, the last played, and its value a tick before (for the frame between)
    private static float recordedPortal = 0.0F;
    private static boolean recordedOnFire = false;
    private static float portal = 0.0F;
    private static float portalBefore = 0.0F;
    // Kept as the game keeps them on its own player
    private static float spinTime = 0.0F;
    private static float spinSpeed = 0.0F;
    private static int waterVisionTime = 0;
    // The entity these belong to, and its tick count when they last moved on: they move on only as it is ticked
    private static int keptFor = -1;
    private static int keptAtTick = -1;
    // While recording: the portal intensity last written
    private static float written = 0.0F;
    private static boolean writtenOnFire = false;

    private static volatile Shown shown = Shown.NONE;

    private RecorderEyes() {
    }

    public static void init() {
        Recorder.everyRecordedTick(RecorderEyes::recordPortal);
        SnapshotExtensions.register(PORTAL, (out, codec) -> {
            LocalPlayer player = Minecraft.getInstance().player;
            out.extension(PORTAL, encode(player == null ? 0.0F : player.portalEffectIntensity, player != null && player.isOnFire()));
        });
        PlaybackExtensions.register(PORTAL, new PlaybackExtensions.Extension() {
            @Override
            public void play(byte[] data, int from, int length, boolean skipping) {
                if (length >= 5) {
                    ByteBuffer buffer = ByteBuffer.wrap(data, from, length);
                    recordedPortal = buffer.getFloat();
                    recordedOnFire = (buffer.get() & 1) != 0;
                }
            }

            @Override
            public void reset() {
                recordedPortal = 0.0F;
                recordedOnFire = false;
            }

            @Override
            public void describe(java.util.function.Consumer<byte[]> out) {
                out.accept(encode(recordedPortal, recordedOnFire));
            }
        });
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> tick());
    }

    // ------------------------------------------------------------------ Whose eyes

    /**
     * The living entity whose overlays and screen effects are shown just now: the one the view is through, in first
     * person, with Show HUD on and the HUD not hidden. Null otherwise, and always outside playback. Client thread
     */
    public static @Nullable LivingEntity subject() {
        if (!Playback.loaded()) {
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (!(Viewer.riding() instanceof LivingEntity living) || !minecraft.options.getCameraType().isFirstPerson()
            || !LooksRenderer.showsHud() || HudHidden.get()) {
            return null;
        }
        return living;
    }

    /** Whether playback decides these overlays and effects (a recording is open), rather than the game */
    public static boolean inPlayback() {
        return Playback.loaded();
    }

    /** What the overlays and effects showed in the last frame, for the self test */
    public static Shown shown() {
        return shown;
    }

    static void shown(Shown now) {
        shown = now;
    }

    // ------------------------------------------------------------------ Kept a tick at a time

    private static void tick() {
        if (!Playback.loaded()) {
            keptFor = -1;
            return;
        }
        // The recorder burns in playback as they did: on their body in every view, and through their eyes
        Player recorder = recorder();
        if (recorder != null && recorder.isOnFire() != recordedOnFire) {
            recorder.setSharedFlagOnFire(recordedOnFire);
        }
        Entity riding = Viewer.riding();
        if (!(riding instanceof LivingEntity living)) {
            keptFor = -1;
            return;
        }
        if (living.getId() != keptFor) {
            keptFor = living.getId();
            keptAtTick = living.tickCount;
            spinTime = 0.0F;
            spinSpeed = 0.0F;
            waterVisionTime = 0;
            portal = portalBefore = portalOf(living);
            return;
        }
        if (living.tickCount == keptAtTick) {
            // Paused, or between ticks: nothing moves on
            return;
        }
        keptAtTick = living.tickCount;
        portalBefore = portal;
        portal = portalOf(living);
        float nausea = living.getEffectBlendFactor(MobEffects.NAUSEA, 1.0F);
        if (!(portal > 0.0F) && !(nausea > 0.0F)) {
            spinSpeed = 0.0F;
        } else {
            spinSpeed = (portal * 20.0F + nausea * 7.0F) / (portal + nausea);
            spinTime += spinSpeed;
        }
        if (living.isEyeInFluid(FluidTags.WATER)) {
            waterVisionTime = Mth.clamp(waterVisionTime + (living.isSpectator() ? 10 : 1), 0, 600);
        } else if (waterVisionTime > 0) {
            waterVisionTime = Mth.clamp(waterVisionTime - 10, 0, 600);
        }
    }

    /** The recorder's stand-in in the playback world, or null */
    private static @Nullable Player recorder() {
        try {
            var manifest = Playback.manifest();
            Minecraft minecraft = Minecraft.getInstance();
            if (manifest == null || manifest.recorderId == null || manifest.recorderId.isEmpty() || minecraft.level == null) {
                return null;
            }
            return minecraft.level.getPlayerByUUID(java.util.UUID.fromString(manifest.recorderId));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The recorded portal intensity belongs to the recorder alone: anyone else rides with none */
    private static float portalOf(LivingEntity living) {
        try {
            var manifest = Playback.manifest();
            return manifest != null && manifest.recorderId != null && living.getUUID().toString().equals(manifest.recorderId) ? recordedPortal : 0.0F;
        } catch (RuntimeException e) {
            return 0.0F;
        }
    }

    /** The portal intensity at this moment between ticks */
    static float portal(float partialTicks) {
        return Mth.lerp(partialTicks, portalBefore, portal);
    }

    /** How used to the water the eyes are, as the game works it out for its own player */
    public static float waterVision(LivingEntity living) {
        if (!living.isEyeInFluid(FluidTags.WATER)) {
            return 0.0F;
        }
        if (waterVisionTime >= 600.0F) {
            return 1.0F;
        }
        float a = Mth.clamp(waterVisionTime / 100.0F, 0.0F, 1.0F);
        float b = waterVisionTime < 100.0F ? 0.0F : Mth.clamp((waterVisionTime - 100.0F) / 500.0F, 0.0F, 1.0F);
        return a * 0.6F + b * 0.39999998F;
    }

    // ------------------------------------------------------------------ The recorder's portal, while recording

    private static void recordPortal() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        float now = player.portalEffectIntensity;
        boolean onFire = player.isOnFire();
        if (Math.abs(now - written) > 1.0E-4F || now == 0.0F && written != 0.0F || onFire != writtenOnFire) {
            written = now;
            writtenOnFire = onFire;
            Recorder.get().writeExtension(PORTAL, encode(now, onFire));
        }
    }

    private static byte[] encode(float portal, boolean onFire) {
        return ByteBuffer.allocate(5).putFloat(portal).put((byte) (onFire ? 1 : 0)).array();
    }

    // ------------------------------------------------------------------ The frame's player state

    /**
     * Fills the frame's player state (the warp, the spin, fire, water and a block in the face) from the one whose eyes
     * the view is through, or empties it of the viewer's own where no one's should show. Called after the game filled
     * it from {@code minecraft.player}. Render thread
     */
    public static void fillPlayerState(PlayerRenderState state, Camera camera, float worldPartialTicks) {
        if (!inPlayback()) {
            return;
        }
        LivingEntity subject = subject();
        if (subject == null) {
            state.portalEffectIntensity = 0.0F;
            state.nauseaEffectIntensity = 0.0F;
            state.spinningEffectAngle = 0.0F;
            state.blockOverlay = null;
            state.waterOverlay = null;
            state.isEyeInWater = false;
            state.isOnFire = false;
            state.itemActivation = null;
            state.customBlockOverlayRenderer = null;
            state.customFluidOverlayRenderer = null;
            state.customFireOverlayRenderer = null;
            return;
        }
        state.portalEffectIntensity = portal(worldPartialTicks);
        state.nauseaEffectIntensity = subject.getEffectBlendFactor(MobEffects.NAUSEA, worldPartialTicks);
        state.spinningEffectAngle = spinTime + worldPartialTicks * spinSpeed;
        state.isUnderWater = subject.isUnderWater();
        state.eyePositionY = subject.getEyePosition(worldPartialTicks).y;
        state.customBlockOverlayRenderer = null;
        state.customFluidOverlayRenderer = null;
        state.customFireOverlayRenderer = null;
        if (state.avatarRenderState != null) {
            // The game shows fire and water only to a player who is not a spectator, as the viewer always is
            state.avatarRenderState.isSpectator = subject.isSpectator();
        }
        state.blockOverlay = null;
        if (!subject.isSleeping()) {
            BlockState blocking = viewBlocking(subject, camera);
            if (blocking != null) {
                var sprite = Minecraft.getInstance().getModelManager().getBlockStateModelSet().getParticleMaterial(blocking).sprite();
                state.blockOverlay = new PlayerRenderState.BlockOverlay(sprite.atlasLocation(), sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1());
            }
        }
        state.isEyeInWater = subject.isEyeInFluid(FluidTags.WATER);
        state.isOnFire = subject.isOnFire();
        state.waterOverlay = null;
        if (state.isEyeInWater) {
            BlockPos eyes = BlockPos.containing(subject.getEyePosition());
            float brightness = Lightmap.getBrightness(subject.level().dimensionType(), subject.level().getMaxLocalRawBrightness(eyes));
            state.waterOverlay = new PlayerRenderState.WaterOverlay(ARGB.colorFromFloat(0.1F, brightness, brightness, brightness),
                -subject.getYRot() / 64.0F, subject.getXRot() / 64.0F);
        }
    }

    /** The block in front of the eyes that the game would draw over the picture, as it works it out for its own player */
    private static @Nullable BlockState viewBlocking(LivingEntity living, Camera camera) {
        if (living.noPhysics) {
            return null;
        }
        AABB nearPlane = camera.getCullFrustum().getNearPlaneBounds().move(living.getEyePosition());
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int i = 0; i < 8; i++) {
            at.set(living.getX() + ((i >> 0) % 2 - 0.5F) * living.getBbWidth() * 0.8F,
                living.getEyeY() + ((i >> 1) % 2 - 0.5F) * 0.1F * living.getScale(),
                living.getZ() + ((i >> 2) % 2 - 0.5F) * living.getBbWidth() * 0.8F);
            BlockState state = living.level().getBlockState(at);
            if (state.getRenderShape() != RenderShape.INVISIBLE && state.isViewBlocking(living.level(), at, nearPlane)) {
                return state;
            }
        }
        return null;
    }

    /**
     * What the overlays and screen effects showed in a frame, for the self test.
     *
     * @param darkness the lightmap's darkness scale
     */
    public record Shown(boolean through, float frost, float portal, float nausea, boolean spyglass, int wornOverlays, boolean onFire,
                        boolean eyesInWater, boolean blockInFace, int sleep, float darkness, float nightVision) {
        public static final Shown NONE = new Shown(false, 0.0F, 0.0F, 0.0F, false, 0, false, false, false, 0, 0.0F, 0.0F);

        Shown withState(PlayerRenderState state) {
            return new Shown(this.through, this.frost, this.portal, state.nauseaEffectIntensity, this.spyglass, this.wornOverlays, state.isOnFire,
                state.isEyeInWater, state.blockOverlay != null, this.sleep, this.darkness, this.nightVision);
        }
    }

    // The parts of what is shown come from different places of the frame: each puts in its own

    public static void shownOverlays(boolean through, float frost, float portalNow, boolean spyglass, int worn) {
        Shown was = shown;
        shown = new Shown(through, frost, portalNow, was.nausea(), spyglass, worn, was.onFire(), was.eyesInWater(), was.blockInFace(), was.sleep(), was.darkness(),
            was.nightVision());
    }

    public static void shownState(PlayerRenderState state) {
        if (inPlayback()) {
            shown = shown.withState(state);
        }
    }

    public static void shownSleep(int sleep) {
        Shown was = shown;
        shown = new Shown(was.through(), was.frost(), was.portal(), was.nausea(), was.spyglass(), was.wornOverlays(), was.onFire(), was.eyesInWater(),
            was.blockInFace(), sleep, was.darkness(), was.nightVision());
    }

    public static void shownLight(float darkness, float nightVision) {
        Shown was = shown;
        shown = new Shown(was.through(), was.frost(), was.portal(), was.nausea(), was.spyglass(), was.wornOverlays(), was.onFire(), was.eyesInWater(),
            was.blockInFace(), was.sleep(), darkness, nightVision);
    }

    static void failOnce(String what, Throwable t) {
        if (!failed) {
            failed = true;
            Host.LOGGER.warn("Dailies: the recorder's overlays and effects could not be shown ({}). Said once", what, t);
        }
    }

    private static boolean failed = false;
}
