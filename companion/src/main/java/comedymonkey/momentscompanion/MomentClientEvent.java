package comedymonkey.momentscompanion;

import net.neoforged.bus.api.Event;

/**
 * A moment as it arrives in the player's game, posted on {@code NeoForge.EVENT_BUS} on the client thread when this mod
 * is installed there. Recorders listen to this; without this mod on the client they register the channel themselves.
 */
public final class MomentClientEvent extends Event {
	private final String type, label, json;

	public MomentClientEvent(String type, String label, String json) {
		this.type = type;
		this.label = label;
		this.json = json;
	}

	/** The kind of event, a short machine name. */
	public String type() {
		return type;
	}

	/** What happened, in words. */
	public String label() {
		return label;
	}

	/** Extra data as a JSON object ("{}" when there is none). */
	public String json() {
		return json;
	}
}
