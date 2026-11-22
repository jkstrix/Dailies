package comedymonkey.dailies.packs;

import comedymonkey.dailies.Host;
import net.minecraft.client.resources.server.ServerPackManager;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What the game is doing with server resource packs right now, kept from the moment of joining whether or not anything
 * is being recorded: a server usually pushes its packs before the world exists, and a recording started later still
 * has to know them (specification REC-19). Playback gives recorded packs to the same part of the game, so it watches
 * here too to learn when they have been loaded. Everything belongs to the client thread.
 */
public final class GamePacks {

    /**
     * A pack the game has in force.
     *
     * @param path the game's own copy of the pack's file
     * @param push what the server said when it pushed it; its content key is empty
     */
    public record Entry(UUID id, Path path, PackLog.Pack push) {
    }

    public interface Listener {
        /** A pack has been loaded and is in force, at this place in the list */
        void pushed(Entry entry, int index);

        /** A pack that was in force no longer is */
        void popped(Entry entry);

        /** A pushed pack was dropped without ever being in force: declined, or it could not be fetched or loaded */
        void notApplied(PackLog.Pack push, String reason);
    }

    private record Held(Object data, Entry entry) {
    }

    private static final int REMEMBERED_PUSHES = 64;
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Map<UUID, PackLog.Pack> PUSHES = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, PackLog.Pack> eldest) {
            return this.size() > REMEMBERED_PUSHES;
        }
    };
    private static List<Held> current = new ArrayList<>();
    private static boolean failed = false;

    private GamePacks() {
    }

    public static void listen(Listener listener) {
        LISTENERS.add(listener);
    }

    /** The packs in force, the first pushed first */
    public static List<Entry> inForce() {
        List<Entry> entries = new ArrayList<>();
        for (Held held : current) {
            entries.add(held.entry);
        }
        return entries;
    }

    public static boolean inForce(UUID id) {
        for (Held held : current) {
            if (held.entry.id.equals(id)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ Told by the game's hooks

    /** A server has pushed a pack. Nothing has been decided about it yet */
    public static void pushPacket(ClientboundResourcePackPushPacket packet) {
        try {
            String prompt = "";
            if (packet.prompt().isPresent()) {
                try {
                    prompt = net.minecraft.network.chat.ComponentSerialization.CODEC.encodeStart(RegistryAccess.EMPTY.createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE),
                        packet.prompt().get()).getOrThrow().toString();
                } catch (RuntimeException e) {
                    prompt = packet.prompt().get().getString();
                }
            }
            PUSHES.remove(packet.id());
            PUSHES.put(packet.id(), new PackLog.Pack(packet.id(), packet.url(), packet.hash(), packet.required(), prompt, ""));
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** A push was refused before the game listed it, because the player had already said no */
    public static void declinedAtOnce(UUID id) {
        try {
            PackLog.Pack push = push(id);
            for (Listener listener : LISTENERS) {
                listener.notApplied(push, "DECLINED");
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** The game has just gone over its packs: see what has changed since the last time */
    public static void scanned(List<ServerPackManager.ServerPackData> packs) {
        try {
            List<Held> now = new ArrayList<>();
            List<PackLog.Pack> dropped = new ArrayList<>();
            List<String> reasons = new ArrayList<>();
            for (ServerPackManager.ServerPackData data : packs) {
                Held held = null;
                for (Held known : current) {
                    if (known.data == data) {
                        held = known;
                    }
                }
                // A pack on its way out is in force until the reload that takes it off has finished
                boolean active = data.activationStatus == ServerPackManager.ActivationStatus.ACTIVE
                    || data.activationStatus == ServerPackManager.ActivationStatus.PENDING && held != null;
                if (active) {
                    now.add(held != null ? held : new Held(data, new Entry(data.id, data.path, push(data.id))));
                } else if (held == null && data.activationStatus == ServerPackManager.ActivationStatus.INACTIVE && data.removalReason != null
                    && data.removalReason != ServerPackManager.RemovalReason.SERVER_REMOVED && data.removalReason != ServerPackManager.RemovalReason.SERVER_REPLACED) {
                    // The game forgets it straight after this, so it is seen here exactly once
                    dropped.add(push(data.id));
                    reasons.add(data.removalReason.name());
                }
            }
            List<Held> before = current;
            current = now;
            for (Held held : before) {
                if (!now.contains(held)) {
                    for (Listener listener : LISTENERS) {
                        listener.popped(held.entry);
                    }
                }
            }
            for (int i = 0; i < now.size(); i++) {
                if (!before.contains(now.get(i))) {
                    for (Listener listener : LISTENERS) {
                        listener.pushed(now.get(i).entry, i);
                    }
                }
            }
            for (int i = 0; i < dropped.size(); i++) {
                for (Listener listener : LISTENERS) {
                    listener.notApplied(dropped.get(i), reasons.get(i));
                }
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** What the server said about a pack, or, for one that came with a world's own files, only its id */
    private static PackLog.Pack push(UUID id) {
        PackLog.Pack push = PUSHES.get(id);
        return push != null ? push : new PackLog.Pack(id, "", "", false, "", "");
    }

    private static void fail(Throwable problem) {
        if (!failed) {
            failed = true;
            Host.LOGGER.error("Dailies: keeping track of the server's resource packs failed, so they may be missing from recordings. Said once", problem);
        }
    }

}
