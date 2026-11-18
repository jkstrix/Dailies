package comedymonkey.dailies.moments.track;

import java.util.Set;

/**
 * The rules for craft and contraption moments (plan 2.1, specification chapter IX), kept apart from the game so that
 * they can be tested on their own. {@link CraftTracker} feeds them what the player's game sees; the numbers are the
 * ones in the specification's event table.
 */
public final class CraftRules {

    private CraftRules() {
    }

    /** The types the companion sends from the server that are craft or train moments rather than custom ones */
    public static final Set<String> SERVER_TYPES = Set.of("craft_assembled", "craft_lost", "train_departed", "train_arrived");
    /** Every craft and contraption type, game or server */
    public static final Set<String> TYPES = Set.of("craft_assembled", "craft_boarded", "craft_first_flight", "altitude_record", "speed_record",
        "craft_lost", "contraption_assembled", "train_departed", "train_arrived");

    /** A craft that appears this long after the player used an assembler, or ran /sable assemble, is theirs */
    public static final long ASSEMBLY_WINDOW_MILLIS = 5_000;
    /** ... and this close to them, in blocks */
    public static final double ASSEMBLY_RADIUS = 16.0;
    /** A craft is flying once its underside is this many blocks above the ground beneath it */
    public static final double FIRST_FLIGHT_HEIGHT = 8.0;
    /** An altitude record beats the best so far by at least this many blocks */
    public static final double ALTITUDE_GAIN = 10.0;
    /** A speed record beats the best so far by at least this fraction */
    public static final double SPEED_GAIN = 0.10;
    /** Speeds below this, in blocks a second, are drifting rather than travelling, and never set a record */
    public static final double SPEED_FLOOR = 4.0;
    /** At most one record of each kind in this long */
    public static final long RECORD_COOLDOWN_MILLIS = 60_000;
    /** A craft removed while going at least this fast, in blocks a second, was lost rather than put away */
    public static final double LOST_SPEED = 8.0;
    /** A craft that shrinks below this fraction of its largest size has lost most of its blocks */
    public static final double LOST_FRACTION = 0.5;
    /** Smaller than this, in cubic blocks, a craft is too small for its size to say anything about lost blocks */
    public static final double LOST_MIN_VOLUME = 8.0;
    /** "On or near" a craft, in blocks */
    public static final double NEAR_RADIUS = 16.0;
    /** How long being near a craft counts for after the player moved off */
    public static final long NEAR_MEMORY_MILLIS = 3_000;
    /** A contraption that spawns this close to the player, in blocks, is one they assembled or watched assemble */
    public static final double CONTRAPTION_RADIUS = 8.0;
    /** The game's and the server's report of one thing that arrive this close together are one event */
    public static final long MERGE_MILLIS = 5_000;

    /** Whether a craft that has just appeared was assembled by the player */
    public static boolean assembledByPlayer(long lastAssemblyAction, long now, double distance) {
        return lastAssemblyAction > 0 && now >= lastAssemblyAction && now - lastAssemblyAction <= ASSEMBLY_WINDOW_MILLIS && distance <= ASSEMBLY_RADIUS;
    }

    /** Whether a craft whose underside is this high above the ground is flying */
    public static boolean flying(double heightAboveGround) {
        return heightAboveGround >= FIRST_FLIGHT_HEIGHT && Double.isFinite(heightAboveGround);
    }

    /** Whether a command typed by the player assembles a Sable craft */
    public static boolean assemblesCraft(String command) {
        String text = command == null ? "" : command.trim();
        if (text.startsWith("/")) {
            text = text.substring(1);
        }
        String[] words = text.split("\\s+");
        return words.length >= 2 && words[0].equalsIgnoreCase("sable") && words[1].equalsIgnoreCase("assemble");
    }

    /** Whether a block is an assembler: a block whose id ends in or contains "assembler", such as simulated:physics_assembler */
    public static boolean isAssembler(String blockId) {
        if (blockId == null) {
            return false;
        }
        String path = blockId.substring(blockId.indexOf(':') + 1);
        return path.contains("assembler");
    }

    /** Whether an entity type is one of Create's contraptions: in the create namespace, with "contraption" in its name */
    public static boolean isContraption(String entityTypeId) {
        return entityTypeId != null && entityTypeId.startsWith("create:") && entityTypeId.contains("contraption");
    }

    /**
     * Whether a craft that disappeared, or shrank, was lost.
     *
     * @param removed   it is gone from the world
     * @param speed     how fast it was going when last seen, in blocks a second
     * @param volume    its size now (0 once removed)
     * @param peak      the largest it has been
     * @param wasNear   the player was on it or near it until a moment ago, so it did not just go out of range
     */
    public static boolean lost(boolean removed, double speed, double volume, double peak, boolean wasNear) {
        if (!wasNear) {
            return false;
        }
        if (removed) {
            return speed >= LOST_SPEED;
        }
        return lostBlocks(volume, peak);
    }

    /** Whether a craft has lost most of its blocks, judged by its size */
    public static boolean lostBlocks(double volume, double peak) {
        return peak >= LOST_MIN_VOLUME && volume < peak * LOST_FRACTION;
    }

    /**
     * A personal best for one world: the highest the player has been aboard a craft, or the fastest. The first value
     * seen is where it starts, without an event; a record is logged when the best is beaten by the margin, at most
     * once in {@link #RECORD_COOLDOWN_MILLIS}. Between records the best still follows the value, so that it is kept for
     * the world, while the next record is measured from the last one logged: a long climb logs once a minute, not once.
     */
    public static final class Best {

        private final boolean altitude;
        private double best;
        private double logged;
        private long lastRecord = Long.MIN_VALUE / 2;

        /**
         * @param altitude true for altitude (a record beats by {@link #ALTITUDE_GAIN} blocks), false for speed (by
         *                 {@link #SPEED_GAIN} of the best, and never below {@link #SPEED_FLOOR})
         * @param stored   the best kept for this world, or NaN when there is none yet
         */
        public Best(boolean altitude, double stored) {
            this.altitude = altitude;
            this.best = stored;
            this.logged = stored;
        }

        public double best() {
            return this.best;
        }

        /** @return whether this value is a record to log */
        public boolean offer(double value, long now) {
            if (!Double.isFinite(value) || (!this.altitude && value < SPEED_FLOOR)) {
                return false;
            }
            if (Double.isNaN(this.best)) {
                this.best = value;
                this.logged = value;
                return false;
            }
            boolean beaten = this.altitude ? value >= this.logged + ALTITUDE_GAIN : value >= Math.max(this.logged, SPEED_FLOOR) * (1.0 + SPEED_GAIN);
            if (value > this.best) {
                this.best = value;
            }
            if (beaten && now - this.lastRecord >= RECORD_COOLDOWN_MILLIS) {
                this.lastRecord = now;
                this.logged = value;
                return true;
            }
            return false;
        }
    }

}
