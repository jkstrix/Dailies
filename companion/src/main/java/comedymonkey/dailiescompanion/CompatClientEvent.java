package comedymonkey.dailiescompanion;

import net.neoforged.bus.api.Event;

/**
 * A plugin's data as it arrives in the player's game, posted on {@code NeoForge.EVENT_BUS} on the client thread when
 * this mod is installed there. Recorders listen to this; without this mod on the client they register the channel
 * themselves.
 */
public final class CompatClientEvent extends Event {
	private final String id;
	private final int version;
	private final byte[] data;

	public CompatClientEvent(String id, int version, byte[] data) {
		this.id = id;
		this.version = version;
		this.data = data;
	}

	/** The plugin's id, such as {@code dailies_companion:plugins}. */
	public String id() {
		return id;
	}

	/** The plugin's version on the server; 0 when the server has no plugin of that id. */
	public int version() {
		return version;
	}

	/** What the plugin sent, in its own format. */
	public byte[] data() {
		return data;
	}
}
