package comedymonkey.dailiescompanion;

import javax.annotation.Nullable;

import com.google.gson.JsonObject;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import org.slf4j.LoggerFactory;

/**
 * Dailies Companion: the server tells a player's game that something worth remembering just happened to them, and a
 * recorder on their side (a replay mod that keeps a log of moments) writes it down with the time and place.
 *
 * <p>
 * Three ways in: the events the server raises by itself (see {@link Detectors}), the {@code /moment} command (command
 * blocks, functions, datapacks, other mods' scripting) and the static methods here (other server mods). Players need nothing installed to join: the channel is optional, and a player
 * whose game isn't listening is simply not sent anything.
 */
@Mod(DailiesCompanion.MODID)
public final class DailiesCompanion {
	public static final String MODID = "dailies_companion";

	public DailiesCompanion(IEventBus modBus, net.neoforged.fml.ModContainer container) {
		container.registerConfig(net.neoforged.fml.config.ModConfig.Type.COMMON, CompanionConfig.SPEC);
		modBus.addListener(MomentPayload::register);
		modBus.addListener(CompatPayload::register); // the server plugin API, see CompanionPlugins
		NeoForge.EVENT_BUS.register(Detectors.class); // the events the server raises by itself
		CraftEvents.init(); // craft and trains, when Sable or Create is installed
		NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> MomentCommand.register(e.getDispatcher()));
	}

	/** Whether this player's game is listening for moments. */
	public static boolean listening(ServerPlayer player) {
		return player != null && player.connection != null && player.connection.hasChannel(MomentPayload.TYPE);
	}

	/**
	 * Sends one moment to one player.
	 *
	 * @param type  a short machine name for the kind of event ("quest_completed", "town_founded"): lower case, no spaces
	 * @param label what happened, as a person would say it ("Founded the town of Brasswick")
	 * @param data  anything else worth keeping (ids, counts, names), or null; at most {@link MomentPayload#MAX_JSON}
	 *              characters of JSON, see {@link #fits}
	 * @return false when the player's game isn't listening, or the data is too long to send
	 */
	public static boolean send(ServerPlayer player, String type, String label, @Nullable JsonObject data) {
		if (!listening(player))
			return false;
		String json = data == null ? "{}" : data.toString();
		if (json.length() > MomentPayload.MAX_JSON) {
			// Cut short it would not be JSON any more, and the recorder would drop it without a word
			LoggerFactory.getLogger("Dailies Companion").warn("Dailies Companion: the data of moment '{}' is {} characters of JSON, more than the {} a moment can carry, so it was not sent", type, json.length(), MomentPayload.MAX_JSON);
			return false;
		}
		PacketDistributor.sendToPlayer(player, new MomentPayload(clip(type, MomentPayload.MAX_TYPE), clip(label, MomentPayload.MAX_LABEL), json));
		return true;
	}

	/** Whether a moment's data is short enough to send: at most {@link MomentPayload#MAX_JSON} characters of JSON. */
	public static boolean fits(@Nullable JsonObject data) {
		return data == null || data.toString().length() <= MomentPayload.MAX_JSON;
	}

	/** Sends one moment to everyone online; returns how many games were listening. */
	public static int sendAll(MinecraftServer server, String type, String label, @Nullable JsonObject data) {
		int n = 0;
		for (ServerPlayer p : server.getPlayerList().getPlayers())
			if (send(p, type, label, data))
				n++;
		return n;
	}

	/** Sends one moment to everyone within radius blocks of a place; returns how many games were listening. */
	public static int sendNear(ServerLevel level, Vec3 pos, double radius, String type, String label, @Nullable JsonObject data) {
		int n = 0;
		for (ServerPlayer p : level.players())
			if (p.position().distanceToSqr(pos) <= radius * radius && send(p, type, label, data))
				n++;
		return n;
	}

	private static String clip(String s, int max) {
		return s == null ? "" : s.length() <= max ? s : s.substring(0, max);
	}
}
