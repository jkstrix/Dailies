package comedymonkey.dailies.effects;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.capture.Recorder;
import comedymonkey.dailies.capture.SnapshotExtensions;
import comedymonkey.dailies.looks.LooksRenderer;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.playback.PlaybackExtensions;
import comedymonkey.dailies.playback.Viewer;
import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;

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
 * fire, eyes in water). This game keeps the warp of the portal and of nausea as one value on its own player, its
 * spinning effect intensity, which only the recorder's own game works out: it is recorded, written only when it changes
 * and in every snapshot. For anyone else the view rides with, and for a recording made before it was recorded, it is
 * worked out here a tick at a time from their nausea, as the game works it out for its own player; so are the spin's
 * clock and how used to water the eyes are.
 */
public final class RecorderEyes {

    /**
     * The extension record of the recorder's own state that the server keeps from their game: the warp's intensity (a
     * float: on this game the portal's and nausea's together, as it keeps them) and whether they are on fire (a byte)
     */
    public static final String PORTAL = "dailies:own_state";

    // The warp as recorded, whether a record of it has been played, the last played, and its value a tick before
    private static float recordedPortal = 0.0F;
    private static boolean recordedOnFire = false;
    private static boolean recordedSeen = false;
    private static float portal = 0.0F;
    private static float portalBefore = 0.0F;
    // Kept as the game keeps them on its own player and in its renderer
    private static int spinTicks = 0;
    private static int waterVisionTime = 0;
    // The entity these belong to, and its tick count when they last moved on: they move on only as it is ticked
    private static int keptFor = -1;
    private static int keptAtTick = -1;
    // While recording: the warp last written
    private static float written = 0.0F;
    private static boolean writtenOnFire = false;
    // A totem going off drawn this frame, for the self test
    private static volatile boolean totemShown = false;

    private static volatile Shown shown = Shown.NONE;

    private RecorderEyes() {
    }

    public static void init() {
        Recorder.everyRecordedTick(RecorderEyes::recordPortal);
        SnapshotExtensions.register(PORTAL, (out, codec) -> {
            LocalPlayer player = Minecraft.getInstance().player;
            out.extension(PORTAL, encode(player == null ? 0.0F : player.spinningEffectIntensity, player != null && player.isOnFire()));
        });
        PlaybackExtensions.register(PORTAL, new PlaybackExtensions.Extension() {
            @Override
            public void play(byte[] data, int from, int length, boolean skipping) {
                if (length >= 5) {
                    ByteBuffer buffer = ByteBuffer.wrap(data, from, length);
                    recordedPortal = buffer.getFloat();
                    recordedOnFire = (buffer.get() & 1) != 0;
                    recordedSeen = true;
                }
            }

            @Override
            public void reset() {
                recordedPortal = 0.0F;
                recordedOnFire = false;
                recordedSeen = false;
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
            || !LooksRenderer.showsHud() || minecraft.options.hideGui) {
            return null;
        }
        return living;
    }

    /** Whether playback decides these overlays and effects (a recording is open), rather than the game */
    public static boolean inPlayback() {
        return Playback.loaded();
    }

    private static volatile boolean skyBlocked = false;
    private static volatile int iconsShown = 0;

    /** How many potion effect icons the HUD was given in the last frame it drew them, for the self test */
    public static int iconsShown() {
        return iconsShown;
    }

    public static void iconsShown(int count) {
        iconsShown = count;
    }

    /** Whether an effect took the sky away in the last frame, for the self test */
    public static boolean skyBlocked() {
        return skyBlocked;
    }

    public static void skyBlocked(boolean blocked) {
        skyBlocked = blocked;
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
            spinTicks = 0;
            waterVisionTime = 0;
            portal = portalBefore = isRecorder(living) && recordedSeen ? recordedPortal : 0.0F;
            return;
        }
        if (living.tickCount == keptAtTick) {
            // Paused, or between ticks: nothing moves on
            return;
        }
        keptAtTick = living.tickCount;
        spinTicks++;
        portalBefore = portal;
        if (isRecorder(living) && recordedSeen) {
            portal = recordedPortal;
        } else {
            // As the game works it out for its own player, with no portal: nausea draws the warp in, it fades over the
            // effect's last three seconds, and it is gone the moment the effect is
            MobEffectInstance nausea = living.getEffect(MobEffects.CONFUSION);
            if (nausea == null) {
                portal = 0.0F;
            } else if (!nausea.endsWithin(60)) {
                portal = Mth.clamp(portal + 0.006666667F, 0.0F, 1.0F);
            } else {
                portal = Mth.clamp(portal - 0.05F, 0.0F, 1.0F);
            }
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

    /** The recorded warp belongs to the recorder alone: anyone else's is worked out here */
    private static boolean isRecorder(LivingEntity living) {
        try {
            var manifest = Playback.manifest();
            return manifest != null && manifest.recorderId != null && living.getUUID().toString().equals(manifest.recorderId);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The warp's intensity a tick before, as the game's own player keeps it in {@code oSpinningEffectIntensity} */
    public static float spinBefore() {
        return portalBefore;
    }

    /** The warp's intensity now, as the game's own player keeps it in {@code spinningEffectIntensity} */
    public static float spinNow() {
        return portal;
    }

    /** The warp's clock, which the game keeps in its renderer and moves on every tick; here only as the ridden entity is ticked */
    public static int spinTicks() {
        return spinTicks;
    }

    /** The warp's intensity at this moment between ticks */
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

    // ------------------------------------------------------------------ The recorder's warp, while recording

    private static void recordPortal() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        float now = player.spinningEffectIntensity;
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

    // ------------------------------------------------------------------ The frame's state

    /**
     * Once a frame, before the world is drawn: empties what the frame's world and screen effects showed, and notes the
     * nausea warp of the one whose eyes the view is through. Render thread
     */
    public static void beginFrame(float partialTicks) {
        if (!inPlayback()) {
            return;
        }
        LivingEntity subject = subject();
        float nausea = subject != null && subject.hasEffect(MobEffects.CONFUSION) ? portal(partialTicks) : 0.0F;
        Shown was = shown;
        shown = new Shown(was.through(), was.frost(), was.portal(), nausea, was.spyglass(), was.wornOverlays(), false, false, false, was.sleep(),
            was.darkness(), was.nightVision());
    }

    /** The block in front of the eyes that the game would draw over the picture, and where, as it works it out for its own player */
    public static @Nullable BlockPos viewBlocking(LivingEntity living) {
        if (living.noPhysics) {
            return null;
        }
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int i = 0; i < 8; i++) {
            at.set(living.getX() + ((i >> 0) % 2 - 0.5F) * living.getBbWidth() * 0.8F,
                living.getEyeY() + ((i >> 1) % 2 - 0.5F) * 0.1F * living.getScale(),
                living.getZ() + ((i >> 2) % 2 - 0.5F) * living.getBbWidth() * 0.8F);
            BlockState state = living.level().getBlockState(at);
            if (state.getRenderShape() != RenderShape.INVISIBLE && state.isViewBlocking(living.level(), at)) {
                return at.immutable();
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
    }

    // The parts of what is shown come from different places of the frame: each puts in its own

    public static void shownOverlays(boolean through, float frost, float portalNow, boolean spyglass, int worn) {
        Shown was = shown;
        shown = new Shown(through, frost, portalNow, was.nausea(), spyglass, worn, was.onFire(), was.eyesInWater(), was.blockInFace(), was.sleep(), was.darkness(),
            was.nightVision());
    }

    public static void shownScreen(boolean onFire, boolean eyesInWater, boolean blockInFace) {
        Shown was = shown;
        shown = new Shown(was.through(), was.frost(), was.portal(), was.nausea(), was.spyglass(), was.wornOverlays(), onFire, eyesInWater, blockInFace,
            was.sleep(), was.darkness(), was.nightVision());
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

    /** Whether a totem going off has been drawn through someone's eyes since this was last asked with clearing. Render thread notes it */
    public static boolean totemShown(boolean clear) {
        boolean was = totemShown;
        if (clear) {
            totemShown = false;
        }
        return was;
    }

    public static void shownTotem() {
        totemShown = true;
    }

    static void failOnce(String what, Throwable t) {
        if (!failed) {
            failed = true;
            Host.LOGGER.warn("Dailies: the recorder's overlays and effects could not be shown ({}). Said once", what, t);
        }
    }

    private static boolean failed = false;
}
