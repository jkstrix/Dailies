package comedymonkey.dailies.playback;

import comedymonkey.dailies.Host;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which recorded mod messages playback keeps to itself (specification PLAY-12, CFG-7). A message is handed back to
 * its mod unless its id, or its whole namespace, is on the list. The built-in list holds messages known to act on
 * the recorder's own connection or body, which would do harm if played to the person watching.
 */
public final class PayloadRules {

    private static final List<String> BUILT_IN = List.of(
        // The server mods that feed Moments: replaying these would log a recording's moments a second time (SRV-4, SRV-14)
        "dailies_companion:*", "moments_companion:*", "mcpcraft:*",
        // Connection housekeeping
        "neoforge:register", "neoforge:unregister", "c:register", "c:unregister", "c:version", "minecraft:register", "minecraft:unregister",
        "minecraft:brand", "neoforge:config_file", "neoforge:frozen_registry", "neoforge:frozen_registry_sync_completed",
        "neoforge:frozen_registry_sync_start", "neoforge:known_registry_data_maps", "neoforge:modded_network", "neoforge:modded_network_query",
        "neoforge:modded_network_setup_failed", "neoforge:advanced_open_screen",
        // Simple Voice Chat: its messages set up the recorder's own voice connection
        "voicechat:*",
        // Sable: these three open its side channel, freeze the player and enter an editing mode (MOD-6)
        "sable:udp_activation", "sable:freeze_player", "sable:enter_gizmo_mode"
    );

    private static String source = null;
    private static Set<String> ignored = Set.of();
    private static Set<String> allowed = Set.of();

    private PayloadRules() {
    }

    public static synchronized boolean ignored(String id) {
        String setting = Host.get().setting("ignoredPayloads", "");
        if (!setting.equals(source)) {
            Set<String> ignore = new HashSet<>(BUILT_IN);
            Set<String> allow = new HashSet<>();
            for (String entry : setting.split("[,\\n\\r]")) {
                String trimmed = entry.trim().toLowerCase(Locale.ROOT);
                if (trimmed.startsWith("+")) {
                    allow.add(trimmed.substring(1).trim());
                } else if (!trimmed.isEmpty()) {
                    ignore.add(trimmed);
                }
            }
            ignored = ignore;
            allowed = allow;
            source = setting;
        }
        String namespace = id.substring(0, Math.max(0, id.indexOf(':'))) + ":*";
        if (allowed.contains(id) || allowed.contains(namespace)) {
            return false;
        }
        return ignored.contains(id) || ignored.contains(namespace);
    }

}
