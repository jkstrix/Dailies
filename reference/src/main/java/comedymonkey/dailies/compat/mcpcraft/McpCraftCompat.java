package comedymonkey.dailies.compat.mcpcraft;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import comedymonkey.dailies.Host;
import comedymonkey.dailies.moments.Moments;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;

import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * McpCraft's job board. McpCraft tells the player's own game about the job board events they have a
 * part in (a job posted, claimed, supplied, finished) by posting a JobBoardClientEvent on the NeoForge event bus, in
 * singleplayer and on a server alike. They are recorded as moments. Reflection only: McpCraft is never a dependency.
 */
public final class McpCraftCompat {

    private static final String CLIENT_EVENT = "dev.mcpcraft.map.event.JobBoardClientEvent";

    private McpCraftCompat() {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void init() {
        if (!Host.get().isModLoaded("mcpcraft")) {
            return;
        }
        try {
            Class<?> eventClass = Class.forName(CLIENT_EVENT);
            Method eventName = eventClass.getMethod("event");
            Method eventJson = eventClass.getMethod("json");
            Consumer<Event> listener = event -> {
                try {
                    JsonElement parsed = JsonParser.parseString((String) eventJson.invoke(event));
                    if (parsed.isJsonObject()) {
                        Moments.onJobBoardEvent((String) eventName.invoke(event), parsed.getAsJsonObject());
                    }
                } catch (Throwable t) {
                    Host.LOGGER.warn("McpCraft compatibility: unable to read a job board event", t);
                }
            };
            NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, (Class) eventClass, (Consumer) listener);
            Host.LOGGER.info("McpCraft compatibility: job board events will be tracked as moments");
        } catch (ClassNotFoundException e) {
            Host.LOGGER.info("McpCraft compatibility: this version of McpCraft has no job board events for client mods, they will not be tracked");
        } catch (Throwable t) {
            Host.LOGGER.error("McpCraft compatibility: unable to listen for job board events", t);
        }
    }

}
