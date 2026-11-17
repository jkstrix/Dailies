package comedymonkey.dailies.playback;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Whom the view rides with, held by UUID and entity id, never by the entity object: after a jump the world may be the
 * same one with every entity taken out and put back, or a new one, and the object the ride began with is then dead.
 * It also decides when to give up on someone who is not in the world: never while the playhead is moving (a jump
 * under way, a drag), at once when an exact jump lands without them, and in plain play only once they have been gone
 * for longer than a death and a respawn take. No game classes, so it can be tested on its own.
 */
final class RideTarget {

    /** How many ticks of the recording someone may be out of the world in plain play before the ride is given up */
    static final int LOST_TICKS = 100;

    private @Nullable UUID uuid = null;
    private int id = -1;
    private int lostAt = -1;

    void ride(@Nullable UUID who, int entityId) {
        this.uuid = who;
        this.id = who == null ? -1 : entityId;
        this.lostAt = -1;
    }

    void clear() {
        this.ride(null, -1);
    }

    @Nullable UUID uuid() {
        return this.uuid;
    }

    /** The entity id they last had: the quick way to find them, checked against the UUID */
    int id() {
        return this.id;
    }

    /** They are in the world, with this entity id */
    void seen(int entityId) {
        this.id = entityId;
        this.lostAt = -1;
    }

    /**
     * They are not in the world at this tick
     *
     * @param moving whether a jump is under way or the world stands at a rough landing of a drag
     * @return true when the ride is to be given up
     */
    boolean missing(int tick, boolean moving) {
        if (this.uuid == null) {
            return false;
        }
        if (this.lostAt < 0 || moving) {
            // A jump moves the playhead anywhere: the time gone is counted from where it lands
            this.lostAt = tick;
            return false;
        }
        return Math.abs(tick - this.lostAt) > LOST_TICKS;
    }

    /**
     * A jump has landed
     *
     * @param present whether they are in the world at the tick it landed on
     * @param rough   whether it was one of the quick landings made while the playhead is dragged
     * @return true when the ride is to be given up
     */
    boolean landed(boolean present, boolean rough) {
        if (this.uuid == null || present) {
            this.lostAt = -1;
            return false;
        }
        return !rough;
    }

}
