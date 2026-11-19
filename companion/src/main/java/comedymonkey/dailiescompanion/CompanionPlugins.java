package comedymonkey.dailiescompanion;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import org.slf4j.LoggerFactory;

/**
 * The server plugin API: helpers on the server register here with an id and a version, push data to one player at a
 * time over the shared channel {@code dailies_companion:compat}, and answer a player's game when it asks. A recorder on
 * the player's side routes what arrives by id to its own client half and keeps it in the recording.
 *
 * <p>
 * One plugin is built in, {@value #PLUGINS}: it tells a game which plugins this server has and their versions, so a
 * client half knows whether its server half is there before it asks. Its data is a varint count, then for each plugin
 * its id (a varint length and UTF-8) and its version (a varint).
 */
public final class CompanionPlugins {
	public static final String PLUGINS = "dailies_companion:plugins";

	private static final Map<String, CompanionPlugin> REGISTERED = Collections.synchronizedMap(new LinkedHashMap<>());

	static {
		register(new CompanionPlugin() {
			@Override
			public String id() {
				return PLUGINS;
			}

			@Override
			public int version() {
				return 1;
			}

			@Override
			public byte[] describe(ServerPlayer player, int clientVersion) {
				return pluginList(plugins());
			}
		});
	}

	private CompanionPlugins() {
	}

	/**
	 * Registers a plugin; call it while the mod loads.
	 *
	 * @throws IllegalArgumentException when the id is not a namespaced id, the version is below 1, or the id is taken
	 */
	public static void register(CompanionPlugin plugin) {
		String id = plugin.id();
		if (id == null || id.length() > CompatPayload.MAX_ID || id.indexOf(':') < 1 || ResourceLocation.tryParse(id) == null)
			throw new IllegalArgumentException("A companion plugin needs a namespaced id such as 'mymod:thing', not '" + id + "'");
		if (plugin.version() < 1)
			throw new IllegalArgumentException("The companion plugin " + id + " has version " + plugin.version() + "; versions start at 1");
		synchronized (REGISTERED) {
			if (REGISTERED.containsKey(id))
				throw new IllegalArgumentException("A companion plugin called " + id + " is registered already");
			REGISTERED.put(id, plugin);
		}
	}

	/** Every plugin registered, by id, with its version. */
	public static Map<String, Integer> plugins() {
		Map<String, Integer> all = new LinkedHashMap<>();
		synchronized (REGISTERED) {
			REGISTERED.forEach((id, plugin) -> all.put(id, plugin.version()));
		}
		return all;
	}

	/** Whether this player's game is listening on the shared channel. */
	public static boolean listening(ServerPlayer player) {
		return player != null && player.connection != null && player.connection.hasChannel(CompatPayload.TYPE);
	}

	/**
	 * Sends a plugin's data to one player, with the plugin's version.
	 *
	 * @return false when the plugin is not registered, the player's game is not listening, or the data is too long
	 */
	public static boolean push(ServerPlayer player, String id, byte[] data) {
		CompanionPlugin plugin = REGISTERED.get(id);
		if (plugin == null) {
			LoggerFactory.getLogger("Dailies Companion").warn("Dailies Companion: nothing was sent for '{}', which is not a registered plugin", id);
			return false;
		}
		return send(player, id, plugin.version(), data);
	}

	/** Sends a plugin's data to everyone online; returns how many games were listening. */
	public static int pushAll(MinecraftServer server, String id, byte[] data) {
		int n = 0;
		for (ServerPlayer p : server.getPlayerList().getPlayers())
			if (push(p, id, data))
				n++;
		return n;
	}

	private static boolean send(ServerPlayer player, String id, int version, byte[] data) {
		if (!listening(player))
			return false;
		byte[] sent = data == null ? new byte[0] : data;
		if (sent.length > CompatPayload.MAX_DATA) {
			LoggerFactory.getLogger("Dailies Companion").warn("Dailies Companion: plugin '{}' tried to send {} bytes, more than the {} the channel carries, so nothing was sent", id, sent.length, CompatPayload.MAX_DATA);
			return false;
		}
		PacketDistributor.sendToPlayer(player, new CompatPayload(CompatPayload.DATA, id, version, sent));
		return true;
	}

	/** A player's game asked for a plugin's data: the plugin answers, or version 0 says the server has none of that id. Server thread */
	static void asked(ServerPlayer player, String id, int clientVersion) {
		CompanionPlugin plugin = REGISTERED.get(id);
		if (plugin == null) {
			send(player, id, 0, null);
			return;
		}
		byte[] data;
		try {
			data = plugin.describe(player, clientVersion);
		} catch (RuntimeException e) {
			LoggerFactory.getLogger("Dailies Companion").warn("Dailies Companion: plugin '{}' failed to answer {}", id, player.getGameProfile().getName(), e);
			return;
		}
		send(player, id, plugin.version(), data);
	}

	/** The built-in plugin's data: which plugins there are, and their versions. */
	static byte[] pluginList(Map<String, Integer> plugins) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		varint(out, plugins.size());
		plugins.forEach((id, version) -> {
			byte[] text = id.getBytes(StandardCharsets.UTF_8);
			varint(out, text.length);
			out.write(text, 0, text.length);
			varint(out, version);
		});
		return out.toByteArray();
	}

	private static void varint(ByteArrayOutputStream out, int value) {
		while ((value & ~0x7F) != 0) {
			out.write((value & 0x7F) | 0x80);
			value >>>= 7;
		}
		out.write(value);
	}
}
