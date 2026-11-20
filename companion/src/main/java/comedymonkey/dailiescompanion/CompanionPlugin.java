package comedymonkey.dailiescompanion;

import javax.annotation.Nullable;

import net.minecraft.server.level.ServerPlayer;

/**
 * A server-side helper that sends a player's game facts only the server knows, for a client compat to record (for
 * example the server half of a mod's compat). Register it with {@link CompanionPlugins#register} while the mod loads,
 * then push data to players with {@link CompanionPlugins#push}. Send each player only facts about their own play or
 * what they could see for themselves: it is kept in their recordings (see {@link CompanionPlugins} on privacy).
 */
public interface CompanionPlugin {

	/** A namespaced id, unique on the server, such as {@code postrider:mail}: the client half asks for it by this. */
	String id();

	/** The version of what this plugin sends, 1 or more; a client half reads it to know what format it was given. */
	int version();

	/**
	 * What this plugin holds for this player now, sent when their game asks for it (as a client half does when it
	 * starts). Server thread.
	 *
	 * @param clientVersion the version of the client half that asked, 0 when it did not say
	 * @return the data, at most {@link CompatPayload#MAX_DATA} bytes, or null to answer with the version alone
	 */
	@Nullable
	default byte[] describe(ServerPlayer player, int clientVersion) {
		return null;
	}
}
