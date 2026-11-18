package comedymonkey.dailies.compat.voicechat;

import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What Simple Voice Chat knew at this point of the recording, as told by the messages {@link VoiceChatPayloads} lets
 * through: every player's state, the recorder's own group, and the groups there are. Kept the whole time a recording
 * plays, whether or not the "voice_chat" look is on, so turning the look on part way shows the right thing at once.
 * Emptied when the world is rebuilt from a snapshot, which tells it all again.
 * <p>
 * The states and groups are voice chat's own objects, held without being looked into; here they are just values, so
 * the rules can be tested without the game.
 *
 * @param <S> a player's state
 * @param <G> a group
 */
public final class RecordedVoiceChat<S, G> {

    private final Map<UUID, S> states = new LinkedHashMap<>();
    private final Map<UUID, G> groups = new LinkedHashMap<>();
    private @Nullable UUID group;
    private int changes = 0;

    public void state(UUID player, S state) {
        this.states.put(player, state);
        this.changes++;
    }

    /** Every player's state at once: those not among them have none, as voice chat itself takes it */
    public void states(Map<UUID, S> all) {
        this.states.clear();
        this.states.putAll(all);
        this.changes++;
    }

    public void removeState(UUID player) {
        if (this.states.remove(player) != null) {
            this.changes++;
        }
    }

    public void addGroup(UUID id, G group) {
        this.groups.put(id, group);
        this.changes++;
    }

    public void removeGroup(UUID id) {
        if (this.groups.remove(id) != null) {
            this.changes++;
        }
    }

    /**
     * The recorder's group, or null for none. Taken as voice chat takes it, even from the answer to a wrong password,
     * which voice chat also takes for the group it is in
     */
    public void joined(@Nullable UUID group) {
        this.group = group;
        this.changes++;
    }

    /** Forgets everything: the world is about to be told it all again from a snapshot */
    public void clear() {
        this.states.clear();
        this.groups.clear();
        this.group = null;
        this.changes++;
    }

    /** Players' states by player id, in the order they were told. Cannot be changed through this view */
    public Map<UUID, S> states() {
        return Collections.unmodifiableMap(this.states);
    }

    /** Groups by id, in the order they were told. Cannot be changed through this view */
    public Map<UUID, G> groups() {
        return Collections.unmodifiableMap(this.groups);
    }

    public @Nullable UUID group() {
        return this.group;
    }

    /** A number that changes whenever anything here does, so whoever copies it out knows when to do so again */
    public int changes() {
        return this.changes;
    }

    public boolean isEmpty() {
        return this.states.isEmpty() && this.groups.isEmpty() && this.group == null;
    }

}
