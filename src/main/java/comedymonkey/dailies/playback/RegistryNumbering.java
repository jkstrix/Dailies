package comedymonkey.dailies.playback;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.capture.WorldData;
import io.netty.buffer.Unpooled;
import net.minecraft.core.DefaultedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.neoforge.registries.RegistryManager;
import net.neoforged.neoforge.registries.RegistrySnapshot;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Puts in force, while a recording plays, the numbering of registry entries its game used (specification PLAY-25).
 * On joining a server NeoForge takes the server's numbering for every registry it sends one for, so a sound, a particle
 * or a block travels by the server's number, and puts the game's own back on leaving. A recording keeps that numbering
 * (REC-37); this does what NeoForge does on joining, from it, before the recording's world is built, and what NeoForge
 * does on leaving once playback ends, fails or another recording opens. Client thread only.
 */
public final class RegistryNumbering {

    /** The world data whose numbering was asked for last, or null when none has been since the game's own was put back */
    private static @Nullable String inForce = null;
    /** Whether the registries hold a numbering other than the game's own */
    private static boolean changed = false;

    private RegistryNumbering() {
    }

    /** Whether a recording's numbering is in force rather than the game's own: for the self test */
    public static boolean changed() {
        return changed;
    }

    /**
     * Puts in force the numbering of the recording's world data named {@code worldName}, unless it is already. Changing
     * nothing when the numbering is the game's own, as in a singleplayer recording or one made before recordings kept it
     * in full. Whatever goes wrong, the game is left with its own numbering and playback goes on
     */
    static void use(String worldName, WorldData data) {
        if (worldName.equals(inForce)) {
            return;
        }
        restore();
        inForce = worldName;
        try {
            Map<Identifier, RegistrySnapshot> own = RegistryManager.takeSnapshot(RegistryManager.SnapshotType.SYNC_TO_CLIENT);
            Map<String, Map<Integer, String>> ownNumbers = new LinkedHashMap<>();
            Map<String, String> fillers = new HashMap<>();
            own.forEach((registry, snapshot) -> {
                Map<Integer, String> numbers = new HashMap<>();
                snapshot.getIds().forEach((number, name) -> numbers.put(number, name.toString()));
                ownNumbers.put(registry.toString(), numbers);
                Identifier filler = filler(registry);
                if (filler != null) {
                    fillers.put(registry.toString(), filler.toString());
                }
            });
            Map<String, List<String>> recorded = new HashMap<>();
            data.ids.forEach((registry, names) -> {
                List<String> text = new ArrayList<>(names.size());
                names.forEach(name -> text.add(name.toString()));
                recorded.put(registry.toString(), text);
            });

            Numbering.Plan plan = Numbering.plan(recorded, ownNumbers, fillers);
            if (!plan.missing().isEmpty()) {
                // Said once a recording: the mods it was made with are not all here. Playback goes on, and what names a missing
                // entry is shown as the registry's entry for nothing (a silent sound, air), or as its last entry where it has none
                StringBuilder said = new StringBuilder();
                plan.missing().forEach((registry, names) -> said.append(said.isEmpty() ? "" : "; ").append(registry).append(": ").append(names.size())
                    .append(" (").append(String.join(", ", names.subList(0, Math.min(5, names.size())))).append(names.size() > 5 ? ", ..." : "").append(')'));
                Host.LOGGER.warn("Dailies: the recording names registry entries this game does not have, which playback shows as nothing or as another: {}", said);
            }
            if (plan.changesNothing()) {
                return;
            }
            Map<Identifier, RegistrySnapshot> snapshots = new LinkedHashMap<>();
            plan.apply().forEach((registry, numbering) -> {
                Identifier name = Identifier.parse(registry);
                SortedMap<Integer, Identifier> numbers = new TreeMap<>();
                numbering.forEach((number, entry) -> numbers.put(number, Identifier.parse(entry)));
                // The game's own aliases go with it: putting a numbering in force clears them, as NeoForge's own does
                snapshots.put(name, snapshot(numbers, own.get(name).getAliases()));
            });
            changed = true;
            Set<ResourceKey<?>> unknown = RegistryManager.applySnapshot(snapshots, false);
            if (!unknown.isEmpty()) {
                throw new IllegalStateException("the numbering names entries this game does not have: " + unknown);
            }
            Host.LOGGER.info("Dailies: the recording's game numbered {} registries differently from this one; playback numbers them as it did: {}",
                snapshots.size(), String.join(", ", plan.apply().keySet()));
        } catch (Throwable t) {
            Host.LOGGER.warn("Dailies: the recording's numbering of registry entries could not be put in force, so playback keeps this game's own and some "
                + "sounds, particles or blocks may show as others", t);
            restore();
            // Not asked again for the same world data: it would fail again
            inForce = worldName;
        }
    }

    /** Puts the game's own numbering back, as NeoForge does on leaving a server. Safe to call at any time */
    static void restore() {
        inForce = null;
        if (!changed) {
            return;
        }
        changed = false;
        try {
            RegistryManager.revertToFrozen();
        } catch (Throwable t) {
            Host.LOGGER.error("Dailies: the game's own numbering of registry entries could not be put back after playback", t);
        }
    }

    /**
     * A numbering in the form NeoForge's handover from a server takes, read from the bytes the server would have sent,
     * since nothing else can make one
     */
    public static RegistrySnapshot snapshot(SortedMap<Integer, Identifier> numbers, Map<Identifier, Identifier> aliases) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ByteBufCodecs.<FriendlyByteBuf, Integer, Identifier, Map<Integer, Identifier>>map(HashMap::new, ByteBufCodecs.VAR_INT, Identifier.STREAM_CODEC).encode(buffer, numbers);
            ByteBufCodecs.<FriendlyByteBuf, Identifier, Identifier, Map<Identifier, Identifier>>map(HashMap::new, Identifier.STREAM_CODEC, Identifier.STREAM_CODEC).encode(buffer, aliases);
            return RegistrySnapshot.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    /** The entry that stands for nothing in a registry, for a number whose own entry this game lacks: a silent sound, air */
    private static @Nullable Identifier filler(Identifier registry) {
        if (registry.equals(Registries.SOUND_EVENT.identifier())) {
            return SoundEvents.EMPTY.location();
        }
        Registry<?> found = BuiltInRegistries.REGISTRY.getValue(registry);
        return found instanceof DefaultedRegistry<?> defaulted ? defaulted.getDefaultKey() : null;
    }

}
