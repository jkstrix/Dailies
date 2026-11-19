package comedymonkey.dailies;

import comedymonkey.dailies.capture.Recorder;
import comedymonkey.dailies.compat.companion.ServerMoments;
import comedymonkey.dailies.compat.mcpcraft.McpCraftCompat;
import comedymonkey.dailies.config.DailiesConfig;
import comedymonkey.dailies.core.CoreHost;
import comedymonkey.dailies.mcp.McpHttpServer;
import comedymonkey.dailies.moments.Moments;
import comedymonkey.dailies.playback.Playback;
import comedymonkey.dailies.playback.Viewer;
import comedymonkey.dailies.ui.Keys;
import comedymonkey.dailies.ui.Notices;
import comedymonkey.dailies.ui.PlaybackBar;
import comedymonkey.dailies.ui.Screens;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Where the mod starts. Client only: the class is never loaded on a dedicated server.
 */
@Mod(value = Dailies.ID, dist = Dist.CLIENT)
public final class Dailies {

    public static final String ID = "dailies";

    public Dailies(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, DailiesConfig.SPEC);
        // The mods list opens Dailies' own settings screen (CFG-4); the file behind it is still NeoForge's
        container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) -> comedymonkey.dailies.ui.SettingsScreen.create(parent));

        // The provided features reach the core only through Host, so it goes in before any of them start
        Host.install(new CoreHost(container));
        loadCompression();
        Moments.init();
        McpCraftCompat.init();
        ServerMoments.init();
        comedymonkey.dailies.compat.companion.CompanionCompat.init();
        Recorder.init();
        comedymonkey.dailies.library.Library.init();
        comedymonkey.dailies.compat.sable.SableCompat.init();
        comedymonkey.dailies.compat.create.CreateCompat.init();
        comedymonkey.dailies.compat.aeronautics.AeronauticsCompat.init();
        comedymonkey.dailies.compat.create.CreateTrains.init();
        comedymonkey.dailies.compat.createaddons.CreateAddonsCompat.init();
        Playback.init();
        comedymonkey.dailies.editor.CameraSteps.init();
        // Other renderers (specification chapter XVI): each does nothing unless its mod is installed
        comedymonkey.dailies.compat.create.FlywheelFrames.init();
        comedymonkey.dailies.compat.distanthorizons.DistantHorizonsCompat.init();
        Viewer.init();
        comedymonkey.dailies.export.Export.init();
        comedymonkey.dailies.sound.Sound.init();
        comedymonkey.dailies.looks.LooksRenderer.init();
        comedymonkey.dailies.compat.voicechat.VoiceChatLook.init();
        comedymonkey.dailies.compat.iris.IrisShaders.init();

        // Optional, so a server without the Dailies Companion stays joinable
        modBus.addListener((RegisterPayloadHandlersEvent event) -> {
            net.neoforged.neoforge.network.registration.PayloadRegistrar registrar = event.registrar("1").optional();
            ServerMoments.registerPayload(registrar);
            comedymonkey.dailies.compat.companion.CompanionCompat.registerPayload(registrar);
        });
        modBus.addListener((ModConfigEvent.Loading event) -> settingsChanged(event));
        modBus.addListener((ModConfigEvent.Reloading event) -> settingsChanged(event));

        modBus.addListener(Notices::register);
        comedymonkey.dailies.extras.Extras.init(modBus);
        comedymonkey.dailies.windows.Windows.init(modBus);
        modBus.addListener(Keys::register);
        modBus.addListener(PlaybackBar::register);
        Keys.init();
        comedymonkey.dailies.command.DailiesCommand.init();
        Screens.init();

        // The scripted runs are the mod dailies_selftest, which starts itself; this only fails a run that lacks it
        ScriptedRun.checkSelfTestLoaded();
    }

    /**
     * Recordings are packed with Zstandard, whose native library is unpacked into the dailies folder rather than a
     * temporary one (see ZstdNative). Loaded now, so a computer it cannot be loaded on is in the log from the start
     * rather than found out when the first recording begins
     */
    private static void loadCompression() {
        comedymonkey.dailies.file.ZstdNative.useFolder(Host.get().dataDirectory().resolve("natives"));
        try {
            comedymonkey.dailies.file.ZstdNative.load();
            Throwable folderFailure = comedymonkey.dailies.file.ZstdNative.folderFailure();
            if (folderFailure != null) {
                Host.LOGGER.warn("Dailies: the Zstandard library could not be unpacked into the dailies folder and was loaded from a temporary file instead", folderFailure);
            }
        } catch (RuntimeException | LinkageError e) {
            Host.LOGGER.error("Dailies: recordings cannot be written or opened on this computer", e);
        }
    }

    private static void settingsChanged(ModConfigEvent event) {
        // The background export game shares the settings, and would otherwise try to take the same port (EXP-13)
        if (event.getConfig().getSpec() == DailiesConfig.SPEC && !comedymonkey.dailies.export.Worker.active()) {
            McpHttpServer.applyConfig();
        }
    }

}
