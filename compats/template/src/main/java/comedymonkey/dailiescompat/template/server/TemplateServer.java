package comedymonkey.dailiescompat.template.server;

import comedymonkey.dailiescompanion.CompanionPlugin;
import comedymonkey.dailiescompanion.CompanionPlugins;
import comedymonkey.dailiescompat.template.HomeFact;
import comedymonkey.dailiescompat.template.TemplateMod;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerSetSpawnEvent;

/**
 * The template compat's server half (specification SRV-9): a plugin of the Dailies Companion that sends each player a
 * fact only the server knows, where that player respawns, when their game asks and whenever it changes. Only the
 * player's own: a server half never sends one player another's inventory, mail, position or messages. Registered only
 * when the companion is loaded ({@link TemplateMod}), so this class is the only one that refers to it. What it sends is
 * written by {@link HomeFact}, which the client half reads with.
 */
public final class TemplateServer {

    private TemplateServer() {
    }

    public static void register() {
        CompanionPlugins.register(new CompanionPlugin() {
            @Override
            public String id() {
                return HomeFact.PLUGIN;
            }

            @Override
            public int version() {
                return HomeFact.VERSION;
            }

            @Override
            public byte[] describe(ServerPlayer player, int clientVersion) {
                return HomeFact.write(player.getRespawnPosition());
            }
        });
        NeoForge.EVENT_BUS.addListener((PlayerSetSpawnEvent event) -> {
            if (!event.isCanceled() && event.getEntity() instanceof ServerPlayer player) {
                CompanionPlugins.push(player, HomeFact.PLUGIN, HomeFact.write(event.getNewSpawn()));
            }
        });
    }

}
