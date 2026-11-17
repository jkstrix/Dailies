package comedymonkey.dailies;

import comedymonkey.dailies.capture.Recorder;
import comedymonkey.dailies.compat.companion.ServerMoments;
import comedymonkey.dailies.compat.mcpcraft.McpCraftCompat;
import comedymonkey.dailies.config.DailiesConfig;
import comedymonkey.dailies.core.CoreHost;
import comedymonkey.dailies.mcp.McpHttpServer;
import comedymonkey.dailies.moments.Moments;
import comedymonkey.dailies.selftest.SelfTest;
import comedymonkey.dailies.ui.Notices;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
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
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

        // The provided features reach the core only through Host, so it goes in before any of them start
        Host.install(new CoreHost(container));
        Moments.init();
        McpCraftCompat.init();
        ServerMoments.init();
        Recorder.init();

        // Optional, so a server without the Moments Companion stays joinable
        modBus.addListener((RegisterPayloadHandlersEvent event) -> ServerMoments.registerPayload(event.registrar("1").optional()));
        modBus.addListener((ModConfigEvent.Loading event) -> settingsChanged(event));
        modBus.addListener((ModConfigEvent.Reloading event) -> settingsChanged(event));

        modBus.addListener(Notices::register);

        SelfTest.startIfAsked();
    }

    private static void settingsChanged(ModConfigEvent event) {
        if (event.getConfig().getSpec() == DailiesConfig.SPEC) {
            McpHttpServer.applyConfig();
        }
    }

}
