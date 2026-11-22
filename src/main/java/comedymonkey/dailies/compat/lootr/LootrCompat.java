package comedymonkey.dailies.compat.lootr;

import comedymonkey.dailies.Host;
import comedymonkey.dailies.capture.Snapshots;
import org.apache.logging.log4j.LogManager;

/**
 * Lootr in recordings. A snapshot asks every block entity near the player what it would send of itself, which for
 * Lootr's chests, barrels, shulker boxes, pots and suspicious blocks on a client is a complete answer (whether it has
 * been opened, and by whom, comes from what the client was told), but Lootr logs an error with each such answer:
 * "Tried to fillUpdateTag on the client side". One for each of its block entities in sight, every two seconds: 1,182 in
 * a minute of recording on the owner's server (20 November). So that one message is left out of the log while a
 * snapshot is being taken. Lootr's answer, and so what is recorded, is unchanged, and anything else it logs, or the
 * same message at any other time, still reaches the log.
 */
public final class LootrCompat {

    /** The logger Lootr's error goes through: {@code LootrAPI.LOG}, named after its class */
    static final String LOGGER = "noobanidus.mods.lootr.common.api.LootrAPI";
    static final String MESSAGE = "Tried to fillUpdateTag on the client side";

    private LootrCompat() {
    }

    public static void init() {
        if (!Host.get().isModLoaded("lootr")) {
            return;
        }
        try {
            if (LogManager.getLogger(LOGGER) instanceof org.apache.logging.log4j.core.Logger logger) {
                logger.addFilter(new SnapshotLogFilter(MESSAGE, Snapshots::taking));
                Host.LOGGER.info("Dailies: Lootr found; its \"{}\" error is left out of the log while snapshots are taken, as its answer is complete", MESSAGE);
            } else {
                Host.LOGGER.info("Dailies: Lootr found, but the log is not one Dailies can filter, so Lootr logs an error for each of its containers in each snapshot");
            }
        } catch (Throwable t) {
            Host.LOGGER.warn("Dailies: Lootr's snapshot errors could not be left out of the log: {}", t.toString());
        }
    }

}
