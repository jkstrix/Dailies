package comedymonkey.dailies.playback;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.file.RecordingFile;
import comedymonkey.dailies.file.RecordingManifest;
import comedymonkey.dailies.mixin.access.TimerAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.Input;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientChatEvent;
import net.neoforged.neoforge.client.event.ClientPauseChangeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Watching a recording (specification chapter IV). The game is given a world session with no server behind it, and
 * the recording is fed into it tick by tick. Nothing is simulated: whatever moves does so because the recording
 * says it did. All state belongs to the client thread; the few flags other threads read are volatile.
 */
public final class Playback {

    /** How long a jump may hold up one frame, so the game stays responsive while it runs (PLAY-15) */
    private static final long JUMP_SLICE_NANOS = 30_000_000L;
    /** A jump runs at least this many ticks of recording before it lands, where the recording allows */
    private static final int RUN_UP_TICKS = 60;

    private static volatile boolean active = false;
    private static volatile boolean loaded = false;
    private static volatile boolean paused = true;
    private static volatile boolean skipping = false;
    private static volatile double ticksPerSecond = 20.0;
    private static volatile int position = 0;
    private static volatile int total = 0;
    private static volatile String recordingId = null;
    private static volatile int jumpTarget = -1;

    private static RecordingFile file;
    private static RecordingManifest manifest;
    private static Feed feed;
    private static Path pendingOpen = null;
    private static boolean pendingClose = false;
    private static int reloadIndex = -1;
    private static boolean pauseAfterJump = true;
    private static boolean fastTick = false;
    private static boolean inFrameStart = false;
    private static boolean leaving = false;
    private static final List<Runnable> WHEN_LOADED = new ArrayList<>();
    private static String lastError = null;
    private static int pauseAt = -1;
    private static boolean serverConfigsLoaded = false;
    /** A switch of point of view (PLAY-19) is asked for: the editor stays as it is and the next recording lands here */
    private static boolean keepEditor = false;
    private static int switchTick = -1;
    private static boolean switchPlayOn = false;

    private Playback() {
    }

    public static void init() {
        PackPlayback.init();
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Pre event) -> tickStart());
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> latchPause());
        // A recorded teleport: the entity is put at its new place at once, where the game would slide it there over three ticks
        PlaybackExtensions.register(comedymonkey.dailies.capture.LightPasses.ID, (data, from, length, skipping) -> comedymonkey.dailies.capture.LightPasses.play(data, from, length));
        PlaybackExtensions.register(comedymonkey.dailies.capture.Recorder.SNAP, (data, from, length, skipping) -> {
            Minecraft minecraft = Minecraft.getInstance();
            if (length < 36 || minecraft.level == null) {
                return;
            }
            java.nio.ByteBuffer in = java.nio.ByteBuffer.wrap(data, from, length);
            net.minecraft.world.entity.Entity entity = minecraft.level.getEntity(in.getInt());
            if (entity == null || entity == minecraft.player) {
                return;
            }
            double x = in.getDouble(), y = in.getDouble(), z = in.getDouble();
            float yaw = in.getFloat(), pitch = in.getFloat();
            entity.moveTo(x, y, z, yaw, pitch);
            entity.setOldPosAndRot();
            entity.lerpTo(x, y, z, yaw, pitch, 1);
        });
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Pre event) -> Viewer.frame());
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> {
            if (active && !leaving) {
                // The person left through the game's own menu
                cleanUp();
            }
        });
        // The pause flag is playback's own while a recording is open; the game's usual reasons to change it do not apply
        NeoForge.EVENT_BUS.addListener((ClientPauseChangeEvent.Pre event) -> {
            if (ownsClock()) {
                event.setCanceled(true);
            }
        });
        // The viewer watches; they cannot talk into the recording or open its containers (PLAY-4). The chat itself
        // opens, since that is where "/dailies" is typed: what is said in it goes nowhere, and commands other than
        // that one are turned away where they would be sent (mixin.playback.CommandMixin)
        NeoForge.EVENT_BUS.addListener((ScreenEvent.Opening event) -> {
            if (loaded && event.getNewScreen() instanceof AbstractContainerScreen<?>) {
                event.setCanceled(true);
            }
        });
        NeoForge.EVENT_BUS.addListener((ClientChatEvent event) -> {
            if (active) {
                event.setCanceled(true);
                Minecraft.getInstance().gui.getChat().addMessage(
                    net.minecraft.network.chat.Component.translatable("dailies.command.no_chat").withStyle(net.minecraft.ChatFormatting.RED));
            }
        });
    }

    // --- What others may ask (any thread) ---

    /** True from the moment a recording is asked for until the playback world has been left */
    public static boolean active() {
        return active;
    }

    /** Whether the recording's world is up and can be watched */
    public static boolean loaded() {
        return loaded;
    }

    public static boolean paused() {
        return paused;
    }

    public static int position() {
        return position;
    }

    public static int total() {
        return total;
    }

    public static double ticksPerSecond() {
        return ticksPerSecond;
    }

    public static @Nullable String recordingId() {
        return recordingId;
    }

    /** Whether a jump is under way, or the game is still loading the recording's resource packs where one landed (PLAY-17) */
    public static boolean jumping() {
        return jumpTarget >= 0 || reloadIndex >= 0 || PackPlayback.covered();
    }

    /** Whether recorded time is being skipped over right now, so nothing from it should be heard or pile up */
    public static boolean skipping() {
        return skipping;
    }

    public static @Nullable String lastError() {
        return lastError;
    }

    public static @Nullable RecordingManifest manifest() {
        return manifest;
    }

    public static @Nullable RecordingFile file() {
        return file;
    }

    /**
     * What the last rebuild of the world from a stored snapshot cost, as milliseconds, chunks left as they were, chunks
     * built, chunks let go, and the tick of the snapshot; null when none has been done. For the tests that time jumps
     */
    public static double[] lastRebuild() {
        Feed.Load load = feed == null ? null : feed.lastLoad();
        return load == null ? null : new double[]{load.millis(), load.chunksKept(), load.chunksBuilt(), load.chunksLetGo(), load.tick()};
    }

    /** Whether playback has a chunk on its list of those the world holds. For the tests that look for chunks left behind */
    public static boolean listsChunk(int x, int z) {
        return feed != null && feed.lists(x, z);
    }

    // --- For the clock hooks ---

    public static boolean ownsClock() {
        return loaded;
    }

    public static boolean gamePaused() {
        return paused && !fastTick;
    }

    public static float millisPerTick() {
        double track = comedymonkey.dailies.editor.EditorSession.speedNow();
        return (float) (1000.0 / Math.max(0.05, track > 0.0 ? track : ticksPerSecond));
    }

    /** The moment of the recording the frame being drawn shows, in ticks: whole when paused, between two ticks when playing */
    public static double frameTime() {
        if (!loaded || paused) {
            return position;
        }
        float partial = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true);
        return Math.max(0.0, position - 1 + partial);
    }

    // --- Controls (client thread) ---

    /** Leaves whatever is open and opens this recording. The work happens over the next frames */
    public static void open(Path recording) {
        active = true;
        loaded = false;
        lastError = null;
        pendingOpen = recording;
        pendingClose = false;
    }

    public static void close() {
        if (active) {
            pendingClose = true;
        }
    }

    /**
     * Shows another recording of the same scene from a tick of it (PLAY-19): the point of view changes, the editor's
     * scene stays, and the camera stays where it is. The recording is opened at the segment that holds the tick, not
     * from its start, so a switch costs what a jump does (PLAY-15). The work happens over the next frames.
     *
     * @param playOn whether playback carries on playing once it has landed
     */
    public static void switchTo(Path recording, int tick, boolean playOn) {
        active = true;
        lastError = null;
        pendingOpen = recording;
        pendingClose = false;
        keepEditor = true;
        switchTick = tick;
        switchPlayOn = playOn;
    }

    public static void play() {
        requireLoaded();
        if (position >= total) {
            viewTo(0, false);
        }
        paused = false;
        pauseAfterJump = false;
    }

    public static void pause() {
        requireLoaded();
        paused = true;
        pauseAfterJump = true;
    }

    /**
     * @param speed ticks of recording per second of real time; 20 is as recorded
     */
    public static void setTicksPerSecond(double speed) {
        ticksPerSecond = Math.max(0.05, Math.min(2000.0, speed));
    }

    /**
     * Jump to a tick. The world there is the same however it is reached (PLAY-11).
     *
     * @param pauseThere whether to stay paused on arrival; otherwise playback carries on as it was
     */
    public static void jumpTo(int tick, boolean pauseThere) {
        jump(tick, pauseThere, false, true);
    }

    /**
     * A jump for someone watching: as exact in time as {@link #jumpTo}, but the world is kept even when it holds
     * machines from mods, which is far quicker and has no blank moment. Things those machines carry loose have been seen
     * to land half a block out afterwards, now and then; {@link #jumpTo}, which exports and the tools use, never does that.
     */
    public static void viewTo(int tick, boolean pauseThere) {
        jump(tick, pauseThere, false, false);
    }

    /**
     * A jump made while the playhead is being dragged: it starts from the nearest stored world and skips the run-up, so
     * it lands sooner and things that ease into place may be a few ticks out. {@link #refine} puts that right.
     */
    public static void scrubTo(int tick) {
        jump(tick, paused, true, false);
    }

    /** After dragging: if the world was last reached by a rough jump, reach the same tick again exactly */
    public static void refine() {
        if (loaded && rough) {
            jump(shown(), paused, false, false);
        }
    }

    /** The tick the person has asked for: where a jump under way is going, else where the recording is */
    public static int shown() {
        int target = jumpTarget;
        return target >= 0 ? target : position;
    }

    /** Whether the world as it stands was reached by a jump that skipped the run-up */
    private static boolean rough = false;
    /** Whether the world as it stands is one with mod machines in it that was kept across a jump */
    private static boolean loose = false;
    /** Whether the jump under way must build such a world from nothing */
    private static boolean strict = true;

    private static void jump(int tick, boolean pauseThere, boolean quick, boolean exact) {
        requireLoaded();
        int target = Math.max(0, Math.min(total, tick));
        pauseAfterJump = pauseThere || paused;
        boolean again = (rough && !quick) || (loose && exact);
        strict = exact;
        if (target == position && !jumping() && !again) {
            paused = pauseAfterJump;
            return;
        }
        int index = feed.segmentIndexAt(target);
        if (again) {
            reloadIndex = index;
        }
        if (!quick && target - feed.segmentStart(index) < RUN_UP_TICKS && feed.continuesFromPrevious(index)) {
            // Landing just after a snapshot: start from the one before, so that everything a game eases into place over a
            // few ticks (a bearing's angle, a craft's smoothing, an entity's last steps) has settled, as it has in plain play
            index--;
        }
        if (target < position || feed.segmentStart(index) > position) {
            // Backwards, or far enough ahead that a later snapshot is the nearer start
            reloadIndex = index;
        }
        if (reloadIndex >= 0) {
            rough = quick;
        }
        jumpTarget = target;
    }

    /** Plays on from here and pauses exactly at a tick */
    public static void playTo(int tick) {
        requireLoaded();
        pauseAt = Math.max(0, Math.min(total, tick));
        if (position < pauseAt) {
            paused = false;
            pauseAfterJump = false;
        }
    }

    public static void step() {
        requireLoaded();
        if (position < total) {
            viewTo(position + 1, true);
        }
    }

    /**
     * Describes the world as it stands at this tick as a snapshot, for a trimmed recording that starts here although no
     * segment does (specification FILE-10). It is written by the same writer that describes a live world, and with it
     * goes what the extensions have kept from the records before this tick (skins, the HUD, where craft are). The
     * person watching is not in it. Client thread, with the recording loaded, standing still and not in a jump.
     */
    public static comedymonkey.dailies.file.Assembler.Snapshot describeWorld() {
        requireLoaded();
        if (jumping()) {
            throw new IllegalStateException("The recording is in the middle of a jump");
        }
        Minecraft minecraft = Minecraft.getInstance();
        Session session = feed.session();
        if (session == null || minecraft.level == null) {
            throw new IllegalStateException("The recording has no world at this tick");
        }
        PlaybackExtensions.beforeDescribeAll();
        SnapshotOut out = new SnapshotOut(session.codec, file);
        comedymonkey.dailies.capture.Snapshots.writeFromPlayback(out, minecraft, session.listener, session.codec, feed.world());
        PlaybackExtensions.describeAll(out::extension);
        // As at the end of a live snapshot: it lets the game stop showing its loading screen
        out.packet(new net.minecraft.network.protocol.game.ClientboundGameEventPacket(net.minecraft.network.protocol.game.ClientboundGameEventPacket.LEVEL_CHUNKS_LOAD_START, 0.0F));
        return out.finish();
    }

    private static final List<Runnable> ON_OPENED = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final List<Runnable> ON_CLOSING = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Something to run on the client thread each time a recording has finished opening */
    public static void onOpened(Runnable listener) {
        ON_OPENED.add(listener);
    }

    /** Something to run on the client thread each time the open recording is about to be closed, while its file is still open */
    public static void onClosing(Runnable listener) {
        ON_CLOSING.add(listener);
    }

    private static void tell(List<Runnable> listeners) {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (Throwable t) {
                Host.LOGGER.warn("Dailies: a part of the mod failed when a recording opened or closed", t);
            }
        }
    }

    /** Runs something on the client thread once the recording that is opening has loaded, or at once if one has */
    public static void whenLoaded(Runnable action) {
        if (loaded && !jumping()) {
            action.run();
        } else {
            WHEN_LOADED.add(action);
        }
    }

    private static void requireLoaded() {
        if (!loaded) {
            throw new IllegalStateException(active ? "The recording is still loading" : "No recording is open");
        }
    }

    // --- The work ---

    /**
     * Tells the game now whether the recording stands still. The game itself only takes that up at the end of a frame,
     * and a frame can hold several ticks: without this, a recording that has just stopped on a tick (by a jump landing,
     * by reaching the tick it was told to play to, by being opened) would have its world ticked on once or more with
     * nothing from the recording, and whatever moves by its own clock, such as a bearing's contraption, would stand
     * ahead of where the recording has it at that tick.
     */
    private static void latchPause() {
        if (loaded) {
            ((comedymonkey.dailies.mixin.access.MinecraftAccess) Minecraft.getInstance()).dailies$setPause(gamePaused());
        }
    }

    /** The start of every frame, outside any game tick: the only place the world may be torn down and rebuilt */
    public static void frameStart() {
        if (!active || inFrameStart) {
            return;
        }
        inFrameStart = true;
        Minecraft minecraft = Minecraft.getInstance();
        try {
            if (pendingClose) {
                pendingClose = false;
                leave(minecraft);
                return;
            }
            if (pendingOpen != null) {
                Path path = pendingOpen;
                pendingOpen = null;
                openNow(minecraft, path);
            }
            if (!loaded) {
                return;
            }
            if (reloadIndex < 0 && feed.reloadWanted() >= 0) {
                // Play ran into a segment that does not follow on from the last one
                reloadIndex = feed.reloadWanted();
            }
            if (reloadIndex >= 0) {
                int index = reloadIndex;
                reloadIndex = -1;
                skipping = true;
                feed.skipping = true;
                feed.load(index, minecraft, strict);
                loose = feed.keptModded();
                position = feed.position();
                if (minecraft.screen instanceof ReceivingLevelScreen || minecraft.screen instanceof GenericMessageScreen) {
                    minecraft.setScreen(null);
                }
            }
            if (jumpTarget >= 0) {
                runJump(minecraft);
            } else if (skipping) {
                skipping = false;
                feed.skipping = false;
                settle(minecraft);
            }
            // The recording's own resource packs, changed only here, between frames, and only once a jump has landed
            PackPlayback.frame(minecraft, jumpTarget < 0 && reloadIndex < 0);
            if (comedymonkey.dailies.export.Export.active()) {
                comedymonkey.dailies.export.Export.frameStart(minecraft);
            }
            if (!jumping() && !WHEN_LOADED.isEmpty()) {
                List<Runnable> actions = new ArrayList<>(WHEN_LOADED);
                WHEN_LOADED.clear();
                actions.forEach(Runnable::run);
            }
        } catch (Throwable t) {
            Host.LOGGER.error("Dailies: playback failed and has been closed", t);
            lastError = t.getMessage() == null ? t.toString() : t.getMessage();
            try {
                leave(minecraft);
            } catch (Throwable again) {
                Host.LOGGER.error("Dailies: the playback world could not be left cleanly", again);
                cleanUp();
            }
        } finally {
            inFrameStart = false;
            latchPause();
        }
    }

    private static void openNow(Minecraft minecraft, Path path) throws Exception {
        boolean keep = keepEditor && file != null;
        int startTick = keep ? switchTick : -1;
        boolean playOn = keep && switchPlayOn;
        keepEditor = false;
        switchTick = -1;
        switchPlayOn = false;
        if (file != null) {
            // Another recording is open: swap it out without going back to the title screen
            tell(ON_CLOSING);
            if (!keep) {
                comedymonkey.dailies.editor.EditorSession.closing();
            }
            feed.close();
            if (!keep || !comedymonkey.dailies.editor.PointOfView.holds(file)) {
                // The scene's own recording stays open for the editor while another point of view is shown
                file.close();
            }
            if (!keep) {
                comedymonkey.dailies.editor.PointOfView.forget(file);
            }
            file = null;
            feed = null;
            loaded = false;
            if (minecraft.level != null) {
                minecraft.clearClientLevel(new GenericMessageScreen(Component.translatable("dailies.playback.opening")));
            }
        } else if (minecraft.level != null) {
            // A live world. Leave it the way its own menu does
            leaving = true;
            try {
                boolean local = minecraft.isLocalServer();
                minecraft.level.disconnect();
                if (local) {
                    minecraft.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")));
                } else {
                    minecraft.disconnect();
                }
            } finally {
                leaving = false;
            }
        }
        minecraft.setScreen(new GenericMessageScreen(Component.translatable("dailies.playback.opening")));

        // No server will send its settings, so every mod's server settings take their defaults, as on a vanilla server
        if (!serverConfigsLoaded) {
            net.neoforged.fml.config.ConfigTracker.INSTANCE.loadDefaultServerConfigs();
            serverConfigsLoaded = true;
        }
        if (keep) {
            RecordingFile own = comedymonkey.dailies.editor.PointOfView.reuse(path);
            // Someone else's recording is only watched: nothing of the scene is saved into it
            file = own != null ? own : RecordingFile.open(path);
        } else {
            // The background export game only reads recordings (EXP-13)
            file = comedymonkey.dailies.export.Worker.active() ? RecordingFile.open(path) : RecordingFile.openForEditing(path);
        }
        manifest = file.manifest();
        if (manifest.segments.isEmpty()) {
            throw new IllegalStateException("The recording is empty");
        }
        recordingId = manifest.id;
        total = manifest.totalTicks;
        if (keep) {
            comedymonkey.dailies.editor.PointOfView.shown(file);
            startTick = Math.max(0, Math.min(total, comedymonkey.dailies.editor.PointOfView.landing(file, startTick)));
        } else {
            comedymonkey.dailies.editor.EditorSession.opened(file);
        }
        feed = new Feed(file);
        paused = true;
        pauseAfterJump = true;
        rough = false;
        if (!keep) {
            ticksPerSecond = 20.0;
        }
        jumpTarget = -1;
        if (!keep) {
            // A switch of point of view keeps the camera where it is: the viewer is not put back by the recorder
            Viewer.reset();
        }
        skipping = true;
        feed.skipping = true;
        feed.load(keep && startTick > 0 ? feed.segmentIndexAt(startTick) : 0, minecraft, true);
        loose = false;
        strict = true;
        position = feed.position();
        skipping = false;
        feed.skipping = false;
        if (minecraft.screen instanceof ReceivingLevelScreen || minecraft.screen instanceof GenericMessageScreen) {
            minecraft.setScreen(null);
        }
        Viewer.startNear(minecraft, manifest.recorderId);
        settle(minecraft);
        loaded = true;
        if (keep && startTick > position) {
            // The rest of the way to the tick that lines up, as a jump does it
            strict = true;
            pauseAfterJump = !playOn;
            jumpTarget = startTick;
        } else if (keep) {
            paused = !playOn;
            pauseAfterJump = !playOn;
        }
        Host.LOGGER.info("Dailies: opened recording {} ({} ticks)", path.getFileName(), total);
        tell(ON_OPENED);
    }

    private static boolean exporting = false;

    /** While an export runs the recording is never paused and never moves on by itself: the export steps it */
    public static void exportMode(boolean on) {
        exporting = on;
        if (on) {
            paused = false;
            pauseAfterJump = false;
            pauseAt = -1;
        }
    }

    /**
     * Plays forward to a tick at once, with everything that happens on the way seen and heard as in normal play.
     *
     * @return false when the tick was not reached this time (the world has to be rebuilt first, or the recording ended)
     */
    public static boolean runTo(Minecraft minecraft, int target) {
        // The game's own pause flag is put back afterwards: left off, the ticks the game runs by itself later in this
        // frame would move the world on without the recording, and everything easing into place would get ahead
        boolean wasPaused = minecraft.isPaused();
        while (position < target) {
            fastTick = true;
            try {
                ((comedymonkey.dailies.mixin.access.MinecraftAccess) minecraft).dailies$setPause(false);
                minecraft.tick();
            } finally {
                fastTick = false;
                ((comedymonkey.dailies.mixin.access.MinecraftAccess) minecraft).dailies$setPause(wasPaused);
            }
            if (feed.reloadWanted() >= 0) {
                return false;
            }
            if (position >= total) {
                break;
            }
        }
        return position >= Math.min(target, total);
    }

    /** Runs as much of a jump as fits in this frame */
    private static void runJump(Minecraft minecraft) throws Exception {
        long deadline = System.nanoTime() + JUMP_SLICE_NANOS;
        skipping = true;
        feed.skipping = true;
        while (position < jumpTarget) {
            fastTick = true;
            try {
                ((comedymonkey.dailies.mixin.access.MinecraftAccess) minecraft).dailies$setPause(false);
                // A whole game tick, so every mod that keeps time by ticks stays in step. Its start feeds the recording
                minecraft.tick();
            } finally {
                fastTick = false;
            }
            if (feed.reloadWanted() >= 0 || position >= total) {
                break;
            }
            if (System.nanoTime() > deadline) {
                hold(minecraft, true);
                return;
            }
        }
        if (feed.reloadWanted() >= 0 && position < jumpTarget) {
            // The stretch ahead starts from its own snapshot; carry on from there next frame
            reloadIndex = feed.reloadWanted();
            hold(minecraft, true);
            return;
        }
        jumpTarget = -1;
        skipping = false;
        feed.skipping = false;
        paused = pauseAfterJump;
        hold(minecraft, paused);
        minecraft.getSoundManager().stop();
        minecraft.particleEngine.setLevel(minecraft.level);
        settle(minecraft);
    }

    /**
     * The game's own pause flag, put back after a jump has ticked the game by hand. The game only looks at what playback
     * wants at the end of a frame, and until then it would run the ticks it thinks this frame owes with the flag as the
     * jump left it, off: the world moved on by up to ten ticks that were not the recording's, in the frame a jump landed
     * and in every frame of a long one
     */
    private static void hold(Minecraft minecraft, boolean still) {
        ((comedymonkey.dailies.mixin.access.MinecraftAccess) minecraft).dailies$setPause(still);
    }

    /** Shows the world exactly as it is at the end of the tick just played, not part way into the next */
    private static void settle(Minecraft minecraft) {
        TimerAccess timer = (TimerAccess) minecraft.getTimer();
        timer.dailies$setPausedPartialTick(1.0F);
        timer.dailies$setPartialTick(0.0F);
        // The switch is for the tests, which measure what the light is like without this
        if (!Boolean.getBoolean("dailies.playback.noSettleLight")) {
            settleLight(minecraft);
        }
        PlaybackExtensions.arrivedAll();
    }

    /**
     * Brings the light up to date at once. The game takes the light the server sends a little at a time, a tenth of
     * what is waiting each frame, which in live play nobody sees. A jump hands it several seconds of the recording in
     * one go, or a whole world: left to itself the light would then arrive over many frames, with chunks drawn in
     * the old light, or none, until it did.
     */
    static void settleLight(Minecraft minecraft) {
        net.minecraft.client.multiplayer.ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        try {
            comedymonkey.dailies.capture.LightPasses.settle(level);
        } catch (RuntimeException e) {
            Host.LOGGER.warn("Dailies: the light could not be brought up to date after a jump; it will catch up over the next frames", e);
        }
    }

    /** The start of every game tick */
    private static void tickStart() {
        if (!loaded || feed == null) {
            return;
        }
        // While resource packs load the picture is held: the recording does not run on under the loading screen
        boolean playing = !paused && jumpTarget < 0 && reloadIndex < 0 && !exporting && !PackPlayback.busy();
        Minecraft minecraft = Minecraft.getInstance();
        if (!fastTick) {
            // The game decides whether it is paused once a frame, after the picture is drawn, and a frame can hold several
            // ticks. Left to that, the ticks of the frame in which play begins would move the recording on while the world
            // stood still, and the ticks left in a frame after play stops or a jump lands would move the world on with
            // nothing recorded behind them: at speed, half a second of time and of everything that eases into place.
            // So every tick is told here whether it is one of the recording's
            if (playing) {
                ((comedymonkey.dailies.mixin.access.MinecraftAccess) minecraft).dailies$setPause(false);
            } else if (paused) {
                ((comedymonkey.dailies.mixin.access.MinecraftAccess) minecraft).dailies$setPause(true);
            }
        }
        if (!fastTick && !playing) {
            return;
        }
        try {
            if (minecraft.level != null && !comedymonkey.dailies.capture.LightPasses.paced()) {
                // A game draws at least one frame between two ticks, and settles its light as it does. Here ticks can
                // follow each other with no frame between (a jump, or play at speed), so the light is settled first.
                // A recording that says when the live game took its light in is followed in that instead
                comedymonkey.dailies.capture.Snapshots.settleLight(minecraft.level);
            }
            if (position >= total || !feed.advance(minecraft)) {
                if (feed.reloadWanted() < 0 && !exporting) {
                    paused = true;
                    pauseAfterJump = true;
                    if (!fastTick) {
                        ((comedymonkey.dailies.mixin.access.MinecraftAccess) minecraft).dailies$setPause(true);
                    }
                }
                return;
            }
            position = feed.position();
            if (!fastTick && position == pauseAt) {
                pauseAt = -1;
                paused = true;
                pauseAfterJump = true;
            }
        } catch (Throwable t) {
            Host.LOGGER.error("Dailies: the recording could not be read past tick {}", position, t);
            lastError = "The recording is damaged after tick " + position;
            paused = true;
            pauseAfterJump = true;
            jumpTarget = -1;
            skipping = false;
        }
    }

    /** The viewer's player has just been created in a new world */
    static void viewerJoined(Minecraft minecraft) {
        if (minecraft.player != null) {
            // The viewer is moved by Dailies every frame, never by the game's own walking and flying
            minecraft.player.input = new Input();
            Viewer.place(minecraft);
        }
    }

    private static void leave(Minecraft minecraft) {
        leaving = true;
        try {
            if (minecraft.level != null) {
                minecraft.level.disconnect();
                minecraft.disconnect();
            }
        } finally {
            leaving = false;
        }
        cleanUp();
        minecraft.setScreen(new TitleScreen());
    }

    private static void cleanUp() {
        if (file != null) {
            tell(ON_CLOSING);
        }
        loaded = false;
        try {
            comedymonkey.dailies.editor.EditorSession.closing();
            if (feed != null) {
                feed.close();
            }
            if (file != null) {
                file.close();
            }
            comedymonkey.dailies.editor.PointOfView.forget(file);
        } catch (Exception e) {
            Host.LOGGER.warn("Dailies: the recording could not be closed cleanly", e);
        }
        feed = null;
        file = null;
        manifest = null;
        recordingId = null;
        jumpTarget = -1;
        reloadIndex = -1;
        skipping = false;
        paused = true;
        pendingOpen = null;
        pendingClose = false;
        keepEditor = false;
        switchTick = -1;
        WHEN_LOADED.clear();
        PackPlayback.closed(Minecraft.getInstance());
        Session.forgetRegistries();
        if (serverConfigsLoaded) {
            serverConfigsLoaded = false;
            try {
                net.neoforged.fml.config.ConfigTracker.INSTANCE.unloadConfigs(net.neoforged.fml.config.ModConfig.Type.SERVER);
            } catch (Throwable t) {
                Host.LOGGER.warn("Dailies: server settings could not be unloaded after playback", t);
            }
        }
        active = false;
    }

}
